package nl.rogro82.pipup

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    fun granted(context: Context, key: String): Boolean? = when (key) {
        KEY_OVERLAY -> overlay(context)
        KEY_INSTALL -> installPackages(context)
        // null = this platform has no device administration at all, which is a
        // different answer from "not granted" - see PowerController.deviceAdminSupported
        KEY_ADMIN -> if (PowerController.deviceAdminSupported(context)) {
            PowerController.deviceAdminActive(context)
        } else null
        KEY_ACCESSIBILITY -> PiPupAccessibilityService.enabledInSettings(context)
        KEY_AUTO_START -> autoStart(context)
        else -> null
    }

    fun asMap(context: Context): Map<String, Any?> = mapOf(
        "overlay" to overlay(context),
        "installPackages" to installPackages(context),
        "autoStart" to autoStart(context),
        "deviceAdmin" to granted(context, KEY_ADMIN),
        "accessibility" to PiPupAccessibilityService.enabledInSettings(context),
        "complete" to complete(context),
        // which of these the app can hand to the user on screen (see fixIntent)
        "fixable" to mapOf(
            KEY_OVERLAY to (fixIntent(context, KEY_OVERLAY) != null),
            KEY_INSTALL to (fixIntent(context, KEY_INSTALL) != null),
            KEY_ADMIN to (fixIntent(context, KEY_ADMIN) != null),
            KEY_ACCESSIBILITY to (fixIntent(context, KEY_ACCESSIBILITY) != null)
        )
    )

    /// The adb command that grants this one, for the devices where the on-screen
    /// route does not exist. Shown on the TV and returned by /permissions/fix.
    fun adbCommand(key: String): String = when (key) {
        KEY_OVERLAY -> "adb shell appops set $PACKAGE SYSTEM_ALERT_WINDOW allow"
        KEY_INSTALL -> "adb shell appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow"
        KEY_ADMIN -> "adb shell dpm set-active-admin $PACKAGE/.AdminReceiver"
        KEY_ACCESSIBILITY ->
            "adb shell settings put secure enabled_accessibility_services " +
                    "<huidige waarde>:$PACKAGE/$PACKAGE.PiPupAccessibilityService"
        KEY_AUTO_START -> "adb shell cmd appops set $PACKAGE android:auto_start allow"
        else -> ""
    }

    /// Intent that takes the user to the screen where this permission is granted, or
    /// null when this device has no such screen.
    ///
    /// "Has no such screen" is the interesting part. Every Android build must *resolve*
    /// these actions to pass Google's compatibility suite, so a plain
    /// `resolveActivity() != null` says yes even where nothing happens: Fire OS answers
    /// with `CTSDummyIntentHandler`, Google TV with `frameworkpackagestubs.Stubs`.
    /// Offering a button that visibly does nothing is worse than offering none, so those
    /// placeholders count as absent and the adb command is shown instead.
    fun fixIntent(context: Context, key: String): Intent? {
        val intent = when (key) {
            KEY_OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            KEY_INSTALL -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            } else return null
            KEY_ADMIN -> if (!PowerController.deviceAdminSupported(context)) return null
            else Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    AdminReceiver.component(context)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.permission_admin_explanation)
                )
            }
            KEY_ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> return null
        }
        return intent.takeIf { isRealActivity(context, it) }
    }

    /// Put the screen that grants `key` in front of the user. Returns false when this
    /// device has no such screen (then only adb can do it) or the launch was refused.
    fun launchFix(context: Context, key: String): Boolean {
        val intent = fixIntent(context, key) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Cannot open the screen for $key: ${ex.message}")
            false
        }
    }

    /// Bring PiPup's own status screen forward, which lists every permission with its
    /// own fix button - the useful landing spot when nothing specific was asked for.
    fun launchApp(context: Context): Boolean = try {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot open the app: ${ex.message}")
        false
    }

    /// First permission that is missing and has an on-screen route, if any.
    fun firstMissing(context: Context): String? = FIXABLE_KEYS.firstOrNull {
        granted(context, it) == false && fixIntent(context, it) != null
    }

    private fun isRealActivity(context: Context, intent: Intent): Boolean {
        val name = try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Cannot resolve ${intent.action}: ${ex.message}")
            null
        } ?: return false
        return PLACEHOLDER_MARKERS.none { name.contains(it, ignoreCase = true) }
    }

    const val KEY_OVERLAY = "overlay"
    const val KEY_INSTALL = "install"
    const val KEY_ADMIN = "admin"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_AUTO_START = "autoStart"

    /// Order matters: this is what "fix the first thing that is missing" walks through.
    val FIXABLE_KEYS = listOf(KEY_OVERLAY, KEY_INSTALL, KEY_ADMIN, KEY_ACCESSIBILITY)

    private const val PACKAGE = "nl.rogro82.pipup"
    private const val OP_AUTO_START = "android:auto_start"

    /// Class-name fragments of the do-nothing activities vendors ship to satisfy CTS.
    private val PLACEHOLDER_MARKERS = listOf("CTSDummy", "frameworkpackagestubs")
}
