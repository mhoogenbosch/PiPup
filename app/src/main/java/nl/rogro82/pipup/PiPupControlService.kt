package nl.rogro82.pipup

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/// "PiPup control" (0.32.0, #83): presses a button in an app on request, e.g. Play on a
/// title page that a deep link opened.
///
/// A separate accessibility service on purpose. PiPupAccessibilityService (screen off,
/// home/back) observes nothing and stays that way: giving *it* window access would hand that
/// access to everyone who enabled it, silently, with an app update. This one is off until
/// someone switches it on over adb (install.sh --click, or ha-pipup's options), and even then:
///
/// * it receives no events (none are declared in control_service.xml); it reads the window
///   only while a /click request runs,
/// * only when the app in front is on ClickPolicy's list, which is set over adb only,
/// * and it answers whether the click happened, never what is on the screen.
///
/// Since 0.32.0-beta.2 a press can also be a **tap**: a short touch on the centre of the view
/// (dispatchGesture, Android 7+), for apps whose buttons take ACTION_CLICK but ignore it, such
/// as Plex (React Native). After a press it watches the pressed view for up to 1.5 s and
/// answers `changed`: whether it left the screen, moved, or its app left the front.
///
///     adb shell settings put secure enabled_accessibility_services \
///         <current value>:nl.rogro82.pipup/nl.rogro82.pipup.PiPupControlService
class PiPupControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 0.32.0-beta.2: also the views Android marks "not important for accessibility", so every
        // id that `uiautomator dump` shows can be found. Set here as well as in the XML: after an
        // app update Android keeps binding an enabled service with its old flags (and capabilities;
        // those cannot be set at runtime, hence `install.sh --click` switching it off and on).
        try {
            serviceInfo?.let {
                it.flags = it.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = it
            }
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "could not include unimportant views: ${ex.message}")
        }
        instance = this
        Log.i(LOG_TAG, "Control service connected (tap: ${canTap()})")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /// The root of a window of [pkg]: the active window first, then the other interactive
    /// windows (a dialog of the same app). Null when [pkg] has no window on screen.
    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) return it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (window in windows) {
                    // app windows only: no keyboard, system or overlay windows are read
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == pkg) return root
                }
            }
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "reading the windows failed: ${ex.message}")
        }
        return null
    }

    /// A short touch on the centre of [node], else of [fallback] (its clickable parent).
    /// Null when Android delivered it, else why not.
    @RequiresApi(Build.VERSION_CODES.N)
    private fun tap(node: AccessibilityNodeInfo, fallback: AccessibilityNodeInfo): String? {
        val point = centreOf(node) ?: centreOf(fallback)
            ?: return "the view has no area on screen to tap"
        val path = Path().apply { moveTo(point.first, point.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.countDown()
            }
        }
        val accepted = try {
            // callbacks on the main thread; this (HTTP worker) thread only waits for them
            dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "tap failed: ${ex.message}")
            false
        }
        if (!accepted) return "Android refused the tap; switch the control service off and on " +
            "again so it may tap (install.sh --click, or the click option in ha-pipup)"
        if (!done.await(TAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "the tap did not finish in time"
        return if (completed.get()) null else "the tap was cancelled"
    }

    companion object {
        const val LOG_TAG = "PiPupControl"

        /// Longest `wait` a caller may ask for; the HTTP request stays open that long.
        /// 30 since 0.32.0-beta.2: a cold start of Plex (logo, profile screen) takes over 10 s.
        const val MAX_WAIT_SECONDS = 30
        private const val POLL_MS = 250L
        /// After a press, how long to watch the pressed view for `changed`.
        private const val CHANGE_WATCH_MS = 1500L
        private const val TAP_DURATION_MS = 50L
        private const val TAP_TIMEOUT_MS = 2000L
        /// A TV screen has a few hundred nodes; this only bounds a pathological tree.
        private const val MAX_NODES = 5000
        private const val MAX_PARENT_HOPS = 10

        @Volatile
        private var instance: PiPupControlService? = null

        /// One click at a time: two requests must not both press, and each may hold its
        /// HTTP thread for up to MAX_WAIT_SECONDS.
        private val busy = AtomicBoolean(false)

        fun connected(): Boolean = instance != null

        /// Whether a tap can be sent: service on, Android 7+, and the gesture capability
        /// granted (a service switched on before 0.32.0-beta.2 may need a restart for it).
        fun canTap(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            return try {
                val capabilities = service.serviceInfo?.capabilities ?: 0
                (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
            } catch (_: Throwable) {
                false
            }
        }

        /// Press the view [id] of [pkg], waiting up to [waitSeconds] for it to appear.
        /// Runs on the calling (HTTP worker) thread; accessibility calls are binder calls
        /// and may be made from any thread.
        fun click(
            context: Context,
            pkg: String?,
            id: String?,
            waitSeconds: Int,
            method: ClickMethod = ClickMethod.CLICK
        ): TvControl.Result {
            if (pkg.isNullOrBlank() || id.isNullOrBlank()) {
                return TvControl.Result(false, 400, "give package=<name> and id=<resource id>")
            }
            if (instance == null) {
                return TvControl.Result(false, 501, "clicking needs the PiPup control service " +
                    "(install.sh --click $pkg, or the click option in ha-pipup)")
            }
            if (method == ClickMethod.TAP && !canTap()) {
                return TvControl.Result(false, 501,
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) "tapping needs Android 7 or newer"
                    else "the control service may not tap yet: switch it off and on again " +
                        "(install.sh --click $pkg, or the click option in ha-pipup)")
            }
            if (!ClickPolicy.allowed(context, pkg)) {
                return TvControl.Result(false, 403, "$pkg is not on the list of apps PiPup may press " +
                    "buttons in (install.sh --click $pkg, or the click option in ha-pipup)")
            }
            if (!busy.compareAndSet(false, true)) {
                return TvControl.Result(false, 409, "another click is still running; try again when it is done")
            }
            return try {
                clickLoop(pkg, id, waitSeconds, method)
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "click $pkg $id failed: ${ex.message}")
                TvControl.Result(false, 500, "click failed: ${ex.message}")
            } finally {
                busy.set(false)
            }
        }

        private fun clickLoop(pkg: String, id: String, waitSeconds: Int, method: ClickMethod): TvControl.Result {
            val deadline = SystemClock.elapsedRealtime() + waitSeconds.coerceIn(0, MAX_WAIT_SECONDS) * 1000L
            var inFront = false
            while (true) {
                val service = instance ?: return TvControl.Result(false, 501, "the control service was switched off")
                val root = service.rootFor(pkg)
                if (root != null) {
                    inFront = true
                    val node = findById(root, pkg, id)
                    if (node != null) {
                        val target = clickTarget(node)
                        val before = Rect().also { node.getBoundsInScreen(it) }
                        val refused: String? = when (method) {
                            ClickMethod.CLICK -> {
                                val ok = try {
                                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                } catch (ex: Throwable) {
                                    Log.w(LOG_TAG, "click on $id failed: ${ex.message}")
                                    false
                                }
                                if (ok) null else "$id was found, but the app refused the click"
                            }
                            ClickMethod.TAP ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) service.tap(node, target)
                                else "tapping needs Android 7 or newer"
                        }
                        if (refused != null) {
                            Log.i(LOG_TAG, "${method.wire} $pkg $id: $refused")
                            return TvControl.Result(false, 500, refused, mapOf("method" to method.wire))
                        }
                        val changed = watchForChange(pkg, node, before)
                        Log.i(LOG_TAG, "${method.wire} $pkg $id: done, changed=$changed")
                        return TvControl.Result(true, 200,
                            "${if (method == ClickMethod.TAP) "tapped" else "clicked"} $id",
                            mapOf("method" to method.wire, "changed" to changed))
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
            val waited = if (waitSeconds > 0) " within ${waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)} s" else ""
            return if (inFront) TvControl.Result(false, 404, "$id not found in $pkg$waited")
                   else TvControl.Result(false, 404, "$pkg is not on screen$waited")
        }

        /// After a press: whether the pressed [node] left the screen, moved away from [before],
        /// or its app left the front within CHANGE_WATCH_MS. A hint, not proof: a press that
        /// only changes a label (play/pause) reads as "not changed". refresh() reads the view
        /// fresh, past the node cache this service never gets events to invalidate.
        private fun watchForChange(pkg: String, node: AccessibilityNodeInfo, before: Rect): Boolean {
            val deadline = SystemClock.elapsedRealtime() + CHANGE_WATCH_MS
            while (true) {
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return false }
                val service = instance ?: return false
                if (service.rootFor(pkg) == null) return true
                val stillThere = try { node.refresh() } catch (_: Throwable) { false }
                if (!stillThere || !node.isVisibleToUser) return true
                val now = Rect().also { node.getBoundsInScreen(it) }
                if (now != before) return true
                if (SystemClock.elapsedRealtime() >= deadline) return false
            }
        }

        private fun centreOf(node: AccessibilityNodeInfo): Pair<Float, Float>? {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return tapPoint(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

        /// Breadth-first search for the view [id]; a visible match wins over a hidden one.
        private fun findById(root: AccessibilityNodeInfo, pkg: String, id: String): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            var hidden: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty() && seen < MAX_NODES) {
                val node = queue.removeFirst()
                seen++
                if (idMatches(node.viewIdResourceName, pkg, id)) {
                    if (node.isVisibleToUser) return node
                    if (hidden == null) hidden = node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return hidden
        }

        /// The node itself when it takes a click, else its nearest ancestor that does
        /// (a label inside a button); the node itself when none does.
        private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var current: AccessibilityNodeInfo? = node
            var hops = 0
            while (current != null && hops <= MAX_PARENT_HOPS) {
                if (takesClick(current)) return current
                current = current.parent
                hops++
            }
            return node
        }

        private fun takesClick(node: AccessibilityNodeInfo): Boolean =
            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }
}

/// Whether a node's resource id [viewId] is the [id] a caller asked for in [pkg]:
/// the full id (`com.app:id/play`), the bare resource name (`play`, matched in any
/// `…:id/play`), or a Jetpack Compose test tag exposed as resource id (`action-button-watch`).
internal fun idMatches(viewId: String?, pkg: String, id: String): Boolean {
    if (viewId.isNullOrEmpty()) return false
    if (viewId == id) return true
    if (id.contains(":id/")) return false
    return viewId == "$pkg:id/$id" || viewId.endsWith(":id/$id")
}

/// How /click and /launch?click= press a view (0.32.0-beta.2, #83).
enum class ClickMethod(val wire: String) {
    /// ACTION_CLICK through accessibility: native views and Jetpack Compose.
    CLICK("click"),
    /// A short touch on the centre of the view (Android 7+), for apps that take ACTION_CLICK
    /// but ignore it, such as Plex (React Native).
    TAP("tap");

    companion object {
        /// Absent or blank = CLICK; an unknown name = null, which the caller answers with 400.
        fun parse(raw: String?): ClickMethod? {
            if (raw.isNullOrBlank()) return CLICK
            val name = raw.trim().lowercase()
            return values().firstOrNull { it.wire == name }
        }
    }
}

/// The centre of a view's on-screen bounds, or null when the view has no area on screen.
internal fun tapPoint(left: Int, top: Int, right: Int, bottom: Int): Pair<Float, Float>? {
    if (right <= left || bottom <= top) return null
    val x = (left + right) / 2f
    val y = (top + bottom) / 2f
    if (x < 0f || y < 0f) return null
    return x to y
}
