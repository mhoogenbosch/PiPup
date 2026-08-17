#!/usr/bin/env bash
#
# PiPup installer: does the whole sideload dance for one or more Android TVs, including
# the app-ops an app is not allowed to grant itself.
#
#   ./install.sh 192.168.1.10 192.168.1.11
#   ./install.sh --power --apk PiPup.apk 192.168.1.10
#
# Without --apk the latest release APK is downloaded from GitHub.
#
# Why a script and not something inside the app: SYSTEM_ALERT_WINDOW and
# REQUEST_INSTALL_PACKAGES are app-ops, and only adb/shell or the system can set them.
# Every reinstall resets them, which is exactly the trap this script exists to avoid.

set -uo pipefail

PACKAGE="nl.rogro82.pipup"
ADMIN_COMPONENT="$PACKAGE/.AdminReceiver"
ACCESSIBILITY_COMPONENT="$PACKAGE/$PACKAGE.PiPupAccessibilityService"
REPO="mhoogenbosch/PiPup"
PORT=7979

APK=""
GRANT_POWER=0
GRANT_ACCESSIBILITY=0
FORCE_UNINSTALL=0
WAKE=0
DEVICES=()

usage() {
    cat <<EOF
Usage: $0 [options] <tv-ip[:port]> [tv-ip ...]

Options:
  --apk <file>        APK to install (default: download the latest release)
  --power             also grant device admin, so POST /power?state=off works
  --accessibility     also enable the accessibility fallback for screen off
                      (only needed where device admin cannot reach standby)
  --force-uninstall   uninstall first when the signature differs
                      (WARNING: wipes the app's stable device id, so Home Assistant
                      sees a new device)
  --wake              wake the TV and start the app in the foreground instead of
                      starting the service in the background. Needed on TCL Google
                      TVs, whose vendor guard freezes a background-started service;
                      elsewhere it just switches the TV on for no reason
  -h, --help          this text

Requires adb on your PATH, and adb debugging enabled on the TV.
EOF
}

log()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)              APK="${2:?--apk needs a file}"; shift 2 ;;
        --power)            GRANT_POWER=1; shift ;;
        --accessibility)    GRANT_ACCESSIBILITY=1; shift ;;
        --force-uninstall)  FORCE_UNINSTALL=1; shift ;;
        --wake)             WAKE=1; shift ;;
        -h|--help)          usage; exit 0 ;;
        -*)                 err "unknown option: $1"; usage; exit 2 ;;
        *)                  DEVICES+=("$1"); shift ;;
    esac
done

