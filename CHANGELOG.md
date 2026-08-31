# Changelog

All notable changes to this fork ([mhoogenbosch/PiPup](https://github.com/mhoogenbosch/PiPup)) are
documented here. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/).
Original app by [rogro82](https://github.com/rogro82/PiPup).

Every version below has a [GitHub release](https://github.com/mhoogenbosch/PiPup/releases) with the
full story (English and Dutch) and the APK.

## [v0.20.1] — 2026-08-31 (the fallback cap no longer races a slow player)
### Fixed
- 0.20.0's 8-second fallback could still fade the poster into a black player shell on a slow device: on a
  TCL Google TV the page committed after 5.7 s and the cap fired at 13.7 s — occasionally just before the
  video's first frame. The watcher now reports when it *finds* a `<video>` element, and a page with one is
  exempt from the cap: its poster simply stays until the video actually plays. The cap (now 20 s) only
  remains for pages whose watcher reports nothing at all (broken or blocked JS).

## [v0.20.0] — 2026-08-31 (the poster waits for the video on web popups)
### Changed
- **On a `web_url` popup the poster now fades when the page's *video* actually plays**, not when the page
  paints. For an MJPEG/snapshot page nothing changes (its paint *is* the image — a watcher reports
  "no video" within ~0.4 s). But a player page such as go2rtc's WebRTC viewer paints its shell seconds
  before the stream flows, and 0.19.x faded the poster at that paint — leaving a black hole until WebRTC
  connected. The watcher also looks inside shadow DOM (go2rtc's `video-rtc` element) and a hard 8 s cap
  keeps a broken page from pinning the poster forever. This makes **WebRTC + poster** the best camera
  route on TVs: instant still, then near-realtime video (sub-second lag, full frame rate), with the slow
  WebRTC start-up fully masked. `firstFrameMs` now measures to actual playback on such pages.

## [v0.19.3] — 2026-08-31 (self-update works on Android 6: ISRG Root X1 bundled)
### Fixed
- **Self-update failed on Android 6 with "Trust anchor for certification path not found" (#41).** GitHub's
  release assets live on `*.githubusercontent.com`, whose TLS chain anchors on ISRG Root X1 (Let's Encrypt) —
  a root Android only ships from 7.1.1, which is why the update *check* (api.github.com, USERTrust root)
  succeeded while the *download* failed. The updater's connections now trust the system store **plus** the
  bundled ISRG Root X1, with full chain validation (no trust-all anywhere); on modern devices nothing
  changes. Integrity stays double-locked regardless: the platform refuses an update APK with a different
  signing certificate or a lower versionCode.

## [v0.19.2] — 2026-08-31 (compact buttons: the margins scale along)
### Fixed
- With `buttonSize` set, the margins **around** the buttons (including the gap between the media frame and
  the button row) stayed at the classic size while the buttons themselves shrank — a compact popup carried a
  full-size gap above its buttons. The margins now scale with the same factor as the button padding; the
  classic look (no `buttonSize`) is unchanged.

## [v0.19.1] — 2026-08-31 (button corners unclipped; `padding`; install errors visible in /state)
Field report with a screenshot (#40 follow-up) and a stuck self-update with an empty `update.error` (#41).
### Added
- **`padding`** (px): the popup's outer margin around content; the classic look is 20, `0` gives a
  near-borderless popup.
### Fixed
- **The buttons' bottom rounded corners were clipped** against the popup border: the button row had no
  bottom margin, and the popup clipped its children (which also trimmed the focus scale-up and slide
  animations at the edges). Bottom margin added; the popup and the button row no longer clip children.
- **A synchronous self-update failure was invisible.** `installLatest`'s early failures — a failed download
  (HTTP error or exception: DNS, TLS/old root store) or a `PackageInstaller` commit exception — only went to
  logcat; `/state.update.error` stayed `null` and the update just seemed to do nothing (exactly the #41
  report: Install pressed, nothing happened, `error: null`). Those paths now set `update.error`, and a
  successful commit clears it.

## [v0.19.0] — 2026-08-31 (compact buttons; entrance/exit animations; TCL keep-alive by default)
Requested (#40): smaller popups were impossible with three buttons dictating the minimum width, and an
entrance animation was wished for.
### Added
- **`buttonSize`** (sp): scales the button text and its padding together, so a popup with buttons can be
  genuinely small. Without the field the classic look is unchanged.
- **`animation`**: `fade`, `slide_left`, `slide_right`, `slide_top`, `slide_bottom` — plays when a popup is
  **built**; an update-in-place of the same popup deliberately does not re-animate (a camera popup
  re-notified every few seconds must not keep sliding in). A popup that **expires naturally** animates out
  the same way; a replace, `/cancel` or button press still tears down instantly, so 0.17.1's "200 = gone
  from `/state`" contract holds. Unknown names show instantly. `/state.lastPopup.animation` echoes it.
### Changed
- **`install.sh` / `install.ps1` enable the accessibility service by default on TCL Google TVs**
  (`--no-accessibility` / `-NoAccessibility` to opt out). Finding from issue #38: a process with a
  system-bound accessibility service sits at `oom_score_adj` 100 ("visible"), out of reach of TCL's vendor
  guard that kills and freezes background apps — a stronger and quieter keep-alive than the activity-start
  route (200). Measured on a TCL Google TV (Android 11) and confirmed on a second owner's set that had
  been dropping off for weeks. The service itself stays dormant. The README's TCL section leads with it now.

## [v0.18.1] — 2026-08-29 (the built-in chime is actually audible over HDMI)
### Fixed
- The 0.18.0 chime (0.42 s) played — logcat showed every frame delivered — but was inaudible on a Nokia 8010
  feeding a soundbar: an HDMI/eARC audio path takes a few hundred ms to open when a new stream starts, and the
  whole chime fell into that gap. The built-in `default` sound is now **1.9 s with a 300 ms silent lead-in**
  and a fuller three-note tail, at full level; the same clip, hosted as a URL, was clearly audible on that TV.
  If you use your own `sound` URL, give it a short silent lead-in too.

## [v0.18.0] — 2026-08-29 (seen over the screensaver; optional notification sound)
Requested (#34): popups were invisible while the Android TV screensaver / ambient mode was showing, and a
chime would help draw attention when someone is at the door.
### Added
- **Screensaver handling.** Measured on a Google TV (Android 11) the overlay actually sits *above* the
  `DreamActivity` and is visible — but on other builds the dream layer is higher, and Android 12+ lets a
  dream hide all app overlays (`setHideOverlayWindows`). An app cannot raise its own z-order, so the fix that
  works everywhere is to **end the screensaver when a popup arrives** (the same wake path as `POST /power`),
  so the popup shows on whatever was behind it. The service tracks `DREAMING_STARTED/STOPPED`; `/state`
  reports `dreaming`. New field **`dismissScreensaver`** (default `true`; `false` keeps the screensaver).
- **`sound`** (optional): `"default"` plays a built-in short chime, any other value is a URL/URI of an audio
  clip; **`soundVolume`** (0–1) scales it. Played once when a popup is newly built — an update-in-place of the
  same popup does **not** replay it (a motion popup re-notified every few seconds would otherwise ding
  constantly). Transient audio focus with ducking: the TV's audio dips and comes back. Like TTS this opens
  an audio path, and some Fire TVs renegotiate HDMI audio briefly when that happens — hence opt-in.
  `/state.lastPopup.sound` tells whether a sound was requested. Both fields also accepted on multipart.

## [v0.17.2] — 2026-08-29 (software video decoding where the hardware decoder breaks the TV's own picture)
Field report (Xiaomi laser projector, Android 6.0.1, Amlogic): text and snapshot popups were harmless, but
closing a **live video popup froze the HDMI source** behind it — logcat showed the Amlogic hardware decoder
(`OMX.amlogic.avc.decoder.awesome`) resetting its surface generation on release. A TCL on Android 8 was
fine. On Amlogic SoCs the MediaCodec decoder and the HDMI input share one video layer, and our decoder
being released took that layer with it. (The popup already renders into a TextureView since 0.15.1, so the
view type is not the cause; the decoder is.)
### Added
- **`softwareDecoder`** (optional) on `media.video`: `true` decodes in software (the vendor hardware decoder
  is never touched), `false` forces hardware, absent = **automatic**: software on **Android < 8** and wherever an
  **Amlogic** H.264 decoder is present, hardware everywhere else — so nothing changes on Fire TV, Google TV
  or Nokia boxes. Decoder fallback is enabled, so a failing software decoder still falls through to hardware.
  Software decoding is fine for a camera sub-stream (640×480 … 720p); a 1080p main stream may be heavy on a
  low-end projector — pick the sub-stream there. Not verified on Amlogic hardware by us (none in the fleet);
  the flag is there so the reporter can flip it either way.
### Changed
- **Orderly ExoPlayer teardown** on every device: `stop()` → detach the TextureView → `release()`, instead of a
  bare `release()` while the decoder is still bound to the surface.

## [v0.17.1] — 2026-08-29 (`/notify` and `/cancel` answer once `/state` reflects the change)
The HTTP thread used to post the popup to the main thread and reply `200` at once. Measured on a Nokia
8010, `/state` read ~20 ms after that reply still showed no popup; ~75 ms later it did. A client that
refreshes its state right after the call (the Home Assistant integration's popup sensor, since ha-pipup
1.15.1) saw the previous state and stayed stale until its next poll.
### Changed
- **`/notify` waits for the popup view to be built before answering**, so a `200` now means "the popup
  is on screen and `/state` shows it". It waits for the view, not for its media — an RTSP handshake or
  a WebView start-up never holds the reply. `/cancel` likewise answers after the popup is gone. If the
  main thread does not get to the request within 2 s (stuck UI), the reply is still `200` with
  "accepted; … still queued" rather than a hung connection.
- A popup that **fails to build now answers `500`** ("popup could not be created") instead of `200` —
  before, such a failure was only visible in logcat while the caller believed the popup was shown.
### Fixed
- **Two requests in quick succession could silently drop the second.** The hand-off to the main thread
  used the popup handler, and `createPopup`/`removePopup` clear that handler's queue
  (`removeCallbacksAndMessages(null)`) — a second `/notify` still queued behind the first was discarded
  while its caller had been told `200`. Requests now go through their own handler.

## [v0.17.0] — 2026-08-29 (poster: never an empty popup while the stream connects)
A live popup opened as an empty box for the seconds an RTSP handshake, a keyframe wait or a WebView
start-up takes — measured 5–6 s (RTSP) and ~1 s (WebView) on a Fire TV and a Nokia 8010 — exactly the
seconds that matter when someone is at the door.
### Added
- **`poster`** (optional) on `media.video` and `media.web`: URL of a still image (e.g. a camera snapshot)
  shown over the stream area the moment the popup appears and **faded out (150 ms) on the stream's first
  rendered frame** — `onRenderedFirstFrame` for video, `onPageCommitVisible` for web (deliberately not
  `onPageFinished`, which never fires for an MJPEG stream). If the poster fails to load nothing happens; if
  the stream never paints, the poster simply stays — a snapshot from seconds ago beats an empty frame, so
  there is no timeout. Update-in-place of the same popup keeps the running stream and lays no new poster
  over it. Additive: payloads without `poster` behave exactly as before.
- The stream area **takes the poster's own aspect** as soon as it is decoded (a snapshot has the camera's
  aspect), so still and live line up exactly with no size jump at hand-over. For `web` this replaces the
  caller's height hint.
- **`/state.lastPopup.firstFrameMs`**: milliseconds from popup creation to the first rendered frame of a
  video/web popup (`null` until painted). Makes the start-up cost — and the poster's gain — measurable from
  the Home Assistant diagnostics. `lastPopup.media.poster` tells whether a poster was used.

## [v0.16.0] — 2026-08-29 (all video via ExoPlayer + TextureView; VideoView removed)
Follow-through on the v0.15.1 finding: the HLS/http `video_url` path (and therefore the HA integration's
`camera_mode: stream`) still used the stock `VideoView` and had the same hardware-video-plane problem.
### Changed
- **Every `video_url` now plays through ExoPlayer rendering into a `TextureView`** — `rtsp://` (RTP over TCP
  forced), **HLS `.m3u8`** (new `media3-exoplayer-hls` module) and progressive http. Composited by the GPU
  inside the popup, so video popups **show over a film that is already playing** on every route, not only
  RTSP. The stock `VideoView` is gone, and with it its system "Can't play this video" dialog (the
  `BadTokenException` crash class) and the "can freeze concurrent live-TV playback" caveat.
- **Audio follows `muted`** on all video routes: `muted: true` (the HA integration's default) selects no
  audio track at all — opening an `AudioTrack` renegotiated HDMI audio and interrupted playback; `muted:
  false` plays the stream's audio as before.
- A failing stream is logged (`ExoPlayer error … <code>`) and never crashes the app.

## [v0.15.1] — 2026-08-29 (RTSP renders — also over video that is already playing)
v0.15.0 played RTSP but showed only the popup frame with no picture, and starting the stream blanked the
screen and interrupted the film that was playing on the TV.
### Fixed
- **RTSP renders into a `TextureView`, not a `SurfaceView`.** Measured on a Fire TV with SurfaceFlinger:
  the decoder rendered frames into the SurfaceView layer, but while another app was playing video
  (which owns the hardware video overlay plane) nothing was composited — the popup showed a transparent
  hole with the film through it, and the same popup rendered fine with no video playing. A TextureView
  is composited into the popup window by the GPU (AOSP: *"TextureView is always composited using GL"*),
  so it needs no overlay plane: RTSP now shows **moving video over a playing film**, with no black
  flash. Verified on a Fire TV (Android 9) with the film running. (Not usable for DRM content —
  irrelevant for a camera stream.)
- Popup **sized to the real video aspect** on `onVideoSizeChanged`; the size is applied to the video view,
  not the popup itself, so the popup keeps its configured position and its title/message (an interim
  build re-centred it and pushed the text out).
- The **audio track is disabled** for the RTSP popup: a camera popup needs no sound, and opening an
  `AudioTrack` switched the TV's audio output (HDMI renegotiation) and interrupted what was playing.
- Note: `camera_mode: stream` / HLS `video_url` still use the stock `VideoView` (a SurfaceView) and keep
  the known caveat of contending with video already playing; RTSP is the path that does not.

## [v0.15.0] — 2026-08-28 (RTSP video via ExoPlayer; remote passes through on Android <8)
Field report: a direct `video_url: "rtsp://…"` didn't play (`MediaPlayer` error 1,-2147483648 / "No content
provider") on Android 6 and 8, though it works in Alex Savin's separate app. Also on Android 6.0.1 the
remote's D-pad/Back/Home were swallowed by a popup until it expired.
### Added
- **RTSP playback via ExoPlayer (media3).** `rtsp://` / `rtsps://` `video_url`s now play through ExoPlayer's
  RTSP module (RTP-over-TCP forced — UDP is unreliable on Wi-Fi) instead of the stock `VideoView`, which
  cannot play RTSP reliably. Everything else (http/HLS, `camera_entity`) stays on `VideoView`, and the
  media3 classes only load when an rtsp URL is actually shown — no impact on other popups or devices.
### Fixed
- On Android < 8 a button-less popup (`TYPE_SYSTEM_ALERT`) could swallow the remote's D-pad/Back/Home
  until it expired; it now also sets `FLAG_NOT_TOUCHABLE`, so it is fully input-transparent and keys reach
  the app behind it. Android 8+ (`TYPE_APPLICATION_OVERLAY`) already passed input through and is unchanged.

## [v0.14.3] — 2026-08-28 (self-update installs the newest release, not the last-checked one)
Field report: a TV running v0.13.0 was asked to update while v0.14.2 was out, and installed v0.14.0.
### Fixed
- The `/update` endpoint skipped its GitHub check whenever the **cached** release tag was already
  newer than the running build (`if (!UpdateManager.updateAvailable) UpdateManager.check()`).
  `installLatest()` then used the `downloadUrl` that the previous check had cached, so a cache from
  before a newer release handed the installer that older APK. The skip only pays off while the cache
  is fresh — which it is precisely *not* on a TV that has been behind for a while, and that is the
  one case where this endpoint gets used. **The check now always runs on an install request.** A
  failed check (offline, GitHub's anonymous 60/h rate limit) leaves the previous cache untouched, so
  it still falls back to a known older release rather than doing nothing.
- `UpdateManager.installLatest()` now documents that it installs the last *checked* release rather
  than whatever is newest on GitHub at that moment — the name suggests otherwise and that is what
  made the behaviour above easy to miss.

## [v0.14.2] — 2026-08-28 (no crash on a failing direct video_url / RTSP stream)
Field report (Android 6.0.1 projector): a direct `video_url: "rtsp://…"` crashed the app instantly.
### Fixed
- `VideoView` shows its built-in "Can't play this video" `AlertDialog` on any playback error or stall
  (common with direct `rtsp://` URLs). This overlay runs from a `Service` with no activity window
  token, so `Dialog.show()` threw `WindowManager$BadTokenException` and crashed the app. Added an
  `OnErrorListener` that returns `true` (error handled → no dialog), so a failing/stalling stream can
  no longer crash PiPup — the popup just stays hidden and is removed by its own duration timer.
  Affects any Android version; surfaced by a direct RTSP stream.

## [v0.14.1] — 2026-08-28 (fix Android 6/7 crash on first request)
Field report: on Android 6.0.1 the app crashed at the first HTTP request with
`NoClassDefFoundError: java.lang.BootstrapMethodError` from inside Jackson's `ObjectMapper`.
### Fixed
- `java.lang.BootstrapMethodError` only exists from Android 8.0 (API 26). Jackson references it in
  `ExceptionUtil` from **2.14.0** onward, so `ObjectMapper` fails to load on API 23–25 and crashes the
  request thread (`/state`, `/notify`, …) — which made v0.14.0 unusable on the very Android 6 devices it
  was meant to support. **Jackson pinned back to 2.13.5** (the last line without that reference);
  `kotlin-reflect` pinned to the project Kotlin version so it matches the stdlib. No API change.

## [v0.14.0] — 2026-08-27 (runs on Android 6.0.1 / API 23)
Reported: install failed with `INSTALL_FAILED_OLDER_SDK` on projectors running Android 6.0.1.
### Changed
- **`minSdk` lowered from 24 to 23**, so the app installs on Android 6.0.1 (API 23) — e.g. the
  Android that ships on many projectors.
- **Overlay window type on Android < 8** now uses `TYPE_SYSTEM_ALERT` (draws over other apps with the
  overlay permission and can take input focus, so buttons work) instead of `TYPE_TOAST` (shows but
  never focuses, and was restricted from Android 7.1). No effect on Android 8+ devices, which use
  `TYPE_APPLICATION_OVERLAY`.
- **`leanback` feature is no longer required** and the launcher activity also declares the normal
  `LAUNCHER` category, so plain-Android devices (not just Android TV) install it and get an icon.

The rest of the code was already version-guarded (foreground service, notification channel, screen
wake via the pre-8.1 window flags, direct-boot prefs, self-update). Verified to build; runtime on
Android 6 depends on the device.

## [v0.13.0] — 2026-08-25 (an icon beside the title and message)
Requested (#19): show an icon next to the popup's title/message, notification-style.
### Added
- Optional **`icon`** field (an image URL, loaded like other media) shown beside the title/message block,
  with **`iconPosition`** `left` (default) or `right` and **`iconWidth`** (pixels, default 96). Available
  on both the JSON and multipart `/notify` endpoints. The text now sits in a column next to the icon; the
  media image stays below, and a media-only popup drops the empty header row.

## [v0.12.2] — 2026-08-24 (visible focus on multi-button popups)
Reported (#18): on a popup with multiple buttons, D-pad navigation worked — the correct button fired —
but nothing on screen showed which button was focused, so it looked unresponsive.
### Fixed
- In an overlay window the platform button background carries no focus state, so moving focus with the
  remote was invisible. Each button now has an explicit **focused/unfocused background** (a bright fill
  when focused, a subtle translucent one otherwise) plus a small **scale bump** on focus, and the first
  button takes focus when the popup appears — so there is a highlight from the start.

## [v0.12.1] — 2026-08-24 (a remote-initiated update no longer stalls invisibly)
Field report: pressing Install in Home Assistant for a sleeping Fire TV (Android 9) did nothing visible,
and afterwards it only said an update was already running.
### Fixed
- Android < 12 cannot install without an on-screen confirmation (the install permission does not change
  that). The app already turns that into a popup with a button, but it did **not wake the screen** — so
  on a sleeping TV the confirmation sat on a black screen and the update stalled invisibly. The app now
  **wakes the screen** when a confirmation is pending, so one remote press finishes it.
- The stalled state (`installing: true`) used to linger for 15 minutes after an unconfirmed attempt. The
  pending install is now **released as soon as its confirmation popup goes away** unconfirmed, so the
  update can be retried immediately.
- `/state.update` gained **`pendingUserAction`** (waiting for the on-screen confirmation) and **`silent`**
  (false on Android < 12, where an install cannot complete without a remote press), so a controller can
  say "confirm on the TV" instead of a bare "installing".

## [v0.12.0] — 2026-08-24 (comes back after a silent power-cut boot)
Field report: after a mains power cut and restore, if the TV boots to standby without being turned on
and is later woken over ADB, PiPup never starts — the connectivity sensor stays offline until the app
is opened by hand from the app drawer.
### Fixed
- **Root cause:** `BOOT_COMPLETED` — which the app already listens for to restart itself — is only
  broadcast once the device reaches a fully started, unlocked user session. A TV that boots to standby
  after a power restore never reaches that point, and waking it over ADB does not re-fire the boot
  broadcast, so the service stayed down.
- The boot receiver **and** the service are now `directBootAware` and also listen for
  **`LOCKED_BOOT_COMPLETED`** (plus `QUICKBOOT_POWERON` for OEM fast-boot). These fire in the early
  locked-boot phase, before turn-on, so the service starts on a silent power-restore boot too.
- App preferences (the stable device id and version markers — none of them sensitive) moved to
  **device-protected storage** so they are readable during direct boot; the existing file is migrated
  once, so the device id — and therefore the Home Assistant unique_id — stays the same.

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
