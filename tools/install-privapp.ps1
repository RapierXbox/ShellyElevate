# pushes the apk into /system/priv-app and grants the manual perms then reboots
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk
)

$pkg = "me.rapierxbox.shellyelevatev2"
$dir = "/system/priv-app/ShellyElevateV2"
$target = "$dir/ShellyElevateV2.apk"

if (-not (Test-Path $Apk)) {
    Write-Error "apk not found: $Apk"
    exit 1
}

Write-Host "note: in-app self update needs this apk to share the signing key of future releases"

& adb wait-for-device

# adbd must run as root or every following step fails with a confusing error
$rootOut = & adb root
if ($rootOut -match "cannot run as root") {
    Write-Error "adb root failed: $rootOut"
    exit 1
}
& adb wait-for-device

# remount system rw with a root fallback and verify it worked
& adb shell "mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /"
$rwCheck = & adb shell "mkdir -p $dir && touch $dir/.rwtest && rm $dir/.rwtest && echo ok"
if ($rwCheck -notmatch "ok") {
    Write-Error "/system is not writable. is this a rooted build?"
    exit 1
}
& adb push $Apk $target
if ($LASTEXITCODE -ne 0) {
    Write-Error "adb push failed"
    exit 1
}
# a wrong label makes priv-app scanning reject the apk on boot
$labelOut = & adb shell "chmod 644 $target && chcon u:object_r:system_file:s0 $target && echo ok"
if ($labelOut -notmatch "ok") {
    Write-Error "chmod/chcon failed: $labelOut"
    exit 1
}
& adb shell "appops set $pkg WRITE_SETTINGS allow"
& adb shell "dumpsys deviceidle whitelist +$pkg"
& adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /"
& adb reboot

Write-Host "done. device is rebooting"
