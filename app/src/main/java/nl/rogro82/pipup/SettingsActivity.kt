package nl.rogro82.pipup

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress
import java.net.HttpURLConnection
import java.net.URL

/// The settings screen (1.1.0): the HTTP switch, and the MQTT broker with its credentials.
/// On the TV only, on purpose: `/settings` has no login, so a password set over the LAN could
/// also be read, or the TV pointed at another broker, by anything on the LAN. Saving writes
/// the prefs and pokes the service (ACTION_RELOAD_SETTINGS); the status line above the form
/// then shows what the service made of it, straight from `/state` over loopback.
class SettingsActivity : Activity() {

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            mHandler.postDelayed(this, MainActivity.REFRESH_INTERVAL_MS)
        }
    }

    private lateinit var switchHttp: Switch
    private lateinit var switchMqtt: Switch
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var checkTls: CheckBox
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private lateinit var editPrefix: EditText
    private lateinit var editName: EditText
    private lateinit var textTopic: TextView
    private lateinit var textMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchHttp = findViewById(R.id.switchHttp)
        switchMqtt = findViewById(R.id.switchMqtt)
        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        checkTls = findViewById(R.id.checkTls)
        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)
        editPrefix = findViewById(R.id.editPrefix)
        editName = findViewById(R.id.editName)
        textTopic = findViewById(R.id.textTopic)
        textMessage = findViewById(R.id.textMessage)

        val p = PiPupService.prefs(this)
        val cfg = PiPupService.mqttConfig(p, "")
        switchHttp.isChecked = p.getBoolean(PiPupService.PREF_HTTP_LAN, true)
        switchMqtt.isChecked = cfg.enabled
        editHost.setText(cfg.host)
        editPort.setText(cfg.port.toString())
        checkTls.isChecked = cfg.tls
        editUsername.setText(cfg.username)
        editPassword.setText(cfg.password)
        editPrefix.setText(cfg.prefix)
        // the stored name only; empty means "the device id", which the status line shows
        editName.setText(p.getString(PiPupService.PREF_MQTT_NAME, null) ?: "")
        editName.hint = getString(R.string.settings_mqtt_name_hint)

        findViewById<Button>(R.id.buttonSave).setOnClickListener { save() }
        checkTls.setOnCheckedChangeListener { _, tls ->
            // the usual port for the usual case, only while the field still holds the other default
            val port = editPort.text.toString().trim()
            if (tls && port == "1883") editPort.setText("8883")
            if (!tls && port == "8883") editPort.setText("1883")
        }
    }

    override fun onResume() {
        super.onResume()
        Permissions.onActivityResumed()
        mHandler.post(mRefresh)
    }

    override fun onPause() {
        super.onPause()
        Permissions.onActivityPaused()
        mHandler.removeCallbacks(mRefresh)
    }

    private fun fromFields(): MqttConfig = MqttConfig(
        enabled = switchMqtt.isChecked,
        host = editHost.text.toString().trim(),
        port = editPort.text.toString().trim().toIntOrNull() ?: -1,
        tls = checkTls.isChecked,
        username = editUsername.text.toString(),
        password = editPassword.text.toString(),
        prefix = editPrefix.text.toString().trim().ifEmpty { MqttConfig.DEFAULT_MQTT_PREFIX },
        // validated with a stand-in when empty: the real default (device id) is always valid
        name = editName.text.toString().trim().ifEmpty { "device-id" }
    )

    private fun save() {
        val cfg = fromFields()
        val error = cfg.validate()
        if (error != null) {
            textMessage.text = getString(R.string.settings_invalid, error)
            return
        }
        val http = switchHttp.isChecked
        PiPupService.prefs(this).edit()
            .putBoolean(PiPupService.PREF_HTTP_LAN, http)
            .putBoolean(PiPupService.PREF_MQTT_ENABLED, cfg.enabled)
            .putString(PiPupService.PREF_MQTT_HOST, cfg.host)
            .putInt(PiPupService.PREF_MQTT_PORT, if (cfg.port in 1..65535) cfg.port else MqttConfig.DEFAULT_MQTT_PORT)
            .putBoolean(PiPupService.PREF_MQTT_TLS, cfg.tls)
            .putString(PiPupService.PREF_MQTT_USERNAME, cfg.username)
            .putString(PiPupService.PREF_MQTT_PASSWORD, cfg.password)
            .putString(PiPupService.PREF_MQTT_PREFIX, cfg.prefix)
            .putString(PiPupService.PREF_MQTT_NAME, editName.text.toString().trim())
            .apply()

        val intent = Intent(this, PiPupService::class.java).setAction(PiPupService.ACTION_RELOAD_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        textMessage.text = getString(if (!http && !cfg.enabled) R.string.settings_nothing_on else R.string.settings_saved)
        refreshStatus()
    }

    /// `/state` over loopback, like the status screen: what the service really does, not
    /// what this screen thinks it saved.
    private fun refreshStatus() {
        Thread {
            val lines = try {
                val conn = URL("http://127.0.0.1:${PiPupService.SERVER_PORT}/state")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val s = Json.readTree(conn.inputStream.bufferedReader().use { it.readText() })
                val http = s.path("http")
                val mqtt = s.path("mqtt")
                val httpLine = if (http.path("lan").asBoolean(true)) {
                    getString(R.string.settings_status_http_on, getIpAddress() ?: "?", PiPupService.SERVER_PORT)
                } else getString(R.string.settings_status_http_off)
                val broker = mqtt.path("broker").takeIf { it.isTextual }?.asText() ?: "?"
                val mqttLine = when {
                    !mqtt.path("enabled").asBoolean(false) -> getString(R.string.settings_status_mqtt_off)
                    mqtt.path("connected").asBoolean(false) ->
                        getString(R.string.settings_status_mqtt_connected, broker, mqtt.path("topic").asText("?"))
                    mqtt.path("lastError").isTextual ->
                        getString(R.string.settings_status_mqtt_error, mqtt.path("lastError").asText())
                    else -> getString(R.string.settings_status_mqtt_connecting, broker)
                }
                // the topic the service uses, or the one these fields would give (with the
                // device id from /state standing in for an empty name)
                val topic = mqtt.path("topic").takeIf { it.isTextual }?.asText()
                    ?: "${editPrefix.text.toString().trim().ifEmpty { MqttConfig.DEFAULT_MQTT_PREFIX }}/" +
                        editName.text.toString().trim().ifEmpty { s.path("id").asText("?") }
                Triple(httpLine, mqttLine, topic)
            } catch (_: Throwable) {
                Triple(getString(R.string.status_service_unreachable), "", null)
            }
            runOnUiThread {
                findViewById<TextView>(R.id.textStatus)?.text =
                    listOf(lines.first, lines.second).filter { it.isNotEmpty() }.joinToString("\n")
                textTopic.text = lines.third?.let { getString(R.string.settings_mqtt_topic, it) } ?: ""
            }
        }.start()
    }
}
