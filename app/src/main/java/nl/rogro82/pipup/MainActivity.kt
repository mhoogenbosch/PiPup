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
import android.widget.TextView
import com.fasterxml.jackson.databind.JsonNode
import nl.rogro82.pipup.Utils.getIpAddress
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshWarning()
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
        mHandler.post(mRefresh)
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(mRefresh)
    }

    /// The one failure mode that looks like success from the outside: without the overlay
    /// permission every /notify is answered with 200 and nothing appears on screen. Say so
    /// here, on the only screen someone with a remote can reach, with the command to fix it.
    /// Re-checked on every refresh so the warning disappears as soon as it is granted.
    private fun refreshWarning() {
        findViewById<TextView>(R.id.textViewWarning)?.visibility =
            if (Permissions.overlay(this)) View.GONE else View.VISIBLE
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
