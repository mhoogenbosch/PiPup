package nl.rogro82.pipup

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.TextureView
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

// TODO: convert dimensions from px to dp

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    /// set by the service after build(); invoked when the user presses a popup button
    var onButton: ((PopupProps.Button) -> Unit)? = null

    private var mProgressAnimator: ObjectAnimator? = null

    open fun create() {
        inflate(context, R.layout.popup,this)

        layoutParams = LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            orientation = VERTICAL
            minimumWidth = 240
        }

        setPadding(20,20,20,20)

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)
        val header = findViewById<LinearLayout>(R.id.popup_header)
        val textcol = findViewById<LinearLayout>(R.id.popup_textcol)

        if(popup.media == null) {
            removeView(frame)
        }

        if(popup.title.isNullOrEmpty()) {
            textcol.removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(parseColorOrDefault(popup.titleColor, PopupProps.DEFAULT_TITLE_COLOR))
        }

        if(popup.message.isNullOrEmpty()) {
            textcol.removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(parseColorOrDefault(popup.messageColor, PopupProps.DEFAULT_MESSAGE_COLOR))
        }

        // Optional icon beside the text block, left (default) or right. Loaded like
        // any other image; adjustViewBounds keeps its aspect ratio at the given width.
        if (!popup.icon.isNullOrEmpty()) {
            val onRight = popup.iconPosition.equals("right", ignoreCase = true)
            val iconView = findViewById<ImageView>(
                if (onRight) R.id.popup_icon_right else R.id.popup_icon_left
            )
            iconView.layoutParams = (iconView.layoutParams as LinearLayout.LayoutParams).apply {
                width = popup.iconWidth
                height = LayoutParams.WRAP_CONTENT
            }
            iconView.visibility = View.VISIBLE
            // override(): the view's height is WRAP_CONTENT, which Glide would otherwise
            // decode at screen size; bound it to the icon width (adjustViewBounds keeps
            // the aspect ratio on display).
            Glide.with(context).load(popup.icon).override(popup.iconWidth).into(iconView)
        }

        // Nothing left in the header (media-only popup) -> drop the empty row.
        if (textcol.childCount == 0 && popup.icon.isNullOrEmpty()) {
            removeView(header)
        }

        // background with an optional border: `urgency` is a shorthand for a
        // (width, color) pair, and borderColor/borderWidth/cornerRadius each
        // override their part of it independently.
        background = GradientDrawable().apply {
            setColor(parseColorOrDefault(popup.backgroundColor, PopupProps.DEFAULT_BACKGROUND_COLOR))

            val preset = PopupProps.URGENCY_PRESETS[popup.urgency?.lowercase()]
            val width = popup.borderWidth ?: preset?.first
                ?: popup.borderColor?.let { PopupProps.DEFAULT_BORDER_WIDTH }
            val color = popup.borderColor ?: preset?.second
                ?: PopupProps.DEFAULT_BORDER_COLOR

            if (width != null && width > 0) {
                setStroke(width, parseColorOrDefault(color, PopupProps.DEFAULT_BORDER_COLOR))
            }
            // rounded corners keep working without a border, and an explicit 0 squares them off
            cornerRadius = popup.cornerRadius
                ?: if (width != null && width > 0) PopupProps.DEFAULT_CORNER_RADIUS else 0f
        }

        // DPAD-focusable buttons (the service makes the overlay window focusable).
        // In an overlay window the platform button background carries no visible
        // focus state, so D-pad navigation moved focus invisibly - the user pressed
        // the arrows, saw nothing change and concluded the popup was not interactive
        // (reported for a 3-button popup on a Shield). Each button gets an explicit
        // focused/unfocused background plus a small scale bump on focus, and the
        // first one takes focus so there is an indicator from the start.
        if (popup.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            var firstButton: Button? = null
            popup.buttons.forEach { btn ->
                val button = Button(context).apply {
                    text = btn.label
                    isFocusable = true
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setPadding(36, 16, 36, 16)
                    stateListAnimator = null   // the platform one would fight our scale
                    background = buttonBackground()
                    setOnClickListener { onButton?.invoke(btn) }
                    setOnFocusChangeListener { v, hasFocus ->
                        val s = if (hasFocus) 1.12f else 1f
                        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                    }
                }
                if (firstButton == null) firstButton = button
                row.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(10, 10, 10, 0)
                })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            // Give the first button focus once the view is attached, so there is a
            // visible highlight before the user touches the remote.
            firstButton?.post { firstButton?.requestFocus() }
        }

        // countdown bar for finite durations
        if (popup.showProgress && !popup.indefinite) {
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 1000
            }
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 8).apply {
                setMargins(0, 12, 0, 0)
            })
            mProgressAnimator = ObjectAnimator.ofInt(bar, "progress", 1000, 0).apply {
                duration = popup.duration * 1000L
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    open fun destroy() {
        try {
            mProgressAnimator?.cancel()
        } catch (_: Throwable) {}
        mProgressAnimator = null
    }

    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup) {
        init { create() }
    }

    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video): PopupView(context, popup) {
        private var mVideoView: VideoView? = null
        private var mExoPlayer: ExoPlayer? = null

        init { create() }

        override fun create() {
            super.create()

            visibility = View.INVISIBLE

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            // rtsp:// goes through ExoPlayer (stock VideoView/MediaPlayer can't play RTSP reliably —
            // it fails with MEDIA_ERROR_UNKNOWN). Everything else keeps the existing VideoView path,
            // and the ExoPlayer/media3 classes only load when an rtsp URL is actually shown.
            val uri = media.uri
            if (uri.startsWith("rtsp://", ignoreCase = true) || uri.startsWith("rtsps://", ignoreCase = true)) {
                createRtsp(frame, uri)
            } else {
                createVideoView(frame)
            }
        }

        /// ExoPlayer path for rtsp://. Forces RTP-over-TCP: UDP interleave is frequently blocked or
        /// unreliable on Wi-Fi, and most IP cameras / RTSP servers support TCP. Errors are logged
        /// (ExoPlayer shows no system dialog, so a failing stream cannot crash us).
        ///
        /// Renders into a SurfaceView (not a TextureView): a Service overlay window is not hardware-
        /// accelerated, where a TextureView renders nothing — but a SurfaceView composites its own
        /// layer and works here, exactly as the stock VideoView (which is a SurfaceView) already does.
        /// The popup is shown immediately at a provisional size so the SurfaceView has a real surface
        /// to decode into (a 0-height view never gets one → nothing renders), then corrected to the
        /// true aspect on onVideoSizeChanged. The audio track is disabled: a camera popup needs no
        /// sound, and opening an AudioTrack grabs the audio output/focus and disrupted media already
        /// playing on the TV. The concurrent hardware *video* decoder is an inherent limit — on
        /// constrained TVs a second AVC decoder can still contend with video playing underneath.
        @UnstableApi
        private fun createRtsp(frame: FrameLayout, uri: String) {
            // TextureView, deliberately NOT a SurfaceView. A SurfaceView is a separate
            // composition layer that SurfaceFlinger puts on a hardware video overlay plane;
            // measured on a Fire TV: while another app was playing video (the film owned the
            // plane) our decoder rendered frames into the SurfaceView layer but nothing was
            // composited — the popup showed a transparent hole with the film through it. The
            // same popup rendered fine with no video playing. A TextureView is composited into
            // this window with the GPU (AOSP: "TextureView is always composited using GL"),
            // so it needs no overlay plane and shows over other video. Requires a hardware-
            // accelerated window, which this overlay is (manifest default applies to it).
            // Not usable for DRM content — irrelevant for a camera stream.
            val textureView = TextureView(context)
            // Size the TextureView (the child), never this popup view: the popup's own
            // layoutParams carry its screen position, and popup_frame is the first child of
            // the vertical layout — a fixed popup height pushed the title/message out and
            // re-centred it. Provisional 16:9 and visible right away so the SurfaceTexture
            // exists before the first frame (a 0-height view never gets one); corrected to the
            // true aspect on onVideoSizeChanged.
            fun sizeTo(w: Int, h: Int) {
                textureView.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
                this@Video.visibility = View.VISIBLE
            }
            frame.addView(textureView)
            sizeTo(media.width, media.width * 9 / 16)
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(uri))
            mExoPlayer = ExoPlayer.Builder(context).build().apply {
                setVideoTextureView(textureView)
                // no audio: avoid grabbing the audio output/focus from whatever is already playing
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            sizeTo(media.width, (media.width.toLong() * videoSize.height / videoSize.width).toInt())
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(LOG_TAG, "ExoPlayer RTSP error for $uri: ${error.errorCodeName}", error)
                    }
                })
                setMediaSource(source)
                playWhenReady = true
                prepare()
            }
        }

        private fun createVideoView(frame: FrameLayout) {
            mVideoView = VideoView(context).apply {
                setVideoURI(Uri.parse(media.uri))
                // Suppress VideoView's built-in "Can't play this video" AlertDialog. It is shown
                // on any playback error or stall (common with direct rtsp:// URLs), but this overlay
                // runs from a Service with no activity window token, so Dialog.show() throws
                // BadTokenException and crashes the app. Returning true marks the error handled, so
                // no dialog is shown; the popup stays hidden and is removed by its own duration timer.
                setOnErrorListener { _, what, extra ->
                    Log.w(LOG_TAG, "VideoView error (what=$what extra=$extra) for ${media.uri} — suppressing dialog")
                    true
                }
                setOnPreparedListener {
                    if (media.muted) {
                        it.setVolume(0f, 0f)
                    }
                    it.setOnVideoSizeChangedListener { _, _, _ ->

                        // resize video and show popup view

                        layoutParams = FrameLayout.LayoutParams(media.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER
                        }

                        this@Video.visibility = View.VISIBLE
                    }
                }

                start()
            }

            frame.addView(mVideoView, FrameLayout.LayoutParams(1, 1))
        }

        override fun destroy() {
            super.destroy()
            try {
                mVideoView?.let { if (it.isPlaying) it.stopPlayback() }
                mVideoView = null
            } catch(e: Throwable) {}
            try {
                mExoPlayer?.release()
                mExoPlayer = null
            } catch(e: Throwable) {}
        }
    }

    private class Image(context: Context, popup: PopupProps, val media: PopupProps.Media.Image): PopupView(context, popup) {
        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            try {
                val imageView = ImageView(context)

                val layoutParams =
                    FrameLayout.LayoutParams(media.width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }

                frame.addView(imageView, layoutParams)

                Glide.with(context)
                    .load(Uri.parse(media.uri))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView)

            } catch(e: Throwable) {
                removeView(frame)
            }
        }
    }

    private class Bitmap(context: Context, popup: PopupProps, val media: PopupProps.Media.Bitmap): PopupView(context, popup) {
        var mImageView: ImageView? = null

        init { create() }

        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(media.image)
            }

            val scaledHeight = ((media.width.toFloat() / media.image.width) * media.image.height).toInt()
            val layoutParams =
                FrameLayout.LayoutParams(media.width, scaledHeight).apply {
                    gravity = Gravity.CENTER
                }

            frame.addView(mImageView, layoutParams)
        }

        override fun destroy() {
            super.destroy()
            try {
                mImageView?.setImageDrawable(null)
                media.image.recycle()
            } catch(e: Throwable) {}
        }
    }

    private class Web(context: Context, popup: PopupProps, val media: PopupProps.Media.Web): PopupView(context, popup) {
        private var mWebView: WebView? = null

        init { create() }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val webView = WebView(context).apply {
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // camera/stream pages (go2rtc, HA) need JS and unattended playback
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                if (media.muted) {
                    // mute every (also dynamically added) media element, so the
                    // page never claims audio focus (audio can stall video on
                    // some Android TV / Fire TV devices)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(MUTE_JS, null)
                        }
                    }
                }
                loadUrl(media.uri)
            }
            mWebView = webView

            val layoutParams = FrameLayout.LayoutParams(
                media.width,
                media.height
            ).apply {
                gravity = Gravity.CENTER
            }

            frame.addView(webView, layoutParams)
        }

        override fun destroy() {
            super.destroy()
            try {
                mWebView?.apply {
                    loadUrl("about:blank")
                    destroy()
                }
                mWebView = null
            } catch(e: Throwable) {}
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"

        /// Bright fill shown on the focused popup button. White text stays readable on it.
        val DEFAULT_BUTTON_FOCUS_COLOR = Color.parseColor("#2979FF")

        /// Focus-aware button background: a bright fill when focused, a subtle
        /// translucent one otherwise — so D-pad navigation is visible in an overlay
        /// window, where the platform button carries no focus state of its own.
        fun buttonBackground(): StateListDrawable {
            fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(fill)
                setStroke(2, stroke)
            }
            val focused = pill(DEFAULT_BUTTON_FOCUS_COLOR, Color.WHITE)
            val normal = pill(Color.parseColor("#33FFFFFF"), Color.parseColor("#66FFFFFF"))
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
        }

        /// A malformed color must never take the whole popup down: an unparseable
        /// value falls back instead of throwing out of create().
        fun parseColorOrDefault(value: String?, fallback: String): Int = try {
            Color.parseColor(value)
        } catch (_: Throwable) {
            Log.w(LOG_TAG, "Unparseable color '$value', using $fallback")
            Color.parseColor(fallback)
        }

        const val MUTE_JS = """
            (function() {
                function muteAll() {
                    document.querySelectorAll('video,audio').forEach(function(m) {
                        m.muted = true;
                        m.volume = 0;
                    });
                }
                muteAll();
                new MutationObserver(muteAll).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """

        fun build(context: Context, popup: PopupProps): PopupView
        {
            return when (popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, popup.media)
                is PopupProps.Media.Video -> Video(context, popup, popup.media)
                is PopupProps.Media.Image -> Image(context, popup, popup.media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, popup.media)
                else -> Default(context, popup)
            }
        }
    }
}