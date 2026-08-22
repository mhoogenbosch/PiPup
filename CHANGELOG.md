# Changelog

All notable changes to this fork ([mhoogenbosch/PiPup](https://github.com/mhoogenbosch/PiPup)) are
documented here. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Original app by [rogro82](https://github.com/rogro82/PiPup).

Every version below has a [GitHub release](https://github.com/mhoogenbosch/PiPup/releases) with the
full story (English and Dutch) and the APK.

## [v0.9.0] — 2026-08-19 (diagnose why a fix button did not appear)
Answer to a field report that the permission fix "does not work". Two causes, both invisible from the
outside, plus the endpoint to see them.
### Added
- **`GET /permissions/diagnose`** — per permission the intent action, the activity that resolves it,
  whether that activity is a vendor placeholder, whether it is fixable and the adb command; plus
  `sdk`/`device`, `deviceAdminSupported`, `backgroundLaunchExempt`, `activityVisible` and `lastFix`
  (what the previous attempt did and how it ended). Reachable over HTTP, so a bug report can carry facts
  instead of a symptom — the people who hit this are holding a remote, not a shell. The Home Assistant
  integration puts the whole block in its diagnostics download.
- `<queries>` declarations for the four settings intents.
### Fixed
- **A fix requested while the overlay permission was missing did nothing and reported success.** From
  Android 10 on, starting an activity from the background needs an exemption; holding
  `SYSTEM_ALERT_WINDOW` is one, a foreground service explicitly is not — so the permission you most
  want a button for is the one whose absence takes the button away, and the platform drops the launch
  silently. `/permissions/fix` now checks up front and answers **501** with a `reason` naming the way
  out: open PiPup on the TV (a visible window is another exemption) and use the button there, or use
  adb. Verified all three ways on a TCL Google TV.
- **Working screens were reported as unavailable on some devices.** The placeholder check used
  `resolveActivity()`, which on Android 11+ is a query and is filtered by package visibility, while
  `startActivity()` is not. Without `<queries>`, any device whose `forceQueryable` list omits Settings
  hid a perfectly good button. An unresolved intent now counts as *worth trying*; only a recognised
  placeholder (`CTSDummy…`, `frameworkpackagestubs…`) is refused. A Google TV here lists 184
  `forceQueryable` packages including Settings, which is why this never showed up in testing.
- The "is my window visible" check was a boolean set by `MainActivity`, so the wake step — which
  launches `WakeActivity` in front of it — made a fix request refuse itself one step before launching.
  The platform's exemption is per app, so it is now a counter over all activities.

## [v0.8.1] — 2026-08-17 (don't wake the TV for a fix it cannot show)
### Fixed
- `POST /permissions/fix` woke the TV **before** checking whether the requested screen exists on that
  device, so a request that was going to answer 501 anyway still switched a TV on. Seen minutes after
  0.8.0 shipped: a failing `pipup.fix_permission` from Home Assistant, aimed at a Fire TV where the
  overlay screen is a CTS placeholder, woke a TV in another room and left it on. The capability check
  now runs first. Verified on a sleeping Fire TV stick — 501 with the adb command, TV stays asleep.

## [v0.8.0] — 2026-08-17 (permission screen with fix buttons)
### Added
- The status screen on the TV lists every permission with its real state and puts a **Fix** button next
  to the missing ones, jumping straight to the system screen where it is granted — operable with the
  remote, no adb prompt needed.
- `POST /permissions/fix` does the same from a controller: no argument for the app's own overview,
  `?what=next` for the first missing one, `?what=overlay` for a specific one. The TV is woken first.
### Changed
- Placeholder intent handlers are treated as **absent**. Every Android build must resolve these intents
  to pass Google's compatibility suite, so "is there an activity for this?" answers yes even where
  nothing happens (Fire OS returns `CTSDummyIntentHandler`, Google TV `frameworkpackagestubs.Stubs`).
  Where the screen is fake the app shows the adb command instead, `/state` reports it as
  `permissions.fixable`, and `/permissions/fix` answers **501** with the command in its body.
- The app still cannot *grant* anything itself — these are app-ops, shell/system territory. It can only
  walk someone to the exact spot, which was the missing half.

## [v0.7.0] — 2026-08-17 (border styling, screen on/off, installers)
### Added
- **Custom border styling** on `/notify`: `borderColor`, `borderWidth`, `cornerRadius`. Each overrides
  *its part* of the `urgency` preset, so `urgency: critical` + `borderWidth: 2` is a thin red border and
  `borderWidth: 0` removes the preset's frame. `cornerRadius` works on borderless popups too.
- **Screen on/off** — `POST /power?state=on|off|toggle`. No second integration (ADB, HDMI-CEC) for
  "wake the TV, then show something". *on* works out of the box; *off* needs a one-time grant per device
  (device admin, or an accessibility fallback for boxes without the device-admin feature).
  `/state` publishes `power.canSleep` and `power.sleepMethod`; `/power` answers **501** where the device
  cannot, so a client can hide the control instead of offering a dead one.
- **Permission reporting** in `/state` (`permissions.overlay`, `installPackages`, vendor `autoStart`,
  `deviceAdmin`, `accessibility`) plus a warning on the TV's status screen when the overlay permission is
  missing — until now that failure was invisible: every popup was answered with HTTP 200 and nothing
  appeared.
- **Installers** `install.sh` / `install.ps1` with `--power`, `--accessibility` and a TCL `--wake` flag.
### Changed
- The multipart path learned `borderColor`/`borderWidth`/`cornerRadius` plus `urgency` and `showProgress`,
  which it silently ignored before, so uploaded snapshots can be styled too.
- An unparseable color falls back to its default instead of dropping the whole popup.
### Notes
- Verified on hardware: a Fire TV stick (Fire OS, Android 9) sleeps via device admin; a Nokia Streaming
  Box 8010 (Android 14) has no device-admin feature at all and sleeps via the accessibility route.
- If you script the accessibility grant yourself: **append** to `enabled_accessibility_services`, don't
  overwrite it — replacing the value disables other accessibility services.

## [v0.6.2] — 2026-07-28 (the service restarts itself after an update)
### Fixed
- The app listens for `MY_PACKAGE_REPLACED`, so the service comes back by itself after a self-update
  instead of staying down until someone opened the app.

## [v0.6.1] — 2026-07-27 (crash fix on repeated start requests)
### Fixed
- `startForeground()` is now called on *every* start command, not just the first — Android kills a
  foreground service that skips it.

## [v0.6.0] — 2026-07-27 (self-update from GitHub releases)
### Added
- The app can update itself from the GitHub releases of this fork.

## [v0.5.0] — 2026-07-27 (status screen & last popup in /state)
### Added
- A status screen on the TV, and the last popup exposed in `/state`.

## [v0.4.0] — 2026-07-26 (lazy TTS & robust webserver start)
### Fixed
- TTS is initialised lazily and the webserver survives a rough start.

## [v0.3.2] — 2026-07-19 (thread-safety & callback fixes)
### Fixed
- Thread-safety around the popup queue and the button callbacks.

## [v0.3.1] · [v0.3.0] — 2026-07-12
### Added
- Popup buttons, progress bar and the `urgency` presets (`info`/`warning`/`critical`).

## [v0.2.0] – [v0.2.6] — 2026-07-03 … 2026-07-11
### Added
- The first fork releases: `/state` endpoint with popup visibility and screen state, a popup counter,
  the dismiss watchdog (`watchdogCleanups`), and the groundwork the later versions build on.

[v0.8.1]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.8.1
[v0.8.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.8.0
[v0.7.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.7.0
[v0.6.2]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.6.2
[v0.6.1]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.6.1
[v0.6.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.6.0
[v0.5.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.5.0
[v0.4.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.4.0
[v0.3.2]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.3.2
[v0.3.1]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.3.1
[v0.3.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.3.0
[v0.2.6]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.2.6
[v0.2.0]: https://github.com/mhoogenbosch/PiPup/releases/tag/v0.2.0
