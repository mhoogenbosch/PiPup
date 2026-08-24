# Changelog

All notable changes to this fork ([mhoogenbosch/PiPup](https://github.com/mhoogenbosch/PiPup)) are
documented here. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Original app by [rogro82](https://github.com/rogro82/PiPup).

Every version below has a [GitHub release](https://github.com/mhoogenbosch/PiPup/releases) with the
full story (English and Dutch) and the APK.

## [v0.11.1] — 2026-08-23 (a Fix button that doesn't lie on locked-down TVs)
Field report from a TCL Smart TV Pro (Android 11): the self-update permission stayed missing no matter
what, and the `/permissions/diagnose` output showed why — `opModes.installPackages: "errored"`.
### Fixed
- When an app-op is in a device-**blocked** state (`errored`/`ignored`) — some TVs lock "install
  unknown apps" for sideloaded apps at the system level, like Samsung's Auto Blocker — the permission
  screen opens but the toggle will not stick. The app no longer offers a Fix button that leads nowhere:
  it shows the **adb command with a "this TV blocks it" note** instead (status screen), and
  `POST /permissions/fix` answers **501** with that reason + command rather than opening a dead screen.
  A neutral `default` op (the normal case) still gets the on-screen Fix button.

## [v0.11.0] — 2026-08-23 (visible updates, and a confirmation that can actually be confirmed)
Field report: pressing Install in Home Assistant made "a screen flash by" on the TV and then nothing.
Diagnosed on hardware: on Android < 12 the installer's confirmation dialog, launched blind from a
background receiver, flashes and vanishes (TCL, Android 11) — the session then waits forever on a
dialog nobody can reach. On Android 12+ the install is silent and over in seconds, so nothing ever
acknowledged the button press.
### Added
- **"Installing PiPup vX…" popup** (with countdown bar) when an update starts via `POST /update` or
  the app's own update popup.
- **Confirmation popup with a button** when the installer demands on-screen confirmation
  (Android < 12): pressing OK launches the system dialog from a *visible window*, so it keeps focus
  instead of flashing away. Repeatable — `POST /update` while a confirmation is pending shows the
  popup again instead of answering "already running".
- **"PiPup updated to vX" popup** after the app replaced itself (screen on only; suppressed on the
  very first run of a version that introduces the marker).
### Notes
- These flows run in the *new* version, so they become visible from the next update cycle onward —
  updating *to* 0.11.0 still uses the old, silent code.

## [v0.10.2] — 2026-08-23 (diagnose: raw app-op modes)
Field follow-up: a TV whose *Install unknown apps* screen shows **Allowed** while the app reports the
self-update permission as MISSING. Those two really can disagree, and the boolean could not say why.
### Added
- `/permissions/diagnose` now reports the **raw app-op modes** (`opModes`) for the install and overlay
  grants. The interesting value is `default` — the state every reinstall resets to, for which
  `canRequestPackageInstalls()` answers **false** on the devices measured here, whatever a Settings
  toggle may show. Also new: `installCheckError` (a swallowed exception in the check used to be
  indistinguishable from a revoked permission), `user` (app-ops are per profile — a Settings screen
  viewed under another profile shows that profile's state) and `targetSdk`.

## [v0.10.1] — 2026-08-22 (security/robustness audit)
Full audit of the fork (all findings verified against a concrete failure scenario before fixing).
### Security
- **Request bodies are now capped at 256 KB.** `/notify` allocated a buffer of whatever
  `Content-Length` claimed — one LAN request with a large header (or an unbounded chunked body) was an
  out-of-memory crash of the service. Verified on hardware: a 900 MB Content-Length now gets HTTP 400
  and the service stays up.
### Fixed
- **Popups longer than ~24.8 days vanished instantly**: the removal delay was computed as `Int * 1000`
  before widening to `Long`, so it overflowed negative and removed the popup immediately.
- **A self-update whose on-TV confirmation was never accepted blocked all future updates** until a
  service restart: the "installing" flag never cleared. It is now a deadline (15 min) — an abandoned
  attempt can be replaced.
- **Every snapshot popup leaked one file descriptor** (`BitmapFactory.decodeStream` does not close its
  stream); a corrupt upload now also gets a clean error instead of a null bitmap.
- Removed the dead `CONNECTIVITY_CHANGE`/`WIFI_STATE_CHANGED` manifest filters: Android has not
  delivered those broadcasts to manifest receivers since API 24/26, so the suggested
  start-on-network-change never happened on any supported device.

## [v0.10.0] — 2026-08-22 (status screen: calm, readable, and honest about what is optional)
All three from one field report (HA forum).
### Changed
- **Screen on/off is no longer presented as a problem.** A TV without the power grants showed a
  permanent yellow *MISSING* with a Fix button — for a feature that is entirely optional — and its
  owner understandably kept pressing it through disables, enables and reboots. The two power routes
  (device admin / accessibility) are now ONE calm line: green *configured (via …)* when either route
  works, neutral *optional, not configured* with a short explanation when neither does. Required
  permissions (overlay, self-update) keep the loud treatment.
- **The permission panel starts mid-screen** instead of at the bottom edge: compact logo/status header,
  and the panel gets the whole lower half, so on most devices nothing needs scrolling.
- **Bigger text** (18sp headline, 15sp explanation, 14sp adb command — was 14/12/11): this is a
  10-foot UI and the old sizes were reported as "very difficult to see".

## [v0.9.1] — 2026-08-19 (device admin: report what is observable)
### Fixed
- `permissions.deviceAdmin` reported `null` on a Fire TV stick (AFTKRT, Android 11) whose admin is
  registered and whose `lockNow()` works — because `hasSystemFeature(FEATURE_DEVICE_ADMIN)` is false
  there. `/state` then contradicted itself: `power.sleepMethod: "device_admin"` next to
  `permissions.deviceAdmin: null`. An active admin is proof and now outranks the flag; `null` means
  only "not active, and no sign the platform supports it".
- The fix button for device admin no longer hides behind that flag either. Measured, the flag is wrong
  in both directions (false where admins register, and reported true on Fire OS 9), so the honest
  filter is the placeholder check that already refuses Fire OS's `CTSDummyDeviceAdminActivity`.

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
