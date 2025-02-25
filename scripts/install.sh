#!/system/bin/sh

APK_NAME="ShellyElevateV2.apk"
INSTALL_PATH="/system/priv-app/ShellyElevateV2"
APK_PATH="$INSTALL_PATH/$APK_NAME"

mount -o remount,rw /system

curl -s "https://api.github.com/repos/RapierXbox/ShellyElevate/releases/latest" \
| grep "browser_download_url.*$APK_NAME" \
| cut -d : -f 2,3 \
| tr -d " \" \
| wget -O "$APK_PATH" -q -

if [ ! -f "$APK_PATH" ]; then
    echo "Download failed"
    exit 1
fi

chmod 644 "$APK_PATH"
chown root:root "$APK_PATH"

mount -o remount,ro /system

reboot
