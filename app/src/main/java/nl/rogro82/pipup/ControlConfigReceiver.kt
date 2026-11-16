package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/// Sets the apps PiPup may press buttons in (0.32.0, #83). Only reachable over adb:
/// the manifest guards this receiver with WRITE_SECURE_SETTINGS, which the adb shell holds
/// and ordinary apps do not. That is the same level of access it takes to switch the
/// control service on, so the list cannot be widened from the LAN.
///
///     adb shell am broadcast -f 0x20 -n nl.rogro82.pipup/.ControlConfigReceiver \
///         -a nl.rogro82.pipup.SET_CLICK_PACKAGES --es packages "com.plexapp.android"
///
/// `--es packages ""` clears the list; without the extra the list is only reported. The
/// broadcast result is `result=1, data="<the list now, comma-separated>"`.
///
/// The same door sets the token that switches `GET /screen.jpg` on (0.33.0, #89):
///
///     adb shell am broadcast -f 0x20 -n nl.rogro82.pipup/.ControlConfigReceiver \
///         -a nl.rogro82.pipup.SET_SCREEN_TOKEN --es token "<16-128 of A-Z a-z 0-9 _ ->"
///
/// `--es token ""` switches it off; without the extra the state is only reported. The result
/// is `data="on"`, `"off"` or `"invalid"` (not a valid token, nothing changed); never the token.
class ControlConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SET_SCREEN_TOKEN) {
            var valid = true
            if (intent.hasExtra(EXTRA_TOKEN)) {
                valid = ScreenShots.setToken(context, intent.getStringExtra(EXTRA_TOKEN))
                Log.i(LOG_TAG, if (!valid) "screenshot token refused: not 16-128 of A-Z a-z 0-9 _ -"
                    else "screenshot ${if (ScreenShots.enabled(context)) "on" else "off"}")
            }
            if (isOrderedBroadcast) {
                resultCode = 1
                resultData = when {
                    !valid -> "invalid"
                    ScreenShots.enabled(context) -> "on"
                    else -> "off"
                }
            }
            return
        }
        if (intent.action != ACTION_SET_CLICK_PACKAGES) return
        if (intent.hasExtra(EXTRA_PACKAGES)) {
            val given = intent.getStringExtra(EXTRA_PACKAGES)
            val packages = ClickPolicy.parse(given)
            ClickPolicy.set(context, packages)
            val rejected = ClickPolicy.rejected(given)
            Log.i(LOG_TAG, "click packages set to $packages" +
                if (rejected.isNotEmpty()) " (ignored, not package names: $rejected)" else "")
        }
        if (isOrderedBroadcast) {
            resultCode = 1
            resultData = ClickPolicy.packages(context).joinToString(",")
        }
    }

    companion object {
        const val LOG_TAG = "PiPupControlConfig"
        const val ACTION_SET_CLICK_PACKAGES = "nl.rogro82.pipup.SET_CLICK_PACKAGES"
        const val EXTRA_PACKAGES = "packages"
        const val ACTION_SET_SCREEN_TOKEN = "nl.rogro82.pipup.SET_SCREEN_TOKEN"
        const val EXTRA_TOKEN = "token"
    }
}
