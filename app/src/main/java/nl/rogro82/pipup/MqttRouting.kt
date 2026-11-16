package nl.rogro82.pipup

import com.fasterxml.jackson.databind.JsonNode

/// The pure part of the MQTT transport (1.1.0): what the settings screen validates, how a
/// topic maps to an endpoint, how a payload becomes request parameters and how an answer
/// is wrapped. No Android, no network - this is what the unit tests cover.

/// Everything the client needs; built from prefs by the service, from the fields by the
/// settings screen. `name` is this TV's topic segment (default: the device id).
data class MqttConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_MQTT_PORT,
    val tls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val prefix: String = DEFAULT_MQTT_PREFIX,
    val name: String = ""
) {
    /// The broker URI Paho wants, never with credentials in it.
    val serverUri: String get() = "${if (tls) "ssl" else "tcp"}://$host:$port"

    /// `<prefix>/<name>`: the root of every topic of this TV.
    val root: String get() = "$prefix/$name"

    /// Null when the config can be used, else the first thing wrong with it, in words
    /// the settings screen can show. A disabled config is always fine: nothing uses it.
    fun validate(): String? {
        if (!enabled) return null
        if (host.isBlank()) return "broker host is empty"
        if (host.any { it.isWhitespace() } || host.contains("://") || host.contains('/')) {
            return "broker host must be a host name or IP address, without scheme or path"
        }
        if (port !in 1..65535) return "port must be between 1 and 65535"
        if (!isTopicSegment(prefix)) return "topic prefix must be letters, digits, _ or -"
        if (!isTopicSegment(name)) return "topic name must be letters, digits, _ or -"
        if (name == BROADCAST_NAME) return "topic name '$BROADCAST_NAME' is reserved for every TV"
        return null
    }

    /// The config without its secret, for logs and /state.
    override fun toString(): String =
        "MqttConfig(enabled=$enabled, uri=$serverUri, user=${username.ifEmpty { "-" }}, root=$root)"

    companion object {
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_MQTT_PREFIX = "pipup"
        /// `<prefix>/all/cmd/#` reaches every TV on the broker.
        const val BROADCAST_NAME = "all"

        private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")
        fun isTopicSegment(s: String): Boolean = SEGMENT.matches(s)
    }
}

/// The endpoint a command topic addresses (`notify`, `cancel`, `power`, `media/seek`, ...), or
/// null when the topic is not one of ours: wrong prefix or name, no `cmd` level, nothing after
/// it, or a path with a wildcard or an empty level in it. The broker only delivers what we
/// subscribed to, but a retained message on a sloppy subscription is cheap to refuse here.
internal fun parseCommandTopic(prefix: String, name: String, topic: String): String? {
    val levels = topic.split('/')
    if (levels.size < 4) return null
    if (levels[0] != prefix) return null
    if (levels[1] != name && levels[1] != MqttConfig.BROADCAST_NAME) return null
    if (levels[2] != "cmd") return null
    val endpoint = levels.drop(3)
    if (endpoint.any { it.isEmpty() || it == "+" || it == "#" }) return null
    return endpoint.joinToString("/")
}

/// Query parameters from a payload, for every endpoint but `notify`. A JSON object's top-level
/// fields become parameters (`{"state":"on"}` is `?state=on`; numbers and booleans as their text;
/// an array becomes repeated values; null is skipped). An empty payload means no parameters.
/// Anything else (a bare string, an array, broken JSON) is refused with the reason.
internal fun payloadToParams(payload: ByteArray): Map<String, List<String>> {
    if (payload.isEmpty() || payload.all { it.toInt().toChar().isWhitespace() }) return emptyMap()
    val node: JsonNode = try {
        Json.readTree(payload)
    } catch (ex: Throwable) {
        throw IllegalArgumentException("payload is not JSON: ${ex.message?.lineSequence()?.firstOrNull()}")
    }
    if (!node.isObject) throw IllegalArgumentException("payload must be a JSON object")
    val params = linkedMapOf<String, List<String>>()
    node.fields().forEach { (key, value) ->
        when {
            value.isNull -> {}
            value.isArray -> params[key] = value.mapNotNull { scalarText(it) }
            else -> scalarText(value)?.let { params[key] = listOf(it) }
        }
    }
    return params
}

private fun scalarText(node: JsonNode): String? = when {
    node.isNull -> null
    node.isValueNode -> node.asText()
    else -> node.toString()   // a nested object: handed over as its JSON text
}

/// What goes on `result/<endpoint>` for a text answer: the HTTP status and the body, the body
/// parsed when it is JSON so a subscriber gets an object, not a string with an object in it.
internal fun resultEnvelope(status: Int, contentType: String?, body: String): Map<String, Any?> {
    val parsed: Any? = if (contentType != null && contentType.startsWith("application/json")) {
        try { Json.readTree(body) } catch (_: Throwable) { body }
    } else body
    return linkedMapOf("status" to status, "body" to parsed)
}

/// Whether an answer is bytes a subscriber wants as they are (a picture), not text to wrap.
internal fun isBinaryContent(contentType: String?): Boolean =
    contentType != null && (contentType.startsWith("image/") || contentType.startsWith("application/octet-stream"))
