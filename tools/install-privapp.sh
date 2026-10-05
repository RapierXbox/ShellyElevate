#!/bin/sh
# pushes the apk into /system/priv-app then reboots and grants the manual perms
# also does what tools/uart-setup.py does: disables cloud.shelly.stargate,
# enables adb over wifi and optionally joins a wifi network
#
# usage: install-privapp.sh <path-to-apk> [ssid] [open]
# the ssid can also come from SHELLY_WIFI_SSID and the password from
# SHELLY_WIFI_PASSWORD or a prompt. without an ssid wifi is skipped
set -e

APK="$1"
SSID="${2:-$SHELLY_WIFI_SSID}"
SECURITY="${3:-wpa2}"
PKG="me.rapierxbox.shellyelevatev2"
DIR="/system/priv-app/ShellyElevateV2"
TARGET="$DIR/ShellyElevateV2.apk"
ADB_TCP_PORT=5555
WPA_CONF="/data/misc/wifi/wpa_supplicant.conf"
WIFI_TIMEOUT=45

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: install-privapp.sh <path-to-apk> [ssid] [open]"
    exit 1
fi

# older adbd runs shell commands in a pty and ends lines with \r
sh_out() {
    adb shell "$1" 2>/dev/null | tr -d '\r'
}

# wpa_supplicant takes 8..63 chars and a quote would end the psk string early
if [ -n "$SSID" ]; then
    case "$SECURITY" in
        open) PASSWORD="" ;;
        wpa2)
            PASSWORD="$SHELLY_WIFI_PASSWORD"
            if [ -z "$PASSWORD" ]; then
                printf 'password for "%s": ' "$SSID"
                stty -echo 2>/dev/null || true
                read -r PASSWORD
                stty echo 2>/dev/null || true
                echo
            fi
            if [ ${#PASSWORD} -lt 8 ] || [ ${#PASSWORD} -gt 63 ]; then
                echo "wifi password must be 8 to 63 characters"
                exit 1
            fi
            ;;
        *) echo "security must be wpa2 or open"; exit 1 ;;
    esac
    case "$SSID$PASSWORD" in
        *\"*) echo "ssid and password must not contain double quotes"; exit 1 ;;
    esac
fi

echo "note: in-app self update needs this apk to share the signing key of future releases"

adb wait-for-device
adb root || true
adb wait-for-device

echo "disabling cloud.shelly.stargate"
if sh_out "pm path cloud.shelly.stargate" | grep -q "package:"; then
    adb shell "pm disable cloud.shelly.stargate"
else
    echo "    not installed"
fi

# adbd falls back to the persist port on boot so this survives the reboot below
echo "enabling adb over wifi on port $ADB_TCP_PORT"
adb shell "settings put global development_settings_enabled 1"
adb shell "settings put global adb_enabled 1"
adb shell "setprop persist.adb.tcp.port $ADB_TCP_PORT"

WIFI_PENDING=""
if [ -n "$SSID" ]; then
    echo "configuring wifi \"$SSID\""
    if adb get-serialno | grep -q ':[0-9]*$'; then
        echo "    skipped: adb runs over wifi and turning wifi off would cut it. use usb for this"
    else
        if [ "$(sh_out 'settings get global airplane_mode_on')" = "1" ]; then
            echo "    airplane mode is on - turning it off"
            adb shell "settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false" >/dev/null
        fi
        # scanning always available keeps wpa_supplicant alive while wifi is off
        # and the config must not be edited under a running supplicant
        adb shell "settings put global wifi_scan_always_enabled 0"
        adb shell "svc wifi disable"
        TRIES=0
        while [ -n "$(sh_out 'pidof wpa_supplicant')" ]; do
            TRIES=$((TRIES + 1))
            if [ "$TRIES" -gt 15 ]; then
                echo "wpa_supplicant wont stop so the config cannot be edited safely"
                exit 1
            fi
            sleep 1
        done

        TMP=$(mktemp -d)
        trap 'rm -rf "$TMP"' EXIT
        if [ "$(sh_out "[ -f $WPA_CONF ] && echo yes")" = "yes" ]; then
            adb pull "$WPA_CONF" "$TMP/in.conf" >/dev/null
        else
            : > "$TMP/in.conf"
        fi
        # drop any existing block for this ssid and append ours
        WANT="ssid=\"$SSID\"" awk '
            BEGIN { want = ENVIRON["WANT"] }
            { s = $0; gsub(/^[ \t]+|[ \t\r]+$/, "", s) }
            !inblock && s ~ /^network=[{]/ { inblock = 1; n = 0; drop = 0; blk[n++] = s; next }
            !inblock { if (s != "") print s; next }
            s == "" { next }
            {
                if (s == want) drop = 1
                blk[n++] = (s == "}") ? s : "    " s
                if (s == "}") { if (!drop) for (i = 0; i < n; i++) print blk[i]; inblock = 0 }
            }
            END { if (inblock) for (i = 0; i < n; i++) print blk[i] }
        ' "$TMP/in.conf" > "$TMP/kept.conf"
        {
            grep -q '^ctrl_interface' "$TMP/kept.conf" || printf 'ctrl_interface=wlan0\nupdate_config=1\n'
            cat "$TMP/kept.conf"
            printf 'network={\n    ssid="%s"\n' "$SSID"
            if [ "$SECURITY" = "open" ]; then
                printf '    key_mgmt=NONE\n'
            else
                printf '    psk="%s"\n    key_mgmt=WPA-PSK\n' "$PASSWORD"
            fi
            printf '    priority=100\n}\n'
        } > "$TMP/out.conf"
        adb push "$TMP/out.conf" "$WPA_CONF.new" >/dev/null
        rm -rf "$TMP"
        adb shell "mv $WPA_CONF.new $WPA_CONF && chown wifi:wifi $WPA_CONF && chmod 660 $WPA_CONF && restorecon $WPA_CONF"
        echo "    saved to $WPA_CONF. it gets loaded on the reboot below"
        WIFI_PENDING=1
    fi
fi

# remount system rw with a root fallback
adb shell "mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /"
adb shell "mkdir -p $DIR"
adb push "$APK" "$TARGET"
adb shell "chmod 644 $TARGET"
adb shell "chcon u:object_r:system_file:s0 $TARGET" || true

# lets the app read the touchscreen so swipes also work while another app is in front
# init runs every rc file in /system/etc/init and a node that does not exist only logs an error
INIT_RC="/system/etc/init/shellyelevate.rc"
RC_BODY='on property:sys.boot_completed=1\n'
for n in $(seq 0 15); do RC_BODY="${RC_BODY}    chmod 0664 /dev/input/event$n\n"; done
adb shell "mkdir -p /system/etc/init && printf '$RC_BODY' > $INIT_RC && chmod 644 $INIT_RC && chcon u:object_r:system_file:s0 $INIT_RC" || true
adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /" || true

# package manager only knows the app after the boot scan so grants must wait for it
echo "rebooting so the priv-app gets scanned"
adb reboot
# without this the old boot can still answer boot_completed=1
adb wait-for-disconnect
adb wait-for-device
TRIES=0
until [ "$(sh_out 'getprop sys.boot_completed')" = "1" ]; do
    TRIES=$((TRIES + 1))
    if [ "$TRIES" -gt 150 ]; then
        echo "device did not finish booting within 5 minutes"
        exit 1
    fi
    sleep 2
done
adb root >/dev/null || true
adb wait-for-device

if ! adb shell "pm path $PKG" | grep -q "package:"; then
    echo "$PKG was not picked up after reboot. check logcat for PackageManager errors"
    exit 1
fi

echo "applying permissions"
adb shell "appops set $PKG WRITE_SETTINGS allow"
adb shell "dumpsys deviceidle whitelist +$PKG" >/dev/null
# runtime perm so the wifi settings section gets scan results
adb shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" || true
if ! adb shell "appops get $PKG WRITE_SETTINGS" | grep -q "allow" \
    || ! adb shell "dumpsys deviceidle whitelist" | grep -q "$PKG" \
    || ! adb shell "dumpsys package $PKG" | grep -q "ACCESS_FINE_LOCATION: granted=true"; then
    echo "permissions did not apply"
    exit 1
fi

WIFI_FAILED=""
if [ -n "$WIFI_PENDING" ]; then
    echo "turning wifi on and waiting for \"$SSID\" (up to ${WIFI_TIMEOUT}s)"
    adb shell "svc wifi enable"
    TRIES=0
    until sh_out "ip -4 addr show wlan0" | grep -q "inet "; do
        TRIES=$((TRIES + 1))
        if [ "$TRIES" -gt "$WIFI_TIMEOUT" ]; then
            echo "wifi did not connect. check the password and that the network is 2.4 GHz"
            WIFI_FAILED=1
            break
        fi
        sleep 1
    done
fi

IP=$(sh_out "ip -4 addr show wlan0" | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -n 1)
if sh_out "pm list packages -d" | grep -q "cloud.shelly.stargate"; then STARGATE=disabled; else STARGATE="not disabled"; fi
echo "done. installed with WRITE_SETTINGS, location and battery whitelist"
echo "    stargate: $STARGATE"
echo "    adb wifi: persist.adb.tcp.port=$(sh_out 'getprop persist.adb.tcp.port')"
echo "    wifi:     ${IP:-no ip}"
if [ -n "$IP" ]; then
    echo "    adb connect $IP:$ADB_TCP_PORT"
fi
[ -z "$WIFI_FAILED" ]
