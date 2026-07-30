package com.antigravity.pptremote

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.view.KeyEvent

class MediaStreamActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var topBarContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvUrl: TextView
    private lateinit var customViewContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://127.0.0.1:8686"
        
        setupLayout(serverUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        
        if (::webView.isInitialized) {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    webView.evaluateJavascript("var v=document.getElementById('mediaVideoPlayer'); if(v) { if(v.paused) v.play(); else v.pause(); }", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    webView.evaluateJavascript("var v=document.getElementById('mediaVideoPlayer'); if(v) v.currentTime -= 5;", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    webView.evaluateJavascript("var v=document.getElementById('mediaVideoPlayer'); if(v) v.currentTime += 5;", null)
                    return true
                }
                KeyEvent.KEYCODE_F -> {
                    webView.evaluateJavascript("var v=document.getElementById('mediaVideoPlayer'); if(v) { if(v.requestFullscreen) { if(!document.fullscreenElement) v.requestFullscreen(); else document.exitFullscreen(); } else if(v.webkitRequestFullscreen) { if(!document.webkitFullscreenElement) v.webkitRequestFullscreen(); else document.webkitExitFullscreen(); } }", null)
                    return true
                }
                KeyEvent.KEYCODE_M -> {
                    webView.evaluateJavascript("var v=document.getElementById('mediaVideoPlayer'); if(v) v.muted = !v.muted;", null)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupLayout(serverUrl: String) {

        // Root Layout
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0D16"))
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Bar
        topBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(pxToDp(12), pxToDp(8), pxToDp(12), pxToDp(8))
            gravity = android.view.Gravity.CENTER_VERTICAL
            elevation = 8f
        }

        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.parseColor("#58A6FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(pxToDp(6), pxToDp(6), pxToDp(6), pxToDp(6))
            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(pxToDp(8), 0, pxToDp(8), 0)
            }
        }

        tvTitle = TextView(this).apply {
            text = "PPT Remote Media Player"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        tvUrl = TextView(this).apply {
            text = serverUrl
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        textContainer.addView(tvTitle)
        textContainer.addView(tvUrl)

        val btnRefresh = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setColorFilter(Color.parseColor("#58A6FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(pxToDp(6), pxToDp(6), pxToDp(6), pxToDp(6))
            setOnClickListener { webView.reload() }
        }

        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#F85149"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(pxToDp(6), pxToDp(6), pxToDp(6), pxToDp(6))
            setOnClickListener { finish() }
        }

        topBarContainer.addView(btnBack)
        topBarContainer.addView(textContainer)
        topBarContainer.addView(btnRefresh)
        topBarContainer.addView(btnClose)

        mainContainer.addView(topBarContainer)

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0A0D16"))
        }
        setupWebView()

        mainContainer.addView(webView)
        rootLayout.addView(mainContainer)

        // Custom Fullscreen Container
        customViewContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        rootLayout.addView(customViewContainer)

        setContentView(rootLayout)

        webView.loadUrl(serverUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tvUrl.text = url ?: ""
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                originalOrientation = requestedOrientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                topBarContainer.visibility = View.GONE
                webView.visibility = View.GONE

                customViewContainer.addView(
                    customView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                customViewContainer.visibility = View.VISIBLE

                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            }

            override fun onHideCustomView() {
                if (customView == null) return

                customViewContainer.visibility = View.GONE
                customViewContainer.removeView(customView)
                customView = null

                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                topBarContainer.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE
                requestedOrientation = originalOrientation

                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun pxToDp(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"

        fun launch(context: Context, serverUrl: String) {
            val intent = Intent(context, MediaStreamActivity::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
            }
            context.startActivity(intent)
        }
    }
}
