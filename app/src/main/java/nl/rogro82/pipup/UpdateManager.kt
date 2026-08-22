package nl.rogro82.pipup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
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
    private const val INSTALL_ACTION = "nl.rogro82.pipup.INSTALL_RESULT"
    private const val NET_TIMEOUT_MS = 15000
    /// An attempt that has not concluded within this window is considered abandoned
    /// (typically: the on-TV confirmation was never accepted) and may be replaced.
    private const val INSTALL_TIMEOUT_MS = 15 * 60 * 1000L

    /// Result of the most recent check; read by /state on the web-server thread.
    @Volatile var latestVersion: String? = null
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
    val isInstalling: Boolean
        get() = installStartedAt.get().let {
            it != 0L && android.os.SystemClock.elapsedRealtime() - it < INSTALL_TIMEOUT_MS
        }

    /// True when GitHub advertises a release newer than the running build.
    val updateAvailable: Boolean
        get() = latestVersion?.let { isNewer(it, BuildConfig.VERSION_NAME) } ?: false

    /// Query the GitHub releases API. Blocking — call from a background thread.
    fun check(): Boolean {
        return try {
            val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
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
    /// call from a background thread. Returns an error message, or null on success
    /// (success here means "handed to the installer": the actual result arrives
    /// asynchronously in the install receiver).
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
                connectTimeout = NET_TIMEOUT_MS
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) {
                return "download failed: HTTP ${conn.responseCode}".also {
                    Log.w(LOG_TAG, it)
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
            null
        } catch (ex: Throwable) {
            installStartedAt.set(0)
            "install failed: ${ex.message ?: ex.javaClass.simpleName}".also {
                Log.e(LOG_TAG, it, ex)
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
                            // Pre-Android-12 (and whenever the system insists): show the
                            // confirmation dialog on the TV. NEW_TASK because we start it
                            // from a receiver, not an activity.
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(confirm) }
                                .onFailure { Log.e(LOG_TAG, "Cannot show install prompt", it) }
                            // Keep `installing` set: the flow is still in progress.
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            Log.i(LOG_TAG, "Update installed")
                            installStartedAt.set(0)
                        }
                        else -> {
                            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            lastError = "install status $status: $msg"
                            Log.w(LOG_TAG, lastError!!)
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
}
