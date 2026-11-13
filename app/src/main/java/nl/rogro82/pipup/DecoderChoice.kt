package nl.rogro82.pipup

import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/// The automatic video decoder rule (0.17.2): software on Android < 8 and wherever an
/// Amlogic H.264 decoder is present, hardware elsewhere. `softwareDecoder` in a popup
/// overrides it.
///
/// 0.32.0-beta.4 (ha-pipup #60): decided once per process, on its own thread, when the
/// service starts. Before, every video popup asked MediaCodecUtil on the main thread. That
/// call is `synchronized` and enumerates the vendor codecs; on a MediaTek TCL (Android 8)
/// with live TV running, stacked video popups froze the app for good while the hardware
/// decoder hung. Whether that lock was the cause could not be checked here, but the main
/// thread has no business waiting on codec enumeration either way.
object DecoderChoice {

    private const val LOG_TAG = "DecoderChoice"
    /// How long a popup waits for the answer when it comes before the decision is made.
    private const val WAIT_MS = 1500L

    private val ready = CountDownLatch(1)
    @Volatile private var software: Boolean? = null
    private var started = false

    /// Start deciding in the background; safe to call more than once.
    fun prime() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            val result = decide()
            software = result
            ready.countDown()
            Log.i(LOG_TAG, "automatic video decoder: ${if (result) "software" else "hardware"} [sdk=${Build.VERSION.SDK_INT}]")
        }, "pipup-decoder-choice").apply { isDaemon = true }.start()
    }

    /// The automatic choice: true = prefer the software decoders. Waits at most [WAIT_MS]
    /// for the background decision; without it, the Android version alone decides.
    fun autoSoftware(): Boolean {
        software?.let { return it }
        prime()
        if (!ready.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(LOG_TAG, "decoder list not read after $WAIT_MS ms; deciding on the Android version alone")
        }
        return software ?: (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
    }

    @OptIn(markerClass = [UnstableApi::class])
    private fun decide(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                .any { it.name.contains("amlogic", ignoreCase = true) }
        } catch (e: Throwable) {
            false
        }
    }
}
