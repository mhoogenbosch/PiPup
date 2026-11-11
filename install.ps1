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
    [switch]$Wake,

    # app >= 0.32.0: give access to the active media sessions (GET /media, POST /media/seek);
    # Android ignores it on low-RAM devices
    [switch]$Media,

    # app >= 0.32.0: let PiPup press buttons in these apps (comma-separated, e.g.
    # com.plexapp.android): enables the separate "PiPup control" accessibility service
    [string]$Click,

    # switch the control service off and clear the list of apps
    [switch]$NoClick
)

$ErrorActionPreference = 'Continue'

$Package = 'nl.rogro82.pipup'
$AdminComponent = "$Package/.AdminReceiver"
$AccessibilityComponent = "$Package/$Package.PiPupAccessibilityService"
$ControlComponent = "$Package/$Package.PiPupControlService"
$MediaComponent = "$Package/$Package.MediaListenerService"
$Repo = 'mhoogenbosch/PiPup'
$Port = 7979

function Write-Ok    { param($m) Write-Host "  [ok] $m" -ForegroundColor Green }
function Write-Warn  { param($m) Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Write-Fail  { param($m) Write-Host "  [x]  $m" -ForegroundColor Red }

# the list goes into a remote shell command: package names only
if ($Click -and $Click -notmatch '^[A-Za-z0-9._,]+$') {
    Write-Fail '-Click: only package names, separated by commas'
    exit 2
}

# Whether the ':'-separated list holds the component, in the full (pkg/pkg.Cls) or short
# (pkg/.Cls) form. The whole component: since 0.32.0 PiPup has two accessibility
# services, and one being on must not read as the other being on.
function Test-Component {
    param([string]$List, [string]$Component)
    $short = $Component.Replace("/$Package.", '/.')
    return @($List -split ':') -contains $Component -or @($List -split ':') -contains $short
}

# The entries of a ':'-separated list, without empty and "null" ones: `settings get` prints
# null when the setting is unset, and a hand-written append (readme before 0.32.0) kept it.
function Get-Components {
    param([string]$List)
    return @(@($List -split ':') | Where-Object { $_ -and $_ -ne 'null' })
}

function Enable-Accessibility {
    param([string]$Target, [string]$Component, [string]$Label)
    $current = ((adb -s $Target shell 'settings get secure enabled_accessibility_services' 2>$null) -join '').Trim()
    if (Test-Component $current $Component) {
        Write-Ok "$Label already enabled"
        return
    }
    $merged = (@(Get-Components $current) + $Component) -join ':'
    adb -s $Target shell "settings put secure enabled_accessibility_services $merged" | Out-Null
    adb -s $Target shell 'settings put secure accessibility_enabled 1' | Out-Null
    Write-Ok "$Label enabled"
}

function Disable-Accessibility {
    param([string]$Target, [string]$Component, [string]$Label)
    $current = ((adb -s $Target shell 'settings get secure enabled_accessibility_services' 2>$null) -join '').Trim()
    if (-not (Test-Component $current $Component)) {
        Write-Ok "$Label already off"
        return
    }
    $short = $Component.Replace("/$Package.", '/.')
    $kept = (@(Get-Components $current) | Where-Object { $_ -ne $Component -and $_ -ne $short }) -join ':'
    if ($kept) {
        adb -s $Target shell "settings put secure enabled_accessibility_services $kept" | Out-Null
    } else {
        adb -s $Target shell 'settings delete secure enabled_accessibility_services' | Out-Null
    }
    Write-Ok "$Label switched off"
}

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
        Enable-Accessibility $target $AccessibilityComponent 'accessibility fallback'
    }

    # 0.32.0 (#83): media sessions. Android lists them for an app whose notification
    # listener is enabled; PiPup's listener reads no notifications.
    if ($Media) {
        $sdk = 0
        [void][int]::TryParse(((adb -s $target shell 'getprop ro.build.version.sdk' 2>$null) -join '').Trim(), [ref]$sdk)
        if ($sdk -ge 26) {
            adb -s $target shell "cmd notification allow_listener $MediaComponent" | Out-Null
        } else {
            # Android < 8 has no `cmd notification allow_listener`; there the setting itself
            # is the list (from 8 on it is only a mirror, so it is not written there)
            $listeners = ((adb -s $target shell 'settings get secure enabled_notification_listeners' 2>$null) -join '').Trim()
            if (-not (Test-Component $listeners $MediaComponent)) {
                $merged = if ($listeners -and $listeners -ne 'null') { "${listeners}:$MediaComponent" } else { $MediaComponent }
                adb -s $target shell "settings put secure enabled_notification_listeners $merged" | Out-Null
            }
        }
        if (((adb -s $target shell 'getprop ro.config.low_ram' 2>$null) -join '').Trim() -eq 'true') {
            Write-Warn 'low-RAM device: Android gives no app access to media sessions here'
        } else {
            Write-Host '  .  media session access requested (verifying below)'
        }
    }

    # 0.32.0 (#83): the separate "PiPup control" service and the apps it may press in.
    # The list can only be set like this (adb), never over the LAN.
    $clickList = $null
    if ($Click) {
        $current = ((adb -s $target shell 'settings get secure enabled_accessibility_services' 2>$null) -join '').Trim()
        if (Test-Component $current $ControlComponent) {
            # 0.32.0-beta.2: off and on again. After an app update Android keeps binding an
            # enabled service with the old version's capabilities: beta.1 -> beta.2 had no tap.
            Disable-Accessibility $target $ControlComponent 'control service (click), restarting:'
            Start-Sleep -Seconds 2
        }
        Enable-Accessibility $target $ControlComponent 'control service (click)'
        $clickList = $Click
    } elseif ($NoClick) {
        Disable-Accessibility $target $ControlComponent 'control service (click)'
        $clickList = ''
    }
    if ($null -ne $clickList) {
        $result = (adb -s $target shell "am broadcast -f 0x20 -n $Package/.ControlConfigReceiver -a $Package.SET_CLICK_PACKAGES --es packages '$clickList'" 2>&1) -join ' '
        if ($result -match 'result=1') {
            $allowed = if ($result -match 'data="([^"]*)"') { $Matches[1] } else { '' }
            Write-Ok "click allowed in: $(if ($allowed) { $allowed } else { 'no app' })"
        } else {
            Write-Fail "could not set the click list (app older than 0.32.0?): $result"
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
        if ($Media) {
            if ($state.media.access) {
                Write-Ok 'media sessions readable (GET /media)'
            } else {
                Write-Warn "media sessions NOT readable yet: $($state.media.reason)"
            }
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
