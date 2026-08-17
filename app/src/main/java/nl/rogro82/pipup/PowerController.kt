package nl.rogro82.pipup

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log

/// Screen on/off for the TV this app runs on, as far as a sideloaded app can reach:
///
/// * **on** - launch [WakeActivity]; that is the supported wake path, and on HDMI-CEC
///   setups it brings the TV set along.
/// * **off** - `DevicePolicyManager.lockNow()` via [AdminReceiver], or
///   [PiPupAccessibilityService]'s `GLOBAL_ACTION_LOCK_SCREEN` when no admin is active.
///   Both need a one-time grant over adb (see install.sh), so this is the one capability
///   that can genuinely be unavailable on a device.
///
/// Nothing here pretends: [sleepMethod] returns null when neither route is granted and
/// /state publishes that, so a controller (e.g. the Home Assistant integration) can hide
/// the off switch instead of offering a button that quietly does nothing.
object PowerController {

    const val LOG_TAG = "PowerController"

    const val METHOD_DEVICE_ADMIN = "device_admin"
    const val METHOD_ACCESSIBILITY = "accessibility"

    /// self-releasing: a leaked wake lock would keep the TV awake forever
    private const val WAKE_LOCK_TIMEOUT_MS = 3_000L

    fun screenOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /// Whether this platform has device administration at all.
    ///
    /// Worth asking, because `dpm set-active-admin` happily reports `Success` on devices
    /// that do not (measured on a Nokia Streaming Box 8010 and a TCL Google TV, both of
    /// which register nothing). Reporting "not supported" instead of "not granted" keeps
    /// anyone from chasing a grant that can never stick.
    fun deviceAdminSupported(context: Context): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query device admin feature: ${ex.message}")
        false
    }

    fun deviceAdminActive(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(AdminReceiver.component(context))
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query device admin: ${ex.message}")
        false
    }

    /// Which route would be used for "off" right now, or null when none is granted.
    fun sleepMethod(context: Context): String? = when {
        deviceAdminActive(context) -> METHOD_DEVICE_ADMIN
        PiPupAccessibilityService.available() -> METHOD_ACCESSIBILITY
        else -> null
    }

    fun canSleep(context: Context): Boolean = sleepMethod(context) != null

    /// Turn the screen on. Returns false only when the platform refused both attempts.
    fun wake(context: Context): Boolean {
        var ok = false

        // Best-effort, and deliberately first: on devices where it still works the screen
        // is already coming up by the time the activity is launched.
        try {
            @Suppress("DEPRECATION")
            val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "PiPup:wake"
                )
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            ok = true
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Wake lock failed: ${ex.message}")
        }

        try {
            context.startActivity(
                Intent(context, WakeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            )
            ok = true
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "WakeActivity launch failed: ${ex.message}")
        }

        return ok
    }

    /// Turn the screen off (standby). Returns false when no route is granted, or when the
    /// granted one refused - the caller reports that instead of claiming success.
    fun sleep(context: Context): Boolean {
        if (deviceAdminActive(context)) {
            try {
                val dpm =
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
                return true
            } catch (ex: Throwable) {
                // e.g. SecurityException when force-lock was revoked behind our back:
                // fall through to the accessibility route rather than giving up
                Log.e(LOG_TAG, "lockNow failed: ${ex.message}")
            }
        }
        if (PiPupAccessibilityService.lockScreen()) {
            return true
        }
        Log.w(LOG_TAG, "No way to turn the screen off: grant device admin or accessibility")
        return false
    }
}
