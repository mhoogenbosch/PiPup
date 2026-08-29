package nl.rogro82.pipup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonIgnoreProperties(ignoreUnknown = true)
data class PopupProps(
    val duration: Int = DEFAULT_DURATION, // seconds; 0 or negative = show until /cancel or replaced
    val id: String? = null,               // optional identifier: re-notify with the same id and content only reschedules the timer (no view rebuild), /cancel?id= cancels selectively
    val position: Position = DEFAULT_POSITION,
    val backgroundColor: String = DEFAULT_BACKGROUND_COLOR,
    val title: String? = null,
    val titleSize: Float = DEFAULT_TITLE_SIZE,
    val titleColor: String = DEFAULT_TITLE_COLOR,
    val message: String? = null,
    val messageSize: Float = DEFAULT_MESSAGE_SIZE,
    val messageColor: String = DEFAULT_MESSAGE_COLOR,
    val media: Media? = null,
    // Optional icon shown beside the title/message block (image URL, loaded like media).
    val icon: String? = null,
    val iconPosition: String? = null,     // "left" (default) or "right"
    val iconWidth: Int = DEFAULT_ICON_WIDTH, // pixels
    val tts: String? = null,              // optional text spoken on the device when the popup is (re)shown
    val ttsLanguage: String? = null,      // optional BCP-47 tag (e.g. "nl-NL"); device default when omitted
    val urgency: String? = null,          // info | warning | critical: colored border preset
    // Explicit border styling; each field overrides the urgency preset on its own, so
    // `urgency` keeps working as a shorthand. Sizes are in pixels, like every other
    // dimension in this API (media width, padding) - see the px/dp TODO in PopupView.
    val borderColor: String? = null,      // #RRGGBB or #AARRGGBB
    val borderWidth: Int? = null,         // 0 = no border, also to switch an urgency border off
    val cornerRadius: Float? = null,      // 0 = square corners
    val showProgress: Boolean = false,    // countdown bar for popups with a finite duration
    val buttons: List<Button> = emptyList(), // DPAD-focusable buttons; pressing one POSTs to callback and dismisses
    val callback: String? = null,         // URL that receives {"popup","button","device"} on a button press
    // 0.18.0: end an active screensaver (DreamService / ambient mode) before showing the popup,
    // so it is seen on every Android version - on some builds the dream layer sits above app
    // overlays, and Android 12+ lets the dream hide them altogether. false keeps the screensaver.
    val dismissScreensaver: Boolean = true,
    // 0.18.0: optional notification sound when a popup is (newly) shown: "default" = built-in
    // chime, or a URL to an audio clip. Not replayed on an update-in-place of the same popup.
    val sound: String? = null,
    val soundVolume: Float? = null        // 0..1; device notification volume when omitted
) {
    val indefinite: Boolean
        get() = duration <= 0

    /// equal except for duration and tts: safe to keep the existing view and only reschedule removal
    fun sameContent(other: PopupProps): Boolean =
        copy(duration = 0, tts = null, ttsLanguage = null, sound = null, soundVolume = null) ==
                other.copy(duration = 0, tts = null, ttsLanguage = null, sound = null, soundVolume = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Button(val id: String, val label: String)

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(
        JsonSubTypes.Type(Media.Video::class, name = "video"),
        JsonSubTypes.Type(Media.Image::class, name = "image"),
        JsonSubTypes.Type(Media.Web::class, name = "web")
    )
    sealed class Media {
        @JsonIgnoreProperties(ignoreUnknown = true)
        // `poster` (optional, video + web): URL of a still image shown immediately over the
        // stream area and faded out on the first rendered frame, so a live popup never opens
        // as an empty box while the stream connects (RTSP handshake, WebView start-up).
        // `softwareDecoder` (optional, video): true = decode in software (bypass the vendor
        // hardware decoder), false = always hardware, null/absent = automatic (software on
        // Android < 8 and on Amlogic SoCs, whose hardware decoder shares the video layer with
        // the HDMI input and freezes it when released - reported on a Xiaomi projector).
        data class Video(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH, val muted: Boolean = false,
                         val poster: String? = null, val softwareDecoder: Boolean? = null): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Image(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Web(val uri: String, val width: Int = 640, val height: Int = 480, val muted: Boolean = false,
                       val poster: String? = null): Media()
        data class Bitmap(val image: android.graphics.Bitmap, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
    }

    // NB: the multipart parser and the ha-pipup integration both map an integer
    // position onto this enum by ORDINAL (values()[n]) — the declaration order is
    // therefore a contract. Do not reorder; append new positions at the end only.
    enum class Position {
        TopRight,   // 0
        TopLeft,    // 1
        BottomRight, // 2
        BottomLeft, // 3
        Center      // 4
    }

    companion object {
        const val DEFAULT_DURATION: Int = 30
        const val DEFAULT_BACKGROUND_COLOR = "#CC000000"
        const val DEFAULT_TITLE_SIZE = 16f
        const val DEFAULT_TITLE_COLOR = "#ffffff"
        const val DEFAULT_MESSAGE_SIZE = 12f
        const val DEFAULT_MESSAGE_COLOR = "#ffffff"
        const val DEFAULT_MEDIA_WIDTH = 480
        const val DEFAULT_ICON_WIDTH = 96           // px; icon beside the title/message
        const val DEFAULT_BORDER_WIDTH = 4          // a borderColor without a borderWidth
        const val DEFAULT_BORDER_COLOR = "#ffffff"  // a borderWidth without a borderColor
        const val DEFAULT_CORNER_RADIUS = 8f        // whenever a border is drawn

        val DEFAULT_POSITION: Position = Position.TopRight

        /// urgency shorthand -> border width (px) and color
        val URGENCY_PRESETS: Map<String, Pair<Int, String>> = mapOf(
            "info" to (4 to "#2196F3"),
            "warning" to (6 to "#FF9800"),
            "critical" to (8 to "#F44336")
        )
    }
}
