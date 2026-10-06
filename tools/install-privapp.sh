#!/usr/bin/env bash
# pushes the apk into /system/priv-app then reboots and grants the manual perms
# also does what tools/uart-setup.py does: disables cloud.shelly.stargate,
# enables adb over wifi and optionally joins a wifi network
#
# usage: install-privapp.sh [options] <apk>
#   <apk> | --apk <path>     apk to install (required)
#   --ssid <ssid>            wifi to join. default SHELLY_WIFI_SSID
#   --password <password>    wifi password. default SHELLY_WIFI_PASSWORD or a prompt
#   --security wpa2|open     default wpa2
#   -h | --help              show this help
# without an ssid wifi is skipped

set -euo pipefail

usage() {
    sed -n '2,/^$/ s/^# \{0,1\}//p' "$0"
}

die() {
    echo "$*" >&2
    exit 1
}

warn() {
    echo "warning: $*" >&2
}

APK=""
SSID="${SHELLY_WIFI_SSID:-}"
PASSWORD="${SHELLY_WIFI_PASSWORD:-}"
SECURITY="wpa2"

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || die "missing value for $1"; APK="$2"; shift 2 ;;
        --apk=*) APK="${1#*=}"; shift ;;
        --ssid) [ $# -ge 2 ] || die "missing value for $1"; SSID="$2"; shift 2 ;;
        --ssid=*) SSID="${1#*=}"; shift ;;
        --password) [ $# -ge 2 ] || die "missing value for $1"; PASSWORD="$2"; shift 2 ;;
        --password=*) PASSWORD="${1#*=}"; shift ;;
        --security) [ $# -ge 2 ] || die "missing value for $1"; SECURITY="$2"; shift 2 ;;
        --security=*) SECURITY="${1#*=}"; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; break ;;
        -*) usage >&2; die "unknown option: $1" ;;
        *)
            [ -z "$APK" ] || die "unexpected argument: $1"
            APK="$1"; shift ;;
    esac
done
if [ $# -gt 0 ] && [ -z "$APK" ]; then
    APK="$1"; shift
fi
[ $# -eq 0 ] || die "unexpected argument: $1"

if [ -z "$APK" ]; then
    usage >&2
    exit 1
fi

SECURITY=$(printf '%s' "$SECURITY" | tr '[:upper:]' '[:lower:]')
case "$SECURITY" in
    wpa2|open) ;;
    *) die "security must be wpa2 or open" ;;
esac

PKG="me.rapierxbox.shellyelevatev2"
DIR="/system/priv-app/ShellyElevateV2"
TARGET="$DIR/ShellyElevateV2.apk"
ADB_TCP_PORT=5555
WPA_CONF="/data/misc/wifi/wpa_supplicant.conf"
WIFI_TIMEOUT=45

# git bash would rewrite device paths like /system into windows paths
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

# so local paths get converted by hand for a windows adb
local_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

# runs adb and keeps going on failure since later checks catch what matters
try_adb() {
    adb "$@" || true
}

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

# older adbd runs shell commands in a pty and ends lines with \r
get_shell() {
    local out
    out=$(adb shell "$1" 2>/dev/null | tr -d '\r') || true
    trim "$out"
}

# case insensitive substring test
contains() {
    local rc=1
    shopt -s nocasematch
    if [[ $1 == *"$2"* ]]; then rc=0; fi
    shopt -u nocasematch
    return $rc
}

