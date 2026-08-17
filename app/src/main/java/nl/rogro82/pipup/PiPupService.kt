package nl.rogro82.pipup

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.util.Locale
import java.util.UUID


class PiPupService : Service(), WebServer.Handler {
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mOverlay: FrameLayout? = null
    private var mPopup: PopupView? = null
    // Written on the main thread, read by the NanoHTTPD worker thread in /state:
    // @Volatile / atomics guarantee the HTTP reader sees a consistent, published
    // value instead of a torn or stale one (the sensors HA polls feed off this).
    @Volatile private var mCurrentProps: PopupProps? = null
    @Volatile private var mShownAt: Long = 0L
    // Last *received* popup (survives dismiss/expiry) — shown on the status screen and
    // in /state so you can verify at the TV what HA actually sent.
    @Volatile private var mLastPopup: PopupProps? = null
    @Volatile private var mLastPopupAt: Long = 0L
    private val mPopupsShown = java.util.concurrent.atomic.AtomicLong(0)
    private val mStartedAt: Long = SystemClock.elapsedRealtime()
    private lateinit var mWebServer: WebServer

    private var mNsdManager: NsdManager? = null
    private var mNsdListener: NsdManager.RegistrationListener? = null

    private val mWatchdogHandler = Handler(Looper.getMainLooper())
    private val mWatchdogCleanups = java.util.concurrent.atomic.AtomicLong(0)

    private var mTts: TextToSpeech? = null
    private var mTtsReady = false
    private var mTtsPending: Pair<String, String?>? = null
    private val mTtsDefaultLocale: Locale = Locale.getDefault()
    private val mTtsIdleHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel(
            "service_channel",
            getString(R.string.service_channel_name),
            getString(R.string.service_channel_name)
        )

        enterForeground()

        // Binding :7979 can fail when the previous process was killed and its socket is not
        // released yet (low-memory devices restart this service within seconds). An unguarded
        // start() threw straight out of onCreate, leaving a live process with a dead server -
        // which no external "is the process running?" check can tell apart from a healthy one.
        mWebServer = WebServer(SERVER_PORT, this)
        if (!startWebServer()) {
            Log.e(LOG_TAG, "Giving up on port $SERVER_PORT; stopping for a clean restart")
            stopSelf()
            return
        }

