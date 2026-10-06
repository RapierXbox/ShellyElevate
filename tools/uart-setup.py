#!/usr/bin/env python3
"""
uart setup for a shelly wall display so tools/install-privapp can take over
not needed when adb already works since install-privapp does the same steps

connects to the debug uart and on demand
- gets a root shell
- disables cloud.shelly.stargate
- enables adb over wifi
- joins a wifi network and waits for an ip

interactive keys: SPACE = setup, s = status, q / Ctrl+C = quit
--setup or --status run once and exit

wifi credentials come from --ssid/--password, SHELLY_WIFI_SSID/SHELLY_WIFI_PASSWORD
or a prompt. they are never stored in this file

requires: pip install pyserial
"""

import argparse
import getpass
import os
import re
import sys
import threading
import time

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    sys.exit("pyserial is missing: pip install pyserial")

DEFAULT_BAUD = 921600
ADB_TCP_PORT = 5555
CMD_TIMEOUT = 20                # seconds to wait for the shell prompt per command
CLI_TIMEOUT = 10                # seconds for a single wpa_cli call
WIFI_TIMEOUT = 45               # seconds to wait for wifi to get an ip

WPA_CONF = "/data/misc/wifi/wpa_supplicant.conf"
# shell prompt at the start of a line e.g. "K400_MT6580_32_N:/ # "
PROMPT_RE = re.compile(rb"\n[^\n]*?[\w.-]+:/\S* [#$] ")
KLOG_RE = re.compile(r"^\s*\[\s*\d+\.\d+\]")          # kernel log lines on the console
IP_RE = re.compile(r"inet (\d+\.\d+\.\d+\.\d+)")


def shell_quote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


def log(msg: str) -> None:
    print(f"\n[*] {msg}", flush=True)


class Wifi:
    def __init__(self, ssid: str, password: str, security: str):
        self.ssid = ssid
        self.password = password
        self.security = security

    def network_block(self) -> list[str]:
        lines = ["network={", f'    ssid="{self.ssid}"']
        if self.security == "open":
            lines.append("    key_mgmt=NONE")
        else:
            lines += [f'    psk="{self.password}"', "    key_mgmt=WPA-PSK"]
        lines += ["    priority=100", "}"]
        return lines

    def rewrite_conf(self, text: str) -> list[str]:
        """drop any existing block for our ssid and append ours"""
        out, block = [], None
        for line in text.splitlines():
            s = line.strip()
            if block is None:
                if s.startswith("network={"):
                    block = [s]
                elif s:
                    out.append(s)
            else:
                block.append(s if s == "}" else "    " + s)
                if s == "}":
                    if f'ssid="{self.ssid}"' not in [b.strip() for b in block]:
                        out += block
                    block = None
        if block:
            out += block
        if not any(l.startswith("ctrl_interface") for l in out):
            out = ["ctrl_interface=wlan0", "update_config=1"] + out
        return out + self.network_block()