# drops any existing block for our ssid and appends ours
update_wpa_conf() {
    local kept
    kept=$(WANT="ssid=\"$SSID\"" awk '
        BEGIN { want = tolower(ENVIRON["WANT"]) }
        { s = $0; gsub(/^[ \t\r\f\v]+|[ \t\r\f\v]+$/, "", s) }
        !inblock && index(s, "network={") == 1 { inblock = 1; n = 0; drop = 0; blk[n++] = s; next }
        !inblock { if (s != "") print s; next }
        s == "" { next }
        {
            if (tolower(s) == want) drop = 1
            blk[n++] = (s == "}") ? s : "    " s
            if (s == "}") { if (!drop) for (i = 0; i < n; i++) print blk[i]; inblock = 0 }
        }
        END { if (inblock) for (i = 0; i < n; i++) print blk[i] }
    ' "$1")
    if [[ $'\n'$kept != *$'\n'ctrl_interface* ]]; then
        printf 'ctrl_interface=wlan0\nupdate_config=1\n'
    fi
    if [ -n "$kept" ]; then
        printf '%s\n' "$kept"
    fi
    printf 'network={\n    ssid="%s"\n' "$SSID"
    if [ "$SECURITY" = "open" ]; then
        printf '    key_mgmt=NONE\n'
    else
        printf '    psk="%s"\n    key_mgmt=WPA-PSK\n' "$PASSWORD"
    fi
    printf '    priority=100\n}\n'
}

if [ ! -f "$APK" ]; then
    die "apk not found: $APK"
fi

# wpa_supplicant takes 8..63 chars and a quote would end the psk string early
if [ -n "$SSID" ]; then
    if [ "$SECURITY" = "open" ]; then
        PASSWORD=""
    else
        if [ -z "$PASSWORD" ]; then
            read -r -s -p "password for \"$SSID\": " PASSWORD || true
            echo >&2
        fi
        if [ ${#PASSWORD} -lt 8 ] || [ ${#PASSWORD} -gt 63 ]; then
            die "wifi password must be 8 to 63 characters"
        fi
    fi
    case "$SSID$PASSWORD" in
        *\"*) die "ssid and password must not contain double quotes" ;;
    esac
fi

echo "note: in-app self update needs this apk to share the signing key of future releases"

try_adb wait-for-device

# adbd must run as root or every following step fails with a confusing error
ROOT_OUT=$(adb root 2>&1 | tr -d '\r') || true
if contains "$ROOT_OUT" "cannot run as root"; then
    die "adb root failed: $ROOT_OUT"
fi
try_adb wait-for-device

echo "disabling cloud.shelly.stargate"
if contains "$(get_shell "pm path cloud.shelly.stargate")" "package:"; then
    try_adb shell "pm disable cloud.shelly.stargate"
else
    echo "    not installed"
fi

# adbd falls back to the persist port on boot so this survives the reboot below
echo "enabling adb over wifi on port $ADB_TCP_PORT"
try_adb shell "settings put global development_settings_enabled 1"
try_adb shell "settings put global adb_enabled 1"
try_adb shell "setprop persist.adb.tcp.port $ADB_TCP_PORT"

WIFI_PENDING=0
if [ -n "$SSID" ]; then
    echo "configuring wifi \"$SSID\""
    SERIAL=$(adb get-serialno | tr -d '\r') || true
    if [[ $SERIAL =~ :[0-9]+$ ]]; then
        echo "    skipped: adb runs over wifi and turning wifi off would cut it. use usb for this"
    else
        if [ "$(get_shell "settings get global airplane_mode_on")" = "1" ]; then
            echo "    airplane mode is on - turning it off"
            try_adb shell "settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false" >/dev/null
        fi
        # scanning always available keeps wpa_supplicant alive while wifi is off
        # and the config must not be edited under a running supplicant
        try_adb shell "settings put global wifi_scan_always_enabled 0"
        try_adb shell "svc wifi disable"
        DEADLINE=$((SECONDS + 15))
        while [ -n "$(get_shell "pidof wpa_supplicant")" ]; do
            if [ "$SECONDS" -gt "$DEADLINE" ]; then
                die "wpa_supplicant wont stop so the config cannot be edited safely"
            fi
            sleep 1
        done

        TMP=$(mktemp -d)
        trap 'rm -rf "$TMP"' EXIT
        : > "$TMP/in.conf"
        if [ "$(get_shell "[ -f $WPA_CONF ] && echo yes")" = "yes" ]; then
            try_adb pull "$WPA_CONF" "$(local_path "$TMP/in.conf")" >/dev/null
        fi
        # lf only and no bom or wpa_supplicant rejects the file
        update_wpa_conf "$TMP/in.conf" > "$TMP/out.conf"
        if ! adb push "$(local_path "$TMP/out.conf")" "$WPA_CONF.new" >/dev/null; then
            die "pushing the wifi config failed"
        fi
        rm -rf "$TMP"
        trap - EXIT
        CONF_OUT=$(get_shell "mv $WPA_CONF.new $WPA_CONF && chown wifi:wifi $WPA_CONF && chmod 660 $WPA_CONF && restorecon $WPA_CONF && echo ok")
        if ! contains "$CONF_OUT" "ok"; then
            die "installing the wifi config failed: $CONF_OUT"
        fi
        echo "    saved to $WPA_CONF. it gets loaded on the reboot below"
        WIFI_PENDING=1
    fi
fi

# remount system rw with a root fallback and verify it worked
try_adb shell "mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /"
RW_CHECK=$(adb shell "mkdir -p $DIR && touch $DIR/.rwtest && rm $DIR/.rwtest && echo ok" | tr -d '\r') || true
if ! contains "$RW_CHECK" "ok"; then
    die "/system is not writable. is this a rooted build?"
fi
if ! adb push "$(local_path "$APK")" "$TARGET"; then
    die "adb push failed"
fi
# a wrong label makes priv-app scanning reject the apk on boot
LABEL_OUT=$(adb shell "chmod 644 $TARGET && chcon u:object_r:system_file:s0 $TARGET && echo ok" | tr -d '\r') || true
if ! contains "$LABEL_OUT" "ok"; then
    die "chmod/chcon failed: $LABEL_OUT"
fi
# lets the app read the touchscreen so swipes also work while another app is in front
# init runs every rc file in /system/etc/init and a node that does not exist only logs an error
INIT_RC="/system/etc/init/shellyelevate.rc"
RC_BODY='on property:sys.boot_completed=1\n'
for n in {0..15}; do
    RC_BODY+="    chmod 0664 /dev/input/event$n\n"
done
RC_OUT=$(adb shell "mkdir -p /system/etc/init && printf '$RC_BODY' > $INIT_RC && chmod 644 $INIT_RC && chcon u:object_r:system_file:s0 $INIT_RC && echo ok" | tr -d '\r') || true
if ! contains "$RC_OUT" "ok"; then
    warn "touchscreen access rule not installed: $RC_OUT"
fi
try_adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /"

# package manager only knows the app after the boot scan so grants must wait for it
echo "rebooting so the priv-app gets scanned"
try_adb reboot
# without this the old boot can still answer boot_completed=1
try_adb wait-for-disconnect
try_adb wait-for-device
DEADLINE=$((SECONDS + 300))
while :; do
    sleep 2
    BOOTED=$(get_shell "getprop sys.boot_completed")
    if [ "$SECONDS" -gt "$DEADLINE" ]; then
        die "device did not finish booting within 5 minutes"
    fi
    [ "$BOOTED" = "1" ] && break
done
try_adb root >/dev/null
try_adb wait-for-device

PM_OUT=$(adb shell "pm path $PKG" | tr -d '\r\n') || true
if ! contains "$PM_OUT" "package:"; then
    die "$PKG was not picked up after reboot. check logcat for PackageManager errors"
fi

echo "applying permissions"
try_adb shell "appops set $PKG WRITE_SETTINGS allow"
# overlay lets the switcher open over other apps and backs the edge swipe strip
# usage stats tells the app display module which app is in front
try_adb shell "appops set $PKG SYSTEM_ALERT_WINDOW allow"
try_adb shell "appops set $PKG GET_USAGE_STATS allow"
try_adb shell "dumpsys deviceidle whitelist +$PKG" >/dev/null
# runtime perm so the wifi settings section gets scan results
try_adb shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION"
OPS_OUT=$(adb shell "appops get $PKG WRITE_SETTINGS" | tr -d '\r\n') || true
IDLE_OUT=$(adb shell "dumpsys deviceidle whitelist" | tr -d '\r') || true
LOC_OUT=$(adb shell "dumpsys package $PKG" | tr -d '\r') || true
if ! contains "$OPS_OUT" "allow" || ! contains "$IDLE_OUT" "$PKG" || ! contains "$LOC_OUT" "ACCESS_FINE_LOCATION: granted=true"; then
    die "permissions did not apply. appops: $OPS_OUT"
fi

WIFI_FAILED=0
if [ "$WIFI_PENDING" = "1" ]; then
    echo "turning wifi on and waiting for \"$SSID\" (up to ${WIFI_TIMEOUT}s)"
    try_adb shell "svc wifi enable"
    DEADLINE=$((SECONDS + WIFI_TIMEOUT))
    while ! contains "$(get_shell "ip -4 addr show wlan0")" "inet "; do
        if [ "$SECONDS" -gt "$DEADLINE" ]; then
            warn "wifi did not connect. check the password and that the network is 2.4 GHz"
            WIFI_FAILED=1
            break
        fi
        sleep 1
    done
fi

IP=""
IP_RE='inet ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)'
if [[ $(get_shell "ip -4 addr show wlan0") =~ $IP_RE ]]; then
    IP="${BASH_REMATCH[1]}"
fi
if contains "$(get_shell "pm list packages -d")" "cloud.shelly.stargate"; then
    STARGATE="disabled"
else
    STARGATE="not disabled"
fi
echo "done. installed at $PM_OUT with WRITE_SETTINGS, location and battery whitelist"
echo "    stargate: $STARGATE"
echo "    adb wifi: persist.adb.tcp.port=$(get_shell "getprop persist.adb.tcp.port")"
echo "    wifi:     ${IP:-no ip}"
if [ -n "$IP" ]; then
    echo "    adb connect $IP:$ADB_TCP_PORT"
fi
if [ "$WIFI_FAILED" = "1" ]; then
    exit 1
fi