        registerNsd()
        startWatchdog()
        startUpdateChecker()
        // TTS is initialised lazily: the engine is a separate ~100MB process and keeping it
        // bound for the entire service lifetime is the single biggest reason this app gets
        // picked by low-memory killers on 1GB TVs. Popups without `tts` never need it.
    }

    /// Post the ongoing notification and become a foreground service.
    ///
    /// MUST be called for *every* startForegroundService(), not just on creation:
    /// when the service is already running only onStartCommand runs, and Android
    /// kills the process with
    /// `RemoteServiceException: Context.startForegroundService() did not then call
    /// Service.startForeground()` if the call is missing. That is exactly what a
    /// keep-alive automation or the connectivity Receiver triggers — the "rescue"
    /// then crashed the app instead of keeping it alive (seen on a TCL TV that sat
    /// at 26% availability because of it). startForeground is idempotent, so
    /// calling it again on an already-foreground service is harmless.
    private fun enterForeground() {
        try {
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else 0

            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java), pendingIntentFlags
            )

            val notification = NotificationCompat.Builder(this, "service_channel")
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_notification_text))
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0

            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, serviceType)
        } catch (ex: Throwable) {
            // Never let this throw out of onCreate/onStartCommand: a failure here
            // would take the whole service down, which is worse than running
            // without the notification.
            Log.e(LOG_TAG, "startForeground failed: ${ex.message}")
        }
    }

    private fun startWebServer(): Boolean {
        repeat(WEBSERVER_START_ATTEMPTS) { attempt ->
            try {
                mWebServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d(LOG_TAG, "WebServer started on :$SERVER_PORT (attempt ${attempt + 1})")
                return true
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "WebServer start attempt ${attempt + 1} failed: ${ex.message}")
                try {
                    mWebServer.stop()
                } catch (_: Throwable) {
                }
                if (attempt < WEBSERVER_START_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(WEBSERVER_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()

        mWatchdogHandler.removeCallbacksAndMessages(null)
        mTtsIdleHandler.removeCallbacksAndMessages(null)

        unregisterNsd()

        shutdownTts()

        try {
            mWebServer.stop()
        } catch (ex: Throwable) {
            // never let teardown throw: onCreate may have bailed out before the server bound
            Log.e(LOG_TAG, "WebServer stop failed: ${ex.message}")
        }
    }

    /// stable device identifier: generated once, survives app updates (not reinstalls)
    private fun deviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
    }

    private fun deviceName(): String = try {
        Settings.Global.getString(contentResolver, "device_name")
    } catch (_: Throwable) {
        null
    }?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun registerNsd() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PiPup ${deviceName()}".take(63)
                serviceType = NSD_SERVICE_TYPE
                port = SERVER_PORT
                setAttribute("id", deviceId())
                setAttribute("name", deviceName())
                setAttribute("version", BuildConfig.VERSION_NAME)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(LOG_TAG, "NSD registered as ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(LOG_TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            mNsdListener = listener
            mNsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
                it.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (ex: Throwable) {
            // discovery is best-effort: the HTTP API works fine without it
            Log.e(LOG_TAG, "NSD registration error: ${ex.message}")
        }
    }

    private fun unregisterNsd() {
        try {
            mNsdListener?.let { mNsdManager?.unregisterService(it) }
        } catch (_: Throwable) {
        }
        mNsdListener = null
        mNsdManager = null
    }

    /// POST the pressed button to the callback URL (HA webhook), off the main thread
    private fun sendButtonCallback(popup: PopupProps, button: PopupProps.Button) {
        val url = popup.callback
        if (url.isNullOrBlank()) {
            Log.w(LOG_TAG, "Button ${button.id} pressed but no callback configured")
            return
        }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = Json.writeValueAsString(mapOf(
                    "popup" to popup.id,
                    "button" to button.id,
                    "label" to button.label,
                    "device" to deviceId(),
                    "name" to deviceName()
                ))
                conn.outputStream.use { it.write(body.toByteArray()) }
                Log.d(LOG_TAG, "Button callback ${button.id} -> HTTP ${conn.responseCode}")
                conn.disconnect()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Button callback failed: ${ex.message}")
            }
        }.start()
    }

    /// idempotent: only ever creates one engine, and only when something wants to speak
    private fun initTts() {
        if (mTts != null) return
        try {
            mTts = TextToSpeech(this) { status ->
                mTtsReady = status == TextToSpeech.SUCCESS
                if (!mTtsReady) {
                    Log.e(LOG_TAG, "TTS init failed ($status)")
                }
                mTtsPending?.let { (text, language) ->
                    mTtsPending = null
                    if (mTtsReady) doSpeak(text, language)
                }
            }
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS unavailable: ${ex.message}")
        }
    }

    private fun speak(text: String, language: String?) {
        mTtsIdleHandler.removeCallbacksAndMessages(null)
        if (mTtsReady) {
            doSpeak(text, language)
        } else {
            mTtsPending = text to language // engine still starting: keep the latest utterance
            initTts()
        }
        // release the engine process again once nobody has spoken for a while
        mTtsIdleHandler.postDelayed({ shutdownTts() }, TTS_IDLE_TIMEOUT_MS)
    }

    private fun shutdownTts() {
        val engine = mTts ?: return
        try {
            engine.stop()
            engine.shutdown()
            Log.d(LOG_TAG, "TTS engine released")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS shutdown failed: ${ex.message}")
        }
        mTts = null
        mTtsReady = false
        mTtsPending = null
    }

    private fun doSpeak(text: String, language: String?) {
        val tts = mTts ?: return
        try {
            val locale = language?.let { Locale.forLanguageTag(it) } ?: mTtsDefaultLocale
            tts.language = locale // best-effort: engine falls back when the language is missing
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pipup-tts")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "TTS error: ${ex.message}")
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Answer every startForegroundService() — see enterForeground(). onCreate
        // does not run for an already-running service, so doing this only there
        // made each extra start request crash the process.
        enterForeground()

        // A restart after a kill (START_STICKY, or the boot/connectivity Receiver)
        // arrives here with the web server gone; bring it back rather than sitting
        // there as a live process with a dead server.
        // isAlive() = started, socket open and the listener thread running — a
        // stricter check than wasStarted(), which stays true after a crash.
        if (!runCatching { mWebServer.isAlive }.getOrDefault(false)) {
            Log.w(LOG_TAG, "WebServer not running on start command; restarting it")
            if (!startWebServer()) {
                Log.e(LOG_TAG, "Giving up on port $SERVER_PORT; stopping for a clean restart")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initNotificationChannel(id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(id, name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = description
        notificationManager.createNotificationChannel(channel)
    }

    private fun removePopup(removeOverlay: Boolean = false) {

        mHandler.removeCallbacksAndMessages(null)

        mCurrentProps = null
        mShownAt = 0L

        // every step guarded: a throwing WebView/VideoView teardown or WindowManager call
        // used to abort the removal halfway, leaving the popup visible on screen while the
        // state already said it was gone (the "popup stays on TV" reports)

        mPopup?.let {
            try {
                it.destroy()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Popup destroy failed: ${ex.message}")
            }
        }
        mPopup = null

        mOverlay?.let { overlay ->
            try {
                overlay.removeAllViews()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Overlay removeAllViews failed: ${ex.message}")
            }
            if (removeOverlay) {
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeViewImmediate(overlay)
                    mOverlay = null
                } catch (ex: IllegalArgumentException) {
                    mOverlay = null // already detached
                } catch (ex: Throwable) {
                    // keep the reference: the watchdog retries the removal
                    Log.e(LOG_TAG, "Overlay removal failed (watchdog will retry): ${ex.message}")
                }
            }
        }
    }

    /// consistency watchdog: no active popup should ever leave an overlay behind.
    /// NB: runs on its own handler — mHandler gets removeCallbacksAndMessages(null) on
    /// every removePopup, which would silently kill a watchdog scheduled there.
    private fun startWatchdog() {
        mWatchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (mCurrentProps == null && (mOverlay != null || mPopup != null)) {
                        Log.w(LOG_TAG, "Watchdog: stale overlay without active popup, force removing")
                        mWatchdogCleanups.incrementAndGet()
                        removePopup(true)
                    }
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Watchdog error: ${ex.message}")
                }
                mWatchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }, WATCHDOG_INTERVAL_MS)
    }

    /// Periodic self-update check against the fork's GitHub releases. Runs on the
    /// watchdog handler (mHandler is cleared on every popup removal) and announces a
    /// new version once, on screen, with an Install button — sideloaded TVs have no
    /// store, and not every user has adb.
    private fun startUpdateChecker() {
        mWatchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                Thread {
                    if (UpdateManager.check() && UpdateManager.updateAvailable) {
                        maybeAnnounceUpdate()
                    }
                }.start()
                mWatchdogHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
            }
        }, UPDATE_CHECK_FIRST_DELAY_MS)
    }

    /// Show the "update available" popup at most once per version, and never over a
    /// popup that is already on screen or while the screen is off.
    private fun maybeAnnounceUpdate() {
        val version = UpdateManager.latestVersion ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_UPDATE_ANNOUNCED, null) == version) return

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive || mCurrentProps != null) return

        prefs.edit().putString(PREF_UPDATE_ANNOUNCED, version).apply()
        mHandler.post {
            createPopup(
                PopupProps(
                    duration = 60,
                    id = UPDATE_POPUP_ID,
                    position = PopupProps.Position.BottomRight,
                    title = getString(R.string.update_available_title, version),
                    message = getString(R.string.update_available_message),
                    buttons = listOf(PopupProps.Button("install", getString(R.string.update_install)))
                )
            )
        }
    }

    private fun scheduleRemoval(popup: PopupProps) {
        // duration <= 0 means: show until /cancel or until replaced
        if (!popup.indefinite) {
            mHandler.postDelayed({
                removePopup(true)
            }, (popup.duration * 1000).toLong())
        }
    }

    @Suppress("DEPRECATION")
    private fun createPopup(popup: PopupProps) {
        try {

            Log.d(LOG_TAG, "Create popup: $popup")

            mLastPopup = popup
            mLastPopupAt = SystemClock.elapsedRealtime()

            // update-in-place: same id and same content -> keep the view (and its
            // video/web stream) alive and only reschedule the removal timer

            val current = mCurrentProps
            if (mPopup != null && current?.id != null &&
                current.id == popup.id && current.sameContent(popup)) {

                Log.d(LOG_TAG, "Popup ${popup.id} unchanged: rescheduling removal only")

                mHandler.removeCallbacksAndMessages(null)
                // still speak when the tts text changed (content comparison ignores tts)
                if (!popup.tts.isNullOrBlank() && popup.tts != current.tts) {
                    speak(popup.tts, popup.ttsLanguage)
                }
                mCurrentProps = popup
                scheduleRemoval(popup)
                return
            }

            // remove current popup

            removePopup()

            // create or reuse the current overlay; the window is only focusable when the
            // popup carries buttons (otherwise it must never steal the remote from the TV app)

            val layoutFlags: Int = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> WindowManager.LayoutParams.TYPE_TOAST
            }

            val windowFlags = if (popup.buttons.isNotEmpty())
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlags,
                windowFlags,
                PixelFormat.TRANSLUCENT
            )

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            mOverlay = when (val overlay = mOverlay) {
                is FrameLayout -> overlay.also {
                    try {
                        wm.updateViewLayout(it, params)
                    } catch (ex: Throwable) {
                        Log.e(LOG_TAG, "updateViewLayout failed: ${ex.message}")
                    }
                }
                else -> object : FrameLayout(this@PiPupService) {
                    // BACK dismisses a focusable (button) popup without firing a callback
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            mHandler.post { removePopup(true) }
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {

                    setPadding(20, 20, 20, 20)

                    wm.addView(this, params)
                }
            }.also {

                // inflate the popup layout

                mPopup = PopupView.build(this, popup)

                mPopup?.onButton = { btn ->
                    // The app's own update popup is handled locally; it has no
                    // callback URL and must not be mistaken for a user button.
                    if ((mCurrentProps ?: popup).id == UPDATE_POPUP_ID) {
                        Thread { UpdateManager.installLatest(this) }.start()
                    } else {
                        // Use the currently shown props, not this closure's `popup`:
                        // an update-in-place reuses the view but can carry a new
                        // callback URL, so the captured value would be stale.
                        sendButtonCallback(mCurrentProps ?: popup, btn)
                    }
                    mHandler.post { removePopup(true) }
                }

                it.addView(mPopup, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ). apply {

                    // position the popup

                    gravity = when(popup.position) {
                        PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                        PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                        PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                        PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                        PopupProps.Position.Center -> Gravity.CENTER
                    }
                })

                if (popup.buttons.isNotEmpty()) {
                    // focus the first button so a single OK press activates it
                    mPopup?.requestFocus()
                }
            }

            mCurrentProps = popup
            mShownAt = SystemClock.elapsedRealtime()
            mPopupsShown.incrementAndGet()

            if (!popup.tts.isNullOrBlank()) {
                speak(popup.tts, popup.ttsLanguage)
            }

            // schedule removal

            scheduleRemoval(popup)

        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
    }

    private fun stateResponse(): NanoHTTPD.Response {
        val current = mCurrentProps
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val state = mutableMapOf<String, Any?>(
            "app" to "PiPup",
            "version" to BuildConfig.VERSION_NAME,
            "id" to deviceId(),
            "name" to deviceName(),
            "visible" to (current != null),
            "screenOn" to powerManager.isInteractive,
            "popupsShown" to mPopupsShown.get(),
            "watchdogCleanups" to mWatchdogCleanups.get(),
            "uptime" to (SystemClock.elapsedRealtime() - mStartedAt) / 1000,
            "device" to mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "android" to Build.VERSION.RELEASE
            )
        )
        if (current != null) {
            state["popup"] = mapOf(
                "id" to current.id,
                "duration" to current.duration,
                "indefinite" to current.indefinite,
                "elapsed" to ((SystemClock.elapsedRealtime() - mShownAt) / 1000)
            )
        }
        state["power"] = mapOf(
            "canWake" to true,
            "canSleep" to PowerController.canSleep(this),
            "sleepMethod" to PowerController.sleepMethod(this)
        )
        state["permissions"] = Permissions.asMap(this)
        state["update"] = mapOf(
            "available" to UpdateManager.updateAvailable,
            "latest" to UpdateManager.latestVersion,
            "installing" to UpdateManager.isInstalling,
            "checkedSecondsAgo" to UpdateManager.lastCheckedAt.takeIf { it > 0 }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
            "error" to UpdateManager.lastError
        )
        val last = mLastPopup
        if (last != null) {
            state["lastPopup"] = mapOf(
                "id" to last.id,
                "position" to last.position.name,
                "duration" to last.duration,
                "indefinite" to last.indefinite,
                "muted" to mediaMuted(last),
                "media" to mediaInfo(last),
                "tts" to !last.tts.isNullOrBlank(),
                "buttons" to last.buttons.size,
                "secondsAgo" to ((SystemClock.elapsedRealtime() - mLastPopupAt) / 1000)
            )
        }
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            APPLICATION_JSON,
            Json.writeValueAsString(state)
        )
    }

    /// POST /power?state=on|off|toggle - screen on/off for this TV.
    ///
    /// Runs straight on the web-server thread: waking (startActivity/wake lock) and
    /// sleeping (lockNow/global action) are binder calls that touch no views, so the
    /// caller gets the real result instead of a fire-and-forget "accepted".
    ///
    /// 501 (not 400) when the device has no way to turn the screen off - that tells a
    /// controller "this device cannot", as opposed to "your request was malformed".
    private fun powerResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val requested = session.parameters["state"]?.firstOrNull()?.lowercase()
        val target = when (requested) {
            "on", "wake", "true", "1" -> true
            "off", "sleep", "false", "0" -> false
            "toggle" -> !PowerController.screenOn(this)
            else -> return InvalidRequest("state must be on, off or toggle")
        }

        val method = if (target) "activity" else PowerController.sleepMethod(this)
        val ok = if (target) PowerController.wake(this) else PowerController.sleep(this)

        Log.d(LOG_TAG, "Power ${if (target) "on" else "off"} via ${method ?: "-"}: ok=$ok")

        val body = Json.writeValueAsString(mapOf(
            "state" to if (target) "on" else "off",
            "ok" to ok,
            "method" to method,
            // the wake activity is still starting up, so this can lag one poll behind
            "screenOn" to PowerController.screenOn(this)
        ))
        return newFixedLengthResponse(
            if (ok) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
            APPLICATION_JSON,
            body
        )
    }

    /// Media summary for /state's lastPopup: {type, width, height?} or null.
    private fun mediaInfo(p: PopupProps): Map<String, Any?>? = when (val m = p.media) {
        is PopupProps.Media.Web -> mapOf("type" to "web", "width" to m.width, "height" to m.height)
        is PopupProps.Media.Video -> mapOf("type" to "video", "width" to m.width)
        is PopupProps.Media.Image -> mapOf("type" to "image", "width" to m.width)
        is PopupProps.Media.Bitmap -> mapOf("type" to "bitmap", "width" to m.width)
        null -> null
    }

    /// Muted flag of the last popup's media; null when the media type has no audio.
    private fun mediaMuted(p: PopupProps): Boolean? = when (val m = p.media) {
        is PopupProps.Media.Web -> m.muted
        is PopupProps.Media.Video -> m.muted
        else -> null
    }

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        return session?.let {
            when(session.method) {
                NanoHTTPD.Method.GET -> {
                    when(session.uri) {
                        "/state" -> stateResponse()
                        else -> InvalidRequest("unknown uri: ${session.uri}")
                    }
                }
                NanoHTTPD.Method.POST -> {

                    when(session.uri) {
                        "/state" -> stateResponse()
                        "/update" -> {
                            // Self-update on request (own popup button, or the Home
                            // Assistant integration's update entity). Runs off the
                            // web-server thread; progress is visible in /state.
                            Thread {
                                if (!UpdateManager.updateAvailable) UpdateManager.check()
                                if (UpdateManager.updateAvailable) {
                                    UpdateManager.installLatest(this@PiPupService)
                                }
                            }.start()
                            OK("update started")
                        }
                        "/power" -> powerResponse(session)
                        "/cancel" -> {
                            // optional ?id=<popup id>: only cancel when it matches the visible popup
                            val id = session.parameters["id"]?.firstOrNull()
                            val current = mCurrentProps

                            if (id != null && current != null && current.id != id) {
                                OK("id mismatch: visible popup is ${current.id}")
                            } else {
                                mHandler.post {
                                    removePopup(true)
                                }
                                OK()
                            }
                        }
                        "/notify" -> {
                            try {
                                val contentType = session.headers["content-type"] ?: APPLICATION_JSON
                                val popup = when {
                                    contentType.startsWith(APPLICATION_JSON) -> {

                                        // read the body by Content-Length when present, else
                                        // fall back to draining the stream (chunked transfer /
                                        // clients that omit the header would otherwise parse as empty)

                                        val declaredLength = session.headers["content-length"]?.toIntOrNull()
                                        val content = if (declaredLength != null && declaredLength >= 0) {
                                            val buf = ByteArray(declaredLength)
                                            var read = 0
                                            while (read < declaredLength) {
                                                val res = session.inputStream.read(buf, read, declaredLength - read)
                                                if (res < 0) break
                                                read += res
                                            }
                                            buf
                                        } else {
                                            session.inputStream.readBytes()
                                        }

                                        Json.readValue(content, PopupProps::class.java)
                                            ?: throw Exception("failed to parse input")

                                    }
                                    contentType.startsWith(MULTIPART_FORM_DATA) -> {

                                        val files = mutableMapOf<String, String>()
                                        session.parseBody(files)

                                        // flatten parameters

                                        val params = session.parameters.mapValues { it.value.firstOrNull() }

                                        val duration = params["duration"]?.toIntOrNull()
                                            ?: PopupProps.DEFAULT_DURATION

                                        val position = PopupProps.Position.values()[params["position"]?.toIntOrNull() ?: 0]

                                        val backgroundColor = params["backgroundColor"]
                                            ?: PopupProps.DEFAULT_BACKGROUND_COLOR

                                        val title = params["title"]

                                        val titleSize = params["titleSize"]?.toFloatOrNull()
                                            ?: PopupProps.DEFAULT_TITLE_SIZE

                                        val titleColor = params["titleColor"]
                                            ?: PopupProps.DEFAULT_TITLE_COLOR

                                        val message = params["message"]

                                        val messageSize = params["messageSize"]?.toFloatOrNull()
                                            ?: PopupProps.DEFAULT_MESSAGE_SIZE

                                        val messageColor = params["messageColor"]
                                            ?: PopupProps.DEFAULT_MESSAGE_COLOR

                                        val media = when(val image = files["image"]) {
                                            is String -> {
                                                File(image).absoluteFile.let {
                                                    val bitmap = BitmapFactory.decodeStream(it.inputStream())
                                                    val imageWidth = params["imageWidth"]?.toIntOrNull() ?: PopupProps.DEFAULT_MEDIA_WIDTH

                                                    PopupProps.Media.Bitmap(image = bitmap, width = imageWidth)
                                                }
                                            }
                                            else -> null
                                        }

                                        PopupProps(
                                            duration = duration,
                                            id = params["id"],
                                            position = position,
                                            backgroundColor =  backgroundColor,
                                            title = title,
                                            titleSize = titleSize,
                                            titleColor = titleColor,
                                            message = message,
                                            messageSize = messageSize,
                                            messageColor = messageColor,
                                            media = media,
                                            tts = params["tts"],
                                            ttsLanguage = params["ttsLanguage"],
                                            // styling also applies to uploaded snapshots
                                            urgency = params["urgency"],
                                            borderColor = params["borderColor"],
                                            borderWidth = params["borderWidth"]?.toIntOrNull(),
                                            cornerRadius = params["cornerRadius"]?.toFloatOrNull(),
                                            showProgress = params["showProgress"]?.toBoolean() ?: false
                                        )
                                    }
                                    else -> throw Exception("invalid content-type")
                                }

                                Log.d(LOG_TAG, "received popup: $popup")

                                mHandler.post {
                                    createPopup(popup)
                                }

                                OK("$popup")


                            } catch (ex: Throwable) {
                                Log.e(LOG_TAG, ex.message ?: "unknown error")
                                InvalidRequest(ex.message)
                            }
                        }
                        else -> InvalidRequest("unknown uri: ${session.uri}")
                    }
                }
                else -> InvalidRequest("invalid method")
            }
        } ?: InvalidRequest()
    }

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        const val NSD_SERVICE_TYPE = "_pipup._tcp."
        const val PREFS_NAME = "pipup"
        const val PREF_DEVICE_ID = "device_id"
        const val PREF_UPDATE_ANNOUNCED = "update_announced"
        const val UPDATE_POPUP_ID = "pipup-update"
        // First check shortly after boot (network is usually up by then), then twice a day.
        // GitHub's anonymous API allows 60 requests/hour per IP; this is nowhere near it.
        const val UPDATE_CHECK_FIRST_DELAY_MS = 120_000L
        const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val WATCHDOG_INTERVAL_MS = 30_000L
        const val ONGOING_NOTIFICATION_ID = 123
        const val WEBSERVER_START_ATTEMPTS = 3
        const val WEBSERVER_RETRY_DELAY_MS = 500L
        const val TTS_IDLE_TIMEOUT_MS = 60_000L
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"

        fun OK(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message)
        fun InvalidRequest(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")
    }
}