class Console:
    def __init__(self, ser: serial.Serial, port: str, baud: int, wifi: Wifi | None):
        self.ser = ser
        self.port = port
        self.baud = baud
        self.wifi = wifi
        self.stop = threading.Event()
        self.echo = True                # print device output to the screen
        self.buf = bytearray()          # output captured since the last send
        self.cond = threading.Condition()
        self.wpa_cli = None

    # ------------------------------------------------------------ low level --
    def reconnect(self) -> None:
        try:
            self.ser.close()
        except Exception:
            pass
        while not self.stop.is_set():
            time.sleep(1)
            try:
                self.ser = serial.Serial(self.port, self.baud, timeout=0.1)
                print(f"\n[reconnected to {self.port}]")
                return
            except serial.SerialException:
                pass

    def reader(self) -> None:
        out = sys.stdout.buffer
        while not self.stop.is_set():
            try:
                data = self.ser.read(self.ser.in_waiting or 1)
            except serial.SerialException as e:
                if self.stop.is_set():
                    break
                print(f"\n[serial error: {e} - usb adapter unplugged? reconnecting...]")
                self.reconnect()
                continue
            if data:
                if self.echo:
                    out.write(data)
                    out.flush()
                with self.cond:
                    self.buf += data
                    self.cond.notify_all()

    def _wait_prompt(self, timeout: float) -> bytes | None:
        deadline = time.monotonic() + timeout
        with self.cond:
            while not (m := PROMPT_RE.search(self.buf)):
                left = deadline - time.monotonic()
                if left <= 0 or self.stop.is_set():
                    return None
                self.cond.wait(left)
            return bytes(self.buf[:m.start()])

    def _send(self, data: bytes) -> None:
        with self.cond:
            self.buf.clear()
        try:
            self.ser.write(data)
            self.ser.flush()
        except serial.SerialException:
            pass                                # the reader reconnects and the caller times out

    def run(self, cmd: str, timeout: float = CMD_TIMEOUT, echo: bool = True) -> str | None:
        """send a command and return its output once the next prompt shows up. None on timeout"""
        self.echo = echo
        try:
            self._send(cmd.encode() + b"\r")
            raw = self._wait_prompt(timeout)
            if raw is None:
                shown = cmd if echo else cmd.split(" ", 1)[0] + " ..."
                print(f"\n[!] timeout after {timeout:.0f}s: {shown} - sending Ctrl+C")
                self._send(b"\x03")
                self._wait_prompt(5)            # resync with the shell
                return None
        finally:
            self.echo = True
        body = raw.split(b"\n", 1)[1] if b"\n" in raw else b""   # drop the echoed command
        lines = body.decode(errors="replace").replace("\r", "").split("\n")
        return "\n".join(l for l in lines if not KLOG_RE.match(l)).strip()

    def q(self, cmd: str, timeout: float = CMD_TIMEOUT) -> str:
        """quiet run without screen echo. '' on timeout"""
        return self.run(cmd, timeout, echo=False) or ""

    def cli(self, args: str) -> str:
        return self.q(f"{self.wpa_cli} {args}", CLI_TIMEOUT)

    # ---------------------------------------------------------------- steps --
    def wake(self) -> bool:
        """get a clean prompt. Ctrl+C kills hung commands and unclosed quotes"""
        received = bytearray()
        for data in (b"\r", b"\x03", b"\x03\r", b"\r"):
            self._send(data)
            if self._wait_prompt(3) is not None:
                time.sleep(0.5)                 # let late prompts arrive then resync
                return self.run("", timeout=5, echo=False) is not None
            with self.cond:
                received += self.buf
        print(f"[!] no shell prompt. device sent {len(received)} bytes: {bytes(received[-300:])!r}")
        if not received:
            print("    nothing at all - check TX/RX/GND wiring and the baud rate, or the device is booting")
        return False

    def get_root(self) -> bool:
        log("getting root")
        if not self.wake():
            return False
        if "uid=0" not in self.q("id"):
            self.run("su", timeout=10)
        out = self.q("id")
        print(f"    {out}")
        if "uid=0" not in out:
            print("[!] could not get root")
            return False
        return True

    def disable_stargate(self) -> None:
        log("disabling cloud.shelly.stargate")
        self.run("pm disable cloud.shelly.stargate")

    def setup_adb(self) -> None:
        log(f"enabling adb over wifi on port {ADB_TCP_PORT}")
        for cmd in [
            "settings put global development_settings_enabled 1",
            "settings put global adb_enabled 1",
            f"setprop persist.adb.tcp.port {ADB_TCP_PORT}",
            f"setprop service.adb.tcp.port {ADB_TCP_PORT}",
            "stop adbd",
            "start adbd",
        ]:
            self.run(cmd)

    # ------------------------------------------------------- wifi helpers --
    def supplicant_running(self) -> bool:
        return bool(self.q("pidof wpa_supplicant").strip())

    def wait_supplicant(self, running: bool, timeout: float) -> bool:
        what = "start" if running else "stop"
        print(f"    waiting for wpa_supplicant to {what} ", end="", flush=True)
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            if self.supplicant_running() == running:
                print(f"- ok ({time.monotonic() - start:.0f}s)")
                return True
            print(".", end="", flush=True)
            time.sleep(1)
        print(f"- still {'stopped' if running else 'running'} after {timeout:.0f}s")
        return False

    def detect_wpa_cli(self) -> None:
        self.wpa_cli = None
        if not self.q("command -v wpa_cli"):
            return
        for cli in ("wpa_cli -p /data/misc/wifi/sockets -i wlan0",
                    "wpa_cli -g@android:wpa_wlan0 IFNAME=wlan0"):
            if "wpa_state=" in self.q(f"{cli} status 2>&1", CLI_TIMEOUT):
                self.wpa_cli = cli
                return

    def network_ids(self) -> list[str]:
        ids = []
        for line in self.cli("list_networks").splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) > 1 and parts[1] == self.wifi.ssid:
                ids.append(parts[0])
        return ids

    def add_network_wpa_cli(self) -> str | None:
        """add the network to the running wpa_supplicant and let it save the config"""
        for nid in self.network_ids():
            print(f"    removing old entry id {nid}: {self.cli(f'remove_network {nid}')}")
        nid = self.cli("add_network").splitlines()[-1:] or [""]
        nid = nid[0].strip()
        if not nid.isdigit():
            print(f"[!] add_network failed: {nid!r}")
            return None
        fields = [("ssid", shell_quote(f'"{self.wifi.ssid}"'))]
        if self.wifi.security == "open":
            fields.append(("key_mgmt", "NONE"))
        else:
            fields += [("psk", shell_quote(f'"{self.wifi.password}"')), ("key_mgmt", "WPA-PSK")]
        fields.append(("priority", "100"))
        results = [f"{k}={self.cli(f'set_network {nid} {k} {v}')}" for k, v in fields]
        results.append(f"enable={self.cli(f'enable_network {nid}')}")
        results.append(f"save={self.cli('save_config')}")
        print(f"    added network id {nid}: {' '.join(results)}")
        return nid

    def write_wifi_conf(self) -> bool:
        """fallback without wpa_cli. edits wpa_supplicant.conf directly so the supplicant must be stopped"""
        exists = "YES" in self.q(f"[ -f {WPA_CONF} ] && echo YES")
        current = self.q(f"cat {WPA_CONF}") if exists else ""
        lines = self.wifi.rewrite_conf(current)
        tmp = WPA_CONF + ".new"
        self.q(f": > {tmp}")
        for i in range(0, len(lines), 4):
            args = " ".join(shell_quote(l) for l in lines[i:i + 4])
            self.q(f"printf '%s\\n' {args} >> {tmp}")
        count = self.q(f"wc -l < {tmp}").strip()
        if count != str(len(lines)):
            print(f"[!] writing config failed (got {count!r} lines, expected {len(lines)})")
            return False
        self.q(f"mv {tmp} {WPA_CONF}; chown wifi:wifi {WPA_CONF}; chmod 660 {WPA_CONF}; "
               f"restorecon {WPA_CONF} 2>/dev/null")
        print(f'    wrote network "{self.wifi.ssid}" to {WPA_CONF}')
        return True

    def conf_has_network(self) -> bool:
        pattern = shell_quote('ssid="' + self.wifi.ssid + '"')
        return self.q(f"grep -c {pattern} {WPA_CONF}").strip() not in ("", "0")

    def wifi_status(self) -> dict:
        st = {"state": "?", "ssid": "", "rssi": "", "ip": ""}
        if self.wpa_cli:
            out = self.cli("status")
            kv = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
            st.update(state=kv.get("wpa_state", "?"), ssid=kv.get("ssid", ""))
            if st["state"] == "COMPLETED":
                if m := re.search(r"RSSI=(-?\d+)", self.cli("signal_poll")):
                    st["rssi"] = m.group(1)
        else:
            out = self.q("dumpsys wifi | grep mWifiInfo | head -n 1")
            if m := re.search(r"SSID: (.*?), BSSID", out):
                st["ssid"] = m.group(1).strip('"')
            if m := re.search(r"Supplicant state: (\w+)", out):
                st["state"] = m.group(1)
            if m := re.search(r"RSSI: (-?\d+)", out):
                st["rssi"] = m.group(1)
        if m := IP_RE.search(self.q("ip -4 addr show wlan0")):
            st["ip"] = m.group(1)
        return st

    def wait_for_wifi(self, timeout: float) -> str:
        """print progress until wlan0 has an ip. returns the ip or ''"""
        start, last = time.monotonic(), None
        while (t := time.monotonic() - start) < timeout and not self.stop.is_set():
            if self.wpa_cli is None:
                self.detect_wpa_cli()
            st = self.wifi_status()
            line = (f"    [{t:4.0f}s] state={st['state']:<16} ssid={st['ssid'] or '-':<16} "
                    f"rssi={st['rssi'] or '-':<5} ip={st['ip'] or '-'}")
            key = (st["state"], st["ssid"], st["ip"])
            print(("\n" if key != last else "\r") + line, end="", flush=True)
            last = key
            if st["ip"] and st["ssid"] in (self.wifi.ssid, ""):
                print()
                return st["ip"]
            time.sleep(1.5)
        print()
        return ""

    def restart_wifi(self) -> None:
        print("    turning wifi off")
        self.q("svc wifi disable")
        self.wait_supplicant(False, 15)
        print("    turning wifi on")
        self.q("svc wifi enable")
        self.wait_supplicant(True, 15)
        time.sleep(2)
        self.detect_wpa_cli()

    def wifi_diagnostics(self) -> None:
        log("wifi diagnostics")
        if self.wpa_cli:
            self.cli("scan")
            time.sleep(4)
            seen = {}
            for r in self.cli("scan_results").splitlines()[1:]:
                parts = r.split("\t")
                if len(parts) >= 5:
                    seen.setdefault(parts[4], (parts[1], parts[2]))
            if self.wifi.ssid in seen:
                freq, sig = seen[self.wifi.ssid]
                print(f'    "{self.wifi.ssid}" is visible: {freq} MHz, {sig} dBm')
            else:
                print(f'    "{self.wifi.ssid}" NOT found in scan (the device is 2.4 GHz only)')
                print(f"    visible: {', '.join(s for s in seen if s) or '-'}")
            print("    configured networks:")
            for l in self.cli("list_networks").splitlines():
                print(f"      {l}")
        else:
            print("    wpa_cli not available")
        print(f"    network in {WPA_CONF}: {'yes' if self.conf_has_network() else 'NO'}")
        print("    recent wifi log:")
        out = self.q("logcat -d -v brief | grep -iE 'wpa_supplicant|CTRL-EVENT|WifiConfig|auth|assoc' "
                     "| grep -v mIsFullScanOngoing | tail -n 30", timeout=30)
        for l in out.splitlines():
            print(f"      {l}")

    def setup_wifi(self) -> str:
        log("configuring wifi")
        if self.q("settings get global airplane_mode_on").strip() == "1":
            print("    airplane mode is on - turning it off")
            self.q("settings put global airplane_mode_on 0; "
                   "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")

        # scanning always available keeps wpa_supplicant alive while wifi is off
        # so a wifi restart would never reload the config
        if self.q("settings get global wifi_scan_always_enabled").strip() == "1":
            print("    disabling 'wifi scanning always available'")
            self.q("settings put global wifi_scan_always_enabled 0")

        print("    turning wifi on")
        self.q("svc wifi enable")
        self.wait_supplicant(True, 15)
        time.sleep(2)
        self.detect_wpa_cli()
        print(f"    wpa_cli: {self.wpa_cli or 'not available'}")

        if self.wpa_cli:
            self.add_network_wpa_cli()
        else:
            self.q("svc wifi disable")
            if self.wait_supplicant(False, 15):
                self.write_wifi_conf()
            else:
                print("[!] wpa_supplicant wont stop so the config cannot be edited safely")
        print(f"    network saved in {WPA_CONF}: {'yes' if self.conf_has_network() else 'NO'}")

        # restart so the android framework loads the network from the config
        self.restart_wifi()
        print(f"    network still in config after restart: {'yes' if self.conf_has_network() else 'NO'}")
        if self.wpa_cli:
            print(f"    supplicant knows network: {'yes' if self.network_ids() else 'NO'}")

        log(f'waiting for "{self.wifi.ssid}" (up to {WIFI_TIMEOUT}s)')
        ip = self.wait_for_wifi(WIFI_TIMEOUT)
        if ip:
            return ip

        if self.wpa_cli:
            ids = self.network_ids() or [self.add_network_wpa_cli() or ""]
            if ids[0]:
                log(f"forcing connect: select_network {ids[0]}")
                print(f"    {self.cli(f'select_network {ids[0]}')}")
                ip = self.wait_for_wifi(30)
                if ip:
                    return ip

        print("[!] wifi did not connect")
        self.wifi_diagnostics()
        return ""

    def adb_status(self) -> str:
        svc = self.q("getprop init.svc.adbd").strip() or "?"
        port = self.q("getprop service.adb.tcp.port").strip() or "-"
        listening = f":{ADB_TCP_PORT}" in self.q(f"netstat -ltn 2>/dev/null | grep :{ADB_TCP_PORT}")
        return f"adbd={svc} tcp.port={port} listening={'yes' if listening else 'no'}"

    # ------------------------------------------------------------- actions --
    def run_setup(self) -> bool:
        print("\n[*** running setup ***]")
        if not self.get_root():
            return False
        self.disable_stargate()
        self.setup_adb()
        ip = self.setup_wifi() if self.wifi else ""

        log("summary")
        print(f"    adb:  {self.adb_status()}")
        if not self.wifi:
            print("    wifi: skipped (no ssid given)")
            return True
        if not ip:
            print("    wifi: NOT connected - see the diagnostics above")
            return False
        print(f"    wifi: connected to {self.wifi.ssid}, ip {ip}")
        print("\n[*** done. next steps ***]")
        print(f"    adb connect {ip}:{ADB_TCP_PORT}")
        print("    tools/install-privapp.ps1 <apk>   (or tools/install-privapp.sh)")
        return True

    def show_status(self) -> bool:
        if not self.get_root():
            return False
        self.detect_wpa_cli()
        st = self.wifi_status()
        log("status")
        print(f"    wifi: state={st['state']} ssid={st['ssid'] or '-'} rssi={st['rssi'] or '-'} "
              f"ip={st['ip'] or '-'} (wpa_cli: {'yes' if self.wpa_cli else 'no'})")
        if self.wpa_cli:
            for l in self.cli("list_networks").splitlines():
                print(f"      {l}")
        print(f"    adb:  {self.adb_status()}")
        disabled = "cloud.shelly.stargate" in self.q("pm list packages -d")
        print(f"    stargate: {'disabled' if disabled else 'enabled'}")
        return True


