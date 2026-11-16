package nl.rogro82.pipup

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/// Which app is in front (0.33.0, #88), also when nothing plays: the launcher, a menu, an app
/// without a media session.
///
/// Needs usage access, which a TV has no settings screen for; it is granted over adb:
///
///     adb shell appops set nl.rogro82.pipup GET_USAGE_STATS allow
///
/// Without it everything here answers null and nothing is read. With it, the app reads the
/// "moved to the front" events Android keeps (UsageStatsManager.queryEvents): package names
/// and times, nothing of what is on the screen, and no accessibility service.
object ForegroundApp {

    const val LOG_TAG = "ForegroundApp"

    /// The app in front and since when (wall clock, ms).
    data class Seen(val pkg: String, val since: Long)

    /// One "moved to the front" event, as far as this object cares.
    internal data class Front(val pkg: String?, val cls: String?, val time: Long)

    /// The first look goes this far back: an app can stay in front for days.
    private const val FIRST_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000
    /// Later looks overlap by this much, so an event stamped just before the last look is not lost.
    private const val OVERLAP_MS = 2_000L
    private const val POLL_SECONDS = 5L

    @Volatile private var seen: Seen? = null
    private var queriedUntil = 0L
    private var poller: ScheduledExecutorService? = null
    /// the package of the last push; only touched on the poll thread
    private var pushed: String? = null

    /// Called (on the poll thread) when another app came to the front.
    @Volatile var onChange: (() -> Unit)? = null

    fun granted(context: Context): Boolean =
        Permissions.opMode(context, AppOpsManager.OPSTR_GET_USAGE_STATS) == "allowed"

    /// The app in front, or null without the grant or before Android reported one.
    @Synchronized
    fun current(context: Context): Seen? {
        if (!granted(context)) {
            seen = null
            queriedUntil = 0L
            return null
        }
        try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (queriedUntil == 0L) now - FIRST_LOOKBACK_MS else queriedUntil - OVERLAP_MS
            val events = manager.queryEvents(from, now)
            val fronts = ArrayList<Front>()
            val event = UsageEvents.Event()
            while (events != null && events.getNextEvent(event)) {
                @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND is ACTIVITY_RESUMED from Android 10 on
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fronts.add(Front(event.packageName, event.className, event.timeStamp))
                }
            }
            queriedUntil = now
            seen = latestFront(seen, fronts, context.packageName)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "reading the usage events failed: ${ex.message}")
        }
        return seen
    }

    /// `foreground` in /state: package, label and since when; null without the grant.
    fun asMap(context: Context): Map<String, Any?>? {
        val front = current(context) ?: return null
        return mapOf(
            "package" to front.pkg,
            "label" to label(context, front.pkg),
            "since" to isoUtc(front.since)
        )
    }

    /// The app's name as its launcher shows it, or null when Android hides the package from
    /// PiPup (package visibility, Android 11+: only launchable apps and the launcher are seen).
    private fun label(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString()
    } catch (_: Throwable) {
        null
    }

    /// Watch for another app coming to the front, so it can be pushed. Only looks while the
    /// screen is on and the grant is there; a grant given later is picked up by itself.
    @Synchronized
    fun start(context: Context) {
        if (poller != null) return
        val app = context.applicationContext
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        poller = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pipup-foreground").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({
                try {
                    if (power.isInteractive) {
                        // against what was last pushed, not against `seen`: a /state
                        // request in between also moves `seen` on
                        val front = current(app)?.pkg
                        if (front != pushed) {
                            pushed = front
                            onChange?.invoke()
                        }
                    }
                } catch (ex: Throwable) {
                    Log.w(LOG_TAG, "poll failed: ${ex.message}")
                }
            }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun stop() {
        poller?.shutdownNow()
        poller = null
    }
}

/// The newest "moved to the front" event wins over [previous]; the same app as before keeps
/// its `since`. PiPup's own WakeActivity is skipped: it is invisible and only turns the screen
/// on, the app under it stays the one in front.
internal fun latestFront(
    previous: ForegroundApp.Seen?,
    events: List<ForegroundApp.Front>,
    ownPackage: String
): ForegroundApp.Seen? {
    var result = previous
    for (event in events.sortedBy { it.time }) {
        val pkg = event.pkg?.takeIf { it.isNotEmpty() } ?: continue
        if (pkg == ownPackage && event.cls?.endsWith("WakeActivity") == true) continue
        val current = result
        if (current != null && event.time < current.since) continue
        if (current == null || current.pkg != pkg) result = ForegroundApp.Seen(pkg, event.time)
    }
    return result
}

/// 2026-11-14T13:20:00Z
internal fun isoUtc(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))
