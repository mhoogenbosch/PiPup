package nl.rogro82.pipup

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/// Device admin with a single purpose: `lockNow()`, which puts an Android TV into
/// standby. Nothing else about the device is managed - the policy file only asks
/// for `force-lock`.
///
/// Activating an admin normally needs an on-screen confirmation, which is awkward
/// with a remote; grant it once over adb instead (install.sh does this for you):
///
///     adb shell dpm set-active-admin nl.rogro82.pipup/.AdminReceiver
///
/// Until then /state reports `power.canSleep: false` and the app never pretends it
/// can turn the screen off.
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
