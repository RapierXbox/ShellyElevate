# pushes the apk into /system/priv-app then reboots and grants the manual perms
# also does what tools/uart-setup.py does: disables cloud.shelly.stargate,
# enables adb over wifi and optionally joins a wifi network
# the ssid can also come from SHELLY_WIFI_SSID and the password from
# SHELLY_WIFI_PASSWORD or a prompt. without an ssid wifi is skipped
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [string]$Ssid = $env:SHELLY_WIFI_SSID,
    [string]$Password = $env:SHELLY_WIFI_PASSWORD,
    [ValidateSet("wpa2", "open")]
    [string]$Security = "wpa2"
)

$pkg = "me.rapierxbox.shellyelevatev2"
$dir = "/system/priv-app/ShellyElevateV2"
$target = "$dir/ShellyElevateV2.apk"
$adbTcpPort = 5555
$wpaConf = "/data/misc/wifi/wpa_supplicant.conf"
$wifiTimeout = 45

# older adbd runs shell commands in a pty and ends lines with \r
function Get-Shell([string]$cmd) {
    ((& adb shell $cmd 2>$null) -join "`n").Replace("`r", "").Trim()
}

# drops any existing block for our ssid and appends ours
function Update-WpaConf([string]$text) {
    $out = New-Object System.Collections.Generic.List[string]
    $block = $null
    foreach ($line in ($text -split "`r?`n")) {
        $s = $line.Trim()
        if ($null -eq $block) {
            if ($s.StartsWith("network={")) { $block = New-Object System.Collections.Generic.List[string]; $block.Add($s) }
            elseif ($s) { $out.Add($s) }
            continue
        }
        if (-not $s) { continue }
        $block.Add($(if ($s -eq "}") { $s } else { "    $s" }))
        if ($s -eq "}") {
            if (-not ($block | Where-Object { $_.Trim() -eq "ssid=`"$Ssid`"" })) { $out.AddRange($block) }
            $block = $null
        }
    }
    if ($block) { $out.AddRange($block) }
    if (-not ($out | Where-Object { $_.StartsWith("ctrl_interface") })) {
        $out.InsertRange(0, [string[]]@("ctrl_interface=wlan0", "update_config=1"))
    }
    $out.Add("network={")
    $out.Add("    ssid=`"$Ssid`"")
    if ($Security -eq "open") {
        $out.Add("    key_mgmt=NONE")
    } else {
        $out.Add("    psk=`"$Password`"")
        $out.Add("    key_mgmt=WPA-PSK")
    }
    $out.Add("    priority=100")
    $out.Add("}")
    ($out -join "`n") + "`n"
}

if (-not (Test-Path $Apk)) {
    Write-Error "apk not found: $Apk"
    exit 1
}

# wpa_supplicant takes 8..63 chars and a quote would end the psk string early
if ($Ssid) {
    if ($Security -eq "open") {
        $Password = ""
    } else {
        if (-not $Password) {
            $secure = Read-Host "password for `"$Ssid`"" -AsSecureString
            $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        }
        if ($Password.Length -lt 8 -or $Password.Length -gt 63) {
            Write-Error "wifi password must be 8 to 63 characters"
            exit 1
        }
    }
    if ($Ssid.Contains('"') -or $Password.Contains('"')) {
        Write-Error "ssid and password must not contain double quotes"
        exit 1
    }
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

Write-Host "disabling cloud.shelly.stargate"
if ((Get-Shell "pm path cloud.shelly.stargate") -match "package:") {
    & adb shell "pm disable cloud.shelly.stargate"
} else {
    Write-Host "    not installed"
}

# adbd falls back to the persist port on boot so this survives the reboot below
Write-Host "enabling adb over wifi on port $adbTcpPort"
& adb shell "settings put global development_settings_enabled 1"
& adb shell "settings put global adb_enabled 1"
& adb shell "setprop persist.adb.tcp.port $adbTcpPort"

$wifiPending = $false
if ($Ssid) {
    Write-Host "configuring wifi `"$Ssid`""
    if ((& adb get-serialno) -match ":\d+$") {
        Write-Host "    skipped: adb runs over wifi and turning wifi off would cut it. use usb for this"
    } else {
        if ((Get-Shell "settings get global airplane_mode_on") -eq "1") {
            Write-Host "    airplane mode is on - turning it off"
            & adb shell "settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false" | Out-Null
        }
        # scanning always available keeps wpa_supplicant alive while wifi is off
        # and the config must not be edited under a running supplicant
        & adb shell "settings put global wifi_scan_always_enabled 0"
        & adb shell "svc wifi disable"
        $deadline = (Get-Date).AddSeconds(15)
        while (Get-Shell "pidof wpa_supplicant") {
            if ((Get-Date) -gt $deadline) {
                Write-Error "wpa_supplicant wont stop so the config cannot be edited safely"
                exit 1
            }
            Start-Sleep -Seconds 1
        }

        $tmp = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $tmp | Out-Null
        try {
            $current = ""
            if ((Get-Shell "[ -f $wpaConf ] && echo yes") -eq "yes") {
                & adb pull $wpaConf "$tmp/in.conf" | Out-Null
                $current = [IO.File]::ReadAllText("$tmp/in.conf")
            }
            # lf only and no bom or wpa_supplicant rejects the file
            [IO.File]::WriteAllText("$tmp/out.conf", (Update-WpaConf $current), (New-Object Text.UTF8Encoding $false))
            & adb push "$tmp/out.conf" "$wpaConf.new" | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Error "pushing the wifi config failed"
                exit 1
            }
        } finally {
            Remove-Item -Recurse -Force $tmp
        }
        $confOut = Get-Shell "mv $wpaConf.new $wpaConf && chown wifi:wifi $wpaConf && chmod 660 $wpaConf && restorecon $wpaConf && echo ok"
        if ($confOut -notmatch "ok") {
            Write-Error "installing the wifi config failed: $confOut"
            exit 1
        }
        Write-Host "    saved to $wpaConf. it gets loaded on the reboot below"
        $wifiPending = $true
    }
}

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
# lets the app read the touchscreen so swipes also work while another app is in front
# init runs every rc file in /system/etc/init and a node that does not exist only logs an error
$initRc = "/system/etc/init/shellyelevate.rc"
$rcBody = "on property:sys.boot_completed=1\n" + ((0..15 | ForEach-Object { "    chmod 0664 /dev/input/event$_\n" }) -join "")
$rcOut = & adb shell "mkdir -p /system/etc/init && printf '$rcBody' > $initRc && chmod 644 $initRc && chcon u:object_r:system_file:s0 $initRc && echo ok"
if ($rcOut -notmatch "ok") {
    Write-Warning "touchscreen access rule not installed: $rcOut"
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
# runtime perm so the wifi settings section gets scan results
& adb shell "pm grant $pkg android.permission.ACCESS_FINE_LOCATION"
$opsOut = (& adb shell "appops get $pkg WRITE_SETTINGS") -join ""
$idleOut = (& adb shell "dumpsys deviceidle whitelist") -join "`n"
$locOut = (& adb shell "dumpsys package $pkg") -join "`n"
if ($opsOut -notmatch "allow" -or $idleOut -notmatch [regex]::Escape($pkg) -or $locOut -notmatch "ACCESS_FINE_LOCATION: granted=true") {
    Write-Error "permissions did not apply. appops: $opsOut"
    exit 1
}

$wifiFailed = $false
if ($wifiPending) {
    Write-Host "turning wifi on and waiting for `"$Ssid`" (up to ${wifiTimeout}s)"
    & adb shell "svc wifi enable"
    $deadline = (Get-Date).AddSeconds($wifiTimeout)
    while ((Get-Shell "ip -4 addr show wlan0") -notmatch "inet ") {
        if ((Get-Date) -gt $deadline) {
            Write-Warning "wifi did not connect. check the password and that the network is 2.4 GHz"
            $wifiFailed = $true
            break
        }
        Start-Sleep -Seconds 1
    }
}

$ip = ""
if ((Get-Shell "ip -4 addr show wlan0") -match "inet (\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
$stargate = if ((Get-Shell "pm list packages -d") -match "cloud\.shelly\.stargate") { "disabled" } else { "not disabled" }
Write-Host "done. installed at $pmOut with WRITE_SETTINGS, location and battery whitelist"
Write-Host "    stargate: $stargate"
Write-Host "    adb wifi: persist.adb.tcp.port=$(Get-Shell 'getprop persist.adb.tcp.port')"
Write-Host "    wifi:     $(if ($ip) { $ip } else { 'no ip' })"
if ($ip) {
    Write-Host "    adb connect ${ip}:$adbTcpPort"
}
if ($wifiFailed) { exit 1 }
