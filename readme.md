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
- **Muted media** (since 0.2.4) — `muted: true` on video/web media plays without audio, so a popup
  never claims audio focus (audio in a popup can freeze video playback on some devices).
- **Text-to-speech** (since 0.2.5) — a `tts` field speaks a text on the TV when the popup appears,
  with optional `ttsLanguage` (BCP-47).
- **mDNS/zeroconf discovery** (since 0.2.5) — the app advertises `_pipup._tcp` with a stable device
  id, so clients (like the Home Assistant integration) find TVs automatically and follow them across
  DHCP address changes.
- WebView media supports JavaScript, DOM storage and unattended (autoplay) playback, and cleartext
  (http) LAN URLs are allowed — required for camera streams from e.g. go2rtc/Frigate.
- Assorted fixes (request-body handling, message size/color defaults, WebView cleanup).

**Home Assistant users:** there is a companion integration —
[mhoogenbosch/ha-pipup](https://github.com/mhoogenbosch/ha-pipup) — with a config flow per TV,
a popup binary sensor and `pipup.show` / `pipup.dismiss` actions (including camera entities).

## Installation (sideloading)

Download the APK from the [releases](../../releases) page and install it with adb.
If you have the original Play Store version installed you need to uninstall that first
(different signature, same application id).

```
adb install -r PiPup.apk
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

The second command grants the overlay permission, which has no settings UI on Android TV.

_After installation or updating, open the application once (or reboot the TV) to make sure the
background service is running._

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

### State

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /state           |
| Method:       | GET (or POST)    |

Returns the current state as JSON:

```json
{
  "app": "PiPup",
  "version": "0.2.5",
  "id": "6f1f9c1e-4a3f-4a44-9d2c-6f1f9c1e4a3f",
  "name": "FireTV Veranda",
  "visible": true,
  "screenOn": true,
  "popupsShown": 12,
  "uptime": 86400,
  "device": { "model": "AFTKA", "manufacturer": "Amazon", "android": "9" },
  "popup": { "id": "doorbell", "duration": 0, "indefinite": true, "elapsed": 42 }
}
```

Since v0.2.3 `/state` also reports whether the screen is on/interactive (`screenOn`), the number of
popups shown since the service started (`popupsShown`), the service uptime in seconds and basic
device info — all surfaced as entities by the Home Assistant integration. Since v0.2.5 it also
reports a stable device `id` (generated once, survives app updates) and the device `name`.

### Discovery

Since v0.2.5 the app advertises itself over mDNS/zeroconf as `_pipup._tcp` (port 7979) with TXT
records `id` (the stable device id), `name` and `version`, enabling automatic discovery.

## Building

CI builds an APK on every push (see `.github/workflows/build.yml`); tagged releases get the APK
attached automatically. Locally: JDK 17 + Android SDK 35, then `./gradlew assembleDebug`.
