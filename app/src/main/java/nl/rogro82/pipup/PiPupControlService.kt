package nl.rogro82.pipup

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
///     adb shell settings put secure enabled_accessibility_services \
///         <current value>:nl.rogro82.pipup/nl.rogro82.pipup.PiPupControlService
class PiPupControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(LOG_TAG, "Control service connected")
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
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == pkg) return root
                }
            }
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "reading the windows failed: ${ex.message}")
        }
        return null
    }

    companion object {
        const val LOG_TAG = "PiPupControl"

        /// Longest `wait` a caller may ask for; the HTTP request stays open that long.
        const val MAX_WAIT_SECONDS = 15
        private const val POLL_MS = 250L
        /// A TV screen has a few hundred nodes; this only bounds a pathological tree.
        private const val MAX_NODES = 5000
        private const val MAX_PARENT_HOPS = 10

        @Volatile
        private var instance: PiPupControlService? = null

        fun connected(): Boolean = instance != null

        /// Press the view [id] of [pkg], waiting up to [waitSeconds] for it to appear.
        /// Runs on the calling (HTTP worker) thread; accessibility calls are binder calls
        /// and may be made from any thread.
        fun click(context: Context, pkg: String?, id: String?, waitSeconds: Int): TvControl.Result {
            if (pkg.isNullOrBlank() || id.isNullOrBlank()) {
                return TvControl.Result(false, 400, "give package=<name> and id=<resource id>")
            }
            if (instance == null) {
                return TvControl.Result(false, 501, "clicking needs the PiPup control service " +
                    "(install.sh --click $pkg, or the click option in ha-pipup)")
            }
            if (!ClickPolicy.allowed(context, pkg)) {
                return TvControl.Result(false, 403, "$pkg is not on the list of apps PiPup may press " +
                    "buttons in (install.sh --click $pkg, or the click option in ha-pipup)")
            }
            return try {
                clickLoop(pkg, id, waitSeconds)
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "click $pkg $id failed: ${ex.message}")
                TvControl.Result(false, 500, "click failed: ${ex.message}")
            }
        }

        private fun clickLoop(pkg: String, id: String, waitSeconds: Int): TvControl.Result {
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
                        val ok = try {
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (ex: Throwable) {
                            Log.w(LOG_TAG, "click on $id failed: ${ex.message}")
                            false
                        }
                        Log.i(LOG_TAG, "click $pkg $id: ${if (ok) "done" else "refused"}")
                        return if (ok) TvControl.Result(true, 200, "clicked $id")
                               else TvControl.Result(false, 500, "$id was found, but the app refused the click")
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
            }
            val waited = if (waitSeconds > 0) " within ${waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)} s" else ""
            return if (inFront) TvControl.Result(false, 404, "$id not found in $pkg$waited")
                   else TvControl.Result(false, 404, "$pkg is not on screen$waited")
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
