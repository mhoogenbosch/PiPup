# PiPup (fork)

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

> **This is a fork of [rogro82/PiPup](https://github.com/rogro82/PiPup)** with a modernized build
> (AndroidX, AGP 8, Kotlin 2, targetSdk 34) and additional features aimed at Home Assistant use:
>
> - **Indefinite popups** — `duration: 0` (or negative) shows a popup until it is cancelled or replaced,
>   e.g. show a camera stream for as long as there is motion.
> - **Popup `id` + update-in-place** — re-sending a notify with the same `id` and content only reschedules
>   the removal timer without rebuilding the view, so a video/web stream keeps playing without flicker.
> - **`/state` endpoint** — query whether a popup is currently visible (used by the
>   [pipup Home Assistant integration](https://github.com/mhoogenbosch/ha-pipup)).
> - **`/cancel` documented** (existed upstream but undocumented) with optional selective `?id=`.
> - WebView media now supports JavaScript, DOM storage and unattended (autoplay) media playback,
>   and cleartext (http) LAN URLs are allowed — required for camera streams from e.g. go2rtc/Frigate.

![](https://github.com/rogro82/PiPup/raw/master/graphics/screenshot-1.png)

__Some example scenarios:__

- Show your camera stream on your TV for as long as there is motion
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful

#### Sideloading:

Download the APK from the [releases](../../releases) page and install it with adb.
If you have the original Play Store version installed you need to uninstall that first
(different signature, same application id).

On Android TV (8.0+), when sideloading, you will need to set the permission for SYSTEM_ALERT_WINDOW manually (using adb) as there is no interface on Android TV to do this.

```
adb install -r PiPup.apk
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

_Important: after installation / updating it is adviced to restart your TV and open the application once to make sure the background-service is running_

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

#### To send notifications with an external media resource (image, url or webview) use application/json


| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /notify          |
| Method:       | POST             |
| Content-Type: | application/json |

Example json data:

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
    "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png", "width": 480
  }}
}
```
All fields are optional and for `media` you can specify 3 types:

```json 
{ "image": { "uri": "address_to_your_image", "width": 480 }}
{ "video": { "uri": "address_to_your_video", "width": 480 }}
{ "web":   { "uri": "address_to_your_resource", "width": 640, "height": 480 }}
```

**Fork additions:**

- `duration`: `0` or negative shows the popup **indefinitely**, until `/cancel` is called or a new popup replaces it.
- `id` (string, optional): identifies the popup. Re-sending a notify with the same `id` and identical content
  only reschedules the removal timer — the view (and a playing video/web stream) is kept as-is.
  Sending different content (or no `id`) rebuilds the popup as before.

#### To send notifications with an image file use multipart/form-data

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

`position` is an enum ranging from 0 to 4

|  | Position    |
| -----: | ----------- |
| 0     | TopRight    |
| 1     | TopLeft     |
| 2     | BottomRight |
| 3     | BottomLeft  |
| 4     | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF

### Cancelling a popup

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /cancel          |
| Method:       | POST             |

Removes the currently visible popup (if any). Optionally pass `?id=<popup id>` to only cancel
when the visible popup has that id — e.g. `POST /cancel?id=doorbell`. If the visible popup has a
different id the call is a no-op (HTTP 200 with an explanatory message), so a delayed "hide camera"
automation cannot accidentally cancel a newer, unrelated popup.

### State

| Property      | Value            |
| ------------- | ---------------- |
| Path:         | /state           |
| Method:       | GET (or POST)    |

Returns the current state as JSON:

```json
{
  "app": "PiPup",
  "version": "0.2.0",
  "visible": true,
  "popup": { "id": "doorbell", "duration": 0, "indefinite": true, "elapsed": 42 }
}
```

## Building

CI builds an APK on every push (see `.github/workflows/build.yml`). Locally: JDK 17 +
Android SDK 35, then `./gradlew assembleDebug`.
