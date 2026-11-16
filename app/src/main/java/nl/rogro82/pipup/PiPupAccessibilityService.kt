package nl.rogro82.pipup

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/// Fallback route for turning the screen off, for devices where the device admin
/// `lockNow()` does not reach standby. An enabled accessibility service may call
/// `GLOBAL_ACTION_LOCK_SCREEN` (Android 9 / API 28 and up). Since 0.29.0 it also sends
/// the home and back keys for POST /key.
///
/// It observes nothing: no event types are declared in accessibility_service.xml, so
/// the service never receives UI content - it exists purely for that one action.
/// Enable it once over adb (install.sh --accessibility does this):
///
///     adb shell settings put secure enabled_accessibility_services \
///         nl.rogro82.pipup/nl.rogro82.pipup.PiPupAccessibilityService
///     adb shell settings put secure accessibility_enabled 1
class PiPupAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(LOG_TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        const val LOG_TAG = "PiPupAccessibility"

        @Volatile
        private var instance: PiPupAccessibilityService? = null

        /// Whether the service is connected *and* the platform has the lock action.
        fun available(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && instance != null

        /// Whether the user (or adb) enabled us in the accessibility settings. Read from
        /// settings rather than from `instance`, so a service that is enabled but not yet
        /// bound still reports as configured.
        fun enabledInSettings(context: Context): Boolean =
            componentEnabled(context, PiPupAccessibilityService::class.java)

        /// Whether the accessibility service [service] of this app is in the enabled list.
        /// Matches the whole component (0.32.0): PiPup has two services since then, and the
        /// control service being on must not read as this one being on.
        fun componentEnabled(context: Context, service: Class<*>): Boolean = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledListContains(enabled, context.packageName, service.name)
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Cannot read accessibility settings: ${ex.message}")
            false
        }

        /// 0.29.0: home/back for POST /key. Same opt-in as the screen-off fallback.
        fun globalAction(action: Int): Boolean {
            val service = instance ?: return false
            return try {
                service.performGlobalAction(action)
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "global action $action failed: ${ex.message}")
                false
            }
        }

        fun lockScreen(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return try {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "GLOBAL_ACTION_LOCK_SCREEN failed: ${ex.message}")
                false
            }
        }
    }
}

/// Whether the `:`-separated enabled-services setting [enabled] holds [className] of
/// [pkg], in the full (`pkg/pkg.Cls`) or short (`pkg/.Cls`) form.
internal fun enabledListContains(enabled: String, pkg: String, className: String): Boolean {
    val short = if (className.startsWith("$pkg.")) "." + className.removePrefix("$pkg.") else null
    return enabled.split(':').any { entry ->
        val parts = entry.trim().split('/', limit = 2)
        parts.size == 2 && parts[0] == pkg && (parts[1] == className || parts[1] == short)
    }
}
