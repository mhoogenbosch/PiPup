<#
.SYNOPSIS
    PiPup installer for Windows: sideloads the APK on one or more Android TVs and grants
    the app-ops an app cannot grant itself.

.EXAMPLE
    .\install.ps1 192.168.1.10 192.168.1.11
.EXAMPLE
    .\install.ps1 -Power -Apk PiPup.apk 192.168.1.10

.NOTES
    SYSTEM_ALERT_WINDOW and REQUEST_INSTALL_PACKAGES are app-ops: only adb/shell or the
    system can set them, and every reinstall resets them. Hence this script.
    Requires adb on PATH and adb debugging enabled on the TV.
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true, Mandatory = $true)]
    [string[]]$Devices,

    # APK to install; downloads the latest release when omitted
    [string]$Apk,

    # also grant device admin, so POST /power?state=off works
    [switch]$Power,

    # also enable the accessibility fallback for screen off
    [switch]$Accessibility,
    # Skip the accessibility service even on TCL, where it is enabled by default
    # (a system-bound service keeps the process alive there).
    [switch]$NoAccessibility,

    # uninstall first on a signature clash (WARNING: wipes the stable device id)
    [switch]$ForceUninstall,

    # wake the TV and start the app in the foreground instead of starting the service
    # in the background; needed on TCL Google TVs, elsewhere it only switches the TV on
    [switch]$Wake
)

$ErrorActionPreference = 'Continue'

$Package = 'nl.rogro82.pipup'
$AdminComponent = "$Package/.AdminReceiver"
$AccessibilityComponent = "$Package/$Package.PiPupAccessibilityService"
$Repo = 'mhoogenbosch/PiPup'
$Port = 7979

function Write-Ok    { param($m) Write-Host "  [ok] $m" -ForegroundColor Green }
function Write-Warn  { param($m) Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Write-Fail  { param($m) Write-Host "  [x]  $m" -ForegroundColor Red }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Fail 'adb not found on PATH'
    exit 1
}

# ---------------------------------------------------------------- APK

if (-not $Apk) {
    if (Test-Path 'PiPup.apk') {
        $Apk = 'PiPup.apk'
        Write-Host 'Using .\PiPup.apk'
    } else {
        Write-Host "Downloading the latest release from github.com/$Repo ..."
        try {
            $release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" `
                -Headers @{ 'User-Agent' = 'pipup-install' }
            $asset = $release.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
            if (-not $asset) { throw 'no APK asset in the latest release' }
            Invoke-WebRequest $asset.browser_download_url -OutFile 'PiPup.apk'
            $Apk = 'PiPup.apk'
            Write-Ok "downloaded $($asset.name)"
        } catch {
            Write-Fail "download failed: $_"
            exit 1
        }
    }
}
if (-not (Test-Path $Apk)) {
    Write-Fail "APK not found: $Apk"
    exit 1
}

# ---------------------------------------------------------------- per device

$failed = 0