# ---------------------------------------------------------------- key input --
class Keys:
    """single key reads without enter on windows and posix"""

    def __enter__(self):
        if os.name == "nt":
            import msvcrt
            self._kbhit, self._getch = msvcrt.kbhit, msvcrt.getwch
        else:
            import select
            import termios
            import tty
            self._fd = sys.stdin.fileno()
            self._old = termios.tcgetattr(self._fd)
            tty.setcbreak(self._fd)
            self._kbhit = lambda: bool(select.select([sys.stdin], [], [], 0)[0])
            self._getch = lambda: sys.stdin.read(1)
        return self

    def __exit__(self, *exc):
        if os.name != "nt":
            import termios
            termios.tcsetattr(self._fd, termios.TCSADRAIN, self._old)

    def poll(self) -> str | None:
        return self._getch() if self._kbhit() else None


def pick_port(port: str | None) -> str:
    if port:
        return port
    ports = list(serial.tools.list_ports.comports())
    if len(ports) == 1:
        return ports[0].device
    listing = "\n".join(f"    {p.device}  {p.description}" for p in ports) or "    none found"
    sys.exit(f"pass --port. serial ports:\n{listing}")


def build_wifi(args) -> Wifi | None:
    ssid = args.ssid or os.environ.get("SHELLY_WIFI_SSID")
    if not ssid:
        return None
    if args.security == "open":
        return Wifi(ssid, "", "open")
    password = args.password or os.environ.get("SHELLY_WIFI_PASSWORD") or getpass.getpass(f'password for "{ssid}": ')
    # wpa_supplicant takes 8..63 chars and a quote would end the psk string early
    if not 8 <= len(password) <= 63:
        sys.exit("wifi password must be 8 to 63 characters")
    if '"' in password or '"' in ssid:
        sys.exit("ssid and password must not contain double quotes")
    return Wifi(ssid, password, "wpa2")


