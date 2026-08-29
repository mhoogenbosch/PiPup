# PiPup

Enhanced notifications for Android TV / Fire TV — show popups (text, images, video or live camera
streams) on your TV from your home-automation system, for **as long as you want**.

> **Credits:** PiPup was originally created by [Rob Groenendijk (rogro82)](https://github.com/rogro82/PiPup).
> This repository is a maintained fork of that project — all credit for the original idea and
> implementation goes to him. The fork modernizes the build (AndroidX, AGP 8, Kotlin 2, targetSdk 34)
> and adds the features below, aimed at Home Assistant use.

![](graphics/screenshot-1.png)

## What this fork adds (compared to [rogro82/PiPup](https://github.com/rogro82/PiPup))

- **Indefinite popups** — `duration: 0` (or negative) shows a popup until it is cancelled or replaced,
  e.g. show a camera stream for exactly as long as there is motion.
- **Popup `id` + update-in-place** — re-sending a notify with the same `id` and content only reschedules
  the removal timer without rebuilding the view, so a video/web stream keeps playing without flicker.
- **`/state` endpoint** — popup visibility, screen on/off (`screenOn`, since 0.2.3), popup counter,
  uptime, device info and a **stable device id** (since 0.2.5).
- **`/cancel`** (existed upstream but undocumented) with optional selective `?id=`.
- **`/notify` and `/cancel` answer once `/state` reflects the change** (since 0.17.1) — a `200` means the
  popup is on (or off) screen, so a client may read `/state` straight after the call. The reply waits for
  the view, not for its media to load. A popup that fails to build answers `500`.
- **Muted media** (since 0.2.4) — `muted: true` on video/web media plays without audio, so a popup
  never claims audio focus (audio in a popup can freeze video playback on some devices).
- **Text-to-speech** (since 0.2.5) — a `tts` field speaks a text on the TV when the popup appears,
  with optional `ttsLanguage` (BCP-47).
- **mDNS/zeroconf discovery** (since 0.2.5) — the app advertises `_pipup._tcp` with a stable device
  id, so clients (like the Home Assistant integration) find TVs automatically and follow them across
  DHCP address changes.
- **Overlay watchdog** (since 0.2.6) — popup removal is guarded step-by-step and a 30s consistency
  check force-removes any overlay left behind by a failed teardown, so a popup can no longer stay
  on screen after its dismiss. `/state` reports `watchdogCleanups` so you can see if it ever fired.
- **Buttons on the popup** (since 0.3.0) — `buttons: [{id, label}]` renders remote-operable buttons:
  the overlay only becomes focusable when buttons are present (it never steals the remote
  otherwise), **OK** activates (POST `{popup, button, label, device, name}` to the `callback` URL
  and dismiss), **BACK** dismisses without an action.
- **Countdown bar** (since 0.3.0) — `showProgress: true` animates a progress bar over a finite duration.
- **Urgency presets** (since 0.3.0) — `urgency: info|warning|critical` adds a blue/orange/red border.
- **Custom border styling** (since 0.7.0) — `borderColor`, `borderWidth` and `cornerRadius` style the
  popup frame yourself; each field overrides its part of the `urgency` preset, so the preset stays a
  shorthand and `borderWidth: 0` switches its border off again. Also available on uploaded snapshots
  (multipart), which previously ignored `urgency` and `showProgress` entirely.
- **Icon beside the text** (since 0.13.0) — an optional `icon` (image URL) shown next to the
  title/message, notification-style, with `iconPosition` (`left`/`right`) and `iconWidth`.
- **Poster** (since 0.17.0) — an optional `poster` (image URL) on `video` and `web` media: a still
  (e.g. a camera snapshot) shown over the stream area the moment the popup appears and faded out on
  the stream's first rendered frame. A live popup never opens as an empty box while the RTSP handshake
  or WebView start-up runs; the stream area takes the poster's aspect, so still and live line up. If the
  stream never paints the poster simply stays. `/state.lastPopup.firstFrameMs` reports the time to first frame.
- **Screen on/off** (since 0.7.0) — `POST /power?state=on|off|toggle` wakes the TV or puts it in
  standby, without a second integration for ADB or HDMI-CEC. `/state` publishes what is actually
  possible on this device (`power.canSleep`, `power.sleepMethod`) instead of accepting a request it
  cannot honour. See [Screen on/off](#screen-onoff).
- **Permission screen with fix buttons** (since 0.8.0) — the status screen on the TV lists every
  permission with its actual state, and a **Fix** button next to the missing ones that jumps straight
  to the system screen where it is granted, operable with the remote. `POST /permissions/fix` does
  the same from a controller (the Home Assistant integration has a button, an action and a
  self-fixing repair). Where a device has no such screen — or where the permission is one the device
  actively **blocks** (some TVs lock "install unknown apps" for sideloaded apps at system level, e.g.
  Samsung's Auto Blocker; since 0.11.1) — the app shows the adb command instead of a dead button. See
  [Permission screen](#permission-screen).
- **Permission reporting + installers** (since 0.7.0) — `/state` reports what the app was granted
  (`permissions.overlay`, `installPackages`, vendor `autoStart`, `deviceAdmin`, `accessibility`) and the
  status screen on the TV warns when the overlay permission is missing — until now that failure was
  invisible: every popup was answered with HTTP 200 and nothing appeared. `install.sh` / `install.ps1`
  ship with each release and do the whole install *including* the app-ops that an app cannot grant
  itself and that every reinstall resets.
- **Localization** (since 0.3.1) — the app UI follows the device language (English/Dutch).
- **Lazy TTS engine** (since 0.4.0) — the speech engine is only bound when a popup actually carries
  a `tts` field and is released again after 60s idle. On Google TV devices this keeps the separate
  ~100MB `com.google.android.tts` process out of memory, which matters a lot on 1GB TVs where the
  low-memory killer picks the heaviest processes.
- **Restart after an update** (since 0.6.2) — the app listens for `MY_PACKAGE_REPLACED`, so the service
  comes back by itself after a self-update (or an `adb install -r`). Before this, replacing the APK left the
  TV silently offline until something started the service again.
- **Starts on a silent power-restore boot** (since 0.12.0) — the boot receiver and the service are
  `directBootAware` and also listen for `LOCKED_BOOT_COMPLETED`, so the service comes up in the early
  locked-boot phase. `BOOT_COMPLETED` alone is only broadcast once the device reaches an *unlocked*
  session, which a TV that boots to standby after a mains-power cut may never reach until it is turned
  on — so before this the app stayed down after a power outage until it was opened by hand. App prefs
  (device id, version markers) live in device-protected storage so they survive direct boot; the id is
  migrated in place and stays the same.
- **Visible updates** (since 0.11.0) — an update started via the Install button (or `POST /update`) shows
  an "Installing PiPup vX…" popup with a countdown, and where the system demands on-screen confirmation
  (Android < 12) the app turns that into a popup **with a button** — a press gives the installer the
  visible window it needs, instead of a confirmation dialog that flashes past and strands the update.
- **Crash fix: repeated start requests** (since 0.6.1) — `startForeground()` is now called on *every*
  `startForegroundService()` (i.e. also in `onStartCommand`), not only on creation. Without it Android
  killed the process with `RemoteServiceException: Context.startForegroundService() did not then call
  Service.startForeground()`, so every keep-alive attempt — an automation, or the connectivity Receiver —
  crashed the app instead of keeping it alive. `onStartCommand` also revives the web server when it is no
  longer alive.
- **Resilient web server startup** (since 0.4.0) — binding port 7979 is retried (3 attempts, 500ms
  apart) and a definitive failure stops the service for a clean restart, instead of leaving a live
  process with a dead server behind.
- **Self-update** (since 0.6.0) — the app checks the fork's GitHub releases twice a day and can install a
  newer version itself: it announces a new release once on screen with an **Install** button, exposes
  `update` in `/state`, and accepts `POST /update` to trigger the update (used by the Home Assistant
  integration's update entity). Android only accepts an APK signed with the same key, so a tampered
  download can never replace the app. On **Android 12+** the self-update is silent; on older devices the
  system shows its install confirmation on the TV, which someone has to accept with the remote (see the
  limitation below). Grant the install permission once (survives updates, not reinstalls):
  `adb shell appops set nl.rogro82.pipup REQUEST_INSTALL_PACKAGES allow`
- WebView media supports JavaScript, DOM storage and unattended (autoplay) playback, and cleartext
  (http) LAN URLs are allowed — required for camera streams from e.g. go2rtc/Frigate.
- Assorted fixes (request-body handling, message size/color defaults, WebView cleanup).

**Home Assistant users:** there is a companion integration —
[mhoogenbosch/ha-pipup](https://github.com/mhoogenbosch/ha-pipup) — with a config flow per TV,
a popup binary sensor and `pipup.show` / `pipup.dismiss` actions (including camera entities).

## Supported devices

**Minimum: Android 6.0.1 (API 23).** Built for Android TV / Fire TV, but since 0.14.0 it also
installs on plain-Android devices (projectors, TV boxes) and gets a normal launcher icon there.

Everything core works on **every** supported version — popups (text, image, video, web, camera),
muted media, TTS, remote-operable buttons, countdown bar, urgency/border styling, the icon beside the
text, `/state`, `/notify`, `/cancel`, mDNS discovery, the overlay watchdog, and turning the screen
**on**. A few things depend on the Android version:

| Capability | Works on |
|---|---|
| Popups, TTS, buttons, styling, `/state`, screen **on** | **6.0.1+** (all) |
| Overlay rendering | 6–7 via `TYPE_SYSTEM_ALERT` (needs the overlay app-op — the installer grants it); 8+ via `TYPE_APPLICATION_OVERLAY` |
| **`video_url`** — `rtsp://`, HLS `.m3u8` (incl. `camera_mode: stream`), progressive http (since 0.16.0) | **6.0.1+**, via ExoPlayer rendered in a `TextureView` — composited by the GPU inside the popup, so it also shows **over video the TV is already playing** (verified on a Fire TV with a film running). RTSP uses RTP-over-TCP. Audio only with `muted: false`. Not for DRM content (irrelevant for cameras). |
| MJPEG camera streams | use **`web_url`** (or the HA integration's `camera_mode: mjpeg`), never `image_url` — `image_url` decodes a single still image and cannot render a multipart MJPEG stream (it shows only the text). For a still, point `image_url` at a snapshot such as Frigate's `/api/<cam>/latest.jpg`. |
| Screen **off** (`POST /power?state=off`) | any version, after a one-time device-admin **or** accessibility grant (`--power` / `--accessibility`); which route works depends on the device |
| **Silent** self-update | **12+** only. On older devices the update still works but the system shows an install confirmation the app wakes the screen for and turns into a popup with a button — one press on the remote finishes it (see [the limitation](https://github.com/mhoogenbosch/ha-pipup#the-update-button-is-silent-only-on-android-12)) |
| Restart after a **silent power-restore boot** (`LOCKED_BOOT_COMPLETED`) | **7.0+** (direct boot). On Android 6 there is no direct boot, so a restart relies on the normal `BOOT_COMPLETED` — fine on a TV/projector without a lock screen |
| `specialUse` foreground-service type | 14+ (cosmetic; older run a normal foreground service) |

> **Android 6 is not hardware-tested by the maintainer** (no API 23 device on hand) — the code paths
> are version-guarded and the APK builds and runs without regression on the Android 9+ fleet, but if
> you hit something on a 6.0.1 device please open an issue with the `/permissions/diagnose` output.

## Installation (sideloading)

### Prerequisite: enable ADB debugging on the TV

Sideloading requires ADB over the network, which is off by default:

- **Android TV / Google TV:** Settings → System → About → press **Build number 7 times**
  (unlocks Developer options) → Settings → System → Developer options → enable
  **USB debugging** (on recent Google TV also **Wireless debugging**).
- **Fire TV:** Settings → My Fire TV → About → press the device name 7 times →
  My Fire TV → Developer options → enable **ADB debugging**.

Then connect from your computer with `adb connect <tv-ip>:5555` and accept the
authorization prompt on the TV (once per computer).

### Install

Grab `install.sh` (Linux/macOS/WSL) or `install.ps1` (Windows) from the
[releases](../../releases) page and point it at your TVs:

```
./install.sh 192.168.1.10 192.168.1.11          # downloads the latest APK itself
./install.sh --power --apk PiPup.apk 192.168.1.10
```

It installs the APK, grants the app-ops below, starts the service and verifies over HTTP that the
app is actually answering with the overlay permission in place. `--power` also activates the device
admin (for screen off), `--accessibility` enables the fallback for that, and `--force-uninstall`
handles a differently-signed build that is already installed — note that uninstalling wipes the
app's stable device id, so Home Assistant sees a new device afterwards.

Sleeping TVs are **left asleep**: the service is started in the background, which does not touch
what is on screen. On a TCL Google TV use `--wake`, because its vendor guard freezes a service
started from the background (see below) — there the app has to come up in the foreground.

> **This is also the way to update Android < 12 TVs silently.** The in-app self-update installs silently
> only on Android 12+; on older devices the OS forces an on-screen confirmation for any *app*-initiated
> install (a platform limit — see [self-update](#what-this-fork-adds-compared-to-rogro82pipup)). A
> **shell**-initiated `adb install -r`, which is what `install.sh` does, has no confirmation on any
> Android version. Run it on a schedule (cron, or a Home Assistant `shell_command`) to keep older TVs
> updated with no interaction — the [ha-pipup readme](https://github.com/mhoogenbosch/ha-pipup#the-update-button-is-silent-only-on-android-12)
> has a ready-made automation.

<details>
<summary>Doing it by hand</summary>

```
adb connect <tv-ip>:5555
adb install -r PiPup.apk
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
adb shell appops set nl.rogro82.pipup REQUEST_INSTALL_PACKAGES allow
```

The overlay permission has no settings UI on Android TV, and the install permission is what lets the
app apply its own updates. **Both are app-ops**: an app cannot grant them to itself (that is
shell/system territory), which is why they are handed out over adb — and why `adb install -r`
**resets them**, so they have to be granted again after every install. `/state` and the status screen
on the TV show whether the overlay permission is currently in place.

If you have the original Play Store version installed you need to uninstall that first (different
signature, same application id).
</details>

_After installation or updating, open the application once (or reboot the TV) to make sure the
background service is running._ Starting the service without bringing the app to the foreground
(handy from an automation, it does not interrupt whatever is playing) also works:

```
adb shell am start-foreground-service -n nl.rogro82.pipup/.PiPupService
```

> **Not on TCL Google TV.** There a background start lands the service at `oom_score_adj` 500 and
> the vendor guard freezes it within seconds. Start the activity instead — see below.

#### TCL Google TVs: vendor guard kills and freezes the app

TCL ships an extra guard (`com.tcl.guard`) with two separate mechanisms. Both look like an app bug
and neither is caused by memory pressure — measured on a 1 GB set, PiPup used 26 MB PSS while the
launcher used 119 MB and the screensaver 91 MB.

**1. It blocks the automatic restart** of a killed service unless the app holds the vendor-specific
`APP_AUTO_START` app-op — it logs `forbid restart Servic ... callee_does't_have_OP_AUTO_START_permission`
and the service never comes back after a kill. The on-screen menu ("Permission Guardian" →
"Auto-start permission") keeps per-app entries locked while its "Automatic management" master switch
is on, so grant the op over adb instead (note the internal name `android:auto_start`; the displayed
name `APP_AUTO_START` is not accepted):

```
adb shell cmd appops set nl.rogro82.pipup android:auto_start allow
adb shell dumpsys deviceidle whitelist +nl.rogro82.pipup
```

Like the overlay permission the app-op resets on reinstall, so repeat it after every update. On
brands without this op (Fire TV, Nokia, …) the command fails with `Unknown operation string` — that
is fine, nothing needs granting there. The deviceidle entry survives reboots and stops
`am_stop_idle_service` from tearing the service down.

**2. It freezes processes** (`persist.sys.freeze=true`, independent of the AOSP freezer). A frozen
process is **alive but SIGSTOPped**, which is why this failure mode is so confusing: `ps` still
lists PiPup while port 7979 no longer answers, so clients hang in a timeout instead of getting a
connection error. Recognise it like this:

```
adb shell 'P=$(pidof nl.rogro82.pipup); grep freezer /proc/$P/cgroup; cat /proc/$P/oom_score_adj'
#  frozen  ->  5:freezer:/frozen   ...  500
#  healthy ->  5:freezer:/thaw     ...  200
adb shell netstat -ltn | grep 7979   # frozen: Recv-Q > 0 on LISTEN, plus CLOSE_WAIT rows
```

Incoming traffic does not thaw the app; only bringing it to the foreground does. **What keeps it
running is `oom_score_adj` 200, and the app only reaches that when it is started from a foreground
context**, i.e. via the activity:

```
adb shell input keyevent KEYCODE_WAKEUP   # only needed while the screensaver is on
adb shell am start -n nl.rogro82.pipup/.MainActivity
adb shell input keyevent KEYCODE_HOME
```

Started this way the app stays up, screensaver included. Two caveats: `am start -W` **hangs** while
the TV is dreaming (use it without `-W`), and this briefly takes over the screen, so avoid it while
someone is watching. It is worth automating the recovery — the
[ha-pipup integration README](https://github.com/mhoogenbosch/ha-pipup#device-notes-tcl-google-tv-needs-a-keep-alive-automation)
has a ready-made Home Assistant automation, including the pitfall that a `ps | grep pipup` guard
silently defeats it (a frozen process is still listed).

## Security

PiPup runs an embedded webserver (NanoHTTPD) on port **7979** with **no authentication**,
and a popup's `web` media is rendered in a WebView with JavaScript and DOM storage enabled.
That means **any device on the same network can display arbitrary content — including
JavaScript — on the TV.** This is by design (camera/stream pages need it), but it makes the
trust boundary the network itself.

- Run PiPup TVs on a **trusted network segment** (not a guest/IoT VLAN that untrusted devices share).
- Traffic is plain HTTP (`usesCleartextTraffic`), so treat everything sent to the popup — URLs,
  TTS text, button callbacks — as visible on the LAN.
- Button presses POST to the `callback` URL supplied with the popup. If you drive security-sensitive
  automations from button events (e.g. unlocking a door), have the caller include an unguessable,
  single-use token in that callback URL and verify it on receipt — the
  [ha-pipup integration](https://github.com/mhoogenbosch/ha-pipup) does this automatically.
- Since 0.7.0 the same unauthenticated port also accepts `POST /power`, so anyone who can reach the TV
  can switch its screen on or off. That is annoying rather than dangerous, but it is a reason not to
  grant the screen-off route (device admin / accessibility) on a TV you deliberately expose. Granting
  nothing leaves `/power?state=off` returning 501, and the device admin only asks for `force-lock` —
  no password, camera or wipe policies — so the worst an attacker gains is a TV that goes to standby.

## Integrating

PiPup runs an embedded webserver (NanoHTTPD) on port **7979**.

### Sending notifications

#### JSON (external media: image, video or webview)

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example:

```json
{
  "duration": 30,
  "id": "doorbell",
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#0066cc",
  "titleSize": 20,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#000000",
  "messageSize": 14,
  "backgroundColor": "#ffffff",
  "media": { "image": {
    "uri": "https://your.host/image.png", "width": 480
  }}
}
```

All fields are optional. For `media` you can specify 3 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480, "muted": true }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480, "muted": true }}
```

`poster` (since 0.17.0, video and web): URL of a still image shown over the stream area until the
stream renders its first frame, then faded out. Use a camera snapshot (e.g. Frigate
`/api/<cam>/latest.jpg`) so the popup shows a picture instantly instead of an empty frame while
RTSP connects or the WebView starts. The stream area takes the poster's aspect, so still and live
match. If the poster fails to load nothing happens; if the stream never paints, the poster stays.
`/state.lastPopup.firstFrameMs` reports the time to first frame.

```json
{ "video": { "uri": "rtsp://cam/sub", "width": 640, "poster": "http://frigate:5000/api/cam/latest.jpg" }}
```

`muted` (since 0.2.4, default `false`): plays the video/web media without audio. For web media every
(also dynamically added) `<video>`/`<audio>` element on the page is muted, so the page never claims
audio focus — audio in a popup can freeze video playback on some Android TV / Fire TV devices.

`tts` (since 0.2.5): a text that is spoken aloud on the TV when the popup appears, using the
device's text-to-speech engine. Optional `ttsLanguage` takes a BCP-47 tag (e.g. `"nl-NL"`);
the device's default locale is used when omitted. Re-sending the same popup `id` with unchanged
content and unchanged `tts` does **not** repeat the speech (only the removal timer is extended);
sending a different `tts` text speaks the new text.

```json
{ "title": "Doorbell", "tts": "Er staat iemand voor de deur", "ttsLanguage": "nl-NL" }
```

Since 0.3.0 three more optional fields:

```json
{
  "urgency": "critical",
  "showProgress": true,
  "buttons": [{ "id": "unlock", "label": "Open the door" }],
  "callback": "http://your-ha:8123/api/webhook/pipup_buttons"
}
```

`urgency` (`info`/`warning`/`critical`) adds a blue/orange/red border. `showProgress` animates a
countdown bar over a finite `duration`. `buttons` (with a `callback` URL) renders remote-operable
buttons: the overlay only takes input focus when buttons are present, **OK** activates the focused
button — the app POSTs `{"popup", "button", "label", "device", "name"}` to the callback and
dismisses — and **BACK** dismisses without an action.

Since 0.7.0 the border can be styled directly, beyond the three urgency presets:

```json
{
  "borderColor": "#00E5FF",
  "borderWidth": 10,
  "cornerRadius": 28
}
```

| Field          | Type / default                                                        |
| -------------- | --------------------------------------------------------------------- |
| `borderColor`  | String `[AA]RRGGBB` (default: the urgency color, else `#ffffff`)       |
| `borderWidth`  | Integer pixels (default: the urgency width, else `4` when a color is given; `0` = no border) |
| `cornerRadius` | Number pixels (default: `8` when a border is drawn, else `0`)          |

Each field independently overrides the `urgency` preset, so the two combine: `urgency: "critical"`
with `borderWidth: 2` keeps the red but makes it thin, and `borderWidth: 0` removes the preset's
border while keeping any other styling. `cornerRadius` works without a border too, for rounded
corners on a plain popup. Sizes are in **pixels**, like every other dimension in this API (media
width, padding) — on a 1080p TV a border of 10 is comfortably visible. An unparseable color falls
back to the default instead of dropping the popup.

Since 0.13.0 an icon can be shown beside the title/message (notification-style):

```json
{
  "icon": "http://your-ha:8123/local/icons/doorbell.png",
  "iconPosition": "left",
  "iconWidth": 96
}
```

`icon` is an image URL, loaded like the other media. `iconPosition` is `left` (default) or `right`;
`iconWidth` is in pixels (default 96, aspect ratio preserved). The title and message sit in a column
next to the icon, and the `media` image (if any) stays below.

- `duration`: seconds to show the popup. **`0` or negative shows it indefinitely**, until `/cancel`
  is called or a new popup replaces it.
- `id` (string, optional): identifies the popup. Re-sending a notify with the same `id` and identical
  content only reschedules the removal timer — the view (and a playing video/web stream) is kept as-is.
  Different content (or no `id`) rebuilds the popup as before.

#### multipart/form-data (uploaded image file)

| Property      | Value               |
| ------------- | ------------------- |
| Path:         | /notify             |
| Method:       | POST                |
| Content-Type: | multipart/form-data |

Form-fields:

| Field           | Type                                         |
| --------------- | -------------------------------------------- |
| duration        | Integer (default=30, 0=indefinite)           |
| id              | String (optional popup identifier)           |
| position        | Integer (0..4, default=0)                    |
| title           | String                                       |
| titleSize       | Integer (default=16)                         |
| titleColor      | string (default=#FFFFFF, format=[AA]RRGGBB   |
| message         | String                                       |
| messageSize     | Integer (default=12)                         |
| messageColor    | String (default=#FFFFFF, format=[AA]RRGGBB   |
| backgroundColor | String (default=#CC000000, format=[AA]RRGGBB |
| image           | File                                         |
| imageWidth      | Integer (default=480)                        |
| tts             | String (optional, spoken aloud, since 0.2.5) |
| ttsLanguage     | String (optional BCP-47 tag, since 0.2.5)    |
| urgency         | String info/warning/critical (since 0.7.0)   |
| borderColor     | String (format=[AA]RRGGBB, since 0.7.0)      |
| borderWidth     | Integer pixels (since 0.7.0)                 |
| cornerRadius    | Number pixels (since 0.7.0)                  |
| icon            | String image URL (since 0.13.0)              |
| iconPosition    | String left/right (default=left, since 0.13.0) |
| iconWidth       | Integer pixels (default=96, since 0.13.0)    |
| showProgress    | Boolean (default=false, since 0.7.0)         |

`position` is an enum ranging from 0 to 4:

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional, e.g. #FFFFFF or #CCFFFFFF.

### Cancelling a popup

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /cancel          |
| Method:       | POST             |

Removes the currently visible popup (if any). Optionally pass `?id=<popup id>` to only cancel when
the visible popup has that id — e.g. `POST /cancel?id=doorbell`. If the visible popup has a different
id the call is a no-op (HTTP 200 with an explanatory message), so a delayed "hide camera" automation
cannot accidentally cancel a newer, unrelated popup.

### Screen on/off

| Property      | Value                        |
| ------------- | ---------------------------- |
| Path:         | /power?state=on\|off\|toggle |
| Method:       | POST                         |

Since 0.7.0. Answers with the result rather than a bare "accepted":

```json
{ "state": "off", "ok": true, "method": "device_admin", "screenOn": false }
```

HTTP 200 when it was carried out, **501** when this device has no way to do it, 400 on a missing or
unknown `state`. (`screenOn` in the reply can lag one poll behind on `state=on`: the wake activity is
still starting up.)

**On** needs nothing: PiPup launches an invisible activity with `setTurnScreenOn(true)`, which is the
supported way to wake a device. On HDMI-CEC setups waking the box also switches the TV to its input.

**Off** is the one capability that can genuinely be missing, because no sideloaded app may put a
device to sleep on its own. There are two routes, and PiPup uses whichever is granted (device admin
first). Grant one **once**, over adb — `install.sh --power` / `--accessibility` do exactly this:

```
# route 1 (preferred): device admin. Only asks for force-lock, nothing else.
adb shell dpm set-active-admin nl.rogro82.pipup/.AdminReceiver

# route 2: accessibility fallback, for devices without the device-admin feature
adb shell settings put secure enabled_accessibility_services \
    nl.rogro82.pipup/nl.rogro82.pipup.PiPupAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Route 2 exists because a fair number of Android TV boxes ship **without** the device-admin feature at
all (`dumpsys device_policy` shows `mHasFeature=false`). Confusingly, `dpm set-active-admin` still
prints `Success` there while nothing is registered — so trust `/state`, not `dpm` (the installer
scripts verify it that way and tell you to switch routes). Verified on hardware:

| Device | Android | Screen on | Screen off |
| --- | --- | --- | --- |
| Fire TV stick (AFTKA) | 9 (Fire OS) | ✓ | ✓ device admin |
| Nokia Streaming Box 8010 | 14 | ✓ | ✓ accessibility (no device-admin feature) |
| TCL Google TV | 11 | ✓ | ✓ accessibility (no device-admin feature) |

Two things seen while testing: a wake request that arrives **within a few seconds of putting the
device to sleep** can be ignored while the sleep transition is still completing (a second call works),
and on Android 13+ an accessibility service enabled over adb can be revoked again by the system — the
switch's `can_sleep` attribute (and `/state`) show that immediately.

⚠️ **When appending to `enabled_accessibility_services`, keep the existing value** (colon-separated) —
overwriting it disables other accessibility services, such as Projectivy Launcher's. The installer
scripts append; the snippet above only holds for a device with none enabled.

⚠️ **Like the app-ops, this grant does not survive a reinstall.** Replacing the APK (`adb install -r`,
including an update) drops the app out of the enabled list, and screen-off silently stops working —
`/state` reports `power.canSleep: false` from then on. Re-run `install.sh --accessibility`, or include
the flag in the install itself. The device admin route does not have this problem.

The accessibility service declares no event types and does not retrieve window content: it is bound
purely so `GLOBAL_ACTION_LOCK_SCREEN` can be called, and reads nothing from your screen.

`/state` publishes the capability so a client can hide a button it cannot honour:

```json
"power": { "canWake": true, "canSleep": true, "sleepMethod": "device_admin" }
```

### Permission screen

| Property      | Value                                                    |
| ------------- | -------------------------------------------------------- |
| Path:         | /permissions/fix[?what=overlay\|install\|admin\|accessibility\|next] |
| Method:       | POST                                                     |

Since 0.8.0. Puts the screen that grants a permission in front of the user and wakes the TV first.
Without `what` it opens PiPup's own status screen, which lists every permission with its own **Fix**
button; `what=next` jumps to the first missing one.

```json
{ "what": "overlay", "ok": true, "granted": false, "adb": null }
```

**The app still cannot grant anything itself** — these are app-ops, which only shell or the system
may set. What it can do is walk someone holding a remote to the exact spot, which is the part that
was missing.

Whether that spot exists differs per device, and *asking* is not enough: every Android build must
resolve these intents to pass Google's compatibility suite, so a plain "is there an activity for
this?" says yes even where nothing happens. Fire OS answers with `CTSDummyIntentHandler`, Google TV
with `frameworkpackagestubs.Stubs`. PiPup treats those placeholders as absent, because a button that
visibly does nothing is worse than no button: `/state` then reports the permission as not fixable,
`/permissions/fix` answers **501**, and both the TV screen and the reply carry the adb command.

Measured on hardware:

| | Fire OS 9 | Google TV 11 | Android 14 |
| --- | --- | --- | --- |
| Overlay permission | adb only | ✓ on screen | ✓ on screen |
| Self-update permission | ✓ on screen | ✓ on screen | ✓ on screen |
| Device admin | adb only | not supported by the platform | not supported by the platform |
| Accessibility | adb only | adb only | ✓ on screen |

`/state` publishes this as `permissions.fixable`, so a controller can show a button only where it
leads somewhere.

#### Permissions the device blocks outright

A settings screen that opens but whose toggle **will not stick** is a third case, distinct from a
missing screen. Some devices lock a permission at system level for sideloaded apps — Samsung's Auto
Blocker, or a TCL that keeps "install unknown apps" off — which shows up as an app-op stuck in the
`errored` or `ignored` state (`/permissions/diagnose` reports `opModes`). Since 0.11.1 PiPup treats
such an op as not fixable on screen: `fixIntent` returns nothing, the status screen shows the adb
command with a "this TV blocks it from its settings screen" note, and `POST /permissions/fix` answers
**501** with that reason and the command — rather than opening a screen where nothing happens. A
neutral `default` op (the normal case) still gets the on-screen Fix button.

#### The one case that cannot work remotely

**Without the overlay permission, the fix screen cannot be opened from Home Assistant.** From Android
10 on, starting an activity from the background is blocked unless the app is exempt, and holding
`SYSTEM_ALERT_WINDOW` is one of the exemptions — while a foreground service is
[explicitly not](https://developer.android.com/guide/components/activities/background-starts). So the
one permission you most want a button for is the one whose absence takes the button away. A blocked
launch does not even throw: the platform drops it silently.

PiPup therefore checks up front and answers **501** with
`reason: "Android blocks starting an activity from the background…"` instead of reporting success and
doing nothing. The way out is a visible window of the app: **open PiPup on the TV** (from the launcher,
or by tapping its ongoing notification — a notification tap is another exemption) and press the **Fix**
button on its status screen, which is running in the foreground and therefore allowed. Or grant it over
adb and never think about it again.

Once the overlay permission *is* granted, everything else — `install`, `accessibility` — opens fine from
Home Assistant with the app in the background.

### Diagnosing "the fix button does nothing"

| Property      | Value                     |
| ------------- | ------------------------- |
| Path:         | /permissions/diagnose     |
| Method:       | GET (or POST)             |

Since 0.9.0, and the first thing to attach to a bug report — no adb or logcat needed:

```json
{
  "sdk": 30,
  "device": { "model": "Smart TV", "manufacturer": "TCL", "android": "11" },
  "backgroundLaunchExempt": true,
  "activityVisible": false,
  "deviceAdminSupported": false,
  "screens": {
    "overlay": {
      "granted": true,
      "action": "android.settings.action.MANAGE_OVERLAY_PERMISSION",
      "resolvedActivity": "com.android.tv.settings.device.apps.specialaccess.SystemAlertActivity",
      "placeholder": false,
      "fixable": true,
      "adb": "adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow"
    }
  },
  "lastFix": { "what": "overlay", "ok": false, "activity": null, "error": "…", "secondsAgo": 42 }
}
```

Read it as: `resolvedActivity` null means nothing handles that intent *or* the platform hides it from
the app; `placeholder: true` means a vendor stub answered and a button would do nothing;
`backgroundLaunchExempt: false` means no launch can happen at all right now (see above); and `lastFix`
says how the previous attempt actually ended. The Home Assistant integration includes this block in its
diagnostics download.

Note on `resolvedActivity`: from Android 11 on, `resolveActivity()` is a *query* and queries are
filtered by package visibility, while `startActivity()` is not — "I cannot see it" is not "it is not
there". The app declares these intents in `<queries>` so it can see them, and treats an unresolved
intent as worth trying rather than impossible. A device whose `forceQueryable` list omits Settings
would otherwise hide a perfectly working button (`adb shell dumpsys package queries` shows that list). Note `deviceAdmin: null` on the last two: those platforms have no device
administration at all (`hasSystemFeature(FEATURE_DEVICE_ADMIN)` is false) — a different answer from
"not granted", and worth distinguishing because `dpm set-active-admin` reports `Success` there
anyway.

### State

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /state           |
| Method:       | GET (or POST)    |

Returns the current state as JSON:

```json
{
  "app": "PiPup",
  "version": "0.7.0",
  "id": "6f1f9c1e-4a3f-4a44-9d2c-6f1f9c1e4a3f",
  "name": "FireTV Veranda",
  "visible": true,
  "screenOn": true,
  "popupsShown": 12,
  "watchdogCleanups": 0,
  "uptime": 86400,
  "device": { "model": "AFTKA", "manufacturer": "Amazon", "android": "9" },
  "popup": { "id": "doorbell", "duration": 0, "indefinite": true, "elapsed": 42 },
  "power": { "canWake": true, "canSleep": true, "sleepMethod": "device_admin" },
  "permissions": {
    "overlay": true, "installPackages": true, "autoStart": null,
    "deviceAdmin": true, "accessibility": false, "complete": true,
    "fixable": { "overlay": false, "install": true, "admin": false, "accessibility": false }
  }
}
```

Since v0.7.0 `permissions` reports what the app was actually granted, and `power` what it can do with
the screen (see [Screen on/off](#screen-onoff)). `overlay: false` is the one to watch: popups are then
accepted with HTTP 200 and stay invisible. `autoStart` is TCL's vendor app-op and is `null` on every
device that does not have it — that is "not applicable", not a problem. `complete` is the short answer
to "can this device show popups at all".

Since v0.5.0 the response also contains `lastPopup`: the parameters of the last *received* popup
(id, position, duration, muted, media type/size, tts, buttons, secondsAgo) — it survives dismiss/expiry,
so you can always verify what your home-automation actually sent. The same block is rendered live on
the app's status screen on the TV.

Since v0.2.3 `/state` also reports whether the screen is on/interactive (`screenOn`), the number of
popups shown since the service started (`popupsShown`), the service uptime in seconds and basic
device info — all surfaced as entities by the Home Assistant integration. Since v0.2.5 it also
reports a stable device `id` (generated once, survives app updates) and the device `name`; since
v0.2.6 `watchdogCleanups` counts how often the overlay watchdog had to force-remove a stale popup.

### Discovery

Since v0.2.5 the app advertises itself over mDNS/zeroconf as `_pipup._tcp` (port 7979) with TXT
records `id` (the stable device id), `name` and `version`, enabling automatic discovery.

## Building

CI builds an APK on every push (see `.github/workflows/build.yml`); tagged releases get the APK
attached automatically. Locally: JDK 17 + Android SDK 35, then `./gradlew assembleDebug`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) — every version also has a
[GitHub release](https://github.com/mhoogenbosch/PiPup/releases) with the full story and the APK.
