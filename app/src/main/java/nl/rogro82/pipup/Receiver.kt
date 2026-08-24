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
///
/// Boot: BOOT_COMPLETED is only broadcast once the device reaches an unlocked user
/// session, which a TV that boots to standby after a mains-power restore may never
/// do until it is turned on. Both this receiver and the service are directBootAware
/// and also listen for LOCKED_BOOT_COMPLETED, so the service starts in the early
/// locked-boot phase — before turn-on — instead (reported in the field: the app
/// never came up after a power cut until it was opened by hand).
class Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PiPupReceiver", "Starting service after ${intent.action}")
        // A stray throw here (e.g. a foreground-service start the platform briefly
        // refuses during locked boot) must not crash the receiver; BOOT_COMPLETED
        // would still follow on unlock.
        runCatching {
            with(context) {
                val serviceIntent = Intent(this, PiPupService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }.onFailure { Log.w("PiPupReceiver", "Service start failed: ${it.message}") }
    }
}
