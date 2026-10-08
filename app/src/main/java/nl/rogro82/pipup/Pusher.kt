package nl.rogro82.pipup

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/// Push of state changes to a controller (0.24.0).
///
/// A controller (the Home Assistant integration) sets a webhook URL through
/// `POST /settings?webhook=...`; from then on every state change is POSTed there as
/// the `/state` JSON plus `event` (and `reason` for a removal). That replaces a
/// controller polling `/state` on a timer. One thread, in order, fire and forget:
/// a failed POST is retried once after 2 s and then dropped. The controller reads
/// `/state` itself when it (re)connects, so a dropped push is not lost state.
///
/// 2026-10-08: bounded. With the controller unreachable every push cost up to
/// 5 s connect + 2 s sleep + 5 s retry on the one thread, and pushes piled up without
/// limit behind it. Now at most [MAX_PENDING] wait (the oldest is dropped), the retry
/// is skipped when a newer push is already waiting (it carries newer state anyway),
/// and a 4xx answer is not retried (the same request would be refused again).
object Pusher {
    private const val LOG_TAG = "PiPupPush"
    private const val TIMEOUT_MS = 5000
    private const val RETRY_DELAY_MS = 2000L
    private const val MAX_PENDING = 16

    private val executor = Executors.newSingleThreadExecutor()

    private class Pending(val target: String, val event: String, val body: String)
    /// Pushes waiting for the thread, oldest first; guarded by itself.
    private val pending = ArrayDeque<Pending>()
    /// Whether a drain task is queued or running; guarded by [pending].
    private var draining = false
    /// Whether the overflow was already logged since the queue last ran empty.
    private var overflowLogged = false

    /// Where pushes go; empty or null = push off. Set by the service from prefs.
    @Volatile var url: String? = null

    @Volatile var lastStatus: Int? = null
        private set
    @Volatile var lastAt: Long = 0L
        private set
    @Volatile var lastEvent: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /// [body] is built by the caller on its own thread, so it describes the state at
    /// the moment of the event, not whatever the state is once the POST goes out.
    fun push(event: String, body: String) {
        val target = url?.takeIf { it.isNotBlank() } ?: return
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) {
                val dropped = pending.removeFirst()
                if (!overflowLogged) {
                    overflowLogged = true
                    Log.w(LOG_TAG, "push queue full ($MAX_PENDING): dropping oldest (${dropped.event}) and further overflow")
                }
            }
            pending.addLast(Pending(target, event, body))
            if (draining) return
            draining = true
        }
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(pending) {
                pending.removeFirstOrNull().also {
                    if (it == null) { draining = false; overflowLogged = false }
                }
            } ?: return
            deliver(next)
        }
    }

    private fun deliver(p: Pending) {
        val code = post(p.target, p.event, p.body)
        if (code != null && code in 200..299) return
        if (code != null && code in 400..499) return      // refused: a retry changes nothing
        if (synchronized(pending) { pending.isNotEmpty() }) return // a newer push supersedes it
        try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { return }
        post(p.target, p.event, p.body)
    }

    /// HTTP status of the POST, or null when it did not get an answer at all.
    private fun post(target: String, event: String, body: String): Int? {
        return try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            conn.disconnect()
            lastStatus = code
            lastAt = System.currentTimeMillis()
            lastEvent = event
            lastError = if (code in 200..299) null else "HTTP $code"
            code
        } catch (ex: Throwable) {
            lastStatus = null
            lastAt = System.currentTimeMillis()
            lastEvent = event
            lastError = ex.message ?: ex.javaClass.simpleName
            Log.w(LOG_TAG, "push $event failed: $lastError")
            null
        }
    }
}
