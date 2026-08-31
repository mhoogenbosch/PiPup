package nl.rogro82.pipup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/// Self-update from the fork's GitHub releases.
///
/// Sideloaded TVs have no store to update them, and adb is not something every
/// user has set up — so the app checks for a newer release itself and can install
/// it on request (from its own popup or from the Home Assistant integration).
///
/// Android only allows an APK to replace an installed one when both are signed
/// with the same key, so a tampered download cannot be installed over this app.
/// On Android 12+ a self-update runs silently (USER_ACTION_NOT_REQUIRED); older
/// versions always show the system's install confirmation on the TV.
object UpdateManager {
    private const val LOG_TAG = "PiPupUpdate"
    private const val RELEASES_URL =
        "https://api.github.com/repos/mhoogenbosch/PiPup/releases/latest"
    private const val HA_PIPUP_RELEASES_URL =
        "https://api.github.com/repos/mhoogenbosch/ha-pipup/releases/latest"

    /// Oldest ha-pipup release that can drive EVERY field this app accepts (0.21.0 needs
    /// the `padding` service field, added in ha-pipup 1.17.1). Bump ONLY when a new app
    /// release adds request fields the integration must know about — part of the release
    /// checklist, not something that tracks GitHub.
    const val MIN_HA_PIPUP = "1.17.1"
    private const val INSTALL_ACTION = "nl.rogro82.pipup.INSTALL_RESULT"
    private const val NET_TIMEOUT_MS = 15000
    /// An attempt that has not concluded within this window is considered abandoned
    /// (typically: the on-TV confirmation was never accepted) and may be replaced.
    private const val INSTALL_TIMEOUT_MS = 15 * 60 * 1000L

    /// Result of the most recent check; read by /state on the web-server thread.
    @Volatile var latestVersion: String? = null
        private set
    /// Latest ha-pipup release tag — BY DEFINITION the recommended integration version,
    /// so there is nothing to keep in sync by hand. null until the first successful check.
    @Volatile var haPipupLatest: String? = null
        private set
    @Volatile var downloadUrl: String? = null
        private set
    @Volatile var lastCheckedAt: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /// 0 = idle; otherwise the elapsedRealtime the current attempt started at. A
    /// timestamp instead of a boolean, because an attempt can die without its result
    /// receiver ever firing: pre-Android-12 the system confirmation has to be accepted
    /// ON the TV, and a dialog nobody accepts (screen off, remote out of reach) used to
    /// leave the flag stuck true forever - every later install answered "an update is
    /// already running" until the service restarted. Seen in the field.
    private val installStartedAt = AtomicLong(0)

    /// The system's install-confirmation intent (Android < 12, or whenever the platform
    /// insists on one). Kept so it can be re-shown: launched blind from the background
    /// it flashes on screen and disappears (seen on a TCL, Android 11), leaving the
    /// session waiting forever on a dialog nobody can reach. The service turns this
    /// into a popup with a button - a press gives the app a visible window, and an
    /// activity started from one keeps focus.
    @Volatile var pendingConfirm: Intent? = null
        private set

    /// Set by the service; called (on a background thread) when the installer asks for
    /// on-screen confirmation, so the service can announce it as a popup.
    @Volatile var onPendingUserAction: (() -> Unit)? = null

    fun clearPendingConfirm() {
        pendingConfirm = null
    }

    /// True while an install is waiting for the on-screen confirmation the platform
    /// demands on Android < 12 — distinct from actively installing, so a controller
    /// can say "confirm on the TV" instead of a bare "installing" or "already running".
    val pendingUserAction: Boolean
        get() = pendingConfirm != null

    /// Whether a self-update can run without an on-screen confirmation (Android 12+).
    val silentInstall: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /// Give up a stalled attempt (its confirmation popup expired unconfirmed), so
    /// /state stops reporting an install in progress and the update can be retried.
    fun abandonPending() {
        pendingConfirm = null
        installStartedAt.set(0)
    }

