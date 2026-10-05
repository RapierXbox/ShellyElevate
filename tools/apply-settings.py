#!/usr/bin/env python3
"""
applies a settings json to many shellyelevate devices through the http api (POST /settings)

only the keys in the json change. a json null removes a key. the input can also be a saved
GET /settings response so one device can be cloned onto others:
    curl http://<ip>:8080/settings > settings.json

usage:
    apply-settings.py settings.json 10.0.30.145 10.0.30.146
    apply-settings.py settings.json -f ips.txt          (one ip per line, # comments)
    apply-settings.py settings.json -f ips.txt --dry-run
    apply-settings.py --list                            (every setting with type and default)
    apply-settings.py --list 10.0.30.145                (plus the display module options of that device)

needs only the python standard library
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

DEFAULT_PORT = 8080
# unique per device so pushing one value to several devices breaks them
PER_DEVICE_KEYS = {"mqttDeviceId"}

# key, type, default, meaning. keep in sync with Constants.java
# N in a key is the 0 based button or input index
# display module options are not listed here. they come from GET /display/modules
SETTINGS = [
    ("display", None, None, None),
    ("displayModule", str, "webview", "what the screen shows: webview or app"),
    ("appSwitcherGesture", str, "swipe_2_up", "off or swipe_<2..5>_<up|down|left|right> opens the app switcher"),
    ("appSwitcherPreviews", bool, True, "screenshots on the app switcher cards"),
    ("general", None, None, None),
    ("webviewUrl", str, "", "dashboard url shown in the webview"),
    ("ignoreSslErrors", bool, False, "accept self signed certificates in the webview"),
    ("liteMode", bool, False, "run without the dashboard ui (background services only)"),
    ("extendedJavascriptInterface", bool, False, "expose extra device calls to page javascript"),
    ("httpServer", bool, True, "http api on port 8080. turning it off cuts this tool off"),
    ("adbWifiEnabled", bool, False, "adb over wifi on port 5555"),
    ("mediaEnabled", bool, False, "media playback through the http api"),
    ("screen", None, None, None),
    ("automaticBrightness", bool, True, "follow the light sensor"),
    ("brightness", int, 255, "fixed brightness 0..255 when automatic is off"),
    ("minBrightness", int, 48, "lowest automatic brightness 0..255"),
    ("nightModeEnabled", bool, False, "dark night mode"),
    ("sleepOptimizationLevel", int, 0, "0 none, 1 standard, 2 aggressive power saving while asleep"),
    ("screensaver", None, None, None),
    ("screenSaver", bool, True, "enable the screensaver"),
    ("screenSaverDelay", int, 45, "idle seconds before it starts (min 5)"),
    ("screenSaverId", int, 0, "0 screen off, 1 clock, 2 clock and date, 3 always on display"),
    ("screenSaverMinBrightness", int, 48, "lowest brightness while the screensaver runs 0..255"),
    ("touchToWake", bool, True, "a touch wakes the screen"),
    ("wakeOnProximity", bool, True, "the proximity sensor wakes the screen"),
    ("proximityKeepAwakeSeconds", int, 30, "seconds to stay awake after proximity"),
    ("input and relays", None, None, None),
    ("switchOnSwipe", bool, True, "swipe toggles the relay"),
    ("publishSwipeEvents", bool, True, "publish multi finger swipes over mqtt"),
    ("powerButtonAutoReboot", bool, True, "long press on the power button reboots"),
    ("buttonRelayEnabled", bool, False, "hardware buttons toggle relays"),
    ("buttonRelayMapN", int, -1, "relay toggled by button N, -1 none"),
    ("swInputModeN", int, 1, "sw input N: 0 detached, 1 button, 2 switch edge, 3 switch follow"),
    ("swInputRelayMapN", int, 0, "relay driven by sw input N"),
    ("swInputInvertN", bool, False, "invert sw input N"),
    ("mqtt", None, None, None),
    ("mqttEnabled", bool, False, "connect to an mqtt broker"),
    ("mqttBroker", str, "", "broker host or ip"),
    ("mqttPort", int, 1883, "broker port"),
    ("mqttUsername", str, "", "broker user"),
    ("mqttPassword", str, "", "broker password"),
    ("mqttDeviceId", str, "shellyelevate-xxxx", "client id and topic prefix. unique per device"),
    ("mqttHomeAssistantDiscovery", bool, True, "publish home assistant discovery"),
    ("mqttRetainState", bool, True, "retain state topics"),
    ("publishThermalSensors", bool, False, "publish soc thermal zones"),
    ("temperature", None, None, None),
    ("dynamicTempOffsetEnabled", bool, False, "correct the room sensor for device heat"),
    ("dynamicTempOffsetZone", str, "mtktsbattery", "thermal zone used as the heat source"),
    ("dynamicTempOffsetBaseline", float, 40.0, "zone temperature with no correction"),
    ("dynamicTempOffsetK", float, 0.3, "degrees removed per degree above baseline"),
    ("bluetooth", None, None, None),
    ("bluetoothProxyEnabled", bool, False, "esphome style bluetooth proxy for home assistant"),
    ("bluetoothProxyName", str, "ShellyElevate", "advertised proxy name"),
    ("voice", None, None, None),
    ("voiceAssistantEnabled", bool, False, "home assistant assist pipeline"),
    ("voiceAssistantToken", str, "", "home assistant long lived access token"),
    ("voiceAssistantPipelineId", str, "", "pipeline id, empty for the default"),
    ("voiceAssistantMaxRecordSeconds", int, 10, "max seconds per request"),
    ("voiceAssistantMuted", bool, False, "mute the microphone"),
    ("voiceWakeEnabled", bool, True, "wake word detection"),
    ("voiceWakeModelName", str, "", "wake word model"),
    ("voiceWakeSensitivity", int, 50, "0..100, 50 is the model cutoff"),
    ("voiceWakeCooldownSec", int, 5, "seconds between detections"),
    ("voiceWakeSoundEnabled", bool, True, "play a tone on wake"),
    ("voiceWakeExperimentalModels", bool, False, "list experimental models"),
    ("voiceScoreBarEnabled", bool, False, "show the live wake score bar"),
]


# display module option keys and types read from a device
MODULE_TYPES: dict = {}
SCHEMA_TYPES = {"bool": bool, "string": str, "url": str, "choice": str, "app": str, "int": int, "float": float}


def load_module_schema(host: str, port: int, timeout: float) -> dict | None:
    try:
        schema = request(base_url(host, port) + "/display/modules", timeout)
    except (urllib.error.URLError, OSError, ValueError):
        return None
    if not schema.get("success"):
        return None
    for module in schema.get("modules", []):
        for option in module.get("options", []):
            typ = SCHEMA_TYPES.get(option.get("type"))
            if typ is not None:
                MODULE_TYPES[option["key"]] = typ
                # the app picker also stores the launcher activity
                if option.get("componentKey"):
                    MODULE_TYPES[option["componentKey"]] = str
    return schema


def print_module_schema(schema: dict) -> None:
    print(f"\ndisplay modules (active: {schema.get('active')})")
    for module in schema.get("modules", []):
        print(f"  {module['id']}: {module['title']}")
        for option in module.get("options", []):
            if option.get("type") == "action":
                continue
            default = json.dumps(option.get("default", None))
            extra = ""
            if option.get("choices"):
                extra = " one of " + ", ".join(c["id"] for c in option["choices"])
            print(f"    {option['key']:<30} {option['type']:<6} {default:<20} {option['title']}{extra}")
            if option.get("componentKey"):
                empty = json.dumps("")
                print(f"    {option['componentKey']:<30} {'string':<6} {empty:<20} launcher activity of the app above")


def known_type(key: str):
    if key in MODULE_TYPES:
        return MODULE_TYPES[key]
    for name, typ, _, _ in SETTINGS:
        if typ is None:
            continue
        if name == key or (name.endswith("N") and key.startswith(name[:-1]) and key[len(name) - 1:].isdigit()):
            return typ
    return None


def print_settings() -> None:
    for name, typ, default, meaning in SETTINGS:
        if typ is None:
            print(f"\n{name}")
            continue
        print(f"  {name:<32} {typ.__name__:<6} {json.dumps(default):<20} {meaning}")
    print("\na json null removes a key so the app falls back to its default")


def type_ok(value, typ) -> bool:
    # json true is an int in python so bools are matched first
    if isinstance(value, bool):
        return typ is bool
    if typ is float:
        return isinstance(value, (int, float))
    return isinstance(value, typ)


def check_settings(settings: dict) -> None:
    for key, value in settings.items():
        typ = known_type(key)
        if typ is None:
            print(f"warning: {key} is not a known setting (typo?)")
        elif value is not None and not type_ok(value, typ):
            print(f"warning: {key} should be {typ.__name__} but got {json.dumps(value)}")


def load_settings(path: str) -> dict:
    try:
        with (sys.stdin if path == "-" else open(path, encoding="utf-8")) as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        sys.exit(f"cannot read {path}: {e}")
    # unwrap a saved GET /settings response
    if isinstance(data, dict) and isinstance(data.get("settings"), dict) and "success" in data:
        data = data["settings"]
    if not isinstance(data, dict) or not data:
        sys.exit(f"{path} must hold a non empty json object of settings")
    return data


def load_hosts(args) -> list[str]:
    hosts = list(args.hosts)
    if args.file:
        try:
            with open(args.file, encoding="utf-8") as f:
                for line in f:
                    line = line.split("#", 1)[0].strip()
                    if line:
                        hosts.append(line)
        except OSError as e:
            sys.exit(f"cannot read {args.file}: {e}")
    # keep order and drop duplicates
    hosts = list(dict.fromkeys(hosts))
    if not hosts:
        sys.exit("no ips given")
    return hosts


def base_url(host: str, port: int) -> str:
    return f"http://{host}" if ":" in host else f"http://{host}:{port}"


def request(url: str, timeout: float, body: dict | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return json.loads(res.read().decode())


def same(want, got) -> bool:
    # floats come back through a java float so compare loosely
    if isinstance(want, (int, float)) and isinstance(got, (int, float)) and not isinstance(want, bool):
        return abs(want - got) < 1e-4
    if isinstance(want, list) and isinstance(got, list):
        return sorted(map(str, want)) == sorted(map(str, got))
    return want == got


def apply(host: str, settings: dict, args) -> tuple[str, bool, str]:
    url = base_url(host, args.port)
    try:
        info = request(url + "/", args.timeout)
        label = f"{info.get('modelName', '?')} {info.get('version', '?')}"
        if args.dry_run:
            current = request(url + "/settings", args.timeout).get("settings", {})
            changes = [k for k, v in settings.items()
                       if (v is None and k in current) or (v is not None and not same(v, current.get(k)))]
            return host, True, f"{label}: would change {len(changes)} key(s) {', '.join(changes)}".rstrip()
        res = request(url + "/settings", args.timeout, settings)
        if not res.get("success"):
            return host, False, f"{label}: device said {res.get('error', 'no success')}"
        stored = res.get("settings", {})
        wrong = [k for k, v in settings.items()
                 if (v is None and k in stored) or (v is not None and not same(v, stored.get(k)))]
        if wrong:
            return host, False, f"{label}: not applied: {', '.join(wrong)}"
        return host, True, f"{label}: applied {len(settings)} key(s)"
    except urllib.error.HTTPError as e:
        return host, False, f"http {e.code}"
    except (urllib.error.URLError, OSError) as e:
        return host, False, f"unreachable ({getattr(e, 'reason', e)})"
    except (ValueError, json.JSONDecodeError):
        return host, False, "bad response (not shellyelevate?)"


def main() -> None:
    if "--list" in sys.argv[1:]:
        print_settings()
        hosts = [a for a in sys.argv[1:] if a != "--list" and not a.startswith("-")]
        if hosts:
            schema = load_module_schema(hosts[0], DEFAULT_PORT, 5)
            if schema is None:
                sys.exit(f"cannot read display modules from {hosts[0]}")
            print_module_schema(schema)
        return

    p = argparse.ArgumentParser(description="apply a settings json to shellyelevate devices. "
                                            "--list prints every setting")
    p.add_argument("json", help="settings json file or - for stdin")
    p.add_argument("hosts", nargs="*", help="device ips, host:port also works")
    p.add_argument("-f", "--file", help="file with one ip per line")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"http port, default {DEFAULT_PORT}")
    p.add_argument("--timeout", type=float, default=5, help="seconds per request, default 5")
    p.add_argument("--dry-run", action="store_true", help="show what would change without writing")
    p.add_argument("--force", action="store_true", help="also push per device keys like mqttDeviceId")
    args = p.parse_args()

    settings = load_settings(args.json)
    hosts = load_hosts(args)
    # module options vary by app version so learn them from the first device
    load_module_schema(hosts[0], args.port, args.timeout)
    check_settings(settings)

    clashes = PER_DEVICE_KEYS & settings.keys()
    if clashes and len(hosts) > 1:
        if not args.force:
            sys.exit(f"{', '.join(sorted(clashes))} must differ per device. remove it from the json "
                     f"or pass --force")
        print(f"warning: pushing {', '.join(sorted(clashes))} to {len(hosts)} devices")

    print(f"{'checking' if args.dry_run else 'applying'} {len(settings)} key(s) on {len(hosts)} device(s)")
    with ThreadPoolExecutor(max_workers=min(16, len(hosts))) as pool:
        results = list(pool.map(lambda h: apply(h, settings, args), hosts))

    width = max(len(h) for h in hosts)
    for host, ok, msg in results:
        print(f"  {'ok  ' if ok else 'FAIL'} {host:<{width}}  {msg}")
    failed = sum(not ok for _, ok, _ in results)
    print(f"{len(results) - failed} ok, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
