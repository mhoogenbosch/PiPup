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
            context.packageManager.canRequestPackageInstalls().also {
                mInstallCheckError = null
            }
        } else true
    } catch (ex: Throwable) {
        // Remember the failure: a swallowed exception here reports as MISSING and is
        // indistinguishable from a genuinely revoked permission - diagnose() shows it.
        mInstallCheckError = "${ex.javaClass.simpleName}: ${ex.message}"
        Log.e(LOG_TAG, "Cannot query install permission: ${ex.message}")
        false
    }

    @Volatile
    private var mInstallCheckError: String? = null

    /// Raw app-op mode ("allowed"/"ignored"/"errored"/"default"/...), or why it cannot
    /// be read. The interesting one is **default**: that is what every reinstall resets
    /// the op to, and canRequestPackageInstalls() answers false for it on the devices
    /// measured here - while a Settings screen may still show a friendly toggle. The
    /// raw mode tells those states apart where the boolean cannot.
    fun opMode(context: Context, op: String): String = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "mode $mode"
        }
    } catch (ex: Throwable) {
        "unavailable: ${ex.message}"
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
        // An active admin is proof, and it outranks any feature flag: measured on a Fire
        // TV stick (AFTKRT, Android 11) that reports FEATURE_DEVICE_ADMIN as absent while
        // `dumpsys device_policy` lists this app's AdminReceiver as an enabled admin and
        // lockNow() works. Reporting null there contradicted /state's own sleepMethod.
        // null therefore means only: not active, and no sign the platform supports it.
        KEY_ADMIN -> when {
            PowerController.deviceAdminActive(context) -> true
            PowerController.deviceAdminSupported(context) -> false
            else -> null
        }
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

    /// The intent for a permission screen, without judging whether it leads anywhere.
    fun rawIntent(context: Context, key: String): Intent? = when (key) {
        KEY_OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        KEY_INSTALL -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else null
        // No feature-flag gate: the flag is false on devices where admins demonstrably do
        // register (see granted()), so the honest filter is the placeholder check in
        // fixIntent() - Fire OS answers this action with CTSDummyDeviceAdminActivity.
        KEY_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.component(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_admin_explanation)
            )
        }
        KEY_ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else -> null
    }

    /// Which activity would handle this intent, or null when nothing does *or* when the
    /// platform hides it from us. Those two are not the same thing, which is the whole
    /// reason [fixIntent] treats them differently.
    fun resolvedActivity(context: Context, intent: Intent): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot resolve ${intent.action}: ${ex.message}")
        null
    }

    /// A do-nothing activity that a vendor ships to satisfy Google's test suite.
    fun isPlaceholder(activity: String?): Boolean =
        activity != null && PLACEHOLDER_MARKERS.any { activity.contains(it, ignoreCase = true) }

    /// Intent that takes the user to the screen where this permission is granted, or null
    /// when this device has no such screen.
    ///
    /// Three outcomes, not two:
    ///
    /// * a real activity -> use it;
    /// * a **placeholder** (Fire OS answers `CTSDummyIntentHandler`, Google TV
    ///   `frameworkpackagestubs.Stubs`) -> refuse, because a button that visibly does
    ///   nothing is worse than no button, and show the adb command instead;
    /// * **nothing resolved** -> still try. `resolveActivity()` is a query, and queries are
    ///   filtered by package visibility on Android 11+, while `startActivity()` is not - so
    ///   "I cannot see it" does not mean "it is not there". Refusing here would hide a
    ///   working button on any device whose forceQueryable list omits Settings.
    fun fixIntent(context: Context, key: String): Intent? {
        val intent = rawIntent(context, key) ?: return null
        return if (isPlaceholder(resolvedActivity(context, intent))) null else intent
    }

    /// Put the screen that grants `key` in front of the user. Returns false when this
    /// device has no such screen (then only adb can do it) or the launch was refused.
    fun launchFix(context: Context, key: String): Boolean {
        val intent = fixIntent(context, key)
        if (intent == null) {
            record(key, false, null, "no screen for this permission on this device")
            return false
        }
        if (!canLaunchActivity(context)) {
            record(key, false, null, BLOCKED_ERROR)
            Log.w(LOG_TAG, "Not opening $key: $BLOCKED_ERROR")
            return false
        }
        val activity = resolvedActivity(context, intent)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            record(key, true, activity, null)
            true
        } catch (ex: Throwable) {
            // ActivityNotFoundException = the screen really is absent; anything else is
            // usually the background-activity-launch restriction (see diagnose()).
            Log.e(LOG_TAG, "Cannot open the screen for $key: ${ex.message}")
            record(key, false, activity, "${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    /// Bring PiPup's own status screen forward, which lists every permission with its
    /// own fix button - the useful landing spot when nothing specific was asked for.
    fun launchApp(context: Context): Boolean {
        if (!canLaunchActivity(context)) {
            record("app", false, "MainActivity", BLOCKED_ERROR)
            Log.w(LOG_TAG, "Not opening the app: $BLOCKED_ERROR")
            return false
        }
        return launchAppUnchecked(context)
    }

    private fun launchAppUnchecked(context: Context): Boolean = try {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        record("app", true, "MainActivity", null)
        true
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot open the app: ${ex.message}")
        record("app", false, "MainActivity", "${ex.javaClass.simpleName}: ${ex.message}")
        false
    }

    /// First permission that is missing and has an on-screen route, if any.
    fun firstMissing(context: Context): String? = FIXABLE_KEYS.firstOrNull {
        granted(context, it) == false && fixIntent(context, it) != null
    }


    /// Everything a remote helper needs to see why a fix button did or did not appear,
    /// without asking the reporter for adb and a logcat.
    fun diagnose(context: Context): Map<String, Any?> = mapOf(
        "sdk" to Build.VERSION.SDK_INT,
        "device" to mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android" to Build.VERSION.RELEASE
        ),
        "permissions" to asMap(context),
        // Raw app-op modes: the boolean above collapses "default" (what a reinstall
        // resets to) and "ignored" into the same false - these do not.
        "opModes" to mapOf(
            "installPackages" to opMode(context, "android:request_install_packages"),
            "overlay" to opMode(context, "android:system_alert_window")
        ),
        "installCheckError" to mInstallCheckError,
        // App-ops are per user: a Settings screen looked at under another profile
        // (e.g. a kids profile on Google TV) shows that profile's state, not ours.
        "user" to android.os.Process.myUserHandle().toString(),
        "targetSdk" to context.applicationInfo.targetSdkVersion,
        // Starting an activity from the background is restricted from Android 10 on, and
        // holding SYSTEM_ALERT_WINDOW is one of the exemptions - so the one case where the
        // overlay permission is missing is also the case where opening its settings screen
        // can be blocked. Worth reporting, because the failure is silent.
        "backgroundLaunchExempt" to canLaunchActivity(context),
        "activityVisible" to activityVisible,
        "deviceAdminSupported" to PowerController.deviceAdminSupported(context),
        "screens" to FIXABLE_KEYS.associateWith { key ->
            val intent = rawIntent(context, key)
            val resolved = intent?.let { resolvedActivity(context, it) }
            mapOf(
                "granted" to granted(context, key),
                "action" to intent?.action,
                "resolvedActivity" to resolved,
                "placeholder" to isPlaceholder(resolved),
                "fixable" to (fixIntent(context, key) != null),
                "adb" to adbCommand(key)
            )
        },
        "lastFix" to lastFix()
    )

    /// Outcome of the most recent fix attempt, so a report can show what actually happened
    /// instead of "it does not work".
    private fun lastFix(): Map<String, Any?>? {
        val fix = mLastFix ?: return null
        return fix + mapOf(
            "secondsAgo" to (android.os.SystemClock.elapsedRealtime() - (fix["at"] as Long)) / 1000
        ) - "at"
    }

    private fun record(what: String, ok: Boolean, activity: String?, error: String?) {
        mLastFix = mapOf(
            "what" to what,
            "ok" to ok,
            "activity" to activity,
            "error" to error,
            "at" to android.os.SystemClock.elapsedRealtime()
        )
    }

    /// written from HTTP worker threads, read by /permissions/diagnose
    @Volatile
    private var mLastFix: Map<String, Any?>? = null

    /// How many of this app's activities are resumed right now.
    ///
    /// A counter, not a flag: the exemption the platform applies is "this *app* has a
    /// visible window", and PiPup has more than one activity. A boolean set by
    /// MainActivity alone read false the moment WakeActivity came up in front of it -
    /// which is exactly what /permissions/fix does one step before launching - so a fix
    /// requested on a sleeping TV refused itself.
    private val mVisibleActivities = java.util.concurrent.atomic.AtomicInteger(0)

    val activityVisible: Boolean
        get() = mVisibleActivities.get() > 0

    fun onActivityResumed() {
        mVisibleActivities.incrementAndGet()
    }

    fun onActivityPaused() {
        mVisibleActivities.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /// Whether this app may start an activity right now.
    ///
    /// From Android 10 on, starting an activity from the background is blocked unless the
    /// app is exempt, and a foreground service is explicitly *not* an exemption. Holding
    /// SYSTEM_ALERT_WINDOW is one - which produces a nasty circle: the case where the
    /// overlay permission is missing is exactly the case where its own settings screen
    /// cannot be opened. Worse, a blocked launch does not throw; it is silently dropped.
    /// So this is checked up front instead of pretending the request succeeded.
    fun canLaunchActivity(context: Context): Boolean =
        activityVisible || overlay(context)

    const val KEY_OVERLAY = "overlay"
    const val KEY_INSTALL = "install"
    const val KEY_ADMIN = "admin"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_AUTO_START = "autoStart"

    /// Order matters: this is what "fix the first thing that is missing" walks through.
    val FIXABLE_KEYS = listOf(KEY_OVERLAY, KEY_INSTALL, KEY_ADMIN, KEY_ACCESSIBILITY)

    /// Said in one line, because it travels to Home Assistant and into bug reports.
    const val BLOCKED_ERROR =
        "Android blocks starting an activity from the background unless the overlay " +
                "permission is granted; open PiPup on the TV (or tap its notification) " +
                "and use the button on its status screen"

    private const val PACKAGE = "nl.rogro82.pipup"
    private const val OP_AUTO_START = "android:auto_start"

    /// Class-name fragments of the do-nothing activities vendors ship to satisfy CTS.
    private val PLACEHOLDER_MARKERS = listOf("CTSDummy", "frameworkpackagestubs")
}
