# pushes the apk into /system/priv-app then reboots and grants the manual perms
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
& adb shell "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /"

# package manager only knows the app after the boot scan so grants must wait for it
Write-Host "rebooting so the priv-app gets scanned"
& adb reboot
# without this the old boot can still answer boot_completed=1
& adb wait-for-disconnect
& adb wait-for-device
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 2
    $booted = (& adb shell getprop sys.boot_completed 2>$null) -join ""
    if ((Get-Date) -gt $deadline) {
        Write-Error "device did not finish booting within 5 minutes"
        exit 1
    }
} until ($booted.Trim() -eq "1")
& adb root | Out-Null
& adb wait-for-device

$pmOut = (& adb shell "pm path $pkg") -join ""
if ($pmOut -notmatch "package:") {
    Write-Error "$pkg was not picked up after reboot. check logcat for PackageManager errors"
    exit 1
}

Write-Host "applying permissions"
& adb shell "appops set $pkg WRITE_SETTINGS allow"
& adb shell "dumpsys deviceidle whitelist +$pkg" | Out-Null
$opsOut = (& adb shell "appops get $pkg WRITE_SETTINGS") -join ""
$idleOut = (& adb shell "dumpsys deviceidle whitelist") -join "`n"
if ($opsOut -notmatch "allow" -or $idleOut -notmatch [regex]::Escape($pkg)) {
    Write-Error "permissions did not apply. appops: $opsOut"
    exit 1
}

Write-Host "done. installed at $pmOut with WRITE_SETTINGS and battery whitelist"
