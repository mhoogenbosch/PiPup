package nl.rogro82.pipup

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/// Invisible activity whose only job is to turn the screen on. Launching an activity
/// with `setTurnScreenOn(true)` is the supported way to wake a device (the wake lock
/// route is deprecated and unreliable from Android 9 onwards), and on HDMI-CEC setups
/// waking the box also switches the TV to its input.
///
/// It is translucent, `noHistory` and finishes itself again, so nothing lands in
/// recents and whatever was in the foreground comes straight back.
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        // hold the screen until we finish, so the device cannot doze straight back off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, FINISH_DELAY_MS)
    }

    companion object {
        /// Long enough for the panel (and a CEC-linked TV) to actually come up.
        const val FINISH_DELAY_MS = 1500L
    }
}
