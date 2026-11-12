package nl.rogro82.pipup

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterModeTest {
    @Test
    fun missingOrUnknownModeKeepsPosterDrivenSizing() {
        listOf(null, "", "unknown").forEach { assertNull(parsePosterMode(it)) }
    }

    @Test
    fun explicitModesSelectImageScalingWithoutPosterDrivenSizing() {
        assertEquals(PosterMode.Fit, parsePosterMode("fit"))
        assertEquals(PosterMode.Fill, parsePosterMode("fill"))
        assertEquals(PosterMode.Stretch, parsePosterMode("stretch"))
        assertEquals(PosterMode.Center, parsePosterMode("center"))
        assertEquals(PosterMode.Fit, parsePosterMode("FIT"))
    }

    @Test
    fun jsonAcceptsModesOnAllStandaloneStreamTypesAndPreservesDefaults() {
        val mapper = jacksonObjectMapper()
        for (type in listOf("video", "web", "whep")) {
            for (mode in listOf(null, "fit", "fill", "stretch", "center", "unknown")) {
                val field = mode?.let { ",\"posterMode\":\"$it\"" } ?: ""
                val json = "{\"media\":{\"$type\":{\"uri\":\"http://localhost/stream\"$field}}}"
                val popup = mapper.readValue(json, PopupProps::class.java)
                val actual = when (val media = popup.media) {
                    is PopupProps.Media.Video -> media.posterMode
                    is PopupProps.Media.Web -> media.posterMode
                    is PopupProps.Media.Whep -> media.posterMode
                    else -> error("Unexpected media type")
                }
                assertEquals(mode, actual)
                assertEquals(popup, mapper.readValue(mapper.writeValueAsString(popup), PopupProps::class.java))
            }
        }
    }
}
