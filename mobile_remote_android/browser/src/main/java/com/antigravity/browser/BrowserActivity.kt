package com.antigravity.browser

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BrowserScreen()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingDownload?.invoke()
            pendingDownload = null
        } else {
            android.widget.Toast.makeText(context, "Storage permission denied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var originalOrientation by remember { mutableStateOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }

    // Handle physical back button or ESC for fullscreen
    BackHandler(enabled = customView != null) {
        webView?.webChromeClient?.onHideCustomView()
    }

    // Handle physical back button for browsing history
    BackHandler(enabled = canGoBack && customView == null) {
        webView?.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top App Bar / Omnibox
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    placeholder = { Text("Search or type URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            var loadUrl = urlInput.trim()
                            val isIpOrLocalhost = loadUrl.startsWith("localhost") || loadUrl.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+.*"))
                            
                            if (loadUrl.contains(" ") && !loadUrl.startsWith("http")) {
                                loadUrl = "https://www.google.com/search?q=${android.net.Uri.encode(loadUrl)}"
                            } else if (!loadUrl.contains(".") && !isIpOrLocalhost) {
                                // Simple fallback to google search if not a direct domain
                                loadUrl = "https://www.google.com/search?q=${android.net.Uri.encode(loadUrl)}"
                            } else if (!loadUrl.startsWith("http://") && !loadUrl.startsWith("https://")) {
                                loadUrl = if (isIpOrLocalhost) "http://$loadUrl" else "https://$loadUrl"
                            }
                            webView?.loadUrl(loadUrl)
                        }
                    ),
                    shape = RoundedCornerShape(25.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        // Loading Progress Bar
        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        // WebView
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                url?.let { 
                                    currentUrl = it 
                                    urlInput = it
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Allow WebView to load all URLs internally
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                progress = newProgress / 100f
                            }
                            
                            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                                if (customView != null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                customView = view
                                customViewCallback = callback
                                if (context is android.app.Activity) {
                                    originalOrientation = context.requestedOrientation
                                    context.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    @Suppress("DEPRECATION")
                                    context.window.decorView.systemUiVisibility = (
                                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    )
                                }
                            }

                            override fun onHideCustomView() {
                                if (customView == null) return
                                customView = null
                                customViewCallback?.onCustomViewHidden()
                                customViewCallback = null
                                if (context is android.app.Activity) {
                                    context.requestedOrientation = originalOrientation
                                    @Suppress("DEPRECATION")
                                    context.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                }
                            }
                        }
                        
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            val enqueueDownload = {
                                val request = android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl))
                                request.setMimeType(mimetype)
                                request.addRequestHeader("User-Agent", userAgent)
                                request.setDescription("Downloading file...")
                                var fileName = android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                                if (fileName.endsWith(".bin") || !fileName.contains(".")) {
                                    if (contentDisposition != null) {
                                        val match = Regex("filename=\"([^\"]+)\"").find(contentDisposition)
                                        if (match != null) {
                                            fileName = match.groupValues[1]
                                        } else {
                                            val utf8Match = Regex("filename\\*=UTF-8''(.+)").find(contentDisposition)
                                            if (utf8Match != null) {
                                                fileName = java.net.URLDecoder.decode(utf8Match.groupValues[1], "UTF-8")
                                            }
                                        }
                                    }
                                    if (fileName.endsWith(".bin") || !fileName.contains(".")) {
                                        try {
                                            val uri = android.net.Uri.parse(downloadUrl)
                                            val pathQuery = uri.getQueryParameter("path")
                                            if (pathQuery != null) {
                                                val lastSlash = pathQuery.lastIndexOf('/')
                                                fileName = if (lastSlash != -1) pathQuery.substring(lastSlash + 1) else pathQuery
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                                request.setTitle(fileName)
                                request.allowScanningByMediaScanner()
                                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                                
                                val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                try {
                                    dm.enqueue(request)
                                    android.widget.Toast.makeText(context, "Downloading...", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }

                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q && 
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                pendingDownload = enqueueDownload
                                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                enqueueDownload()
                            }
                        }

                        loadUrl(currentUrl)
                        webView = this
                    }
                },
                update = {
                    webView = it
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom Navigation Bar
        BottomAppBar(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { webView?.goBack() },
                    enabled = canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                
                IconButton(
                    onClick = { webView?.goForward() },
                    enabled = canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
                
                IconButton(
                    onClick = { webView?.reload() }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                
                IconButton(
                    onClick = { showHistory = true }
                ) {
                    Icon(Icons.Default.History, contentDescription = "History")
                }
                
                IconButton(
                    onClick = { 
                        urlInput = "https://www.google.com"
                        webView?.loadUrl(urlInput)
                    }
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Home")
                }
            }
        }
        
        if (showHistory) {
            @OptIn(ExperimentalMaterial3Api::class)
            ModalBottomSheet(onDismissRequest = { showHistory = false }) {
                val historyList = webView?.copyBackForwardList()
                if (historyList != null && historyList.size > 0) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(historyList.size) { index ->
                            val reverseIndex = historyList.size - 1 - index
                            val item = historyList.getItemAtIndex(reverseIndex)
                            val isCurrent = reverseIndex == historyList.currentIndex
                            ListItem(
                                headlineContent = { Text(item.title ?: item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { 
                                    if (isCurrent) Icon(Icons.AutoMirrored.Filled.ArrowForward, "Current") 
                                },
                                modifier = Modifier.clickable {
                                    webView?.goBackOrForward(reverseIndex - historyList.currentIndex)
                                    showHistory = false
                                }
                            )
                        }
                    }
                } else {
                    Text("No history yet.", modifier = Modifier.padding(16.dp))
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
        }
        
        if (customView != null) {
            AndroidView(
                factory = { context ->
                    android.widget.FrameLayout(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        addView(customView, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
