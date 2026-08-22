/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package nl.rogro82.pipup

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.fasterxml.jackson.databind.JsonNode
import nl.rogro82.pipup.Utils.getIpAddress
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val mHandler = Handler(Looper.getMainLooper())

    /// last rendered permission state; the panel is only rebuilt when it changes
    private var mPermissionSignature: String? = null
    private val mRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshPermissions()
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // start service in foreground

        val textViewConnection = findViewById<TextView>(R.id.textViewConnection)
        val textViewServerAddress = findViewById<TextView>(R.id.textViewServerAddress)

        when(val ipAddress = getIpAddress()) {
            is String -> {
                textViewConnection.setText(R.string.server_running)
                textViewServerAddress.apply {
                    visibility = View.VISIBLE
                    text = resources.getString(
                        R.string.server_address,
                        ipAddress,
                        PiPupService.SERVER_PORT
                    )
                }
            }
            else -> {
                textViewConnection.setText(R.string.no_network_connection)
                textViewServerAddress.visibility = View.INVISIBLE
            }
        }


        val serviceIntent = Intent(this, PiPupService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        // A visible window is what makes the fix buttons work at all: it is one of the
        // exemptions from the background-activity-launch restriction, and on a TV without
        // the overlay permission it is the only one available.
        Permissions.onActivityResumed()
        mHandler.post(mRefresh)
    }

    override fun onPause() {
        super.onPause()
        Permissions.onActivityPaused()
        mHandler.removeCallbacks(mRefresh)
    }

    /// Permission panel: the one class of failure that looks like success from the
    /// outside. Without the overlay permission every /notify is answered with 200 and
    /// nothing appears on screen, and `adb install -r` silently resets it again.
    ///
    /// So this screen - the only one someone with a remote can reach - shows the actual
    /// state of each grant and, where the device has a screen for it, a button that goes
    /// straight there. Where it has none (Fire OS answers those intents with do-nothing
    /// CTS placeholders) it prints the adb command instead of a button that does nothing.
    ///
    /// Rebuilt on every refresh, so a permission granted elsewhere - by adb, or from
    /// Home Assistant - turns green here within two seconds.
    private fun refreshPermissions() {
        val panel = findViewById<LinearLayout>(R.id.permissionsPanel) ?: return
        val rows = Permissions.FIXABLE_KEYS.mapNotNull { key ->
            val granted = Permissions.granted(this, key) ?: return@mapNotNull null
            // Optional grants are only worth screen space when they are missing;
            // the overlay is always shown, because everything depends on it.
            if (granted && key != Permissions.KEY_OVERLAY) return@mapNotNull null
            Triple(key, granted, Permissions.fixIntent(this, key) != null)
        }

        // Cheap redraw guard: rebuilding every 2s would fight the DPAD for focus.
        val signature = rows.joinToString("|") { "${it.first}${it.second}${it.third}" }
        if (signature == mPermissionSignature) return
        mPermissionSignature = signature

        panel.removeAllViews()
        rows.forEach { (key, granted, fixable) -> panel.addView(permissionRow(key, granted, fixable)) }
    }

    private fun permissionRow(key: String, granted: Boolean, fixable: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 6, 0, 6)
        }

        val label = when (key) {
            Permissions.KEY_OVERLAY -> R.string.permission_overlay
            Permissions.KEY_INSTALL -> R.string.permission_install
            Permissions.KEY_ADMIN -> R.string.permission_admin
            else -> R.string.permission_accessibility
        }
        row.addView(TextView(this).apply {
            text = getString(
                if (granted) R.string.permission_line_ok else R.string.permission_line_missing,
                getString(label)
            )
            textSize = 14f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            // a granted line is reassurance, a missing one is the message: only the
            // second gets a colour that pulls the eye across the room
            setTextColor(resources.getColor(if (granted) R.color.ok else R.color.warning))
            if (!granted) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        if (granted) return row

        row.addView(TextView(this).apply {
            text = getString(
                when (key) {
                    Permissions.KEY_OVERLAY -> R.string.permission_overlay_why
                    Permissions.KEY_INSTALL -> R.string.permission_install_why
                    Permissions.KEY_ADMIN -> R.string.permission_admin_why
                    else -> R.string.permission_accessibility_why
                }
            )
            textSize = 12f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })

        if (fixable) {
            row.addView(
                Button(this).apply {
                    text = getString(R.string.permission_fix)
                    isFocusable = true
                    setOnClickListener { Permissions.launchFix(this@MainActivity, key) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            )
        } else {
            // no screen on this device: the command is the only honest answer
            row.addView(TextView(this).apply {
                text = Permissions.adbCommand(key)
                textSize = 11f
                setTextIsSelectable(false)
            })
        }
        return row
    }

    /// Live status block below the server address. Fetched from the service's own
    /// /state endpoint over loopback: it is the exact same JSON Home Assistant sees,
    /// so what this screen shows is by definition what integrations get — no second
    /// code path that can drift.
    private fun refreshStatus() {
        Thread {
            val text = try {
                val conn = URL("http://127.0.0.1:${PiPupService.SERVER_PORT}/state")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                formatStatus(Json.readTree(body))
            } catch (_: Throwable) {
                getString(R.string.status_service_unreachable)
            }
            runOnUiThread {
                findViewById<TextView>(R.id.textViewStatus)?.text = text
            }
        }.start()
    }

    private fun formatStatus(s: JsonNode): String {
        val sb = StringBuilder()

        sb.append("v").append(s.path("version").asText("?"))
        sb.append("  •  popups: ").append(s.path("popupsShown").asLong(0))
        sb.append("  •  up: ").append(formatDuration(s.path("uptime").asLong(0)))

        val last = s.path("lastPopup")
        if (last.isObject) {
            sb.append("\n\n").append(getString(R.string.status_last_popup))
                .append(" (").append(formatDuration(last.path("secondsAgo").asLong(0)))
                .append(" ago)\n")

            val parts = mutableListOf<String>()
            last.path("id").takeIf { it.isTextual }?.let { parts.add("id=${it.asText()}") }
            parts.add(last.path("position").asText("?"))
            parts.add(
                if (last.path("indefinite").asBoolean(false)) "∞"
                else "${last.path("duration").asInt(0)}s"
            )
            val media = last.path("media")
            if (media.isObject) {
                val dims = if (media.has("height"))
                    "${media.path("width").asInt()}×${media.path("height").asInt()}"
                else "${media.path("width").asInt()}w"
                parts.add("${media.path("type").asText()} $dims")
                when (last.path("muted").asBoolean(false)) {
                    true -> parts.add("muted")
                    false -> if (!last.path("muted").isNull) parts.add("sound")
                }
            }
            if (last.path("tts").asBoolean(false)) parts.add("tts")
            last.path("buttons").asInt(0).takeIf { it > 0 }?.let { parts.add("$it btn") }

            sb.append(parts.joinToString("  •  "))
        } else {
            sb.append("\n\n").append(getString(R.string.status_no_popups_yet))
        }

        return sb.toString()
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 2000L
    }
}