def parse_args():
    p = argparse.ArgumentParser(description="set up a shelly wall display over its debug uart")
    p.add_argument("--port", help="serial port like COM3 or /dev/ttyUSB0. auto detected when only one exists")
    p.add_argument("--baud", type=int, default=DEFAULT_BAUD, help=f"default {DEFAULT_BAUD}")
    p.add_argument("--ssid", help="wifi to join. or set SHELLY_WIFI_SSID. without one wifi is skipped")
    p.add_argument("--password", help="or set SHELLY_WIFI_PASSWORD. prompted when missing")
    p.add_argument("--security", choices=["wpa2", "open"], default="wpa2")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--setup", action="store_true", help="run the setup once and exit")
    mode.add_argument("--status", action="store_true", help="print the status once and exit")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    port = pick_port(args.port)
    wifi = build_wifi(args) if not args.status else None
    try:
        ser = serial.Serial(port, args.baud, timeout=0.1)
    except serial.SerialException as e:
        sys.exit(f"cannot open {port}: {e}")

    con = Console(ser, port, args.baud, wifi)
    threading.Thread(target=con.reader, daemon=True).start()

    if args.setup or args.status:
        try:
            ok = con.run_setup() if args.setup else con.show_status()
        except KeyboardInterrupt:
            ok = False
        con.stop.set()
        con.ser.close()
        sys.exit(0 if ok else 1)

    print(f"connected to {port} @ {args.baud}. SPACE = setup, s = status, q = quit\n")
    busy = threading.Lock()

    def start(job):
        if not busy.acquire(blocking=False):
            return

        def wrapper():
            try:
                job()
            finally:
                busy.release()
        threading.Thread(target=wrapper, daemon=True).start()

    try:
        with Keys() as keys:
            while not con.stop.is_set():
                ch = keys.poll()
                if ch == " ":
                    start(con.run_setup)
                elif ch in ("s", "S"):
                    start(con.show_status)
                elif ch in ("q", "Q", "\x03"):
                    break
                time.sleep(0.02)
    except KeyboardInterrupt:
        pass
    finally:
        con.stop.set()
        con.ser.close()
        print("\n[closed]")


if __name__ == "__main__":
    main()
