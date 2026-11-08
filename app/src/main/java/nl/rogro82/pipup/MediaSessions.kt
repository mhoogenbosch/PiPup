package nl.rogro82.pipup

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import kotlin.math.abs

/// What is playing, in any app (0.32.0, #83): GET /media, POST /media/seek and `/state.media`.
///
/// Reads the active media sessions - the same sessions a Bluetooth remote's play button
/// reaches - through MediaSessionManager. Android hands those to an app with an enabled
/// notification listener (MediaListenerService, granted over adb). Without the grant
/// everything here reports `access: false` and the endpoints answer 501.
object MediaSessions {

    const val LOG_TAG = "MediaSessions"

    /// A reported position further than this from where playback should be means a seek.
    private const val SEEK_JUMP_MS = 2000L
    /// Session callbacks arrive in bursts (state + metadata + position); push once after them.
    private const val COALESCE_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    /// Called (main thread) after a change worth pushing; set by PiPupService.
    @Volatile var onChange: (() -> Unit)? = null

    @Volatile private var appContext: Context? = null
    private var listening = false
    private val tracked = mutableMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lastPushed: Snapshot? = null
    private var checkPending = false

    fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

    private fun manager(context: Context) =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    /// The active sessions in Android's priority order, or null without access.
    private fun controllers(context: Context): List<MediaController>? = try {
        manager(context).getActiveSessions(component(context))
    } catch (_: SecurityException) {
        null
    } catch (ex: Throwable) {
        Log.w(LOG_TAG, "getActiveSessions failed: ${ex.message}")
        null
    }

    /// Why there is no access: Android ignores the grant on low-RAM devices.
    private fun reason(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (am.isLowRamDevice) "low_ram" else "not_granted"
    }

    fun unavailableMessage(context: Context): String = when (reason(context)) {
        "low_ram" -> "this device is marked low-RAM, and Android gives no app access to media sessions there"
        else -> "media session access is not granted: adb shell cmd notification allow_listener " +
            "${context.packageName}/${MediaListenerService::class.java.name} (install.sh --media)"
    }

    /// The session /media reports: the first one that plays, else the first one.
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { isPlaying(it.playbackState?.state) } ?: list.firstOrNull()

    /// GET /media and /state.media.
    fun snapshot(context: Context): Map<String, Any?> {
        val list = controllers(context) ?: return mapOf("access" to false, "reason" to reason(context))
        val controller = pick(list) ?: return mapOf("access" to true, "package" to null)
        return mapOf("access" to true) + describe(controller).toMap()
    }

