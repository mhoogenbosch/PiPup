package nl.rogro82.pipup

import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/// The MQTT transport (1.1.0). The TV connects *out* to a broker, so nothing on the
/// sending side has to know its address, and every POST endpoint of the HTTP API is
/// reachable as a topic:
///
///     <prefix>/<name>/cmd/<endpoint>      a request: `notify` takes the popup JSON as its
///                                         payload, every other endpoint a JSON object whose
///                                         fields are the query parameters (`{"state":"on"}`)
///     <prefix>/all/cmd/<endpoint>         the same, to every TV on the broker
///     <prefix>/<name>/result/<endpoint>   the answer: `{"status":200,"body":...}`, or the
///                                         bytes themselves for a picture
///     <prefix>/<name>/state               the /state JSON, retained, on every change
///     <prefix>/<name>/event               the same body, not retained: one message per event
///     <prefix>/<name>/availability        `online` / `offline` (retained; the last will)
///
/// A message is handed to the very same [WebServer.Handler] as an HTTP request, through
/// [LocalSession], so the two transports cannot drift apart. Like [Pusher]: an object, one
/// worker thread for connecting, `@Volatile` status for /state, and never the password in a log.
object Mqtt {
    private const val LOG_TAG = "PiPupMqtt"
    private const val KEEP_ALIVE_S = 60
    private const val CONNECT_TIMEOUT_S = 10
    private const val DISCONNECT_WAIT_MS = 2_000L
    private const val MAX_INFLIGHT = 50
    private const val QOS_COMMAND = 1
    private const val QOS_STATE = 0

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PiPupMqtt").apply { isDaemon = true }
    }

    /// What answers the requests; set by the service, cleared on destroy.
    @Volatile var handler: WebServer.Handler? = null

    /// guarded by [this]
    private var config: MqttConfig? = null
    private var client: MqttAsyncClient? = null
    private var connecting = false

    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var connectedAt: Long = 0L
    private val received = AtomicLong(0)
    @Volatile private var lastReceivedAt: Long = 0L
    @Volatile private var lastTopic: String? = null

    val enabled: Boolean get() = synchronized(this) { config?.enabled == true }

    /// True while state changes should be published here (next to the webhook).
    val wantsEvents: Boolean get() = connected

    /// Use this config from now on. Unchanged = nothing happens; changed = the old client is
    /// torn down (with an `offline`) and, when enabled and valid, a new one connects. Called
    /// on the main thread; the network work runs on [executor].
    fun apply(newConfig: MqttConfig, newHandler: WebServer.Handler) {
        handler = newHandler
        val old: MqttAsyncClient?
        val oldConfig: MqttConfig?
        synchronized(this) {
            if (newConfig == config && client != null) return
            if (newConfig == config && !newConfig.enabled) return
            old = client
            oldConfig = config
            client = null
            connected = false
            connecting = false
            config = newConfig
        }
        executor.execute {
            old?.let { teardown(it, oldConfig) }
            val error = newConfig.validate()
            when {
                !newConfig.enabled -> { lastError = null; Log.i(LOG_TAG, "off") }
                error != null -> { lastError = error; Log.w(LOG_TAG, "not connecting: $error") }
                else -> connect(newConfig)
            }
        }
    }

    /// Watchdog hook: Paho reconnects by itself after a *lost* connection, but not after a
    /// connect that never succeeded (broker down at boot, Wi-Fi not up yet). This retries
    /// that case once per tick, off the main thread.
    fun ensureConnected() {
        val cfg = synchronized(this) {
            val cfg = config ?: return
            if (!cfg.enabled || connected || connecting || client != null) return
            cfg
        }
        executor.execute { if (!connected) connect(cfg) }
    }

    /// Say goodbye and let go of the broker. Blocks briefly (service teardown).
    fun stop() {
        val old: MqttAsyncClient?
        val oldConfig: MqttConfig?
        synchronized(this) {
            old = client
            oldConfig = config
            client = null
            connected = false
            connecting = false
            config = null
        }
        handler = null
        old?.let { teardown(it, oldConfig) }
    }

    private fun connect(cfg: MqttConfig) {
        synchronized(this) {
            if (cfg != config || connecting || client != null) return
            connecting = true
        }
        // The client id is the topic name, so the broker's client list reads like the topics
        // do. Two TVs given the same name then throw each other off the broker - loud, and
        // better than both answering the same topics in silence.
        val c = try {
            MqttAsyncClient(cfg.serverUri, "pipup-${cfg.name}", MemoryPersistence())
        } catch (ex: Throwable) {
            fail("cannot create client: ${ex.message}")
            synchronized(this) { connecting = false }
            return
        }
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = KEEP_ALIVE_S
            connectionTimeout = CONNECT_TIMEOUT_S
            maxInflight = MAX_INFLIGHT
            if (cfg.username.isNotEmpty()) userName = cfg.username
            if (cfg.password.isNotEmpty()) password = cfg.password.toCharArray()
            setWill("${cfg.root}/availability", "offline".toByteArray(), QOS_COMMAND, true)
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connected = true
                connectedAt = SystemClock.elapsedRealtime()
                lastError = null
                Log.i(LOG_TAG, "${if (reconnect) "reconnected" else "connected"} to ${cfg.serverUri} as ${cfg.root}")
                try {
                    c.publish("${cfg.root}/availability", "online".toByteArray(), QOS_COMMAND, true)
                    c.subscribe(
                        arrayOf("${cfg.root}/cmd/#", "${cfg.prefix}/${MqttConfig.BROADCAST_NAME}/cmd/#"),
                        intArrayOf(QOS_COMMAND, QOS_COMMAND)
                    )
                } catch (ex: Throwable) {
                    fail("subscribe failed: ${ex.message}")
                }
                onConnected?.invoke()
            }

            override fun connectionLost(cause: Throwable?) {
                connected = false
                lastError = "connection lost: ${cause?.message ?: cause?.javaClass?.simpleName ?: "?"}"
                Log.w(LOG_TAG, lastError!!)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                // Never let this throw: Paho drops the connection when it does.
                try {
                    dispatch(cfg, c, topic, message.payload)
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "handling $topic failed: ${ex.message}", ex)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        synchronized(this) { client = c }
        try {
            c.connect(options).waitForCompletion(CONNECT_TIMEOUT_S * 1000L + 2_000L)
        } catch (ex: Throwable) {
            // The first connect does not auto-retry (see ensureConnected); drop the client
            // so the watchdog tries again with a fresh one.
            fail("connect to ${cfg.serverUri} failed: ${describe(ex)}")
            synchronized(this) { if (client === c) client = null }
            runCatching { c.close(true) }
        } finally {
            synchronized(this) { connecting = false }
        }
    }

    /// Set by the service: publish the current state right after a (re)connect, so a retained
    /// `state` exists before the first change and a subscriber that just came up sees it.
    @Volatile var onConnected: (() -> Unit)? = null

    private fun teardown(c: MqttAsyncClient, cfg: MqttConfig?) {
        try {
            if (c.isConnected) {
                // the will only fires on an unclean loss; say it ourselves on a clean stop
                if (cfg != null) runCatching {
                    c.publish("${cfg.root}/availability", "offline".toByteArray(), QOS_COMMAND, true)
                        .waitForCompletion(DISCONNECT_WAIT_MS)
                }
                c.disconnect().waitForCompletion(DISCONNECT_WAIT_MS)
                Log.i(LOG_TAG, "disconnected from ${cfg?.serverUri ?: "broker"}")
            }
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "disconnect: ${describe(ex)}")
        } finally {
            runCatching { c.close(true) }
        }
    }

    private fun dispatch(cfg: MqttConfig, c: MqttAsyncClient, topic: String, payload: ByteArray) {
        received.incrementAndGet()
        lastReceivedAt = SystemClock.elapsedRealtime()
        lastTopic = topic
        val endpoint = parseCommandTopic(cfg.prefix, cfg.name, topic)
        if (endpoint == null) {
            Log.w(LOG_TAG, "ignored: $topic is not a command topic of ${cfg.root}")
            return
        }
        val h = handler
        if (h == null) {
            Log.w(LOG_TAG, "ignored: $topic arrived while the service is down")
            return
        }
        if (payload.size > PiPupService.MAX_JSON_BODY_BYTES) {
            publishResult(c, cfg, endpoint, 413, PiPupService.APPLICATION_JSON,
                Json.writeValueAsString(mapOf("error" to "payload too large (max ${PiPupService.MAX_JSON_BODY_BYTES} bytes)")))
            return
        }
        val session = try {
            if (endpoint == "notify") {
                LocalSession(NanoHTTPD.Method.POST, "/notify", emptyMap(), payload, PiPupService.APPLICATION_JSON)
            } else {
                LocalSession(NanoHTTPD.Method.POST, "/$endpoint", payloadToParams(payload), ByteArray(0), null)
            }
        } catch (ex: IllegalArgumentException) {
            publishResult(c, cfg, endpoint, 400, "text/plain", "invalid request: ${ex.message}")
            return
        }
        val response = h.handleHttpRequest(session)
        val status = response.status.requestStatus
        val contentType = response.mimeType
        val bytes = response.data?.use { it.readBytes() } ?: ByteArray(0)
        Log.d(LOG_TAG, "$topic -> $status")
        if (isBinaryContent(contentType) && status in 200..299) {
            publishRaw(c, "${cfg.root}/result/$endpoint", bytes)
        } else {
            publishResult(c, cfg, endpoint, status, contentType, String(bytes, Charsets.UTF_8))
        }
    }

    private fun publishResult(c: MqttAsyncClient, cfg: MqttConfig, endpoint: String, status: Int, contentType: String?, body: String) {
        publishRaw(c, "${cfg.root}/result/$endpoint",
            Json.writeValueAsString(resultEnvelope(status, contentType, body)).toByteArray())
    }

    private fun publishRaw(c: MqttAsyncClient, topic: String, bytes: ByteArray, retained: Boolean = false) {
        try {
            c.publish(topic, bytes, QOS_STATE, retained)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "publish $topic failed: ${describe(ex)}")
        }
    }

    /// One state change (the webhook body), from [PiPupService.emit]: retained on `state`,
    /// and once more, not retained, on `event`.
    fun publishState(event: String, json: String) {
        val (c, cfg) = synchronized(this) { (client ?: return) to (config ?: return) }
        if (!connected) return
        val bytes = json.toByteArray()
        publishRaw(c, "${cfg.root}/state", bytes, retained = true)
        publishRaw(c, "${cfg.root}/event", bytes)
    }

    /// A popup button was pressed (1.1.0): `{"popup","button","label","device","name"}` on
    /// `<root>/button`, QoS 1 and not retained - an answer is meant once, for whoever listens now.
    fun publishButton(json: String) {
        val (c, cfg) = synchronized(this) { (client ?: return) to (config ?: return) }
        if (!connected) return
        try {
            c.publish("${cfg.root}/button", json.toByteArray(), QOS_COMMAND, false)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "publish button failed: ${describe(ex)}")
        }
    }

    /// The `mqtt` block of /state. Never the password.
    fun status(): Map<String, Any?> {
        val cfg = synchronized(this) { config }
        val now = SystemClock.elapsedRealtime()
        return linkedMapOf(
            "enabled" to (cfg?.enabled == true),
            "connected" to connected,
            "broker" to cfg?.takeIf { it.enabled }?.let { "${it.host}:${it.port}" },
            "tls" to (cfg?.tls == true),
            "username" to cfg?.username?.ifEmpty { null },
            "topic" to cfg?.takeIf { it.enabled }?.root,
            "lastError" to lastError,
            "connectedSecondsAgo" to if (connected && connectedAt > 0) (now - connectedAt) / 1000 else null,
            "received" to received.get(),
            "lastReceivedSecondsAgo" to if (lastReceivedAt > 0) (now - lastReceivedAt) / 1000 else null,
            "lastTopic" to lastTopic
        )
    }

    private fun fail(message: String) {
        connected = false
        lastError = message
        Log.w(LOG_TAG, message)
    }

    private fun describe(ex: Throwable): String = when (ex) {
        is MqttException -> "${ex.message} (${ex.reasonCode})" + (ex.cause?.let { ": ${it.message}" } ?: "")
        else -> ex.message ?: ex.javaClass.simpleName
    }
}
