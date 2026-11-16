package nl.rogro82.pipup

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/// A picture of the screen on request (0.33.0, #89): `GET /screen.jpg`.
///
/// The most sensitive thing PiPup can do, so it is fenced in on every side:
///
/// * **off** until a token is set, and the token is set over adb only (ControlConfigReceiver,
///   guarded by WRITE_SECURE_SETTINGS) - never over HTTP, where there is no login;
/// * every request must carry that token (`X-PiPup-Token`, or `Authorization: Bearer`);
/// * it takes the control service, which is itself switched on over adb only;
/// * Android 11+ only (AccessibilityService.takeScreenshot); protected video comes out black;
/// * one picture per request, nothing is kept beyond a one-second cache, nothing is pushed.
object ScreenShots {

    const val LOG_TAG = "PiPupScreen"

    private const val PREFS_NAME = "pipup_control"
    private const val KEY_TOKEN = "screenToken"

    const val DEFAULT_WIDTH = 640
    const val MIN_WIDTH = 160
    const val MAX_WIDTH = 1920
    private const val JPEG_QUALITY = 80
    /// Android refuses a second screenshot within a second (333 ms from Android 13 on); a
    /// request inside that window gets the picture just taken.
    private const val CACHE_MS = 1_000L
    private const val TIMEOUT_MS = 5_000L

    /// 16-128 characters, URL-safe: long enough not to be guessed over the LAN.
    private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")

    private class Cached(val width: Int, val at: Long, val bytes: ByteArray)

    @Volatile private var cached: Cached? = null
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pipup-screen").apply { isDaemon = true }
    }

    internal fun validToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    /// Null or empty switches the screenshot off. False when [token] is not a valid token
    /// (nothing changes then).
    fun setToken(context: Context, token: String?): Boolean {
        if (!token.isNullOrEmpty() && !validToken(token)) return false
        prefs(context).edit().apply {
            if (token.isNullOrEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.commit()
        cached = null
        return true
    }

    /// Whether a token is set, which is what "switched on" means.
    fun enabled(context: Context): Boolean = token(context) != null

    private fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { validToken(it) }

    /// Whether [given] is the token; compared in constant time.
    internal fun tokenMatches(expected: String?, given: String?): Boolean {
        if (expected.isNullOrEmpty() || given.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    /// Why /screen.jpg cannot answer here, or null when it can.
    fun unavailableReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "a screenshot needs Android 11 or newer"
        if (!enabled(context)) return "the screenshot is switched off (install.sh --screen, or the " +
            "screenshot option in ha-pipup)"
        val service = PiPupControlService.service()
            ?: return "the screenshot needs the PiPup control service (install.sh --screen, or the " +
                "screenshot option in ha-pipup)"
        val granted = try {
            val capabilities = service.serviceInfo?.capabilities ?: 0
            (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        } catch (_: Throwable) {
            false
        }
        return if (granted) null else "the control service may not take a screenshot yet: switch it " +
            "off and on again (install.sh --screen, or the screenshot option in ha-pipup)"
    }

    /// `width=`: [DEFAULT_WIDTH] when absent, null when it is not a number in range.
    internal fun parseWidth(raw: String?): Int? {
        if (raw.isNullOrBlank()) return DEFAULT_WIDTH
        return raw.trim().toIntOrNull()?.takeIf { it in MIN_WIDTH..MAX_WIDTH }
    }

    /// The size of a [width] wide picture of a [srcWidth] x [srcHeight] screen; never larger
    /// than the screen itself.
    internal fun scaledSize(srcWidth: Int, srcHeight: Int, width: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 0 to 0
        val w = minOf(width, srcWidth)
        val h = maxOf(1, Math.round(srcHeight.toFloat() * w / srcWidth))
        return w to h
    }

    /// The screen as JPEG, [width] wide, for a request that carried [givenToken].
    /// 501 = not possible or not switched on, 401 = no or a wrong token, 409 = the screen is
    /// off, 429 = asked too fast, 500 = Android failed.
    fun capture(context: Context, givenToken: String?, width: Int): Pair<ByteArray?, TvControl.Result> {
        unavailableReason(context)?.let { return null to TvControl.Result(false, 501, it) }
        if (!tokenMatches(token(context), givenToken)) {
            return null to TvControl.Result(false, 401, "give the screenshot token in the header " +
                "X-PiPup-Token (it was set with install.sh --screen, or by ha-pipup)")
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return null to TvControl.Result(false, 409, "the screen is off")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null to TvControl.Result(false, 501, "a screenshot needs Android 11 or newer")
        }
        return synchronized(lock) {
            val recent = cached?.takeIf {
                it.width == width && SystemClock.elapsedRealtime() - it.at < CACHE_MS
            }
            val service = PiPupControlService.service()
            when {
                recent != null -> recent.bytes to TvControl.Result(true, 200, "screenshot")
                service == null ->
                    null to TvControl.Result(false, 501, "the control service was switched off")
                else -> try {
                    take(service, width)
                } catch (ex: Throwable) {
                    Log.w(LOG_TAG, "screenshot failed: ${ex.message}")
                    null to TvControl.Result(false, 500, "screenshot failed: ${ex.message}")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun take(service: AccessibilityService, width: Int): Pair<ByteArray?, TvControl.Result> {
        val done = CountDownLatch(1)
        val shot = AtomicReference<AccessibilityService.ScreenshotResult?>(null)
        val error = AtomicReference<Int?>(null)
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    shot.set(screenshot)
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    error.set(errorCode)
                    done.countDown()
                }
            })
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return null to TvControl.Result(false, 500, "Android did not answer the screenshot in time")
        }
        val result = shot.get() ?: return null to failure(error.get())
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: return null to TvControl.Result(false, 500, "Android gave an unreadable screenshot")
            // a hardware bitmap cannot be drawn on a canvas, so no scaling without this copy
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            if (software == null) return null to TvControl.Result(false, 500, "no memory for the screenshot")
            val (w, h) = scaledSize(software.width, software.height, width)
            val scaled = if (w == software.width) software else Bitmap.createScaledBitmap(software, w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== software) scaled.recycle()
            software.recycle()
            val bytes = out.toByteArray()
            cached = Cached(width, SystemClock.elapsedRealtime(), bytes)
            return bytes to TvControl.Result(true, 200, "screenshot")
        } finally {
            buffer.close()
        }
    }

    private fun failure(code: Int?): TvControl.Result = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            TvControl.Result(false, 429, "asked too fast: Android allows about one screenshot per second")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            TvControl.Result(false, 501, "the control service may not take a screenshot yet: switch it " +
                "off and on again (install.sh --screen, or the screenshot option in ha-pipup)")
        else -> TvControl.Result(false, 500, "Android could not take the screenshot (error $code)")
    }

    /// Device-protected storage, like the click list: readable before the first unlock.
    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.applicationContext.createDeviceProtectedStorageContext()
        else context.applicationContext)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
