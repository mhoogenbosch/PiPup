package nl.rogro82.pipup

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.*
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

        if(popup.media == null) {
            removeView(frame)
        }

        if(popup.title.isNullOrEmpty()) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(Color.parseColor(popup.titleColor))
        }

        if(popup.message.isNullOrEmpty()) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(Color.parseColor(popup.messageColor))
        }

        // background with optional urgency border preset
        background = GradientDrawable().apply {
            try {
                setColor(Color.parseColor(popup.backgroundColor))
            } catch (_: Throwable) {
                setColor(Color.parseColor(PopupProps.DEFAULT_BACKGROUND_COLOR))
            }
            when (popup.urgency?.lowercase()) {
                "info" -> { setStroke(4, Color.parseColor("#2196F3")); cornerRadius = 8f }
                "warning" -> { setStroke(6, Color.parseColor("#FF9800")); cornerRadius = 8f }
                "critical" -> { setStroke(8, Color.parseColor("#F44336")); cornerRadius = 8f }
            }
        }

        // DPAD-focusable buttons (the service makes the overlay window focusable)
        if (popup.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            popup.buttons.forEach { btn ->
                row.addView(Button(context).apply {
                    text = btn.label
                    isFocusable = true
                    setOnClickListener { onButton?.invoke(btn) }
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(10, 10, 10, 0)
                })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        private lateinit var mVideoView: VideoView

        init { create() }

        override fun create() {
            super.create()

            visibility = View.INVISIBLE

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            mVideoView = VideoView(context).apply {
                setVideoURI(Uri.parse(media.uri))
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
                if(mVideoView.isPlaying) {
                    mVideoView.stopPlayback()
                }
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