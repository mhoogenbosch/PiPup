package nl.rogro82.pipup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log

/// Plays the optional popup notification sound (0.18.0): "default" = the bundled chime
/// (res/raw/chime.wav), anything else = a URL/URI MediaPlayer can open. One sound at a time;
/// a new one stops the previous. Transient audio focus with ducking, so whatever the TV is
/// playing dips for the chime and comes back - the same audio path TTS uses, with the same
/// caveat that some Fire TVs renegotiate HDMI audio briefly when it opens.
object SoundPlayer {
    private const val LOG_TAG = "PiPupSound"
    private var mPlayer: MediaPlayer? = null
    private var mFocus: AudioFocusRequest? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(context: Context, sound: String, volume: Float?) {
        stop(context)
        val uri = if (sound.equals("default", ignoreCase = true))
            Uri.parse("android.resource://${context.packageName}/${R.raw.chime}")
        else Uri.parse(sound)
        try {
            val player = MediaPlayer()
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            volume?.coerceIn(0f, 1f)?.let { player.setVolume(it, it) }
            player.setOnPreparedListener {
                requestFocus(context)
                it.start()
            }
            player.setOnCompletionListener { stop(context) }
            player.setOnErrorListener { _, what, extra ->
                Log.w(LOG_TAG, "sound failed ($what/$extra): $sound")
                stop(context)
                true
            }
            mPlayer = player
            player.prepareAsync()
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "cannot play sound $sound: ${ex.message}")
            stop(context)
        }
    }

    fun stop(context: Context) {
        mPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mPlayer = null
        abandonFocus(context)
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).build()
            mFocus = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mFocus?.let { am.abandonAudioFocusRequest(it) }
            mFocus = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
