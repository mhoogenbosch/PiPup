package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayAspectTest {
    @Test
    fun squarePixelsKeepStoredShape() {
        assertEquals(360, heightForDisplayAspect(640, 1920, 1080, 1f))
    }

    @Test
    fun anamorphic640x360WithThreeFourSampleAspectShowsAsFourThree() {
        assertEquals(480, heightForDisplayAspect(640, 640, 360, 0.75f))
    }

    @Test
    fun ntscD1WithTenElevenSampleAspectShowsAsFourThree() {
        assertEquals(480, heightForDisplayAspect(640, 704, 480, 10f / 11f))
    }

    @Test
    fun unknownRatioFallsBackToSquarePixels() {
        assertEquals(360, heightForDisplayAspect(640, 640, 360, 0f))
    }
}
