package com.antigravity.pptremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    private var mediaSession: MediaSession? = null

    companion object {
        private const val CHANNEL_ID = "ppt_remote_service"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "PptRemote::ServiceWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour; re-acquired each time the service starts

        private const val ACTION_NEXT = "com.antigravity.pptremote.action.NEXT"
        private const val ACTION_PREVIOUS = "com.antigravity.pptremote.action.PREVIOUS"
        private const val ACTION_START = "com.antigravity.pptremote.action.START"
        private const val ACTION_STOP_SHOW = "com.antigravity.pptremote.action.STOP_SHOW"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    android.util.Log.w("RemoteControlService", "POST_NOTIFICATIONS permission not granted")
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
            val intent = Intent(context, RemoteControlService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        initializeMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), serviceType)
            when (intent?.action) {
                ACTION_NEXT -> executeBridgeAction("next")
                ACTION_PREVIOUS -> executeBridgeAction("previous")
                ACTION_START -> executeBridgeAction("start")
                ACTION_STOP_SHOW -> executeBridgeAction("stop")
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Failed to start foreground", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
    }

    private fun initializeMediaSession() {
        val volumeProvider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    AudioManager.ADJUST_RAISE -> executeBridgeAction("next")
                    AudioManager.ADJUST_LOWER -> executeBridgeAction("previous")
                }
            }
        }

        mediaSession = MediaSession(this, "PptRemoteSession").apply {
            setPlaybackToRemote(volumeProvider)
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun executeBridgeAction(command: String) {
        serviceScope.launch {
            try {
                val bridgeUrl = resolveBridgeUrl() ?: return@launch
                val presentationId = resolvePresentationId(bridgeUrl) ?: return@launch

                when (command) {
                    "next" -> client.next(bridgeUrl, presentationId)
                    "previous" -> client.previous(bridgeUrl, presentationId)
                    "start" -> client.startSlideshow(bridgeUrl, presentationId)
                    "stop" -> client.stopSlideshow(bridgeUrl, presentationId)
                }
            } catch (e: Exception) {
                android.util.Log.e("RemoteControlService", "Bridge action failed: $command", e)
            }
        }
    }

    private fun resolveBridgeUrl(): String? {
        val stored = RemotePrefs.getBridgeUrl(this).trim()
        if (stored.isNotBlank()) {
            return stored
        }

        val discovered = client.discoverBridge()
        if (!discovered.isNullOrBlank()) {
            RemotePrefs.setBridgeUrl(this, discovered)
            return discovered
        }
        return null
    }

    private fun resolvePresentationId(bridgeUrl: String): String? {
        val stored = RemotePrefs.getSelectedPresentationId(this)
        val presentations = client.fetchPresentations(bridgeUrl)

        if (stored != null && presentations.any { it.id == stored }) {
            return stored
        }

        val selected = presentations.firstOrNull { it.inSlideshow }?.id ?: presentations.firstOrNull()?.id
        RemotePrefs.setSelectedPresentationId(this, selected)
        return selected
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("RemoteControlService", "Failed to release wake lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PowerPoint Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PowerPoint remote controls active in the background"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, RemoteControlService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, RemoteControlService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopShowIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, RemoteControlService::class.java).setAction(ACTION_STOP_SHOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val startShowIntent = PendingIntent.getService(
            this,
            13,
            Intent(this, RemoteControlService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PowerPoint Remote")
            .setContentText("Background controls active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_media_play, "Start", startShowIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopShowIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
