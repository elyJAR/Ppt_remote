package com.antigravity.pptremote

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
import kotlinx.coroutines.launch

class RemoteControlService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BridgeClient()
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "ppt_remote_service"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "PptRemote::ServiceWakeLock"

        const val ACTION_NEXT     = "com.antigravity.pptremote.action.NEXT"
        const val ACTION_PREVIOUS = "com.antigravity.pptremote.action.PREVIOUS"
        const val ACTION_START    = "com.antigravity.pptremote.action.START"
        const val ACTION_STOP_SHOW = "com.antigravity.pptremote.action.STOP_SHOW"

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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification button actions — these fire even when screen is off
        when (intent?.action) {
            ACTION_NEXT      -> executeBridgeAction("next")
            ACTION_PREVIOUS  -> executeBridgeAction("previous")
            ACTION_START     -> executeBridgeAction("start")
            ACTION_STOP_SHOW -> executeBridgeAction("stop")
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "startForeground failed", e)
            stopSelf()
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
                val bridgeUrl = resolveBridgeUrl() ?: return@launch
                val presentationId = resolvePresentationId(bridgeUrl) ?: return@launch
                when (command) {
                    "next"     -> client.next(bridgeUrl, presentationId)
                    "previous" -> client.previous(bridgeUrl, presentationId)
                    "start"    -> client.startSlideshow(bridgeUrl, presentationId)
                    "stop"     -> client.stopSlideshow(bridgeUrl, presentationId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "Bridge action '$command' failed", e)
            }
        }
    }

    private fun resolveBridgeUrl(): String? {
        val stored = RemotePrefs.getBridgeUrl(this).trim()
        if (stored.isNotBlank()) return stored
        val discovered = client.discoverBridge()
        if (!discovered.isNullOrBlank()) {
            RemotePrefs.setBridgeUrl(this, discovered)
            return discovered
        }
        return null
    }

    private fun resolvePresentationId(bridgeUrl: String): String? {
        val stored = RemotePrefs.getSelectedPresentationId(this)
        val presentations = try { client.fetchPresentations(bridgeUrl) } catch (e: Exception) { emptyList() }
        if (stored != null && presentations.any { it.id == stored }) return stored
        val selected = presentations.firstOrNull { it.inSlideshow }?.id ?: presentations.firstOrNull()?.id
        RemotePrefs.setSelectedPresentationId(this, selected)
        return selected
    }

    // -------------------------------------------------------------------------
    // Wake lock
    // -------------------------------------------------------------------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { acquire() }
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Wake lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        catch (e: Exception) { android.util.Log.e("RemoteControlService", "Wake lock release failed", e) }
    }

    // -------------------------------------------------------------------------
    // Notification with Previous / Next / Start / Stop buttons
    // These buttons work on the lock screen and when the screen is off
    // -------------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PowerPoint Remote Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background slide controls" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode,
            Intent(this, RemoteControlService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PowerPoint Remote")
            .setContentText("Tap ⏮ ⏭ to change slides — works with screen off")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "⏮ Prev",  serviceIntent(ACTION_PREVIOUS, 10))
            .addAction(android.R.drawable.ic_media_next,     "⏭ Next",  serviceIntent(ACTION_NEXT, 11))
            .addAction(android.R.drawable.ic_media_play,     "▶ Start", serviceIntent(ACTION_START, 12))
            .addAction(android.R.drawable.ic_media_pause,    "⏹ Stop",  serviceIntent(ACTION_STOP_SHOW, 13))
            .build()
    }
}
