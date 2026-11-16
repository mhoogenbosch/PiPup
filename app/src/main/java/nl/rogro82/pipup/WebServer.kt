package nl.rogro82.pipup

import fi.iki.elonen.NanoHTTPD

/// [hostname] null = every interface (the LAN); "127.0.0.1" = loopback only, which is what
/// the *HTTP server on the network* switch turns off (1.1.0): the app's own status and
/// settings screens keep reading /state over loopback either way.
class WebServer(hostname: String?, port: Int, private val handler: Handler): NanoHTTPD(hostname, port) {
    interface Handler {
        fun handleHttpRequest(session: IHTTPSession?): Response
    }

    override fun serve(session: IHTTPSession?): Response {
        return handler.handleHttpRequest(session)
    }
}
