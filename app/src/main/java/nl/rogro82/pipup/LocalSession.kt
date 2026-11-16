package nl.rogro82.pipup

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream

/// A request that did not come in over a socket (1.1.0): an MQTT message dressed as an HTTP
/// session, so it walks through [PiPupService.handleHttpRequest] exactly like a `POST` from
/// the LAN would. One code path for both transports means nothing can drift between them.
///
/// Only what the handler reads is real: method, uri, lower-cased headers, parameters and
/// the body stream. `parseBody` (multipart uploads) is not supported over MQTT.
class LocalSession(
    private val method: NanoHTTPD.Method,
    private val uri: String,
    private val params: Map<String, List<String>>,
    private val body: ByteArray,
    contentType: String?,
    private val origin: String = "mqtt"
) : NanoHTTPD.IHTTPSession {

    private val headers: Map<String, String> = buildMap {
        put("content-length", body.size.toString())
        if (contentType != null) put("content-type", contentType)
        put("x-pipup-origin", origin)
    }
    private val stream = ByteArrayInputStream(body)

    override fun execute() {}
    // CookieHandler is an inner class of a server instance; nothing here reads cookies
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = headers
    override fun getInputStream(): InputStream = stream
    override fun getMethod(): NanoHTTPD.Method = method
    @Deprecated("NanoHTTPD", ReplaceWith("getParameters()"))
    override fun getParms(): Map<String, String> = params.mapValues { it.value.firstOrNull() ?: "" }
    override fun getParameters(): Map<String, List<String>> = params
    override fun getQueryParameterString(): String =
        params.entries.joinToString("&") { (k, vs) -> vs.joinToString("&") { v -> "$k=$v" } }
    override fun getUri(): String = uri
    override fun parseBody(files: MutableMap<String, String>?) {
        throw NanoHTTPD.ResponseException(
            NanoHTTPD.Response.Status.BAD_REQUEST, "multipart bodies are not supported over $origin"
        )
    }
    override fun getRemoteIpAddress(): String = "127.0.0.1"
    override fun getRemoteHostName(): String = origin
}