foreach ($device in $Devices) {
    if ($device -match ':') {
        $target = $device
        $tvHost = $device.Split(':')[0]
    } else {
        $target = "${device}:5555"
        $tvHost = $device
    }

    Write-Host ''
    Write-Host "=== $target ==="

    $connect = (adb connect $target 2>&1) -join ' '
    if ($connect -notmatch 'connected to') {
        Write-Fail 'cannot connect (adb debugging on? authorised on the TV?)'
        $failed++
        continue
    }
    adb -s $target wait-for-device | Out-Null

    $out = (adb -s $target install -r $Apk 2>&1) -join ' '
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        if ($ForceUninstall) {
            Write-Warn 'different signature: uninstalling first (device id is lost)'
            adb -s $target uninstall $Package | Out-Null
            $out = (adb -s $target install -r $Apk 2>&1) -join ' '
        } else {
            Write-Fail "another build of $Package is installed (different signature)."
            Write-Fail "re-run with -ForceUninstall, or: adb -s $target uninstall $Package"
            $failed++
            continue
        }
    }
    if ($out -notmatch 'Success') {
        Write-Fail "install failed: $out"
        $failed++
        continue
    }
    Write-Ok "installed $(Split-Path $Apk -Leaf)"

    # The two app-ops that a reinstall resets and the app cannot set itself.
    adb -s $target shell "appops set $Package SYSTEM_ALERT_WINDOW allow" | Out-Null
    Write-Ok 'overlay permission granted'
    adb -s $target shell "appops set $Package REQUEST_INSTALL_PACKAGES allow" | Out-Null
    Write-Ok 'self-update permission granted'

    # TCL only; every other brand answers "Unknown operation string", which is fine.
    $autostart = (adb -s $target shell "cmd appops set $Package android:auto_start allow" 2>&1) -join ' '
    if ($autostart -match 'Unknown operation string') {
        Write-Host '  .  no vendor auto-start op on this device (normal outside TCL)'
    } else {
        Write-Ok 'auto-start op granted (TCL)'
        # TCL: a system-bound accessibility service is what keeps the process alive
        # (oom_score_adj 100, out of the vendor guard's reach) - see the README's TCL section.
        if ($NoAccessibility) {
            Write-Host '  . TCL: accessibility keep-alive skipped (-NoAccessibility)'
        } elseif (-not $Accessibility) {
            Write-Host '  . TCL: enabling the accessibility service (keeps the process alive; -NoAccessibility to skip)'
            $Accessibility = $true
        }
    }

    if ($Power) {
        # NB: do not trust this command's output. On devices without the device-admin
        # feature (a fair number of Android TV boxes) `dpm set-active-admin` reports
        # Success while registering nothing at all - seen on both a Nokia Streaming Box
        # 8010 and a TCL Google TV. The real check is /state below.
        $admin = (adb -s $target shell "dpm set-active-admin $AdminComponent" 2>&1) -join ' '
        if ($admin -match 'Success|now an active admin') {
            Write-Host '  .  device admin requested (verifying below)'
        } else {
            Write-Warn "device admin refused: $admin"
        }
    }

    if ($Accessibility) {
        $current = ((adb -s $target shell 'settings get secure enabled_accessibility_services' 2>$null) -join '').Trim()
        if ($current -like "*$Package*") {
            Write-Ok 'accessibility service already enabled'
        } else {
            $merged = if ($current -and $current -ne 'null') { "${current}:$AccessibilityComponent" } else { $AccessibilityComponent }
            adb -s $target shell "settings put secure enabled_accessibility_services $merged" | Out-Null
            adb -s $target shell 'settings put secure accessibility_enabled 1' | Out-Null
            Write-Ok 'accessibility fallback enabled'
        }
    }

    # Deliberately not via the activity by default: a foreground-service start does not
    # touch what is on screen, while waking a sleeping TV to install an app is exactly
    # what you do not want on a set of them at once. -Wake is for TCL Google TVs, whose
    # vendor guard freezes a background-started service; a dreaming device needs the key
    # event first or `am start` hangs.
    if ($Wake) {
        adb -s $target shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
        adb -s $target shell 'input keyevent KEYCODE_HOME' | Out-Null
        adb -s $target shell "am start -n $Package/.MainActivity" | Out-Null
    } else {
        adb -s $target shell "am start-foreground-service -n $Package/.PiPupService" | Out-Null
    }

    # Verify from this machine: Android TVs have no curl.
    $state = $null
    foreach ($attempt in 1..10) {
        try {
            $state = Invoke-RestMethod "http://${tvHost}:$Port/state" -TimeoutSec 2
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if ($state) {
        Write-Ok "running: v$($state.version) on http://${tvHost}:$Port (overlay=$($state.permissions.overlay))"
        if (-not $state.permissions.overlay) {
            Write-Fail 'overlay permission still missing - popups will stay invisible'
        }
        if ($Power -or $Accessibility) {
            if ($state.power.canSleep) {
                Write-Ok "screen off available via $($state.power.sleepMethod)"
            } else {
                Write-Warn 'screen off NOT available: this device has no working device-admin'
                Write-Warn 'feature (the dpm command reports Success anyway). Re-run with'
                Write-Warn '-Accessibility to use the fallback route instead.'
            }
        }
    } else {
        Write-Warn "no answer on http://${tvHost}:$Port/state yet; open the app once on the TV"
    }

    adb disconnect $target | Out-Null
}

Write-Host ''
if ($failed -gt 0) {
    Write-Fail "$failed of $($Devices.Count) device(s) failed"
    exit 1
}
Write-Ok "done: $($Devices.Count) device(s)"
