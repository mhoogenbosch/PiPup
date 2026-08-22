package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService

/// Starts the service on boot and after the app's own APK is replaced. That second
/// one matters for the self-updater: replacing the APK stops the app, and without
/// MY_PACKAGE_REPLACED nothing brings the service back — the TV would silently drop
/// off until the next reboot. (Network-change broadcasts are no longer delivered to
/// manifest receivers on any supported Android, so there is no filter for them.)
class Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PiPupReceiver", "Starting service after ${intent.action}")
        with(context) {
            val serviceIntent = Intent(this, PiPupService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}
