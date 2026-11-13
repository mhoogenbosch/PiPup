package nl.rogro82.pipup

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/// What is playing, in any app (0.32.0, #83): GET /media, POST /media/seek and `/state.media`;
/// since 0.32.0-beta.4 also POST /key into the session and GET /media/art.
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
    /// GET /media/art: the longest side of the image handed out, and what is read at most.
    private const val ART_MAX_PX = 600
    private const val ART_MAX_BYTES = 8 * 1024 * 1024
    private const val ART_TIMEOUT_MS = 5000
    /// artKey reads ART_SAMPLE_GRID x ART_SAMPLE_GRID pixels of a bitmap (0.32.0-beta.5).
    private const val ART_SAMPLE_GRID = 4

    /// Where a session keeps its artwork: as a bitmap, or as a URI (in this order).
    private val ART_BITMAP_KEYS = listOf(MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    private val ART_URI_KEYS = listOf(MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

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

    /// GET /media and /state.media. Never throws: it is part of every /state and push, and a
    /// session that dies while it is read (a DeadObjectException) must not take those down.
    fun snapshot(context: Context): Map<String, Any?> = try {
        val list = controllers(context)
        val controller = list?.let { pick(it) }
        when {
            list == null -> mapOf("access" to false, "reason" to reason(context))
            controller == null -> mapOf("access" to true, "package" to null)
            else -> mapOf("access" to true) + describe(controller).toMap()
        }
    } catch (ex: Throwable) {
        Log.w(LOG_TAG, "reading the media session failed: ${ex.message}")
        mapOf("access" to true, "package" to null)
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
            canSeek = ((state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L,
            actions = actionNames(state?.actions ?: 0L),
            artKey = meta?.let { artKey(controller.packageName, it) }
        )
    }

    /// Identifies the session's artwork without encoding it: the item, where its image lives,
    /// and for a bitmap a few of its pixels. Changes when the picture does, so a controller
    /// fetches /media/art only then. Null when the session has no artwork. The URI itself is
    /// not handed out: a media server's artwork URL can carry its access token.
    ///
    /// 0.32.0-beta.5: the duration and the pixel samples were added. YouTube's TV client
    /// leaves the title empty, gives no artwork URI and reuses the same metadata and bitmap
    /// from video to video, so title, size and the bitmap's identity stay the same while the
    /// picture changes (described in another bridge's ArtResolver; ideas only, no code).
    private fun artKey(pkg: String?, meta: MediaMetadata): String? {
        var source: String? = null
        var samples: List<Int> = emptyList()
        for (key in ART_BITMAP_KEYS) {
            val bitmap = runCatching { meta.getBitmap(key) }.getOrNull() ?: continue
            source = "$key:${bitmap.width}x${bitmap.height}"
            samples = samplePixels(bitmap)
            break
        }
        source = source ?: ART_URI_KEYS.firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.isNotBlank() } }
            ?: return null
        return artKeyOf(listOf(pkg, meta.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            meta.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()), source, samples)
    }

    /// [ART_SAMPLE_GRID]² pixels spread over the bitmap: cheap (no copy, no scaling) and enough
    /// to tell one picture from the next. Empty when the bitmap cannot be read (recycled, or a
    /// hardware bitmap): the other fields of the key decide then.
    private fun samplePixels(bitmap: Bitmap): List<Int> = try {
        if (bitmap.isRecycled) emptyList()
        else samplePoints(bitmap.width, bitmap.height, ART_SAMPLE_GRID).map { (x, y) -> bitmap.getPixel(x, y) }
    } catch (_: Throwable) {
        emptyList()
    }

    /// The session a request is about: that of [pkg], else the one /media reports.
    /// Null result = no access; a [TvControl.Result] = the reason there is no session.
    private fun sessionFor(context: Context, pkg: String?): Pair<MediaController?, TvControl.Result?>? {
        val list = controllers(context) ?: return null
        return if (pkg != null) {
            list.firstOrNull { it.packageName == pkg }?.let { it to null }
                ?: (null to TvControl.Result(false, 404, "$pkg has no media session"))
        } else {
            pick(list)?.let { it to null } ?: (null to TvControl.Result(false, 404, "nothing is playing"))
        }
    }

    /// POST /key for a media key (0.32.0-beta.4, #83): straight into the app's own media
    /// session, through its transport controls. The media-key route (dispatchMediaKeyEvent)
    /// reaches the app only through MediaSession.Callback.onMediaButtonEvent, which acts on a
    /// key only when the app lists that action in its PlaybackState - and Plex on a TCL did
    /// nothing with play or pause that way. A transport control calls onPlay/onPause directly,
    /// as a phone's media notification does.
    ///
    /// Null when no session takes it (no access, or nothing active): the caller then sends
    /// the media key as before. A 404 when [pkg] was asked for and has no session.
    fun transport(context: Context, key: String, pkg: String?): TvControl.Result? {
        val (controller, missing) = sessionFor(context, pkg) ?: return null
        if (controller == null) return if (pkg != null) missing else null
        return try {
            val playing = isPlaying(controller.playbackState?.state)
            val action = transportAction(key, playing) ?: return null
            val controls = controller.transportControls
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "stop" -> controls.stop()
                "skipToNext" -> controls.skipToNext()
                "skipToPrevious" -> controls.skipToPrevious()
                "rewind" -> controls.rewind()
                "fastForward" -> controls.fastForward()
            }
            Log.i(LOG_TAG, "$key -> ${controller.packageName}.$action")
            TvControl.Result(true, 200, "sent $key", mapOf("via" to "session",
                "package" to controller.packageName, "action" to action))
        } catch (ex: Throwable) {
            // the session went away between finding and using it: the media key still may land
            Log.w(LOG_TAG, "$key into the session failed: ${ex.message}")
            null
        }
    }

    class Art(val bytes: ByteArray, val mime: String, val key: String)

    /// GET /media/art (0.32.0-beta.4, ha-pipup #61): the artwork of the session /media reports
    /// (or of [pkg]), at most [ART_MAX_PX] on its longest side. Runs on the web-server thread.
    fun art(context: Context, pkg: String?): Pair<Art?, TvControl.Result> {
        val (controller, missing) = sessionFor(context, pkg)
            ?: return null to TvControl.Result(false, 501, unavailableMessage(context))
        if (controller == null) return null to missing!!
        val noArt = TvControl.Result(false, 404, "${controller.packageName} shows no artwork")
        val meta = try { controller.metadata } catch (ex: Throwable) {
            return null to TvControl.Result(false, 404, "the media session ended: ${ex.message}")
        } ?: return null to noArt
        val key = artKey(controller.packageName, meta) ?: return null to noArt
        return try {
            val bitmap = ART_BITMAP_KEYS.firstNotNullOfOrNull { runCatching { meta.getBitmap(it) }.getOrNull() }
                ?: ART_URI_KEYS.firstNotNullOfOrNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } }
                    ?.let { decode(readUri(context, it)) }
                ?: return null to TvControl.Result(false, 404, "the artwork of ${controller.packageName} could not be read")
            val (bytes, mime) = encode(bitmap)
            Art(bytes, mime, key) to TvControl.Result(true, 200, "ok")
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "artwork of ${controller.packageName}: ${ex.message}")
            null to TvControl.Result(false, 404, "the artwork of ${controller.packageName} could not be read: ${ex.message}")
        }
    }

    /// The bytes behind an artwork URI: content:, android.resource: and file: through the
    /// content resolver (the app may refuse: then there is no artwork), http(s) fetched here.
    private fun readUri(context: Context, value: String): ByteArray {
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val conn = URL(value).openConnection() as HttpURLConnection
                conn.connectTimeout = ART_TIMEOUT_MS
                conn.readTimeout = ART_TIMEOUT_MS
                try {
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { readCapped(it) }
                } finally {
                    conn.disconnect()
                }
            }
            "content", "android.resource", "file" ->
                (context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no stream"))
                    .use { readCapped(it) }
            else -> throw IllegalArgumentException("unsupported artwork URI scheme ${uri.scheme}")
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > ART_MAX_BYTES) throw IllegalStateException("artwork larger than ${ART_MAX_BYTES / 1024 / 1024} MB")
        }
        return out.toByteArray()
    }

    /// Decode, sampled down on the way in so a poster of several thousand pixels does not
    /// need its full size in memory.
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, ART_MAX_PX)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /// JPEG, or PNG when the image has transparency (an app icon), scaled to [ART_MAX_PX].
    private fun encode(source: Bitmap): Pair<ByteArray, String> {
        val (w, h) = fitWithin(source.width, source.height, ART_MAX_PX)
        val bitmap = if (w != source.width || h != source.height) Bitmap.createScaledBitmap(source, w, h, true) else source
        val png = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to (if (png) "image/png" else "image/jpeg")
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
        val now = try { describe(controller) } catch (ex: Throwable) {
            return TvControl.Result(false, 404, "the media session ended: ${ex.message}")
        }
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

    // Everything below runs on the main thread, where an exception would end the whole
    // process (popups included): each entry point catches everything.
    private fun startOnMain() = guarded("start") {
        val context = appContext ?: return@guarded
        val list = controllers(context)
        if (list == null) {
            // access gone (revoked): drop the registrations, so a later grant sets them up anew
            if (listening || tracked.isNotEmpty()) stopTracking(context)
            return@guarded
        }
        if (!listening) {
            manager(context).addOnActiveSessionsChangedListener(sessionsListener, component(context), handler)
            listening = true
            Log.i(LOG_TAG, "tracking media sessions")
        }
        track(list)
    }

    private fun stopTracking(context: Context) {
        runCatching { manager(context).removeOnActiveSessionsChangedListener(sessionsListener) }
        tracked.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        tracked.clear()
        listening = false
        lastPushed = null
        Log.i(LOG_TAG, "media session access gone; stopped tracking")
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        guarded("sessions changed") {
            track(list ?: emptyList())
            scheduleCheck()
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "$what failed: ${ex.message}")
        }
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
            guarded("change check") {
                val context = appContext ?: return@guarded
                val list = controllers(context) ?: return@guarded
                val now = pick(list)?.let { describe(it) } ?: Snapshot.NONE
                if (worthPushing(lastPushed, now)) {
                    lastPushed = now
                    onChange?.invoke()
                }
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
        val canSeek: Boolean,
        val actions: List<String> = emptyList(),
        val artKey: String? = null
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
            "canSeek" to canSeek,
            // 0.32.0-beta.4: what the app says it accepts (diagnostics; /key uses the
            // transport controls either way), and which artwork /media/art hands out
            "actions" to actions,
            "artKey" to artKey
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
            before.durationMs != now.durationMs || before.canSeek != now.canSeek ||
            before.actions != now.actions || before.artKey != now.artKey) return true
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

/// PlaybackState.actions -> the names /media lists (0.32.0-beta.4), in a fixed order.
private val ACTION_NAMES = linkedMapOf(
    PlaybackState.ACTION_PLAY to "play",
    PlaybackState.ACTION_PAUSE to "pause",
    PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
    PlaybackState.ACTION_STOP to "stop",
    PlaybackState.ACTION_SKIP_TO_NEXT to "next",
    PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous",
    PlaybackState.ACTION_SEEK_TO to "seek",
    PlaybackState.ACTION_REWIND to "rewind",
    PlaybackState.ACTION_FAST_FORWARD to "fast_forward"
)

internal fun actionNames(actions: Long): List<String> =
    ACTION_NAMES.filterKeys { (actions and it) != 0L }.values.toList()

/// POST /key name -> the transport control to call; play_pause becomes pause while
/// something plays and play otherwise. Null for a name that is not a media key.
internal fun transportAction(key: String, playing: Boolean): String? = when (key) {
    "play" -> "play"
    "pause" -> "pause"
    "play_pause" -> if (playing) "pause" else "play"
    "stop" -> "stop"
    "next" -> "skipToNext"
    "previous" -> "skipToPrevious"
    "rewind" -> "rewind"
    "fast_forward" -> "fastForward"
    else -> null
}

/// The centres of a [grid] x [grid] raster over a [width] x [height] image (0.32.0-beta.5);
/// empty for an image without pixels.
internal fun samplePoints(width: Int, height: Int, grid: Int): List<Pair<Int, Int>> {
    if (width <= 0 || height <= 0 || grid <= 0) return emptyList()
    return (0 until grid).flatMap { row ->
        (0 until grid).map { col ->
            ((2 * col + 1) * width / (2 * grid)).coerceIn(0, width - 1) to
                ((2 * row + 1) * height / (2 * grid)).coerceIn(0, height - 1)
        }
    }
}

/// artKey from the item's fields, the artwork's source and its pixel samples: 8 hex digits.
internal fun artKeyOf(fields: List<String?>, source: String, samples: List<Int>): String =
    "%08x".format((fields + source + samples.joinToString(",")).joinToString("|").hashCode())

/// [width] x [height] scaled down (never up) so the longest side is at most [maxPx].
internal fun fitWithin(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= maxPx || longest <= 0) return width to height
    val scale = maxPx.toDouble() / longest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/// The largest power-of-two sample size that keeps the longest side at or above [maxPx]
/// (BitmapFactory then decodes a smaller copy; the exact size follows in fitWithin).
internal fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxPx) sample *= 2
    return sample
}

/// Where a seek lands: [absolute], or [current] + [relative]; never before 0 or past
/// [durationMs]. Null when a relative seek has no current position to start from.
internal fun seekTarget(current: Long?, absolute: Long?, relative: Long?, durationMs: Long?): Long? {
    val target = absolute ?: (current ?: return null) + (relative ?: 0L)
    val clamped = target.coerceAtLeast(0L)
    return if (durationMs != null && durationMs > 0) clamped.coerceAtMost(durationMs) else clamped
}
