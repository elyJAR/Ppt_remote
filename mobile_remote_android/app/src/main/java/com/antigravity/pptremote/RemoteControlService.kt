package com.antigravity.pptremote

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RemoteControlService : Service() {

    interface WebServerSecurityListener {
        fun onRequestConnection(clientIp: String, onResponse: (Boolean) -> Unit)
        fun onRequestDelete(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit)
        fun onRequestUpload(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit)
        fun onRequestDownload(clientIp: String, fileName: String, onResponse: (Boolean) -> Unit)
    }

    class SecurityDecision {
        private var approved: Boolean? = null

        @Synchronized
        fun setDecision(value: Boolean) {
            approved = value
            (this as Object).notifyAll()
        }

        @Synchronized
        fun getDecision(): Boolean {
            val start = System.currentTimeMillis()
            while (approved == null) {
                val remaining = 20000 - (System.currentTimeMillis() - start)
                if (remaining <= 0) break
                try {
                    (this as Object).wait(remaining)
                } catch (e: InterruptedException) {
                    break
                }
            }
            return approved ?: false
        }
    }


    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BridgeClient()
    private var wakeLock: PowerManager.WakeLock? = null
    private var isStarted = false
    private var mediaSession: MediaSessionCompat? = null

    companion object {
        private const val CHANNEL_ID        = "ppt_remote_service"
        private const val NOTIFICATION_ID   = 1
        private const val WAKE_LOCK_TAG     = "PptRemote::ServiceWakeLock"

        const val ACTION_NEXT          = "com.antigravity.pptremote.action.NEXT"
        const val ACTION_PREVIOUS      = "com.antigravity.pptremote.action.PREVIOUS"
        const val ACTION_START         = "com.antigravity.pptremote.action.START"
        const val ACTION_STOP_SHOW     = "com.antigravity.pptremote.action.STOP_SHOW"
        const val ACTION_STOP_SERVICE  = "com.antigravity.pptremote.action.STOP_SERVICE"
        const val ACTION_STOP_FTP      = "com.antigravity.pptremote.action.STOP_FTP"
        const val ACTION_TOGGLE_FTP    = "com.antigravity.pptremote.action.TOGGLE_FTP"
        const val EXTRA_FTP_HOME_DIR   = "com.antigravity.pptremote.action.FTP_HOME_DIR"
        const val ACTION_TOGGLE_WEB_SERVER = "com.antigravity.pptremote.action.TOGGLE_WEB_SERVER"
        const val EXTRA_WEB_ROOT_DIR   = "com.antigravity.pptremote.extra.WEB_ROOT_DIR"
        const val EXTRA_WEB_PIN        = "com.antigravity.pptremote.extra.WEB_PIN"

        @Volatile var activeUploadName: String? = null
        @Volatile var activeUploadProgress: Float = 0f
        @Volatile var activeUploadBytes: Long = 0L
        @Volatile var activeUploadTotal: Long = 0L
        @Volatile var isUploadCancelled: Boolean = false

        private val ftpManager = FtpServerManager()
        private var webFileServer: WebFileServer? = null

        var securityListener: WebServerSecurityListener? = null

        const val CHANNEL_ID_ALERTS         = "ppt_remote_alerts"
        const val ACTION_APPROVE_REQUEST    = "com.antigravity.pptremote.action.APPROVE_REQUEST"
        const val ACTION_DENY_REQUEST       = "com.antigravity.pptremote.action.DENY_REQUEST"
        const val EXTRA_REQUEST_ID          = "com.antigravity.pptremote.extra.REQUEST_ID"

        private val pendingRequests = ConcurrentHashMap<String, Pair<Int, (Boolean) -> Unit>>()
        private var notificationIdCounter = 2000

        fun showUploadRequestNotification(
            context: Context,
            clientIp: String,
            fileName: String,
            requestId: String,
            onResponse: (Boolean) -> Unit
        ) {
            val notificationId = notificationIdCounter++
            pendingRequests[requestId] = Pair(notificationId, onResponse)

            try {
                RemoteControlService.start(context)
            } catch (_: Exception) {}

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    "Security & Upload Approval Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High priority notification alerts for file upload and download approvals"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300)
                    setSound(ringtoneUri, audioAttributes)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }

            val approveIntent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_APPROVE_REQUEST
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            val approvePending = PendingIntent.getService(
                context, requestId.hashCode() + 1, approveIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val denyIntent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_DENY_REQUEST
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            val denyPending = PendingIntent.getService(
                context, requestId.hashCode() + 2, denyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val openApp = PendingIntent.getActivity(
                context, requestId.hashCode() + 3,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("LAUNCHED_FOR_AUTH", true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setContentTitle("Upload Requested by $clientIp")
                .setContentText("Allow upload of \"$fileName\"?")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Device at IP $clientIp wants to upload file:\n\"$fileName\"\n\nTap APPROVE or DENY."))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .addAction(R.drawable.ic_play, "APPROVE", approvePending)
                .addAction(R.drawable.ic_stop, "DENY", denyPending)

            nm.notify(notificationId, builder.build())
        }

        fun resolveRequest(requestId: String, approved: Boolean, context: Context? = null) {
            val entry = pendingRequests.remove(requestId)
            if (entry != null) {
                val (notificationId, callback) = entry
                callback.invoke(approved)
                if (context != null) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notificationId)
                }
            }
        }

        fun toggleWebServer(context: Context, rootDir: String, pin: String) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_TOGGLE_WEB_SERVER
                putExtra(EXTRA_WEB_ROOT_DIR, rootDir)
                putExtra(EXTRA_WEB_PIN, pin)
            }
            context.startService(intent)
        }

        fun isWebServerRunning(): Boolean = webFileServer?.isRunning() == true

        private fun getLocalIpAddress(): String? {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    val list = java.util.Collections.list(interfaces)
                    for (intf in list) {
                        if (intf.isLoopback || !intf.isUp) continue
                        val name = intf.name.lowercase()
                        if (name.contains("wlan") || name.contains("ap") || name.contains("softap") || name.contains("rndis")) {
                            for (addr in java.util.Collections.list(intf.inetAddresses)) {
                                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                    val host = addr.hostAddress
                                    if (!host.isNullOrBlank() && host != "0.0.0.0") {
                                        return host
                                    }
                                }
                            }
                        }
                    }
                    for (intf in list) {
                        if (intf.isLoopback || !intf.isUp) continue
                        for (addr in java.util.Collections.list(intf.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                val host = addr.hostAddress
                                if (!host.isNullOrBlank() && host != "0.0.0.0") {
                                    return host
                                }
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                android.util.Log.e("RemoteControlService", "Error getting local IP address", ex)
            }
            return null
        }

        fun getWebServerUrl(context: Context): String? {
            val server = webFileServer ?: return null
            if (!server.isRunning()) return null
            val useHttps = RemotePrefs.isHttpsEnabled(context)
            val scheme = if (useHttps) "https" else "http"
            return try {
                val ipStr = getLocalIpAddress()
                if (!ipStr.isNullOrBlank() && ipStr != "0.0.0.0") {
                    "$scheme://$ipStr:${server.port}"
                } else {
                    @Suppress("DEPRECATION")
                    val wifiManager = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val ip = wifiManager.connectionInfo.ipAddress
                    val ipStrLegacy = android.text.format.Formatter.formatIpAddress(ip)
                    "$scheme://$ipStrLegacy:${server.port}"
                }
            } catch (_: Exception) { null }
        }

        fun toggleFtp(context: Context, homeDir: String? = null) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_TOGGLE_FTP
                putExtra(EXTRA_FTP_HOME_DIR, homeDir)
            }
            context.startService(intent)
        }

        fun getActiveFtpPath(): String? = ftpManager.activePath

        fun isFtpRunning(): Boolean = ftpManager.isRunning()

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    android.util.Log.w("RemoteControlService", "POST_NOTIFICATIONS not granted")
                    return
                }
            }
            try {
                val intent = Intent(context, RemoteControlService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "Failed to start service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (RemoteControlService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Route media button events from the lock screen through MediaSession
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_NOT_STICKY
        }

        // Always call startForeground first — required on Android 8+ to avoid ANR.
        if (!isStarted) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                isStarted = true

                // Auto-start FTP if enabled
                if (RemotePrefs.isFtpAutoStart(this)) {
                    ftpManager.start(this)
                    RemotePrefs.setFtpEnabled(this, true)
                }
            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "startForeground failed", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Handle notification button actions — fire even when screen is off
        when (intent?.action) {
            ACTION_APPROVE_REQUEST -> {
                val reqId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (reqId != null) {
                    resolveRequest(reqId, true, this)
                }
            }
            ACTION_DENY_REQUEST -> {
                val reqId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (reqId != null) {
                    resolveRequest(reqId, false, this)
                }
            }
            ACTION_NEXT          -> executeBridgeAction("next")
            ACTION_PREVIOUS      -> executeBridgeAction("previous")
            ACTION_START         -> executeBridgeAction("start")
            ACTION_STOP_SHOW     -> executeBridgeAction("stop")
            ACTION_TOGGLE_FTP    -> {
                val homeDir = intent.getStringExtra(EXTRA_FTP_HOME_DIR)
                // If homeDir is null, it's a toggle request from the main switch
                if (homeDir == null) {
                    if (ftpManager.isRunning()) {
                        ftpManager.stop()
                        RemotePrefs.setFtpEnabled(this, false)
                    } else {
                        ftpManager.start(this)
                        RemotePrefs.setFtpEnabled(this, true)
                    }
                } 
                // If homeDir is provided, it's a path switch request from a storage button
                else {
                    // Always start/restart on the new path without stopping first
                    ftpManager.start(this, homeDir = homeDir)
                    RemotePrefs.setFtpEnabled(this, true)
                }
            }
            ACTION_STOP_SERVICE  -> {
                ftpManager.stop()
                webFileServer?.stop()
                webFileServer = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WEB_SERVER -> {
                val rootDir = intent.getStringExtra(EXTRA_WEB_ROOT_DIR) ?: return START_STICKY
                val pin = intent.getStringExtra(EXTRA_WEB_PIN).orEmpty()
                val current = webFileServer
                if (current != null && current.isRunning()) {
                    current.stop()
                    webFileServer = null
                    RemotePrefs.setWebServerEnabled(this, false)
                } else {
                    current?.stop()
                    val port = RemotePrefs.getWebServerPort(this)
                    val customFolder = RemotePrefs.getWebServerSharedFolder(this)
                    val allowedRoots = if (customFolder != null) {
                        listOf(customFolder)
                    } else {
                        ftpManager.getStorageVolumes(this).map { it.path }
                    }
                    val srv = WebFileServer(this, rootDir, pin, port, allowedRoots)
                    if (srv.start()) {
                        webFileServer = srv
                        RemotePrefs.setWebServerEnabled(this, true)
                        android.util.Log.i("RemoteControlService", "Web server started at port ${srv.port}")
                    } else {
                        android.util.Log.e("RemoteControlService", "Web server failed to start")
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ftpManager.stop()
        webFileServer?.stop()
        webFileServer = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        releaseWakeLock()
    }

    // -------------------------------------------------------------------------
    // MediaSession — routes volume key events from lock screen / background
    // -------------------------------------------------------------------------
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "PptRemoteSession").apply {
            // Accept media button events (volume keys on lock screen)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            // Publish a non-null playback state so the session is considered active
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE
                )
                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
            setPlaybackState(state)

            setCallback(object : MediaSessionCompat.Callback() {
                // Volume Up → Previous slide
                override fun onSkipToPrevious() {
                    android.util.Log.d("RemoteControlService", "MediaSession: skip to previous")
                    executeBridgeAction("previous")
                }

                // Volume Down → Next slide
                override fun onSkipToNext() {
                    android.util.Log.d("RemoteControlService", "MediaSession: skip to next")
                    executeBridgeAction("next")
                }

                override fun onPlay() { executeBridgeAction("start") }
                override fun onPause() { executeBridgeAction("stop") }

                // Handle raw key events for volume buttons on lock screen
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                executeBridgeAction("previous")
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                executeBridgeAction("next")
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            isActive = true
        }
    }

    // -------------------------------------------------------------------------
    // Bridge actions — run on IO thread, work regardless of Activity state
    // -------------------------------------------------------------------------
    private fun executeBridgeAction(command: String) {
        serviceScope.launch {
            try {
                val bridgeUrl = resolveBridgeUrl() ?: run {
                    android.util.Log.w("RemoteControlService", "No bridge URL for action '$command'")
                    return@launch
                }
                // Sync API key from prefs into client
                client.apiKey = RemotePrefs.getApiKey(this@RemoteControlService)

                val presentationId = resolvePresentationId(bridgeUrl) ?: run {
                    android.util.Log.w("RemoteControlService", "No presentation for action '$command'")
                    return@launch
                }

                when (command) {
                    "next"     -> client.next(bridgeUrl, presentationId)
                    "previous" -> client.previous(bridgeUrl, presentationId)
                    "start"    -> client.startSlideshow(bridgeUrl, presentationId)
                    "stop"     -> client.stopSlideshow(bridgeUrl, presentationId)
                }

                // Brief delay to let PowerPoint update, then refresh notification text
                delay(600)
                updateNotificationWithSlideInfo(bridgeUrl, presentationId)

            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "Bridge action '$command' failed", e)
            }
        }
    }

    private fun resolveBridgeUrl(): String? {
        // Multi-bridge: use the active bridge URL
        val stored = RemotePrefs.getActiveBridgeUrl(this).trim()
        if (stored.isNotBlank()) return stored
        
        val discovered = client.discoverBridge(
            timeoutMs = 3000,
            discoveryPort = RemotePrefs.getBridgePort(this) + 1
        )
        val first = discovered.firstOrNull()
        if (first != null) {
            RemotePrefs.setBridgeUrl(this, first.url)
            RemotePrefs.setSelectedBridgeId(this, first.id)
            return first.url
        }
        return null
    }

    private fun resolvePresentationId(bridgeUrl: String): String? {
        val stored = RemotePrefs.getSelectedPresentationId(this)
        val presentations = try {
            client.fetchPresentations(bridgeUrl)
        } catch (e: Exception) {
            emptyList()
        }
        if (stored != null && presentations.any { it.id == stored }) return stored
        val selected = presentations.firstOrNull { it.inSlideshow }?.id
            ?: presentations.firstOrNull()?.id
        RemotePrefs.setSelectedPresentationId(this, selected)
        return selected
    }

    // -------------------------------------------------------------------------
    // Wake lock
    // -------------------------------------------------------------------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(10 * 60 * 1000L) }
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Wake lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Wake lock release failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PowerPoint Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background slide controls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun pendingServiceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, RemoteControlService::class.java).also { it.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createNotification(slideInfo: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // MediaSession token lets the lock screen show media controls
        val sessionToken = mediaSession?.sessionToken

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PowerPoint Remote")
            .setContentText(slideInfo ?: RemotePrefs.getNotificationText(this))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setColor(ContextCompat.getColor(this, R.color.ios_accent))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_previous, "⏮ Prev",  pendingServiceIntent(ACTION_PREVIOUS, 10))
            .addAction(R.drawable.ic_next,     "⏭ Next",  pendingServiceIntent(ACTION_NEXT,     11))
            .addAction(R.drawable.ic_play,     "▶ Start", pendingServiceIntent(ACTION_START,    12))
            .addAction(R.drawable.ic_stop,     "⏹ Exit",  pendingServiceIntent(ACTION_STOP_SERVICE, 13))

        // Attach MediaSession so Android routes lock-screen volume keys to our session
        if (sessionToken != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1) // show Prev + Next in compact view
            )
        }

        return builder.build()
    }

    private fun updateNotificationWithSlideInfo(bridgeUrl: String, presentationId: String) {
        try {
            val presentations = client.fetchPresentations(bridgeUrl)
            val pres = presentations.find { it.id == presentationId }

            val slideInfo = if (pres != null) {
                val slidePart = if (pres.currentSlide != null)
                    "Slide ${pres.currentSlide}/${pres.totalSlides}"
                else
                    "${pres.totalSlides} slides"
                "${pres.name} • $slidePart"
            } else {
                RemotePrefs.getNotificationText(this)
            }

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, createNotification(slideInfo))

        } catch (e: Exception) {
            android.util.Log.w("RemoteControlService", "Notification slide update failed", e)
        }
    }
}
