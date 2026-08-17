package nl.rogro82.pipup

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/// What PiPup was actually granted on this device.
///
/// An app cannot grant itself any of these: `appops` is shell/system territory, which is
/// exactly why the readme hands out adb commands. What it *can* do is report the truth, so
/// the status screen and /state show a missing overlay permission instead of leaving you
/// with an app that answers every request happily and never draws anything.
object Permissions {

    const val LOG_TAG = "Permissions"

    /// SYSTEM_ALERT_WINDOW - without it popups are accepted but never appear.
    fun overlay(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query overlay permission: ${ex.message}")
        false
    }

    /// REQUEST_INSTALL_PACKAGES - needed for the self-update to install its download.
    fun installPackages(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query install permission: ${ex.message}")
        false
    }

    /// TCL's vendor app-op that decides whether Android may restart a killed service.
    /// Returns null on devices that do not have the op at all (Fire TV, Nokia, Xiaomi, ...),
    /// which is a different thing from "not granted" and must not be reported as a problem.
    fun autoStart(context: Context): Boolean? = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OP_AUTO_START, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_AUTO_START, android.os.Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        // IllegalArgumentException("Unknown operation string") on every non-TCL device
        null
    }

    /// Everything this device needs for popups to work. Vendor-specific and
    /// power-related grants are reported separately: they are optional.
    fun complete(context: Context): Boolean = overlay(context)

    fun asMap(context: Context): Map<String, Any?> = mapOf(
        "overlay" to overlay(context),
        "installPackages" to installPackages(context),
        "autoStart" to autoStart(context),
        "deviceAdmin" to PowerController.deviceAdminActive(context),
        "accessibility" to PiPupAccessibilityService.enabledInSettings(context),
        "complete" to complete(context)
    )

    private const val OP_AUTO_START = "android:auto_start"
}
