#!/bin/sh
# pushes the apk into /system/priv-app then reboots and grants the manual perms
set -e

APK="$1"
PKG="me.rapierxbox.shellyelevatev2"
DIR="/system/priv-app/ShellyElevateV2"
TARGET="$DIR/ShellyElevateV2.apk"

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: install-privapp.sh <path-to-apk>"
    exit 1
fi

echo "note: in-app self update needs this apk to share the signing key of future releases"

adb wait-for-device
adb root || true
adb wait-for-device

# remount system rw with a root fallback
adb shell "mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /"
adb shell "mkdir -p $DIR"
adb push "$APK" "$TARGET"
adb shell "chmod 644 $TARGET"
adb shell "chcon u:object_r:system_file:s0 $TARGET" || true
adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /" || true

# package manager only knows the app after the boot scan so grants must wait for it
echo "rebooting so the priv-app gets scanned"
adb reboot
# without this the old boot can still answer boot_completed=1
adb wait-for-disconnect
adb wait-for-device
TRIES=0
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
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
if ! adb shell "appops get $PKG WRITE_SETTINGS" | grep -q "allow" \
    || ! adb shell "dumpsys deviceidle whitelist" | grep -q "$PKG"; then
    echo "permissions did not apply"
    exit 1
fi

echo "done. installed with WRITE_SETTINGS and battery whitelist"
