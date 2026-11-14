package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 0.33.0 (#88, #89): the pure parts of `foreground` in /state and of /screen.jpg.
class ForegroundScreenTest {

    private val own = "nl.rogro82.pipup"

    private fun front(pkg: String?, time: Long, cls: String? = null) = ForegroundApp.Front(pkg, cls, time)

    // ---------------------------------------------------------------- foreground

    @Test
    fun theNewestEventWins() {
        val seen = latestFront(null, listOf(front("a.b", 10), front("c.d", 30), front("e.f", 20)), own)
        assertEquals(ForegroundApp.Seen("c.d", 30), seen)
    }

    @Test
    fun theSameAppKeepsItsSince() {
        val before = ForegroundApp.Seen("a.b", 10)
        // another activity of the same app came to the front
        assertEquals(before, latestFront(before, listOf(front("a.b", 50)), own))
        // also within one batch
        assertEquals(ForegroundApp.Seen("a.b", 10),
            latestFront(null, listOf(front("a.b", 10), front("a.b", 40)), own))
    }

    @Test
    fun noEventsKeepsWhatWasSeen() {
        val before = ForegroundApp.Seen("a.b", 10)
        assertEquals(before, latestFront(before, emptyList(), own))
        assertNull(latestFront(null, emptyList(), own))
    }

    @Test
    fun eventsFromBeforeWhatWasSeenAreSkipped() {
        // the overlap of two looks hands the same old event again
        val before = ForegroundApp.Seen("c.d", 30)
        assertEquals(before, latestFront(before, listOf(front("a.b", 29)), own))
    }

    @Test
    fun theInvisibleWakeActivityIsNotAnApp() {
        val seen = latestFront(null,
            listOf(front("a.b", 10), front(own, 20, "nl.rogro82.pipup.WakeActivity")), own)
        assertEquals(ForegroundApp.Seen("a.b", 10), seen)
        // PiPup's own status screen is one
        assertEquals(ForegroundApp.Seen(own, 20),
            latestFront(null, listOf(front("a.b", 10), front(own, 20, "nl.rogro82.pipup.MainActivity")), own))
    }

    @Test
    fun eventsWithoutAPackageAreSkipped() {
        assertNull(latestFront(null, listOf(front(null, 10), front("", 20)), own))
    }

    @Test
    fun sinceIsWrittenInUtc() {
        assertEquals("1970-01-01T00:00:00Z", isoUtc(0))
        assertEquals("2026-11-14T13:20:00Z", isoUtc(1794662400000L))
    }

    // ---------------------------------------------------------------- screenshot

    @Test
    fun aTokenIsSixteenToHundredTwentyEightUrlSafeCharacters() {
        assertTrue(ScreenShots.validToken("abcdefghijklmnop"))
        assertTrue(ScreenShots.validToken("A1_-" + "x".repeat(124)))
        assertFalse(ScreenShots.validToken("abcdefghijklmno"))       // 15
        assertFalse(ScreenShots.validToken("x".repeat(129)))
        assertFalse(ScreenShots.validToken("abcdefghijklmno p"))
        assertFalse(ScreenShots.validToken("abcdefghijklmnop\""))
        assertFalse(ScreenShots.validToken(""))
        assertFalse(ScreenShots.validToken(null))
    }

    @Test
    fun onlyTheExactTokenMatches() {
        assertTrue(ScreenShots.tokenMatches("abcdefghijklmnop", "abcdefghijklmnop"))
        assertFalse(ScreenShots.tokenMatches("abcdefghijklmnop", "abcdefghijklmnoq"))
        assertFalse(ScreenShots.tokenMatches("abcdefghijklmnop", "abcdefghijklmnopq"))
        assertFalse(ScreenShots.tokenMatches("abcdefghijklmnop", null))
        assertFalse(ScreenShots.tokenMatches("abcdefghijklmnop", ""))
        // no token set = nothing matches, not even nothing
        assertFalse(ScreenShots.tokenMatches(null, null))
        assertFalse(ScreenShots.tokenMatches(null, ""))
        assertFalse(ScreenShots.tokenMatches("", ""))
    }

    @Test
    fun widthDefaultsAndStaysInRange() {
        assertEquals(640, ScreenShots.parseWidth(null))
        assertEquals(640, ScreenShots.parseWidth(" "))
        assertEquals(160, ScreenShots.parseWidth("160"))
        assertEquals(1920, ScreenShots.parseWidth("1920"))
        assertNull(ScreenShots.parseWidth("159"))
        assertNull(ScreenShots.parseWidth("1921"))
        assertNull(ScreenShots.parseWidth("wide"))
    }

    @Test
    fun thePictureKeepsItsShapeAndIsNeverEnlarged() {
        assertEquals(640 to 360, ScreenShots.scaledSize(1920, 1080, 640))
        assertEquals(1920 to 1080, ScreenShots.scaledSize(1920, 1080, 1920))
        assertEquals(1280 to 720, ScreenShots.scaledSize(1280, 720, 1920))
        assertEquals(160 to 1, ScreenShots.scaledSize(4000, 10, 160))
        assertEquals(0 to 0, ScreenShots.scaledSize(0, 0, 640))
    }
}
