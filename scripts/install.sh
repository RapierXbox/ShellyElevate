#!/system/bin/sh

pm disable cloud.shelly.stargate

APK_URLS=$(curl -s "https://api.github.com/repos/RapierXbox/ShellyElevate/releases/latest" \
    | grep "browser_download_url.*apk" \
    | cut -d '"' -f 4)


for url in $APK_URLS; do
    wget "$url"
done


for apk in *.apk; do
    pm install -r "$apk"
done

reboot
