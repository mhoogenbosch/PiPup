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
    val tts: String? = null,              // optional text spoken on the device when the popup is (re)shown
    val ttsLanguage: String? = null,      // optional BCP-47 tag (e.g. "nl-NL"); device default when omitted
    val urgency: String? = null,          // info | warning | critical: colored border preset
    val showProgress: Boolean = false,    // countdown bar for popups with a finite duration
    val buttons: List<Button> = emptyList(), // DPAD-focusable buttons; pressing one POSTs to callback and dismisses
    val callback: String? = null          // URL that receives {"popup","button","device"} on a button press
) {
    val indefinite: Boolean
        get() = duration <= 0

    /// equal except for duration and tts: safe to keep the existing view and only reschedule removal
    fun sameContent(other: PopupProps): Boolean =
        copy(duration = 0, tts = null, ttsLanguage = null) ==
                other.copy(duration = 0, tts = null, ttsLanguage = null)

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
        data class Video(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH, val muted: Boolean = false): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Image(val uri: String, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Web(val uri: String, val width: Int = 640, val height: Int = 480, val muted: Boolean = false): Media()
        data class Bitmap(val image: android.graphics.Bitmap, val width: Int = DEFAULT_MEDIA_WIDTH): Media()
    }

    enum class Position(index: Int) {
        TopRight(0),
        TopLeft(1),
        BottomRight(2),
        BottomLeft(3),
        Center(4)
    }

    companion object {
        const val DEFAULT_DURATION: Int = 30
        const val DEFAULT_BACKGROUND_COLOR = "#CC000000"
        const val DEFAULT_TITLE_SIZE = 16f
        const val DEFAULT_TITLE_COLOR = "#ffffff"
        const val DEFAULT_MESSAGE_SIZE = 12f
        const val DEFAULT_MESSAGE_COLOR = "#ffffff"
        const val DEFAULT_MEDIA_WIDTH = 480

        val DEFAULT_POSITION: Position = Position.TopRight
    }
}
