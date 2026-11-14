package nl.rogro82.pipup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/// TV control (0.29.0): start an app, send a media key, set the volume - the everyday
/// remote-control actions a controller otherwise needs adb for (and adb is exactly what
/// keeps breaking: a TV that drops its debugging grant after an update, or Android 14
/// builds where the adb route of a media-player integration stopped working).
///
/// Everything here uses public APIs that a sideloaded app may call without extra grants:
///
/// * **launch** - `startActivity` from the background is allowed because this app holds
///   "display over other apps" (SYSTEM_ALERT_WINDOW exempts it from the Android 10+
///   background-activity-start rules). That permission is a hard requirement of PiPup
///   anyway, so launch works wherever popups work.
/// * **media keys** - `AudioManager.dispatchMediaKeyEvent` delivers to the active media
///   session (Netflix, YouTube, Spotify, Jellyfin, ...), the same path a Bluetooth remote's
///   play button takes. Since 0.32.0-beta.4 they go into the session's transport controls
///   instead when media session access is granted (see MediaSessions.transport).
/// * **volume** - `AudioManager` on STREAM_MUSIC. On a TV that passes volume through to
///   the set or an amplifier over HDMI-CEC (`isVolumeFixed`), an absolute level cannot be
///   set; steps and mute are still sent and the platform forwards them where it can.
/// * **home / back** - only with the accessibility service enabled (the same opt-in that
///   already serves as the screen-off fallback); otherwise reported as unavailable.
///
/// Which app is in the foreground takes usage access, a grant a TV has no screen for: without
/// it a controller only learns what *it* launched last. Granted over adb, ForegroundApp
/// (0.33.0, #88) reports the app in front.
object TvControl {

    const val LOG_TAG = "TvControl"