    val isInstalling: Boolean
        get() = installStartedAt.get().let {
            it != 0L && android.os.SystemClock.elapsedRealtime() - it < INSTALL_TIMEOUT_MS
        }

    /// True when GitHub advertises a release newer than the running build.
    val updateAvailable: Boolean
        get() = latestVersion?.let { isNewer(it, BuildConfig.VERSION_NAME) } ?: false

    /// Refresh the recommended integration version (= the latest ha-pipup release).
    /// Non-fatal by design: any miss (offline, rate limit) keeps the previous value.
    private fun refreshHaPipupLatest() {
        try {
            val conn = (URL(HA_PIPUP_RELEASES_URL).openConnection() as HttpURLConnection).apply {
                applyUpdaterTls(this)
                connectTimeout = NET_TIMEOUT_MS
                readTimeout = NET_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) return
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Json.readTree(body).path("tag_name").asText("").removePrefix("v")
                .takeIf { it.isNotBlank() }?.let { haPipupLatest = it }
        } catch (_: Throwable) { /* keep the cached value */ }
    }

    /// Query the GitHub releases API. Blocking — call from a background thread.
    fun check(): Boolean {
        refreshHaPipupLatest()
        return try {
            val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                applyUpdaterTls(this)
                connectTimeout = NET_TIMEOUT_MS
                readTimeout = NET_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) {
                // 403 here is almost always the anonymous rate limit (60/h per IP).
                lastError = "GitHub returned ${conn.responseCode}"
                Log.w(LOG_TAG, "Update check failed: ${lastError}")
                return false
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val node = Json.readTree(body)
            latestVersion = node.path("tag_name").asText("").removePrefix("v").ifBlank { null }
            downloadUrl = node.path("assets").firstOrNull {
                it.path("name").asText("").endsWith(".apk")
            }?.path("browser_download_url")?.asText()
            lastCheckedAt = System.currentTimeMillis()
            lastError = null
            Log.d(LOG_TAG, "Latest release: $latestVersion (running ${BuildConfig.VERSION_NAME})")
            true
        } catch (ex: Throwable) {
            lastError = ex.message ?: ex.javaClass.simpleName
            Log.w(LOG_TAG, "Update check failed: ${lastError}")
            false
        }
    }

    /// Download the release APK and hand it to the package installer. Blocking —
    /// call from a background thread.
    ///
    /// NB despite the name this installs the LAST CHECKED release, not whatever is
    /// newest on GitHub right now: it uses the `downloadUrl` that [check] cached.
    /// Callers that act on a user request must call [check] first, or they can hand
    /// the installer a stale APK.
    ///
    /// Returns an error message, or null on success
    /// (success here means "handed to the installer": the actual result arrives
    /// asynchronously in the install receiver).
    /// TLS for the updater's own connections: the system trust store PLUS ISRG Root X1.
    ///
    /// GitHub's *release assets* live on `*.githubusercontent.com`, whose chain anchors on
    /// ISRG Root X1 (Let's Encrypt) — a root Android only ships from 7.1.1. On Android 6
    /// the update check succeeds (api.github.com anchors on USERTrust, which 6 has) but the
    /// download dies with "Trust anchor for certification path not found" (#41, Xiaomi
    /// projector). Bundling that one public root and accepting system-or-ISRG restores the
    /// self-update there with full chain validation — no trust-all anywhere. Integrity is
    /// double-locked anyway: the platform refuses an update APK whose signing certificate
    /// differs or whose versionCode is lower. Falls back to the default factory when
    /// anything in the setup throws, so modern devices can never be worse off.
    private val legacySafeSocketFactory: SSLSocketFactory? by lazy {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val isrg = cf.generateCertificate(ISRG_ROOT_X1_PEM.byteInputStream()) as X509Certificate

            fun managerFor(keyStore: KeyStore?): X509TrustManager {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore)
                return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            }