    private fun describe(controller: MediaController): Snapshot {
        val state = controller.playbackState
        val meta = controller.metadata
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val position = state?.let {
            extrapolate(it.position, it.lastPositionUpdateTime, it.playbackSpeed,
                isPlaying(it.state), SystemClock.elapsedRealtime())
        }?.let { if (duration != null) it.coerceAtMost(duration) else it }
        return Snapshot(
            pkg = controller.packageName,
            state = stateName(state?.state),
            positionMs = position,
            atWallMs = System.currentTimeMillis(),
            atElapsedMs = SystemClock.elapsedRealtime(),
            durationMs = duration,
            speed = (state?.playbackSpeed ?: 0f).toDouble(),
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            canSeek = ((state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        )
    }

    /// POST /media/seek: exactly one of [ms] (absolute) or [by] (relative), optionally in
    /// the session of [pkg] instead of the one /media reports.
    fun seek(context: Context, ms: String?, by: String?, pkg: String?): TvControl.Result {
        if ((ms == null) == (by == null)) {
            return TvControl.Result(false, 400, "give exactly one of ms=<position in ms> or by=<+/- ms>")
        }
        val absolute = ms?.let { it.toLongOrNull() ?: return TvControl.Result(false, 400, "ms must be a whole number") }
        val relative = by?.let { it.removePrefix("+").toLongOrNull() ?: return TvControl.Result(false, 400, "by must be a whole number") }
        if (absolute != null && absolute < 0) return TvControl.Result(false, 400, "ms must be 0 or more")
        val list = controllers(context) ?: return TvControl.Result(false, 501, unavailableMessage(context))
        val controller = if (pkg != null) {
            list.firstOrNull { it.packageName == pkg } ?: return TvControl.Result(false, 404, "$pkg has no media session")
        } else {
            pick(list) ?: return TvControl.Result(false, 404, "nothing is playing")
        }
        val now = describe(controller)
        if (!now.canSeek) return TvControl.Result(false, 409, "${now.pkg} does not allow seeking")
        val target = seekTarget(now.positionMs, absolute, relative, now.durationMs)
            ?: return TvControl.Result(false, 409, "${now.pkg} reports no position, so by= cannot be used; use ms=")
        return try {
            controller.transportControls.seekTo(target)
            Log.i(LOG_TAG, "seek ${now.pkg} to $target ms")
            TvControl.Result(true, 200, "seeked", mapOf("package" to now.pkg, "positionMs" to target))
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "seek failed: ${ex.message}")
            TvControl.Result(false, 500, "seek failed: ${ex.message}")
        }
    }

    // ---------------------------------------------------------------- change tracking

    /// Start (or refresh) tracking the active sessions, so changes are pushed at once instead
    /// of at the next poll. Safe to call repeatedly and from any thread; without access it
    /// does nothing and is tried again by the watchdog and when the listener connects.
    fun start(context: Context) {
        appContext = context.applicationContext
        handler.post { startOnMain() }
    }

    private fun startOnMain() {
        val context = appContext ?: return
        val list = controllers(context) ?: return
        if (!listening) {
            try {
                manager(context).addOnActiveSessionsChangedListener(sessionsListener, component(context), handler)
                listening = true
                Log.i(LOG_TAG, "tracking media sessions")
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "cannot track media sessions: ${ex.message}")
                return
            }
        }
        track(list)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list ?: emptyList())
        scheduleCheck()
    }

    private fun track(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        for (controller in list) {
            if (tracked.containsKey(controller.sessionToken)) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleCheck()
                override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleCheck()
                override fun onSessionDestroyed() = scheduleCheck()
            }
            try {
                controller.registerCallback(callback, handler)
                tracked[controller.sessionToken] = controller to callback
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "cannot follow ${controller.packageName}: ${ex.message}")
            }
        }
    }

    private fun scheduleCheck() {
        if (checkPending) return
        checkPending = true
        handler.postDelayed({
            checkPending = false
            val context = appContext ?: return@postDelayed
            val list = controllers(context) ?: return@postDelayed
            val now = pick(list)?.let { describe(it) } ?: Snapshot.NONE
            if (worthPushing(lastPushed, now)) {
                lastPushed = now
                runCatching { onChange?.invoke() }
            }
        }, COALESCE_MS)
    }

    internal data class Snapshot(
        val pkg: String?,
        val state: String?,
        val positionMs: Long?,
        val atWallMs: Long,
        val atElapsedMs: Long,
        val durationMs: Long?,
        val speed: Double,
        val title: String?,
        val artist: String?,
        val album: String?,
        val canSeek: Boolean
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "package" to pkg,
            "state" to state,
            "positionMs" to positionMs,
            "positionUpdatedAt" to Instant.ofEpochMilli(atWallMs).toString(),
            "durationMs" to durationMs,
            "speed" to speed,
            "title" to title,
            "artist" to artist,
            "album" to album,
            "canSeek" to canSeek
        )

        companion object {
            val NONE = Snapshot(null, null, null, 0, 0, null, 0.0, null, null, null, false)
        }
    }

    /// Push when what is playing changed, or the position jumped (a seek); not for the
    /// position simply moving on, which a controller extrapolates itself.
    internal fun worthPushing(before: Snapshot?, now: Snapshot): Boolean {
        if (before == null) return true
        if (before.pkg != now.pkg || before.state != now.state || before.title != now.title ||
            before.artist != now.artist || before.album != now.album ||
            before.durationMs != now.durationMs || before.canSeek != now.canSeek) return true
        val was = before.positionMs ?: return now.positionMs != null
        val pos = now.positionMs ?: return true
        val expected = if (before.state == "playing")
            was + ((now.atElapsedMs - before.atElapsedMs) * before.speed).toLong() else was
        return abs(pos - expected) > SEEK_JUMP_MS
    }
}

internal fun isPlaying(state: Int?): Boolean = when (state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
    else -> false
}

/// PlaybackState -> playing / paused / buffering / stopped.
internal fun stateName(state: Int?): String = when {
    isPlaying(state) -> "playing"
    state == PlaybackState.STATE_PAUSED -> "paused"
    state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING -> "buffering"
    else -> "stopped"
}

/// The position now: the reported one moved on by the time since the app reported it, at
/// the playback speed, while playing. Null when the app reports no position.
internal fun extrapolate(positionMs: Long, updatedAtElapsed: Long, speed: Float, playing: Boolean, nowElapsed: Long): Long? {
    if (positionMs < 0) return null // PlaybackState.PLAYBACK_POSITION_UNKNOWN
    if (!playing || updatedAtElapsed <= 0 || nowElapsed <= updatedAtElapsed) return positionMs
    return positionMs + ((nowElapsed - updatedAtElapsed) * speed.toDouble()).toLong()
}

/// Where a seek lands: [absolute], or [current] + [relative]; never before 0 or past
/// [durationMs]. Null when a relative seek has no current position to start from.
internal fun seekTarget(current: Long?, absolute: Long?, relative: Long?, durationMs: Long?): Long? {
    val target = absolute ?: (current ?: return null) + (relative ?: 0L)
    val clamped = target.coerceAtLeast(0L)
    return if (durationMs != null && durationMs > 0) clamped.coerceAtMost(durationMs) else clamped
}
