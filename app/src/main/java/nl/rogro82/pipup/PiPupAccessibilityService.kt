package nl.rogro82.pipup

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/// Fallback route for turning the screen off, for devices where the device admin
/// `lockNow()` does not reach standby. An enabled accessibility service may call
/// `GLOBAL_ACTION_LOCK_SCREEN` (Android 9 / API 28 and up).
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
        fun enabledInSettings(context: Context): Boolean = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.split(':').any { it.substringBefore('/') == context.packageName }
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Cannot read accessibility settings: ${ex.message}")
            false
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