            val system = managerFor(null) // null = the platform's default trust store
            val extra = managerFor(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg-root-x1", isrg)
            })

            val composite = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (ex: java.security.cert.CertificateException) {
                        extra.checkServerTrusted(chain, authType) // full validation against ISRG Root X1
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    system.acceptedIssuers + extra.acceptedIssuers
            }

            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(composite), null)
            }.socketFactory
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "legacy TLS factory unavailable, using platform default: ${ex.message}")
            null
        }
    }

    /// Apply the updater trust store to a connection (no-op for plain http and on failure).
    private fun applyUpdaterTls(conn: HttpURLConnection) {
        val factory = legacySafeSocketFactory ?: return
        (conn as? HttpsURLConnection)?.sslSocketFactory = factory
    }

    fun installLatest(context: Context): String? {
        val url = downloadUrl ?: return "no download URL (run a check first)"
        val now = android.os.SystemClock.elapsedRealtime()
        val running = installStartedAt.get()
        if (running != 0L && now - running < INSTALL_TIMEOUT_MS) {
            return "an update is already running"
        }
        // idle, or a stale attempt past its deadline: claim (or steal) the slot
        if (!installStartedAt.compareAndSet(running, now)) {
            return "an update is already running"
        }

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                applyUpdaterTls(this)
                connectTimeout = NET_TIMEOUT_MS
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) {
                return "download failed: HTTP ${conn.responseCode}".also {
                    Log.w(LOG_TAG, it)
                    lastError = it   // 0.19.1: synchronous failures were invisible in /state
                    installStartedAt.set(0)
                }
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Self-update without a confirmation dialog. Only honoured on
                // Android 12+; older devices show the system installer UI, which
                // someone has to accept with the remote.
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("pipup.apk", 0, -1).use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                    session.fsync(out)
                }
                registerResultReceiver(context)
                session.commit(pendingIntent(context, sessionId).intentSender)
            }
            Log.i(LOG_TAG, "Update session $sessionId committed for v$latestVersion")
            lastError = null
            null
        } catch (ex: Throwable) {
            installStartedAt.set(0)
            "install failed: ${ex.message ?: ex.javaClass.simpleName}".also {
                Log.e(LOG_TAG, it, ex)
                lastError = it   // 0.19.1: a download/commit exception now shows in /state
            }
        }
    }

    private fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // MUTABLE: the installer fills in status extras on this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context, sessionId, Intent(INSTALL_ACTION).setPackage(context.packageName), flags
        )
    }

    private var receiverRegistered = false

    @Synchronized
    private fun registerResultReceiver(context: Context) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                    )) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            // Pre-Android-12 (and whenever the system insists): the user
                            // has to confirm on the TV. Launching the dialog blind from
                            // this receiver made it flash and vanish (TCL, Android 11) -
                            // so keep the intent and let the service offer it as a popup
                            // with a button; we still try the direct launch as a bonus.
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            pendingConfirm = confirm
                            runCatching { ctx.startActivity(confirm) }
                                .onFailure { Log.e(LOG_TAG, "Cannot show install prompt", it) }
                            runCatching { onPendingUserAction?.invoke() }
                            // Keep `installing` set: the flow is still in progress.
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            Log.i(LOG_TAG, "Update installed")
                            pendingConfirm = null
                            installStartedAt.set(0)
                        }
                        else -> {
                            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            lastError = "install status $status: $msg"
                            Log.w(LOG_TAG, lastError!!)
                            pendingConfirm = null
                            installStartedAt.set(0)
                        }
                    }
                }
            },
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    /// Semantic-version comparison ("0.10.0" is newer than "0.9.0"); non-numeric
    /// suffixes are ignored, an unparsable version is never considered newer.
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /// ISRG Root X1 (Let's Encrypt), self-signed, valid until 2035-06-04. Public root
    /// certificate, verified against the published SHA-256 fingerprint
    /// 96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6.
    private const val ISRG_ROOT_X1_PEM = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
"""
}
