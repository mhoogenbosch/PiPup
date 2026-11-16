package nl.rogro82.pipup

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 0.32.0 (#83): the pure parts of /media, /media/seek and /click.
class MediaClickTest {

    // ---------------------------------------------------------------- media

    @Test
    fun stateNamesFollowTheFourStates() {
        assertEquals("playing", stateName(PlaybackState.STATE_PLAYING))
        assertEquals("playing", stateName(PlaybackState.STATE_FAST_FORWARDING))
        assertEquals("playing", stateName(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertEquals("paused", stateName(PlaybackState.STATE_PAUSED))
        assertEquals("buffering", stateName(PlaybackState.STATE_BUFFERING))
        assertEquals("buffering", stateName(PlaybackState.STATE_CONNECTING))
        assertEquals("stopped", stateName(PlaybackState.STATE_STOPPED))
        assertEquals("stopped", stateName(PlaybackState.STATE_NONE))
        assertEquals("stopped", stateName(PlaybackState.STATE_ERROR))
        assertEquals("stopped", stateName(null))
    }

    @Test
    fun positionMovesOnOnlyWhilePlaying() {
        // reported 60 s at t=10 s, now t=15 s, normal speed
        assertEquals(65_000L, extrapolate(60_000, 10_000, 1f, playing = true, nowElapsed = 15_000))
        assertEquals(70_000L, extrapolate(60_000, 10_000, 2f, playing = true, nowElapsed = 15_000))
        assertEquals(60_000L, extrapolate(60_000, 10_000, 1f, playing = false, nowElapsed = 15_000))
        // no update time, or a clock that went backwards: the reported position as is
        assertEquals(60_000L, extrapolate(60_000, 0, 1f, playing = true, nowElapsed = 15_000))
        assertEquals(60_000L, extrapolate(60_000, 20_000, 1f, playing = true, nowElapsed = 15_000))
        // PlaybackState.PLAYBACK_POSITION_UNKNOWN
        assertNull(extrapolate(-1, 10_000, 1f, playing = true, nowElapsed = 15_000))
    }

    @Test
    fun seekTargetClampsToTheTitle() {
        assertEquals(30_000L, seekTarget(10_000, 30_000, null, 100_000))
        assertEquals(0L, seekTarget(5_000, null, -10_000, 100_000))          // not before the start
        assertEquals(100_000L, seekTarget(95_000, null, 10_000, 100_000))    // not past the end
        assertEquals(200_000L, seekTarget(null, 200_000, null, null))        // unknown duration
        assertEquals(15_000L, seekTarget(25_000, null, -10_000, null))
        assertNull(seekTarget(null, null, -10_000, 100_000))                 // by= needs a position
    }

    private fun snap(pkg: String? = "com.plexapp.android", state: String? = "playing", pos: Long? = 60_000,
                     at: Long = 10_000, title: String? = "Film", canSeek: Boolean = true) =
        MediaSessions.Snapshot(pkg, state, pos, 0, at, 5_400_000, 1.0, title, null, null, canSeek)

    @Test
    fun pushesChangesNotProgress() {
        val before = snap()
        assertTrue(MediaSessions.worthPushing(null, before))
        // playback simply moving on: 5 s later, 5 s further
        assertFalse(MediaSessions.worthPushing(before, snap(pos = 65_000, at = 15_000)))
        // within the 2 s margin
        assertFalse(MediaSessions.worthPushing(before, snap(pos = 66_500, at = 15_000)))
        // a seek
        assertTrue(MediaSessions.worthPushing(before, snap(pos = 600_000, at = 15_000)))
        // paused: the position should stand still
        val paused = snap(state = "paused")
        assertFalse(MediaSessions.worthPushing(paused, snap(state = "paused", at = 20_000)))
        assertTrue(MediaSessions.worthPushing(paused, snap(state = "paused", pos = 0, at = 20_000)))
        // what plays changed
        assertTrue(MediaSessions.worthPushing(before, snap(state = "paused", pos = 65_000, at = 15_000)))
        assertTrue(MediaSessions.worthPushing(before, snap(title = "Other", pos = 65_000, at = 15_000)))
        assertTrue(MediaSessions.worthPushing(before, snap(canSeek = false, pos = 65_000, at = 15_000)))
        assertTrue(MediaSessions.worthPushing(before, MediaSessions.Snapshot.NONE))
    }

    @Test
    fun snapshotJsonUsesTheApiNames() {
        val map = MediaSessions.Snapshot("com.app", "paused", 1_000, 1_762_358_400_000, 0, 2_000, 0.0,
            "T", "A", "B", true).toMap()
        assertEquals(listOf("package", "state", "positionMs", "positionUpdatedAt", "durationMs", "speed",
            "title", "artist", "album", "canSeek", "actions", "artKey"), map.keys.toList())
        assertEquals("2025-11-05T16:00:00Z", map["positionUpdatedAt"])
    }

    // ---------------------------------------------------------------- 0.32.0-beta.4: keys and artwork

    @Test
    fun actionNamesFollowThePlaybackStateBits() {
        assertEquals(emptyList<String>(), actionNames(0L))
        assertEquals(listOf("play", "pause", "seek"),
            actionNames(PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY))
        assertEquals(listOf("play_pause", "stop", "next", "previous", "rewind", "fast_forward"),
            actionNames(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD))
    }

    @Test
    fun mediaKeysMapToTransportControls() {
        assertEquals("play", transportAction("play", playing = true))
        assertEquals("pause", transportAction("pause", playing = false))
        assertEquals("pause", transportAction("play_pause", playing = true))
        assertEquals("play", transportAction("play_pause", playing = false))
        assertEquals("stop", transportAction("stop", playing = true))
        assertEquals("skipToNext", transportAction("next", playing = true))
        assertEquals("skipToPrevious", transportAction("previous", playing = true))
        assertEquals("rewind", transportAction("rewind", playing = true))
        assertEquals("fastForward", transportAction("fast_forward", playing = true))
        assertNull(transportAction("home", playing = true))
        // every media key /key accepts has a transport control
        TvControl.MEDIA_KEYS.keys.forEach { assertTrue(it, transportAction(it, false) != null) }
    }

    @Test
    fun artworkScalesDownNeverUp() {
        assertEquals(300 to 200, fitWithin(300, 200, 600))
        assertEquals(600 to 338, fitWithin(1920, 1080, 600))
        assertEquals(400 to 600, fitWithin(1000, 1500, 600))
        assertEquals(600 to 1, fitWithin(6000, 2, 600))
        assertEquals(0 to 0, fitWithin(0, 0, 600))
    }

    @Test
    fun sampleSizeKeepsAtLeastTheTargetSize() {
        assertEquals(1, sampleSize(600, 400, 600))
        assertEquals(1, sampleSize(1199, 600, 600))
        assertEquals(2, sampleSize(1200, 600, 600))
        assertEquals(4, sampleSize(3840, 2160, 600))   // 960 >= 600, 480 would be too small
        assertEquals(1, sampleSize(100, 100, 600))
    }

    @Test
    fun samplePointsStayInsideTheImage() {
        val points = samplePoints(600, 338, 4)
        assertEquals(16, points.size)
        assertEquals(75 to 42, points.first())
        assertEquals(525 to 295, points.last())
        points.forEach { (x, y) -> assertTrue(x in 0 until 600 && y in 0 until 338) }
        assertEquals(List(16) { 0 to 0 }, samplePoints(1, 1, 4))
        assertEquals(emptyList<Pair<Int, Int>>(), samplePoints(0, 100, 4))
    }

    @Test
    fun artKeyFollowsPixelsAndDuration() {
        // YouTube's TV client: empty title, the same bitmap object, the same size from video to video
        val fields = listOf("com.amazon.firetv.youtube", null, null, "Channel", null, null, "212000")
        val source = "android.media.metadata.ART:1280x720"
        val key = artKeyOf(fields, source, listOf(1, 2, 3))
        assertEquals(8, key.length)
        assertEquals(key, artKeyOf(fields, source, listOf(1, 2, 3)))
        assertTrue(key != artKeyOf(fields, source, listOf(1, 2, 4)))
        assertTrue(key != artKeyOf(fields.dropLast(1) + "634000", source, listOf(1, 2, 3)))
        // a link: no samples, the link itself tells pictures apart
        assertTrue(artKeyOf(fields, "http://plex/a.jpg", emptyList()) != artKeyOf(fields, "http://plex/b.jpg", emptyList()))
    }

    @Test
    fun newArtworkOrActionsArePushed() {
        val before = snap()
        val withArt = before.copy(artKey = "0badf00d")
        assertTrue(MediaSessions.worthPushing(before, withArt))
        assertFalse(MediaSessions.worthPushing(withArt, withArt.copy(positionMs = 65_000, atElapsedMs = 15_000)))
        assertTrue(MediaSessions.worthPushing(withArt, withArt.copy(artKey = "12345678")))
        assertTrue(MediaSessions.worthPushing(before, before.copy(actions = listOf("pause"))))
    }

    // ---------------------------------------------------------------- click

    @Test
    fun idMatchesFullBareAndComposeTags() {
        val pkg = "com.plexapp.android"
        assertTrue(idMatches("com.plexapp.android:id/play", pkg, "com.plexapp.android:id/play"))
        assertTrue(idMatches("com.plexapp.android:id/play", pkg, "play"))
        assertTrue(idMatches("com.plexapp.lib:id/play", pkg, "play"))           // a library's resource
        assertTrue(idMatches("action-button-watch", pkg, "action-button-watch")) // Compose testTag
        assertFalse(idMatches("com.plexapp.android:id/play_all", pkg, "play"))
        assertFalse(idMatches("com.other:id/play", pkg, "com.plexapp.android:id/play"))
        assertFalse(idMatches(null, pkg, "play"))
        assertFalse(idMatches("", pkg, "play"))
    }

    @Test
    fun clickListParsesPackageNamesOnly() {
        assertEquals(listOf("com.plexapp.android", "com.google.android.youtube.tv"),
            ClickPolicy.parse(" com.plexapp.android, com.google.android.youtube.tv ;com.plexapp.android"))
        assertEquals(emptyList<String>(), ClickPolicy.parse(""))
        assertEquals(emptyList<String>(), ClickPolicy.parse(null))
        assertEquals(listOf("a.b"), ClickPolicy.parse("a.b nodot 1a.b a..b *"))
        assertEquals(listOf("nodot", "1a.b", "a..b", "*"), ClickPolicy.rejected("a.b nodot 1a.b a..b *"))
    }

    @Test
    fun enabledListMatchesTheWholeComponent() {
        val pkg = "nl.rogro82.pipup"
        val main = "nl.rogro82.pipup.PiPupAccessibilityService"
        val control = "nl.rogro82.pipup.PiPupControlService"
        val onlyControl = "com.other/.Svc:nl.rogro82.pipup/nl.rogro82.pipup.PiPupControlService"
        assertFalse(enabledListContains(onlyControl, pkg, main))   // the bug this guards against
        assertTrue(enabledListContains(onlyControl, pkg, control))
        assertTrue(enabledListContains("nl.rogro82.pipup/.PiPupAccessibilityService", pkg, main))
        assertFalse(enabledListContains("", pkg, main))
        assertFalse(enabledListContains("null", pkg, main))
    }

    // ---------------------------------------------------------------- tap (0.32.0-beta.2)

    @Test
    fun methodDefaultsToClickAndRejectsUnknownNames() {
        assertEquals(ClickMethod.CLICK, ClickMethod.parse(null))
        assertEquals(ClickMethod.CLICK, ClickMethod.parse(""))
        assertEquals(ClickMethod.CLICK, ClickMethod.parse("  "))
        assertEquals(ClickMethod.CLICK, ClickMethod.parse("click"))
        assertEquals(ClickMethod.TAP, ClickMethod.parse("tap"))
        assertEquals(ClickMethod.TAP, ClickMethod.parse(" TAP "))
        assertEquals(ClickMethod.OK, ClickMethod.parse("ok"))
        assertEquals(ClickMethod.OK, ClickMethod.parse("OK"))
        assertEquals("ok", ClickMethod.OK.wire)
        assertNull(ClickMethod.parse("press"))
        assertNull(ClickMethod.parse("tap;rm"))
    }

    @Test
    fun tapLandsOnTheCentreOfTheBounds() {
        // Plex's Watch button on the TCL, from uiautomator: [128,564][267,628]
        assertEquals(197.5f to 596f, tapPoint(128, 564, 267, 628))
        assertEquals(1f to 1f, tapPoint(0, 0, 2, 2))
    }

    @Test
    fun noTapWithoutAreaOnScreen() {
        assertNull(tapPoint(10, 10, 10, 50))     // no width
        assertNull(tapPoint(10, 50, 60, 50))     // no height
        assertNull(tapPoint(60, 10, 10, 50))     // inverted
        assertNull(tapPoint(-100, -100, -20, -20)) // off screen
    }

    // ---------------------------------------------------------------- versions

    @Test
    fun releaseIsNewerThanItsPreReleases() {
        assertTrue(UpdateManager.isNewer("0.32.0", "0.32.0-beta.1"))
        assertTrue(UpdateManager.isNewer("0.32.0-beta.2", "0.32.0-beta.1"))
        assertTrue(UpdateManager.isNewer("0.32.0-beta.10", "0.32.0-beta.9"))
        assertFalse(UpdateManager.isNewer("0.32.0-beta.1", "0.32.0"))
        assertFalse(UpdateManager.isNewer("0.31.0", "0.32.0-beta.1"))      // no downgrade to the last release
        assertTrue(UpdateManager.isNewer("0.32.0-beta.1", "0.31.0"))
        assertTrue(UpdateManager.isNewer("1.32.0", "1.32.0b1"))            // ha-pipup, PEP 440
        assertFalse(UpdateManager.isNewer("1.30.2", "1.32.0b1"))
    }

    @Test
    fun plainVersionsCompareAsBefore() {
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.0"))
        assertTrue(UpdateManager.isNewer("v0.31.0", "0.30.1"))
        assertFalse(UpdateManager.isNewer("0.31.0", "0.31.0"))
        assertFalse(UpdateManager.isNewer("0.31", "0.31.0"))
        assertTrue(UpdateManager.isNewer("0.31.1", "0.31"))
        assertFalse(UpdateManager.isNewer("0.31.0+build5", "0.31.0"))
        assertFalse(UpdateManager.isNewer("garbage", "0.1.0"))
        assertTrue(UpdateManager.isNewer("0.1.0", "garbage"))
    }
}