if [ ${#DEVICES[@]} -eq 0 ]; then
    usage
    exit 2
fi

command -v adb >/dev/null 2>&1 || { err "adb not found on PATH"; exit 1; }

# ---------------------------------------------------------------- APK

if [ -z "$APK" ]; then
    if [ -f PiPup.apk ]; then
        APK="PiPup.apk"
        log "Using ./PiPup.apk"
    else
        command -v curl >/dev/null 2>&1 || { err "need curl to download the APK, or pass --apk"; exit 1; }
        log "Downloading the latest release from github.com/$REPO ..."
        url=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
              | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)
        [ -n "$url" ] || { err "could not find an APK in the latest release"; exit 1; }
        curl -fsSL -o PiPup.apk "$url" || { err "download failed"; exit 1; }
        APK="PiPup.apk"
        ok "downloaded $(basename "$url")"
    fi
fi
[ -f "$APK" ] || { err "APK not found: $APK"; exit 1; }

# ---------------------------------------------------------------- per device

failed=0

for device in "${DEVICES[@]}"; do
    case "$device" in
        *:*) target="$device"; host="${device%%:*}" ;;
        *)   target="$device:5555"; host="$device" ;;
    esac

    log ""
    log "=== $target ==="

    if ! adb connect "$target" 2>&1 | grep -qE 'connected to'; then
        err "cannot connect (adb debugging on? authorised on the TV?)"
        failed=$((failed + 1))
        continue
    fi
    adb -s "$target" wait-for-device

    # install; a signature clash means a differently-signed build (e.g. the Play Store
    # version) is installed and Android refuses to replace it in place
    out=$(adb -s "$target" install -r "$APK" 2>&1)
    if printf '%s' "$out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match'; then
        if [ "$FORCE_UNINSTALL" -eq 1 ]; then
            warn "different signature: uninstalling first (device id is lost)"
            adb -s "$target" uninstall "$PACKAGE" >/dev/null 2>&1
            out=$(adb -s "$target" install -r "$APK" 2>&1)
        else
            err "another build of $PACKAGE is installed (different signature)."
            err "re-run with --force-uninstall, or: adb -s $target uninstall $PACKAGE"
            failed=$((failed + 1))
            continue
        fi
    fi
    if ! printf '%s' "$out" | grep -q 'Success'; then
        err "install failed: $(printf '%s' "$out" | tr '\n' ' ')"
        failed=$((failed + 1))
        continue
    fi
    ok "installed $(basename "$APK")"

    # The two app-ops that a reinstall resets and the app cannot set itself.
    adb -s "$target" shell "appops set $PACKAGE SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1 \
        && ok "overlay permission granted" || err "could not grant SYSTEM_ALERT_WINDOW"
    adb -s "$target" shell "appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow" >/dev/null 2>&1 \
        && ok "self-update permission granted" || warn "could not grant REQUEST_INSTALL_PACKAGES"

    # TCL only: lets Android restart the service after a low-memory kill. Every other
    # brand answers "Unknown operation string", which is fine - nothing to grant there.
    autostart=$(adb -s "$target" shell "cmd appops set $PACKAGE android:auto_start allow" 2>&1)
    if printf '%s' "$autostart" | grep -q 'Unknown operation string'; then
        log "  · no vendor auto-start op on this device (normal outside TCL)"
    else
        ok "auto-start op granted (TCL)"
    fi

    if [ "$GRANT_POWER" -eq 1 ]; then
        # NB: do not trust this command's output. On devices without the device-admin
        # feature (a fair number of Android TV boxes) `dpm set-active-admin` reports
        # `Success` while registering nothing at all - seen on both a Nokia Streaming
        # Box 8010 and a TCL Google TV. The real check is /state below.
        admin=$(adb -s "$target" shell "dpm set-active-admin $ADMIN_COMPONENT" 2>&1)
        if printf '%s' "$admin" | grep -qi 'Success\|now an active admin'; then
            log "  · device admin requested (verifying below)"
        else
            warn "device admin refused: $(printf '%s' "$admin" | tr '\n' ' ')"
        fi
    fi

    if [ "$GRANT_ACCESSIBILITY" -eq 1 ]; then
        current=$(adb -s "$target" shell "settings get secure enabled_accessibility_services" 2>/dev/null | tr -d '\r')
        merged=""
        case "$current" in
            *"$PACKAGE"*) ok "accessibility service already enabled" ;;
            null|"")      merged="$ACCESSIBILITY_COMPONENT" ;;
            *)            merged="$current:$ACCESSIBILITY_COMPONENT" ;;
        esac
        if [ -n "$merged" ]; then
            adb -s "$target" shell "settings put secure enabled_accessibility_services $merged" >/dev/null 2>&1
            adb -s "$target" shell "settings put secure accessibility_enabled 1" >/dev/null 2>&1
            ok "accessibility fallback enabled"
        fi
    fi

    # Bring the service up. Deliberately NOT via the activity by default: a
    # foreground-service start does not touch what is on screen, while waking a
    # sleeping TV to install an app is exactly what you do not want on a set of
    # them at once. --wake is for TCL Google TVs, where the vendor guard freezes a
    # service that was started from the background - there the activity path is the
    # working one, and a dreaming device needs the key event first or `am start` hangs.
    if [ "$WAKE" -eq 1 ]; then
        adb -s "$target" shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
        adb -s "$target" shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
        adb -s "$target" shell "am start -n $PACKAGE/.MainActivity" >/dev/null 2>&1
    else
        adb -s "$target" shell "am start-foreground-service -n $PACKAGE/.PiPupService" >/dev/null 2>&1
    fi

    # Verify from this machine rather than from the TV: Android TVs have no curl.
    state=""
    if command -v curl >/dev/null 2>&1; then
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            state=$(curl -fsS --max-time 2 "http://$host:$PORT/state" 2>/dev/null) && break
            sleep 1
        done
    fi
    if [ -n "$state" ]; then
        version=$(printf '%s' "$state" | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
        # Anchored on "permissions": since app 0.8.0 there is a second "overlay" key
        # inside "fixable", and a loose match returned both values at once.
        overlay=$(printf '%s' "$state" \
            | grep -o '"permissions":{"overlay":[a-z]*' | cut -d: -f3)
        ok "running: v${version:-?} on http://$host:$PORT (overlay=${overlay:-?})"
        [ "${overlay:-}" = "true" ] || err "overlay permission still missing - popups will stay invisible"

        if [ "$GRANT_POWER" -eq 1 ] || [ "$GRANT_ACCESSIBILITY" -eq 1 ]; then
            can_sleep=$(printf '%s' "$state" | grep -o '"canSleep":[a-z]*' | cut -d: -f2)
            method=$(printf '%s' "$state" | grep -o '"sleepMethod":"[^"]*"' | cut -d'"' -f4)
            if [ "${can_sleep:-}" = "true" ]; then
                ok "screen off available via ${method:-?}"
            else
                warn "screen off NOT available: this device has no working device-admin"
                warn "feature (the dpm command reports Success anyway). Re-run with"
                warn "--accessibility to use the fallback route instead."
            fi
        fi
    else
        warn "no answer on http://$host:$PORT/state yet; open the app once on the TV"
    fi

    adb disconnect "$target" >/dev/null 2>&1
done

log ""
if [ "$failed" -gt 0 ]; then
    err "$failed of ${#DEVICES[@]} device(s) failed"
    exit 1
fi
ok "done: ${#DEVICES[@]} device(s)"
