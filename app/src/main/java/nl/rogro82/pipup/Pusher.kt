package nl.rogro82.pipup

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/// Push of state changes to a controller (0.23.0).
///
/// A controller (the Home Assistant integration) sets a webhook URL through
/// `POST /settings?webhook=...`; from then on every state change is POSTed there as
/// the `/state` JSON plus `event` (and `reason` for a removal). That replaces a
/// controller polling `/state` on a timer. One thread, in order, fire and forget:
/// a failed POST is retried once after 2 s and then dropped. The controller reads
/// `/state` itself when it (re)connects, so a dropped push is not lost state.
object Pusher {
    private const val LOG_TAG = "PiPupPush"
    private const val TIMEOUT_MS = 5000
    private const val RETRY_DELAY_MS = 2000L

    private val executor = Executors.newSingleThreadExecutor()

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
        executor.execute {
            if (!post(target, event, body)) {
                try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { return@execute }
                post(target, event, body)
            }
        }
    }

    private fun post(target: String, event: String, body: String): Boolean {
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
            code in 200..299
        } catch (ex: Throwable) {
            lastStatus = null
            lastAt = System.currentTimeMillis()
            lastEvent = event
            lastError = ex.message ?: ex.javaClass.simpleName
            Log.w(LOG_TAG, "push $event failed: $lastError")
            false
        }
    }
}
