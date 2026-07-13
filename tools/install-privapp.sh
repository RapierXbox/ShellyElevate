#!/bin/sh
# pushes the apk into /system/priv-app and grants the manual perms then reboots
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
adb shell "appops set $PKG WRITE_SETTINGS allow" || true
adb shell "dumpsys deviceidle whitelist +$PKG" || true
adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /" || true
adb reboot

echo "done. device is rebooting"
