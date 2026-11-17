package com.theunwindfront.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Foreground service that keeps playback alive in the background and shows the media notification.
 * Notification buttons go through the MediaSession, so they behave like lock-screen controls.
 */
class AudioService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controls = AudioFunctions.getMediaController(this)?.transportControls
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (AudioFunctions.isPlayingNow()) controls?.pause() else controls?.play()
            }
            ACTION_NEXT -> controls?.skipToNext()
            ACTION_PREVIOUS -> controls?.skipToPrevious()
            ACTION_STOP -> controls?.stop()
            else -> {
                val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
                // startForeground must always be called after startForegroundService, even if we stop right away.
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundServiceType)
                if (AudioFunctions.mediaPlayer == null) {
                    // Nothing is playing (e.g. playback stopped before the service started).
                    stopSelf()
                }
            }
        }
        // Don't let the system restart the service after the process is killed: there would be
        // no player behind the notification.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for background audio playback"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AudioService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playing = AudioFunctions.isPlayingNow()

        val style = MediaStyle()
            .setMediaSession(AudioFunctions.getSessionToken(this))
            .setShowActionsInCompactView(1) // play/pause

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AudioFunctions.notificationTitle())
            .setContentText(AudioFunctions.notificationArtist() ?: "")
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 4))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(style)
            .addAction(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(ACTION_PREVIOUS, 2))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                servicePendingIntent(ACTION_TOGGLE, 1)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", servicePendingIntent(ACTION_NEXT, 3))

        AudioFunctions.currentArtwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    internal fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "audio_playback_channel"
        const val ACTION_TOGGLE = "com.theunwindfront.audio.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.theunwindfront.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.theunwindfront.audio.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.theunwindfront.audio.ACTION_STOP"

        @Volatile
        private var instance: AudioService? = null

        val isRunning: Boolean get() = instance != null

        /** Starts the foreground service (title/artist are read from the current metadata). */
        @Suppress("UNUSED_PARAMETER")
        fun start(context: Context, title: String, artist: String? = null) {
            val intent = Intent(context, AudioService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioService::class.java))
        }

        /** Rebuilds the notification if the service is running; never starts it. */
        fun refreshState() {
            val service = instance ?: return
            Handler(Looper.getMainLooper()).post { service.refreshNotification() }
        }

        /** Rebuilds the notification if the service is running. Kept for backwards compatibility. */
        @Suppress("UNUSED_PARAMETER")
        fun refreshState(context: Context) = refreshState()
    }
}
