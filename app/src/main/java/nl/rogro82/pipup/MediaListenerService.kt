package nl.rogro82.pipup

import android.service.notification.NotificationListenerService
import android.util.Log

/// Exists only for the grant (0.32.0, #83): Android hands the list of active media sessions
/// to an app whose notification listener is enabled. Notifications themselves are not read:
/// onNotificationPosted is not overridden, so nothing is done with them.
///
///     adb shell cmd notification allow_listener nl.rogro82.pipup/nl.rogro82.pipup.MediaListenerService
///
/// (install.sh --media does this). On a low-RAM device Android ignores the grant.
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(MediaSessions.LOG_TAG, "Media listener connected")
        // the grant may have arrived after the service started: (re)start tracking now
        MediaSessions.start(applicationContext)
    }
}
