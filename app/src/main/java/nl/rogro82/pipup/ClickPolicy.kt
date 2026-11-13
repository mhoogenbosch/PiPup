package nl.rogro82.pipup

import android.content.Context
import android.os.Build

/// The apps PiPup may press buttons in (0.32.0, #83). Empty = every click is refused.
///
/// The list is set over adb only (ControlConfigReceiver), never over HTTP: the web API has
/// no login, so a list that any device on the LAN could widen would protect nothing. Setting
/// it takes the same access as switching the control service on in the first place.
object ClickPolicy {

    private const val PREFS_NAME = "pipup_control"
    private const val KEY_PACKAGES = "clickPackages"

    /// Android package names: at least two dot-separated parts, each starting with a letter.
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /// "a.b, c.d" -> [a.b, c.d]. Commas, semicolons and whitespace separate; names that are
    /// not package names are dropped, duplicates kept once, order kept.
    internal fun parse(value: String?): List<String> =
        value.orEmpty()
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME) }
            .distinct()

    /// The parts of [value] that [parse] drops, for the log.
    internal fun rejected(value: String?): List<String> =
        value.orEmpty()
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(PACKAGE_NAME) }

    fun packages(context: Context): List<String> =
        parse(prefs(context).getString(KEY_PACKAGES, null))

    fun allowed(context: Context, pkg: String): Boolean = pkg in packages(context)

    fun set(context: Context, packages: List<String>) {
        prefs(context).edit().putString(KEY_PACKAGES, packages.joinToString(",")).commit()
    }

    /// Device-protected storage, like the service's own prefs: readable before the first unlock.
    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.applicationContext.createDeviceProtectedStorageContext()
        else context.applicationContext)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
