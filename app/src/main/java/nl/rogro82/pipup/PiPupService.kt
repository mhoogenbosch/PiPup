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
    private var mCurrentProps: PopupProps? = null
    private var mShownAt: Long = 0L
    private var mPopupsShown: Long = 0L
    private val mStartedAt: Long = SystemClock.elapsedRealtime()
    private lateinit var mWebServer: WebServer

    private var mNsdManager: NsdManager? = null
    private var mNsdListener: NsdManager.RegistrationListener? = null

    private val mWatchdogHandler = Handler(Looper.getMainLooper())
    private var mWatchdogCleanups: Long = 0L

    private var mTts: TextToSpeech? = null
    private var mTtsReady = false
    private var mTtsPending: Pair<String, String?>? = null
    private val mTtsDefaultLocale: Locale = Locale.getDefault()

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel("service_channel", "Service channel", "Service channel")

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java), pendingIntentFlags
        )

        val mBuilder = NotificationCompat.Builder(this, "service_channel")
            .setContentTitle("PiPup")
            .setContentText("Service running")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setOngoing(true)

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, mBuilder.build(), serviceType)

        mWebServer = WebServer(SERVER_PORT, this).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }

        Log.d(LOG_TAG, "WebServer started")

        registerNsd()
        initTts()
        startWatchdog()
    }

    override fun onDestroy() {
        super.onDestroy()

        mWatchdogHandler.removeCallbacksAndMessages(null)

        unregisterNsd()

        mTts?.let {
            it.stop()
            it.shutdown()
        }
        mTts = null
        mTtsReady = false

        mWebServer.stop()
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

    private fun initTts() {
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
        if (mTtsReady) doSpeak(text, language)
        else mTtsPending = text to language // engine still starting: keep the latest utterance
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
                        mWatchdogCleanups++
                        removePopup(true)
                    }
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Watchdog error: ${ex.message}")
                }
                mWatchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }, WATCHDOG_INTERVAL_MS)
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
                    sendButtonCallback(popup, btn)
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
            mPopupsShown++

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
            "popupsShown" to mPopupsShown,
            "watchdogCleanups" to mWatchdogCleanups,
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
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            APPLICATION_JSON,
            Json.writeValueAsString(state)
        )
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

                                        // try to handle it as json

                                        val contentLength = session.headers["content-length"]?.toInt() ?: 0
                                        val content = ByteArray(contentLength)

                                        var read = 0
                                        while (read < contentLength) {
                                            val res = session.inputStream.read(content, read, contentLength - read)
                                            if (res < 0) break
                                            read += res
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
                                            ttsLanguage = params["ttsLanguage"]
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
        const val WATCHDOG_INTERVAL_MS = 30_000L
        const val ONGOING_NOTIFICATION_ID = 123
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"

        fun OK(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message)
        fun InvalidRequest(message: String? = null): NanoHTTPD.Response = newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message")
    }
}
