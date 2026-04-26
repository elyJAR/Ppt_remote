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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RemoteControlService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BridgeClient()
    private var wakeLock: PowerManager.WakeLock? = null
    private var isStarted = false

    companion object {
        private const val CHANNEL_ID        = "ppt_remote_service"
        private const val NOTIFICATION_ID   = 1
        private const val WAKE_LOCK_TAG     = "PptRemote::ServiceWakeLock"

        const val ACTION_NEXT          = "com.antigravity.pptremote.action.NEXT"
        const val ACTION_PREVIOUS      = "com.antigravity.pptremote.action.PREVIOUS"
        const val ACTION_START         = "com.antigravity.pptremote.action.START"
        const val ACTION_STOP_SHOW     = "com.antigravity.pptremote.action.STOP_SHOW"
        const val ACTION_STOP_SERVICE  = "com.antigravity.pptremote.action.STOP_SERVICE"

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground first — required on Android 8+ to avoid ANR.
        // On subsequent calls (action intents) this simply updates the notification.
        if (!isStarted) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                isStarted = true
            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "startForeground failed", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Handle notification button actions — these fire even when screen is off
        when (intent?.action) {
            ACTION_NEXT          -> executeBridgeAction("next")
            ACTION_PREVIOUS      -> executeBridgeAction("previous")
            ACTION_START         -> executeBridgeAction("start")
            ACTION_STOP_SHOW     -> executeBridgeAction("stop")
            ACTION_STOP_SERVICE  -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
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
        val stored = RemotePrefs.getBridgeUrl(this).trim()
        if (stored.isNotBlank()) return stored
        val discovered = client.discoverBridge(3000, RemotePrefs.getBridgePort(this) + 1)
        if (!discovered.isNullOrBlank()) {
            RemotePrefs.setBridgeUrl(this, discovered)
            return discovered
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
                .apply { acquire(10 * 60 * 1000L) } // 10-minute timeout safety cap
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
                // Allow notification on lock screen
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PowerPoint Remote")
            .setContentText(slideInfo ?: RemotePrefs.getNotificationText(this))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show on lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_previous, "⏮ Prev",  pendingServiceIntent(ACTION_PREVIOUS, 10))
            .addAction(R.drawable.ic_next,     "⏭ Next",  pendingServiceIntent(ACTION_NEXT,     11))
            .addAction(R.drawable.ic_play,     "▶ Start", pendingServiceIntent(ACTION_START,    12))
            .addAction(R.drawable.ic_stop,     "⏹ Exit",  pendingServiceIntent(ACTION_STOP_SERVICE, 13))
            .build()
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
