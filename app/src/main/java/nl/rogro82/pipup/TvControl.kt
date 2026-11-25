package nl.rogro82.pipup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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

    /// 1.1.0 (#95): the D-pad and OK as accessibility global actions. Android only offers
    /// these from Android 13 (API 33); below that only adb can press them.
    val DPAD_KEYS: Map<String, Int> = linkedMapOf(
        "up" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_UP,
        "down" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_DOWN,
        "left" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_LEFT,
        "right" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT,
        "ok" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
    )

    /// 1.1.0 (#95): keys that are not a key press - wake the screen, close system dialogs.
    const val KEY_WAKEUP = "wakeup"
    const val KEY_CLOSE_DIALOGS = "close_dialogs"

    /// How long `wakeup` waits for a screensaver to end.
    private const val DREAM_WAIT_MS = 3000L
    private const val DREAM_POLL_MS = 100L

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

    /// D-pad and OK (1.1.0, #95): Android 13+ and either accessibility service of PiPup on -
    /// the screen-off one, or the control service (a global action reads nothing on screen).
    fun dpadAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (PiPupAccessibilityService.available() || PiPupControlService.connected())

    /// ACTION_CLOSE_SYSTEM_DIALOGS (1.1.0, #95): any app may send it up to Android 11; from
    /// Android 12 only the system may (an app targeting 12+ gets a SecurityException).
    fun closeDialogsAvailable(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    /// Key names this device can send right now, in the order /state lists them.
    fun availableKeys(): List<String> =
        MEDIA_KEYS.keys.toList() + KEY_WAKEUP +
            (if (globalKeysAvailable()) GLOBAL_KEYS.keys.toList() else emptyList()) +
            (if (dpadAvailable()) DPAD_KEYS.keys.toList() else emptyList()) +
            (if (closeDialogsAvailable()) listOf(KEY_CLOSE_DIALOGS) else emptyList())

    /// Every name POST /key knows, available here or not (for the 400 answer).
    private fun knownKeys(): List<String> =
        MEDIA_KEYS.keys.toList() + KEY_WAKEUP + GLOBAL_KEYS.keys + DPAD_KEYS.keys + KEY_CLOSE_DIALOGS

    /// POST /key. A media key goes into the app's media session when PiPup may see the
    /// sessions (0.32.0-beta.4, #83; [pkg] picks the app, else the one /media reports), and
    /// as a media key event otherwise. The answer's `via` says which: `session` or `media_key`.
    ///
    /// 1.1.0 (#95): `wakeup`, the D-pad (`up`, `down`, `left`, `right`, `ok`, Android 13+) and
    /// `close_dialogs` (up to Android 11). [dreaming] tells whether a screensaver runs.
    fun key(context: Context, name: String?, pkg: String? = null, dreaming: () -> Boolean = { false }): Result {
        val key = name?.lowercase()?.trim()
        if (key == KEY_WAKEUP) return wakeup(context, dreaming)
        if (key == KEY_CLOSE_DIALOGS) return closeDialogs(context)
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
        DPAD_KEYS[key]?.let { action ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return Result(false, 501, "$key: before Android 13 an app cannot press the D-pad or OK; " +
                    "send KEYCODE_DPAD_* over adb")
            }
            if (!dpadAvailable()) {
                return Result(false, 501, "$key needs one of PiPup's accessibility services " +
                    "(install.sh --accessibility or --click)")
            }
            val sent = if (PiPupAccessibilityService.available()) PiPupAccessibilityService.globalAction(action)
                       else globalActionViaControl(action)
            return if (sent) Result(true, 200, "sent $key") else Result(false, 500, "$key refused by the system")
        }
        return Result(false, 400, "key must be one of ${knownKeys().joinToString(", ")}")
    }

    private fun globalActionViaControl(action: Int): Boolean = try {
        PiPupControlService.service()?.performGlobalAction(action) ?: false
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "global action $action failed: ${ex.message}")
        false
    }

    /// `wakeup` (1.1.0, #95): the screen on and the screensaver ended, and nothing else. It
    /// acts only when the screen is off or a screensaver runs: the wake path starts an
    /// activity, which can pause the app on screen, so a TV in use is left alone. `woke` says
    /// whether there was something to do and it worked; with the screen still off the caller
    /// wakes the TV another way (adb KEYCODE_WAKEUP). Blocking: web-server thread only.
    private fun wakeup(context: Context, dreaming: () -> Boolean): Result {
        val wasOn = PowerController.screenOn(context)
        if (wasOn && !dreaming()) {
            return Result(true, 200, "already awake", mapOf("woke" to false, "screenOn" to true, "dreaming" to false))
        }
        val tried = PowerController.wake(context)
        val on = tried && PowerController.waitForScreen(context, true)
        val awake = on && waitUntil(DREAM_WAIT_MS) { !dreaming() }
        if (!on) Log.w(LOG_TAG, "wakeup: the screen stayed off")
        return Result(true, 200, if (awake) "woke" else if (on) "the screensaver did not end" else "the screen stayed off",
            mapOf("woke" to awake, "screenOn" to PowerController.screenOn(context), "dreaming" to dreaming()))
    }

    private fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (done()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try { Thread.sleep(DREAM_POLL_MS) } catch (_: InterruptedException) { return false }
        }
    }

    /// `close_dialogs` (1.1.0, #95): ACTION_CLOSE_SYSTEM_DIALOGS, which closes system dialogs
    /// such as the notification panel or recents. Android 12 and newer keep it for the system.
    private fun closeDialogs(context: Context): Result {
        if (!closeDialogsAvailable()) {
            return Result(false, 501, "only the system may close system dialogs since Android 12; " +
                "use adb: am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        }
        return try {
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            Result(true, 200, "sent close_dialogs")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "close_dialogs failed: ${ex.message}")
            Result(false, 500, "close_dialogs failed: ${ex.message}")
        }
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