    /// Key names accepted by POST /key, in the order /state lists them.
    val MEDIA_KEYS: Map<String, Int> = linkedMapOf(
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
        "fast_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    )

    /// Keys that go through the accessibility service as a global action.
    val GLOBAL_KEYS: Map<String, Int> = linkedMapOf(
        "home" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
        "back" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
    )

    /// Package and time of the last successful launch; null until the first one.
    @Volatile var lastLaunched: String? = null
        private set
    @Volatile private var lastLaunchedAt: Long = 0L

    fun lastLaunchedSecondsAgo(): Long? =
        lastLaunchedAt.takeIf { it > 0 }?.let { (SystemClock.elapsedRealtime() - it) / 1000 }

    data class Result(val ok: Boolean, val status: Int, val message: String, val extra: Map<String, Any?> = emptyMap())

    // ---------------------------------------------------------------- apps

    /// Launchable apps, sorted by label. A TV launcher entry (LEANBACK_LAUNCHER) wins over a
    /// phone launcher entry of the same package; PiPup itself is left out.
    fun apps(context: Context): List<Map<String, Any?>> {
        val pm = context.packageManager
        val found = linkedMapOf<String, Map<String, Any?>>()
        for ((category, leanback) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = try { pm.queryIntentActivities(intent, 0) } catch (ex: Throwable) {
                Log.e(LOG_TAG, "query $category failed: ${ex.message}"); emptyList()
            }
            for (info in infos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                val label = try { info.loadLabel(pm)?.toString() } catch (_: Throwable) { null } ?: pkg
                found[pkg] = mapOf("package" to pkg, "label" to label, "tv" to leanback)
            }
        }
        return found.values.sortedBy { (it["label"] as String).lowercase() }
    }

    // ---------------------------------------------------------------- launch

    /// Start [pkg] (its TV launcher entry, else its phone launcher entry), or open [uri]
    /// (a deep link, optionally limited to [pkg]). Wakes the screen first when it is off
    /// and [wake] is true, since an app started on a sleeping TV is not seen.
    fun launch(context: Context, pkg: String?, uri: String?, wake: Boolean): Result {
        if (pkg.isNullOrBlank() && uri.isNullOrBlank()) {
            return Result(false, 400, "give package=<name> and/or uri=<deep link>")
        }
        val pm = context.packageManager
        val intent: Intent = if (!uri.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { if (!pkg.isNullOrBlank()) setPackage(pkg) }
        } else {
            pm.getLeanbackLaunchIntentForPackage(pkg!!) ?: pm.getLaunchIntentForPackage(pkg)
                ?: return if (isInstalled(pm, pkg)) Result(false, 404, "$pkg has no launcher entry")
                          else Result(false, 404, "$pkg is not installed")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // 0.32.0-beta.3: "woke" only when the screen really came on (within 3 s); the
        // attempt alone said yes on a TCL whose screen stayed off. The app is opened either
        // way, and the answer's screenOn tells the caller to wake it another way (adb).
        val wasOff = wake && !PowerController.screenOn(context)
        val woke = wasOff && PowerController.wake(context) && PowerController.waitForScreen(context, true)
        if (wasOff && !woke) Log.w(LOG_TAG, "the screen stayed off after the wake attempt")
        return try {
            context.startActivity(intent)
            val started = intent.`package` ?: intent.component?.packageName ?: pkg
            lastLaunched = started
            lastLaunchedAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "launched ${started ?: uri}" + if (woke) " (woke the screen first)" else "")
            Result(true, 200, "launched", mapOf("package" to started, "uri" to uri, "woke" to woke,
                "screenOn" to PowerController.screenOn(context)))
        } catch (ex: ActivityNotFoundException) {
            Result(false, 404, "nothing handles ${uri ?: pkg}")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "launch ${uri ?: pkg} failed: ${ex.message}")
            Result(false, 500, "launch failed: ${ex.message}")
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) { false }

    // ---------------------------------------------------------------- keys

    fun globalKeysAvailable(): Boolean = PiPupAccessibilityService.available()

    /// Key names this device can send right now.
    fun availableKeys(): List<String> =
        MEDIA_KEYS.keys.toList() + if (globalKeysAvailable()) GLOBAL_KEYS.keys.toList() else emptyList()

    /// POST /key. A media key goes into the app's media session when PiPup may see the
    /// sessions (0.32.0-beta.4, #83; [pkg] picks the app, else the one /media reports), and
    /// as a media key event otherwise. The answer's `via` says which: `session` or `media_key`.
    fun key(context: Context, name: String?, pkg: String? = null): Result {
        val key = name?.lowercase()?.trim()
        MEDIA_KEYS[key]?.let { code ->
            MediaSessions.transport(context, key!!, pkg?.takeIf { it.isNotBlank() })?.let { return it }
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                val now = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
                Result(true, 200, "sent $key", mapOf("via" to "media_key"))
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "media key $key failed: ${ex.message}")
                Result(false, 500, "media key failed: ${ex.message}")
            }
        }
        GLOBAL_KEYS[key]?.let { action ->
            if (!globalKeysAvailable()) {
                return Result(false, 501, "$key needs the accessibility service " +
                    "(install.sh --accessibility, or enable PiPup under Settings > Accessibility)")
            }
            return if (PiPupAccessibilityService.globalAction(action)) Result(true, 200, "sent $key")
                   else Result(false, 500, "$key refused by the system")
        }
        return Result(false, 400, "key must be one of ${(MEDIA_KEYS.keys + GLOBAL_KEYS.keys).joinToString(", ")}")
    }

    // ---------------------------------------------------------------- volume

    fun volumeState(context: Context): Map<String, Any?> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val step = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            mapOf(
                "level" to stepToPercent(step, max),
                "step" to step,
                "max" to max,
                "muted" to am.isStreamMute(AudioManager.STREAM_MUSIC),
                // true = the volume belongs to the TV set / amplifier (HDMI-CEC, passthrough):
                // no absolute level, only steps and mute
                "fixed" to am.isVolumeFixed
            )
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "volume state failed: ${ex.message}")
            mapOf("error" to ex.message)
        }
    }

    /// Exactly one of [level] (0-100), [step] (up/down) or [mute] (on/off/toggle).
    /// [showUi] shows the system volume bar, as a remote press would.
    fun volume(context: Context, level: String?, step: String?, mute: String?, showUi: Boolean): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        val given = listOf(level, step, mute).count { !it.isNullOrBlank() }
        if (given != 1) return Result(false, 400, "give exactly one of level=0-100, step=up|down, mute=on|off|toggle")
        return try {
            when {
                !level.isNullOrBlank() -> {
                    val pct = level.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                        ?: return Result(false, 400, "level must be 0-100")
                    if (am.isVolumeFixed) {
                        return Result(false, 501, "this device has a fixed volume (set by the TV or " +
                            "amplifier over HDMI-CEC); use step=up|down or mute", volumeState(context))
                    }
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, percentToStep(pct, max), flags)
                }
                !step.isNullOrBlank() -> {
                    val dir = when (step.lowercase()) {
                        "up", "+", "raise" -> AudioManager.ADJUST_RAISE
                        "down", "-", "lower" -> AudioManager.ADJUST_LOWER
                        else -> return Result(false, 400, "step must be up or down")
                    }
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, flags)
                }
                else -> {
                    val dir = when (mute!!.lowercase()) {
                        "on", "true", "1" -> AudioManager.ADJUST_MUTE
                        "off", "false", "0" -> AudioManager.ADJUST_UNMUTE
                        "toggle" -> AudioManager.ADJUST_TOGGLE_MUTE
                        else -> return Result(false, 400, "mute must be on, off or toggle")
                    }
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, flags)
                }
            }
            Result(true, 200, "ok", volumeState(context))
        } catch (ex: SecurityException) {
            // Do Not Disturb policy can refuse volume changes
            Result(false, 403, "volume change refused: ${ex.message}", volumeState(context))
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "volume failed: ${ex.message}")
            Result(false, 500, "volume failed: ${ex.message}")
        }
    }
}

/// Volume step -> percentage (0-100, rounded). A device without steps reads as 0.
internal fun stepToPercent(step: Int, max: Int): Int =
    if (max <= 0) 0 else Math.round(step.coerceIn(0, max) * 100.0 / max).toInt()

/// Percentage -> the nearest volume step. Any level above 0 gets at least step 1, so
/// "1 %" on a 15-step TV is quiet, not silent.
internal fun percentToStep(percent: Double, max: Int): Int {
    if (max <= 0) return 0
    val p = percent.coerceIn(0.0, 100.0)
    val step = Math.round(p * max / 100.0).toInt()
    return if (p > 0 && step == 0) 1 else step
}
