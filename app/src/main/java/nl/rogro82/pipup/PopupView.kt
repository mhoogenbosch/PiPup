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
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

// TODO: convert dimensions from px to dp

@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    /// set by the service after build(); invoked when the user presses a popup button
    var onButton: ((PopupProps.Button) -> Unit)? = null

    /// set by the service after build(); invoked once, with the milliseconds between this
    /// view's creation and the first rendered frame of its stream (video/web). Reported in
    /// /state.lastPopup.firstFrameMs so the start-up cost of a live popup is measurable.
    var onFirstFrame: ((Long) -> Unit)? = null
    private val mCreatedAt = android.os.SystemClock.elapsedRealtime()
    private var mFirstFrameReported = false

    /// Poster: a still image (e.g. a camera snapshot) laid over the stream area the moment
    /// the popup appears, so a live popup never opens as an empty box while the stream
    /// connects (RTSP handshake, WebView start-up, waiting for a keyframe). Faded out on
    /// the first rendered frame. If the poster fails to load nothing happens (Glide fails
    /// silently); if the stream never paints the poster simply stays — a snapshot from
    /// seconds ago beats an empty frame, so there is deliberately no timeout.
    protected var mPoster: ImageView? = null

    /// `onSized` receives the poster's intrinsic width/height once decoded, so the caller can
    /// give the stream area the poster's aspect ratio right away. A camera snapshot has the same
    /// aspect as the camera's stream, so still and live then line up exactly and the hand-over
    /// shows no size jump (a provisional 16:9 box letterboxed a 4:3 snapshot, then jumped).
    protected fun attachPoster(frame: FrameLayout, url: String?, params: FrameLayout.LayoutParams,
                               onSized: ((Int, Int) -> Unit)? = null) {
        if (url.isNullOrEmpty()) return
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY   // box already has the image's aspect
            setBackgroundColor(Color.TRANSPARENT)
        }
        frame.addView(iv, params)   // added after the stream view => drawn on top
        mPoster = iv
        try {
            Glide.with(context).asBitmap().load(url)
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                        if (mPoster !== iv) return   // already handed over / destroyed
                        iv.setImageBitmap(resource)
                        if (resource.width > 0 && resource.height > 0) onSized?.invoke(resource.width, resource.height)
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) { iv.setImageDrawable(null) }
                    override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                        Log.w(LOG_TAG, "poster failed to load: $url")
                    }
                })
        } catch (_: Throwable) {}
    }

    /// Called by the subclass when its stream rendered its first frame: fades the poster out
    /// (150 ms, then removed) and reports firstFrameMs once.
    protected fun onStreamFirstFrame() {
        if (!mFirstFrameReported) {
            mFirstFrameReported = true
            val ms = android.os.SystemClock.elapsedRealtime() - mCreatedAt
            Log.d(LOG_TAG, "first frame after ${ms} ms" + (if (mPoster != null) " (poster shown)" else ""))
            try { onFirstFrame?.invoke(ms) } catch (_: Throwable) {}
        }
        val iv = mPoster ?: return
        mPoster = null
        iv.animate().alpha(0f).setDuration(150).withEndAction {
            try { Glide.with(context).clear(iv) } catch (_: Throwable) {}
            (iv.parent as? ViewGroup)?.removeView(iv)
        }.start()
    }

    protected fun destroyPoster() {
        mPoster?.let { iv ->
            try { Glide.with(context).clear(iv) } catch (_: Throwable) {}
            iv.setImageDrawable(null)
        }
        mPoster = null
    }

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

    /// All video_url playback goes through ExoPlayer rendering into a TextureView.
    ///
    /// Why not the stock VideoView (used up to 0.15.x)? It is a SurfaceView: a separate
    /// composition layer that SurfaceFlinger puts on the hardware video overlay plane. Measured
    /// on a Fire TV while another app was playing a film (which owns that plane): the decoder
    /// rendered frames, but nothing was composited — the popup was a transparent hole with the
    /// film through it; the same popup rendered fine with no video playing. A TextureView is
    /// composited into this window by the GPU (AOSP: "TextureView is always composited using
    /// GL"), needs no overlay plane, and shows over other video — verified on the same TV with
    /// the film running. VideoView also popped a system AlertDialog on error, which crashed a
    /// Service-hosted overlay (BadTokenException); ExoPlayer just reports the error.
    ///
    /// Sources: rtsp:// via RtspMediaSource with RTP-over-TCP forced (UDP is unreliable on
    /// Wi-Fi); everything else (HLS .m3u8 as used by camera_mode: stream, progressive http)
    /// via DefaultMediaSourceFactory. Audio plays only when muted=false — an AudioTrack
    /// renegotiates HDMI audio and interrupted playback on the TV, and the HA integration
    /// defaults to muted.
    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video): PopupView(context, popup) {
        private var mExoPlayer: ExoPlayer? = null
        private var mTextureView: TextureView? = null

        init { create() }

        @UnstableApi
        override fun create() {
            super.create()

            visibility = View.INVISIBLE

            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            val uri = media.uri

            val textureView = TextureView(context)
            mTextureView = textureView
            // Size the TextureView (the child), never this popup view: the popup's own
            // layoutParams carry its screen position, and popup_frame is the first child of
            // the vertical layout — a fixed popup height would push the title/message out and
            // re-centre it. Provisional 16:9 and visible right away so the SurfaceTexture exists
            // before the first frame (a 0-height view never gets one); corrected to the true
            // aspect on onVideoSizeChanged.
            fun sizeTo(w: Int, h: Int) {
                textureView.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
                mPoster?.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
                this@Video.visibility = View.VISIBLE
            }
            frame.addView(textureView)
            attachPoster(frame, media.poster,
                FrameLayout.LayoutParams(media.width, media.width * 9 / 16).apply { gravity = Gravity.CENTER }
            ) { pw, ph -> sizeTo(media.width, (media.width.toLong() * ph / pw).toInt()) }
            sizeTo(media.width, media.width * 9 / 16)

            val isRtsp = uri.startsWith("rtsp://", ignoreCase = true) || uri.startsWith("rtsps://", ignoreCase = true)
            val item = MediaItem.fromUri(uri)
            val source = if (isRtsp)
                RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item)
            else
                DefaultMediaSourceFactory(context).createMediaSource(item)

            // Decoder choice (0.17.2). The vendor hardware decoder is the default everywhere,
            // except where it is known to break the TV's own video: on Amlogic SoCs the
            // MediaCodec decoder and the HDMI input share one video layer, and releasing our
            // decoder when the popup closes froze the HDMI picture (Xiaomi projector, Android
            // 6.0.1, OMX.amlogic.avc.decoder.awesome; the TCL on Android 8 was fine). There
            // we prefer the software decoders (OMX.google.* / c2.android.*), which never
            // touch that layer. `softwareDecoder` in the payload overrides the automatic rule.
            val software = media.softwareDecoder ?: autoSoftwareDecoder()
            Log.i(LOG_TAG, "video decoder: ${if (software) "software (preferred)" else "hardware (default)"}" +
                " [auto=${media.softwareDecoder == null}, sdk=${Build.VERSION.SDK_INT}]")
            val renderers = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(if (software) SOFTWARE_FIRST_SELECTOR else MediaCodecSelector.DEFAULT)

            mExoPlayer = ExoPlayer.Builder(context, renderers).build().apply {
                setVideoTextureView(textureView)
                if (media.muted) {
                    // no audio track at all: avoid grabbing the audio output from whatever plays
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                }
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            sizeTo(media.width, (media.width.toLong() * videoSize.height / videoSize.width).toInt())
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        onStreamFirstFrame()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(LOG_TAG, "ExoPlayer error for $uri: ${error.errorCodeName}", error)
                    }
                })
                setMediaSource(source)
                playWhenReady = true
                prepare()
            }
        }

        override fun destroy() {
            super.destroy()
            destroyPoster()
            // Orderly teardown: stop decoding, detach the output surface, then release. A bare
            // release() lets the decoder die while still bound to the surface, which is the
            // moment a vendor pipeline (Amlogic) lost sync with the HDMI input.
            try {
                mExoPlayer?.let { player ->
                    runCatching { player.stop() }
                    mTextureView?.let { tv -> runCatching { player.clearVideoTextureView(tv) } }
                    player.release()
                }
            } catch(e: Throwable) {
            } finally {
                mExoPlayer = null
                mTextureView = null
            }
        }

        companion object {
            /// Software decoders first, then the rest as ExoPlayer would order them; only for
            /// video mime types (audio is disabled anyway). With decoder fallback enabled a
            /// failing software decoder still falls through to the hardware one.
            @UnstableApi
            val SOFTWARE_FIRST_SELECTOR = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (MimeTypes.isVideo(mimeType)) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
            }

            /// Automatic rule: software on Android < 8 (old vendor decoders, the projector
            /// class of devices) and wherever an Amlogic decoder is present, hardware elsewhere.
            @UnstableApi
            fun autoSoftwareDecoder(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
                return try {
                    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                        .any { it.name.contains("amlogic", ignoreCase = true) }
                } catch (e: Throwable) {
                    false
                }
            }
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
                webViewClient = object : WebViewClient() {
                    // First visible paint of the page. Used for the poster hand-over and
                    // the firstFrameMs measurement. Deliberately NOT onPageFinished: that
                    // fires when everything has loaded, and an MJPEG stream never finishes.
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        onStreamFirstFrame()
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // mute every (also dynamically added) media element, so the
                        // page never claims audio focus (audio can stall video on
                        // some Android TV / Fire TV devices)
                        if (media.muted) view?.evaluateJavascript(MUTE_JS, null)
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
            attachPoster(frame, media.poster,
                FrameLayout.LayoutParams(media.width, media.height).apply { gravity = Gravity.CENTER }
            ) { pw, ph ->
                // Give the web view the poster's aspect at the requested width, so still and
                // live stream match (an MJPEG page scales the image to the view width anyway).
                val h = (media.width.toLong() * ph / pw).toInt()
                webView.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
                mPoster?.layoutParams = FrameLayout.LayoutParams(media.width, h).apply { gravity = Gravity.CENTER }
            }
        }

        override fun destroy() {
            super.destroy()
            destroyPoster()
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