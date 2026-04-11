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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

class AudioService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH_STATE -> {
                refreshNotification()
            }
            else -> {
                intent?.getStringExtra(EXTRA_TITLE)?.let { currentTitle = it }
                intent?.getStringExtra(EXTRA_ARTIST)?.let { currentArtist = it }
                
                val notification = buildNotification()
                
                val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }

                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionToken = AudioFunctions.getSessionToken(this)
        val style = MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0) // Show play/pause in compact view

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist ?: "")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(style)

        AudioFunctions.currentArtwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val NOTIFICATION_ID = 1338
        const val CHANNEL_ID = "audio_playback_channel"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val ACTION_REFRESH_STATE = "com.theunwindfront.audio.ACTION_REFRESH_STATE"

        var currentTitle: String = "Now Playing"
        var currentArtist: String? = null

        fun start(context: Context, title: String, artist: String? = null) {
            currentTitle = title
            currentArtist = artist
            val intent = Intent(context, AudioService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                artist?.let { putExtra(EXTRA_ARTIST, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioService::class.java))
        }

        fun refreshState(context: Context) {
            val intent = Intent(context, AudioService::class.java).apply {
                action = ACTION_REFRESH_STATE
            }
            context.startService(intent)
        }

        /** Update notification text when new metadata arrives. */
        fun updateNotification(context: Context, title: String, artist: String? = null) {
            start(context, title, artist)
        }
    }
}

