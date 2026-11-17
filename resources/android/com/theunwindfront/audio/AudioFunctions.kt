package com.theunwindfront.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.lifecycle.NativePHPLifecycle
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * AudioFunctions handles bridge calls and manages native playback state.
 */
class AudioFunctions {
    companion object {
        internal var mediaPlayer: MediaPlayer? = null
        internal var mediaSession: MediaSessionCompat? = null
        internal var activityRef: WeakReference<FragmentActivity>? = null
        internal var appContext: Context? = null

        // ── State ─────────────────────────────────────────────────────────────
        internal var currentUrl = ""
        private var metaTitle: String? = null
        private var metaArtist: String? = null
        private var metaAlbum: String? = null
        private var metaDurationMs: Long? = null
        private var metaArtworkSource: String? = null
        private var metaClip: String? = null
        private var metaMetadata: Map<String, Any>? = null
        private var isStream: Boolean = false
        private var streamMountpoint: String? = null
        internal var currentArtwork: Bitmap? = null
        private var currentArtworkSource: String? = null
        private var isPrepared = false

        // Playlist. playlistIndex is a position in the play order; shuffledOrder[position] is
        // the index into `playlist` (identity order when shuffle is off).
        private val playlist = mutableListOf<Map<String, Any>>()
        private var playlistIndex = -1
        private var repeatMode = "none"
        private var shuffleMode = false
        private var shuffledOrder = mutableListOf<Int>()

        // Settings & Monitoring
        private var isBuffering = false
        private var isInBackground = false
        private val pendingEvents = mutableListOf<Map<String, Any>>()
        private const val MAX_PENDING_EVENTS = 200
        private var lastPlaybackState: Pair<Boolean, Boolean>? = null
        private var playbackRate = 1.0f
        private var progressIntervalMs = 1000L
        private var userVolume = 1.0f

        // Handlers
        private var progressHandler: Handler? = null
        private var progressRunnable: Runnable? = null
        private var sleepTimerHandler: Handler? = null
        private var sleepTimerRunnable: Runnable? = null
        private var audioManager: AudioManager? = null
        private var audioFocusRequest: AudioFocusRequest? = null
        private var pausedByFocusLoss = false
        private var isDucked = false
        private var wifiLock: WifiManager.WifiLock? = null
        private var noisyReceiverRegistered = false

        init {
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) {
                isInBackground = false
                flushPendingEvents()
            }
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { isInBackground = true }
        }

        // ── Events ────────────────────────────────────────────────────────────

        private const val EVENT_PREFIX = "Theunwindfront\\Audio\\Events\\"

        private fun sendEvent(name: String, payload: Map<String, Any>) {
            val eventFullName = "$EVENT_PREFIX$name"
            if (isInBackground) {
                // Progress updates are only useful live, so they aren't queued.
                if (name == "PlaybackProgressUpdated") return
                if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeAt(0)
                pendingEvents.add(mapOf("event" to eventFullName, "payload" to payload))
                return
            }
            dispatch(eventFullName, payload)
        }

        private fun dispatch(eventFullName: String, payload: Map<String, Any>) {
            val activity = activityRef?.get() ?: return
            val json = JSONObject(payload).toString()
            Handler(Looper.getMainLooper()).post {
                NativeActionCoordinator.dispatchEvent(activity, eventFullName, json)
            }
        }

        /** Delivers events queued while the app was in the background. */
        private fun flushPendingEvents() {
            if (pendingEvents.isEmpty() || activityRef?.get() == null) return
            val events = pendingEvents.toList()
            pendingEvents.clear()
            events.forEach { event ->
                @Suppress("UNCHECKED_CAST")
                val payload = event["payload"] as? Map<String, Any> ?: emptyMap()
                dispatch(event["event"] as String, payload)
            }
        }

        /** Sends PlaybackStateChanged when playing/buffering changes (or metadata changed). */
        private fun notifyStateChanged(metadataChanged: Boolean = false) {
            val state = Pair(isPlayingNow(), isBuffering)
            if (!metadataChanged && state == lastPlaybackState) return
            lastPlaybackState = state
            sendEvent("PlaybackStateChanged", statePayload() + ("metadataChanged" to metadataChanged))
        }

        // ── Safe player accessors ─────────────────────────────────────────────
        // MediaPlayer throws or enters its error state when queried before it's prepared.

        internal fun isPlayingNow(): Boolean =
            try { isPrepared && mediaPlayer?.isPlaying == true } catch (_: IllegalStateException) { false }

        private fun positionSeconds(): Double =
            try { if (isPrepared) (mediaPlayer?.currentPosition ?: 0) / 1000.0 else 0.0 } catch (_: IllegalStateException) { 0.0 }

        private fun durationSeconds(): Double =
            try {
                if (isPrepared && !isStream) max(0, mediaPlayer?.duration ?: 0) / 1000.0 else 0.0
            } catch (_: IllegalStateException) { 0.0 }

        private fun effectiveVolume(): Float = if (isDucked) userVolume * 0.2f else userVolume

        private fun applyVolume() {
            val v = effectiveVolume()
            try { mediaPlayer?.setVolume(v, v) } catch (_: IllegalStateException) {}
        }

        // ── Payloads ──────────────────────────────────────────────────────────

        private fun trackPayload(): Map<String, Any> {
            val map = mutableMapOf<String, Any>("url" to currentUrl)
            metaTitle?.let { map["title"] = it }
            metaArtist?.let { map["artist"] = it }
            metaAlbum?.let { map["album"] = it }
            metaDurationMs?.let { map["duration"] = it / 1000.0 }
            metaArtworkSource?.let { map["artwork"] = it }
            metaClip?.let { map["clip"] = it }
            metaMetadata?.let { map["metadata"] = it }
            map["isStream"] = isStream
            if (isStream) map["streamType"] = "radio"
            streamMountpoint?.let { map["mountpoint"] = it }
            return map
        }

        private fun statePayload(): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "track" to trackPayload(),
                "position" to positionSeconds(),
                "duration" to durationSeconds(),
                "isPlaying" to isPlayingNow(),
                "isBuffering" to isBuffering,
                "playbackRate" to playbackRate,
                "repeatMode" to repeatMode,
                "shuffleMode" to shuffleMode,
                "playlistIndex" to playlistIndex,
                "playlistTotal" to playlist.size
            )
            map["isStream"] = isStream
            if (isStream) map["streamType"] = "radio"
            streamMountpoint?.let { map["mountpoint"] = it }
            return map
        }

        // ── Notification / MediaSession ───────────────────────────────────────

        internal fun notificationTitle(): String = metaTitle ?: if (isStream) "Live Stream" else "Now Playing"
        internal fun notificationArtist(): String? = metaArtist

        private fun buildMetadata(): MediaMetadataCompat {
            val builder = MediaMetadataCompat.Builder()
            builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, notificationTitle())
            metaArtist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            metaAlbum?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            if (!isStream) {
                val durationMs = metaDurationMs ?: (durationSeconds() * 1000).toLong().takeIf { it > 0 }
                durationMs?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            }
            currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            return builder.build()
        }

        private fun updateSessionState() {
            val session = mediaSession ?: return
            val playing = isPlayingNow()
            var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
            if (!isStream) {
                actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
                if (playlist.size > 1) {
                    actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                }
            }
            val state = when {
                mediaPlayer == null -> PlaybackStateCompat.STATE_STOPPED
                isBuffering -> PlaybackStateCompat.STATE_BUFFERING
                playing -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            }
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, (positionSeconds() * 1000).toLong(), if (playing) playbackRate else 0.0f)
                    .build()
            )
        }

        /** Pushes metadata + state to the MediaSession and the foreground notification. */
        private fun refreshSession() {
            mediaSession?.setMetadata(buildMetadata())
            updateSessionState()
            AudioService.refreshState()
        }

        fun getOrCreateSession(context: Context): MediaSessionCompat {
            mediaSession?.let { return it }
            val session = MediaSessionCompat(context.applicationContext, "NativePHPAudio")
            session.isActive = true
            // These callbacks come from the lock screen, notification, headset or Bluetooth.
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumeInternal(remote = true) }
                override fun onPause() { pauseInternal(remote = true) }
                override fun onSkipToNext() { nextTrackInternal(remote = true) }
                override fun onSkipToPrevious() { previousTrackInternal(remote = true) }
                override fun onSeekTo(pos: Long) { seekInternal(pos / 1000.0, remote = true) }
                override fun onStop() { stopInternal(remote = true) }
            })
            mediaSession = session
            mediaSession?.setMetadata(buildMetadata())
            updateSessionState()
            return session
        }

        fun getSessionToken(context: Context): MediaSessionCompat.Token = getOrCreateSession(context).sessionToken

        fun getMediaController(context: Context): MediaControllerCompat? {
            val token = getOrCreateSession(context).sessionToken
            return MediaControllerCompat(context, token)
        }

        // ── Transport ─────────────────────────────────────────────────────────

        /** Starts the media foreground service if it isn't running yet (e.g. resuming after load()). */
        private fun ensureForegroundService(context: Context) {
            if (AudioService.isRunning) return
            try {
                AudioService.start(context, notificationTitle(), metaArtist)
            } catch (_: Exception) {
                // Android 12+ can refuse to start a foreground service from the background.
            }
        }

        private fun resumeInternal(remote: Boolean = false) {
            val player = mediaPlayer ?: return
            if (!isPrepared) return
            val context = appContext
            context?.let { requestAudioFocus(it); registerNoisyReceiver(it) }
            try { player.start() } catch (_: IllegalStateException) { return }
            applyRate()
            acquireWifiLockIfNeeded()
            startProgressTimer()
            context?.let { ensureForegroundService(it) }
            refreshSession()
            sendEvent("PlaybackResumed", statePayload())
            if (remote) sendEvent("RemotePlayReceived", statePayload())
            notifyStateChanged()
        }

        private fun pauseInternal(remote: Boolean = false) {
            val player = mediaPlayer ?: return
            try { if (isPrepared && player.isPlaying) player.pause() } catch (_: IllegalStateException) {}
            stopProgressTimer()
            releaseWifiLock()
            refreshSession()
            sendEvent("PlaybackPaused", statePayload())
            if (remote) sendEvent("RemotePauseReceived", statePayload())
            notifyStateChanged()
        }

        private fun stopInternal(remote: Boolean = false) {
            val payload = statePayload()
            releasePlayer()
            isStream = false
            streamMountpoint = null
            stopProgressTimer()
            cancelSleepTimerInternal()
            abandonAudioFocus()
            unregisterNoisyReceiver()
            updateSessionState()
            appContext?.let { AudioService.stop(it) }
            sendEvent("PlaybackStopped", payload)
            if (remote) sendEvent("RemoteStopReceived", payload)
            notifyStateChanged()
        }

        private fun seekInternal(seconds: Double, remote: Boolean = false) {
            val player = mediaPlayer ?: return
            if (!isPrepared) return
            val from = positionSeconds()
            val to = max(0.0, seconds)
            try { player.seekTo((to * 1000).toInt()) } catch (_: IllegalStateException) { return }
            updateSessionState()
            val payload = mapOf("from" to from, "to" to to)
            sendEvent("PlaybackSeeked", payload)
            if (remote) sendEvent("RemoteSeekReceived", payload)
        }

        private fun applyRate() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val player = mediaPlayer ?: return
                    if (isPrepared && player.isPlaying) {
                        player.playbackParams = player.playbackParams.setSpeed(playbackRate)
                    }
                } catch (_: Exception) {}
            }
        }

        private fun startProgressTimer() {
            stopProgressTimer()
            val handler = Handler(Looper.getMainLooper())
            progressHandler = handler
            progressRunnable = object : Runnable {
                override fun run() {
                    if (isPlayingNow()) {
                        sendEvent("PlaybackProgressUpdated", statePayload())
                    }
                    handler.postDelayed(this, progressIntervalMs)
                }
            }.also { handler.postDelayed(it, progressIntervalMs) }
        }

        private fun stopProgressTimer() {
            progressRunnable?.let { progressHandler?.removeCallbacks(it) }
            progressHandler = null
            progressRunnable = null
        }

        // ── Audio focus ───────────────────────────────────────────────────────

        private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    isDucked = false
                    applyVolume()
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        resumeInternal()
                    }
                    sendEvent("AudioFocusGained", statePayload())
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (isPlayingNow()) {
                        // Only resume automatically after a short (transient) interruption.
                        pausedByFocusLoss = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        pauseInternal()
                        sendEvent(if (focusChange == AudioManager.AUDIOFOCUS_LOSS) "AudioFocusLost" else "AudioFocusLostTransient", statePayload())
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    isDucked = true
                    applyVolume()
                    sendEvent("AudioFocusDucked", statePayload())
                }
            }
        }

        private fun requestAudioFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }

        private fun abandonAudioFocus() {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(focusListener)
                }
            }
            audioFocusRequest = null
            audioManager = null
            pausedByFocusLoss = false
            isDucked = false
        }

        // ── Headphones unplugged ──────────────────────────────────────────────

        private val noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlayingNow()) {
                    pauseInternal()
                }
            }
        }

        private fun registerNoisyReceiver(context: Context) {
            if (noisyReceiverRegistered) return
            ContextCompat.registerReceiver(
                context.applicationContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }

        private fun unregisterNoisyReceiver() {
            if (!noisyReceiverRegistered) return
            try { appContext?.unregisterReceiver(noisyReceiver) } catch (_: IllegalArgumentException) {}
            noisyReceiverRegistered = false
        }

        // ── Wi-Fi lock (keeps network streams alive with the screen off) ──────

        private fun acquireWifiLockIfNeeded() {
            if (!(currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) return
            val context = appContext ?: return
            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                @Suppress("DEPRECATION")
                val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NativePHPAudio")
                lock.setReferenceCounted(false)
                wifiLock = lock
            }
            try { wifiLock?.takeIf { !it.isHeld }?.acquire() } catch (_: Exception) {}
        }

        private fun releaseWifiLock() {
            try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        }

        // ── Player lifecycle ──────────────────────────────────────────────────

        private fun releasePlayer() {
            isPrepared = false
            isBuffering = false
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            releaseWifiLock()
        }

        private fun reportFailure(error: String) {
            isBuffering = false
            stopProgressTimer()
            sendEvent("PlaybackFailed", mapOf("track" to trackPayload(), "error" to error))
            updateSessionState()
            notifyStateChanged()
        }

        /**
         * Creates a MediaPlayer for [url] and prepares it asynchronously.
         * [onPrepared] runs once it's ready (after playback starts when [autoStart] is true).
         */
        private fun startPlayer(context: Context, url: String, autoStart: Boolean, onPrepared: () -> Unit) {
            releasePlayer()
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
                // Keeps the CPU awake during playback with the screen off.
                player.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://") || url.startsWith("file://")) {
                    player.setDataSource(context, Uri.parse(url))
                } else {
                    player.setDataSource(url)
                }
            } catch (e: Exception) {
                player.release()
                reportFailure(e.message ?: e.javaClass.simpleName)
                return
            }
            mediaPlayer = player

            player.setOnPreparedListener { mp ->
                if (mediaPlayer !== mp) return@setOnPreparedListener
                isPrepared = true
                applyVolume()
                if (autoStart) {
                    requestAudioFocus(context)
                    registerNoisyReceiver(context)
                    mp.start()
                    applyRate()
                    acquireWifiLockIfNeeded()
                    startProgressTimer()
                    ensureForegroundService(context)
                }
                onPrepared()
                refreshSession()
                notifyStateChanged()
            }

            player.setOnInfoListener { mp, what, _ ->
                if (mediaPlayer === mp) {
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            isBuffering = true
                            sendEvent("PlaybackBuffering", statePayload())
                            updateSessionState()
                            notifyStateChanged()
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            isBuffering = false
                            sendEvent("PlaybackReady", statePayload())
                            updateSessionState()
                            notifyStateChanged()
                        }
                    }
                }
                true
            }

            player.setOnCompletionListener { mp ->
                if (mediaPlayer !== mp) return@setOnCompletionListener
                sendEvent("PlaybackCompleted", statePayload())
                if (isStream) {
                    updateSessionState()
                    notifyStateChanged()
                } else {
                    nextTrackInternal(reason = "auto")
                }
            }

            // Returning true stops MediaPlayer from also calling the completion listener,
            // which would otherwise skip to the next track on every error.
            player.setOnErrorListener { mp, what, extra ->
                if (mediaPlayer === mp) {
                    isPrepared = false
                    reportFailure("what: $what extra: $extra")
                }
                true
            }

            try {
                player.prepareAsync()
            } catch (e: IllegalStateException) {
                releasePlayer()
                reportFailure(e.message ?: "Could not prepare player")
            }
        }

        private fun loadArtworkAsync() {
            val src = metaArtworkSource
            if (src == null) {
                currentArtwork = null
                currentArtworkSource = null
                return
            }
            if (src == currentArtworkSource && currentArtwork != null) return
            currentArtwork = null
            Thread {
                val bitmap = try {
                    if (src.startsWith("http://") || src.startsWith("https://")) {
                        val connection = URL(src).openConnection() as HttpURLConnection
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        try {
                            connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        } finally {
                            connection.disconnect()
                        }
                    } else {
                        BitmapFactory.decodeFile(src)
                    }
                } catch (_: Exception) { null }
                Handler(Looper.getMainLooper()).post {
                    if (bitmap != null && metaArtworkSource == src) {
                        currentArtwork = bitmap
                        currentArtworkSource = src
                        refreshSession()
                    }
                }
            }.start()
        }

        // ── Playlist ──────────────────────────────────────────────────────────

        private fun effectiveIndex(position: Int): Int =
            if (shuffledOrder.size == playlist.size) shuffledOrder.getOrNull(position) ?: position else position

        /** The playlist index of the current track, or -1. */
        private fun currentTrackIndex(): Int =
            if (playlistIndex in playlist.indices) effectiveIndex(playlistIndex) else -1

        /**
         * Rebuilds the play order. When shuffling, [anchor] (default: the current track) is moved to
         * the front so the playing track doesn't change; playlistIndex is updated to match.
         */
        private fun rebuildOrder(anchor: Int = currentTrackIndex()) {
            val order = playlist.indices.toMutableList()
            if (shuffleMode) {
                order.shuffle()
                if (anchor in playlist.indices) {
                    order.remove(anchor)
                    order.add(0, anchor)
                }
            }
            shuffledOrder = order
            if (playlistIndex >= 0 && anchor in playlist.indices) {
                playlistIndex = order.indexOf(anchor)
            }
        }

        internal fun playTrackAtInternal(index: Int, autoStart: Boolean = true, reason: String = "user") {
            if (index !in playlist.indices) return
            val context = activityRef?.get() ?: appContext ?: return

            val lastIdx = playlistIndex
            val lastPos = positionSeconds()
            val lastTrack = if (lastIdx in playlist.indices) playlist.getOrNull(effectiveIndex(lastIdx)) else null

            playlistIndex = index
            isStream = false
            streamMountpoint = null
            val track = playlist[effectiveIndex(index)]

            metaTitle = track["title"] as? String
            metaArtist = track["artist"] as? String
            metaAlbum = track["album"] as? String
            metaDurationMs = (track["duration"] as? Number)?.let { (it.toDouble() * 1000).toLong() }
            metaArtworkSource = track["artwork"] as? String
            metaClip = track["clip"] as? String
            @Suppress("UNCHECKED_CAST")
            metaMetadata = track["metadata"] as? Map<String, Any>

            val url = (track["url"] as? String)?.trim()
            if (url.isNullOrEmpty()) {
                currentUrl = ""
                releasePlayer()
                reportFailure("Missing or invalid track url")
                return
            }
            currentUrl = url

            startPlayer(context, url, autoStart) {
                val changedPayload = mutableMapOf<String, Any>(
                    "index" to index,
                    "reason" to reason,
                    "track" to trackPayload()
                )
                if (lastIdx >= 0) {
                    changedPayload["lastIndex"] = lastIdx
                    changedPayload["lastPosition"] = lastPos
                    lastTrack?.let { changedPayload["lastTrack"] = it }
                }
                sendEvent("PlaylistTrackChanged", changedPayload)
                sendEvent(if (autoStart) "PlaybackStarted" else "PlaybackLoaded", statePayload())
            }

            loadArtworkAsync()
        }

        private fun nextTrackInternal(reason: String = "user", remote: Boolean = false) {
            if (playlist.isNotEmpty()) {
                when {
                    reason == "auto" && repeatMode == "one" -> playTrackAtInternal(max(playlistIndex, 0), reason = "repeat")
                    playlistIndex < playlist.size - 1 -> playTrackAtInternal(playlistIndex + 1, reason = reason)
                    repeatMode == "all" -> playTrackAtInternal(0, reason = reason)
                    reason == "auto" -> {
                        sendEvent("PlaylistEnded", statePayload())
                        updateSessionState()
                        notifyStateChanged()
                    }
                }
            }
            if (remote) sendEvent("RemoteNextTrackReceived", statePayload())
        }

        private fun previousTrackInternal(remote: Boolean = false) {
            if (playlistIndex > 0) {
                playTrackAtInternal(playlistIndex - 1)
            } else if (repeatMode == "all" && playlist.isNotEmpty()) {
                playTrackAtInternal(playlist.size - 1)
            }
            if (remote) sendEvent("RemotePreviousTrackReceived", statePayload())
        }

        private fun cancelSleepTimerInternal() {
            sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
            sleepTimerHandler = null
            sleepTimerRunnable = null
        }

        private fun applyMetadata(p: Map<String, Any>, includeClassicalFields: Boolean = true) {
            (p["title"] as? String)?.let { metaTitle = it }
            (p["artist"] as? String)?.let { metaArtist = it }
            (p["album"] as? String)?.let { metaAlbum = it }
            if (includeClassicalFields) {
                (p["duration"] as? Number)?.let { metaDurationMs = (it.toDouble() * 1000).toLong() }
            }
            (p["artwork"] as? String)?.let { metaArtworkSource = it }
            if (includeClassicalFields) {
                (p["clip"] as? String)?.let { metaClip = it }
            }
            @Suppress("UNCHECKED_CAST")
            (p["metadata"] as? Map<String, Any>)?.let { metaMetadata = it }
        }

        private fun resolveStreamUrl(source: String, parameters: Map<String, Any>): String? {
            if (source.startsWith("http://") || source.startsWith("https://")) return source
            if (source.contains("://")) return null
            val server = (parameters["serverUrl"] as? String
                ?: parameters["serverURL"] as? String
                ?: parameters["baseUrl"] as? String
                ?: parameters["baseURL"] as? String
                ?: parameters["host"] as? String)?.trim()
                ?: return null
            if (!server.startsWith("http://") && !server.startsWith("https://")) return null
            val base = if (server.endsWith("/")) server else "$server/"
            val mount = if (source.startsWith("/")) source.substring(1) else source
            return "$base$mount"
        }

        /**
         * Runs [block] on the main thread and waits for the result. Bridge calls arrive on the PHP
         * thread, while MediaPlayer callbacks, timers and focus changes run on the main thread, so
         * all player state is only touched there.
         */
        private fun <T> onMain(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val latch = CountDownLatch(1)
            var result: Result<T>? = null
            Handler(Looper.getMainLooper()).post {
                result = runCatching(block)
                latch.countDown()
            }
            if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Audio bridge call timed out")
            return result!!.getOrThrow()
        }

        private fun remember(activity: FragmentActivity) {
            activityRef = WeakReference(activity)
            appContext = activity.applicationContext
        }

        // ── Bridge function implementations ───────────────────────────────────

        internal fun play(activity: FragmentActivity, parameters: Map<String, Any>, autoStart: Boolean): Map<String, Any> {
            remember(activity)
            val url = parameters["url"] as? String ?: return mapOf("success" to false, "error" to "Missing url")
            playlist.clear()
            playlist.add(parameters.toMutableMap().apply { this["url"] = url })
            shuffledOrder = mutableListOf(0)
            playlistIndex = -1
            playTrackAtInternal(0, autoStart = autoStart)
            return mapOf("success" to true)
        }

        internal fun playStream(activity: FragmentActivity, parameters: Map<String, Any>): Map<String, Any> {
            val mountpoint = ((parameters["mountpoint"] as? String)
                ?: (parameters["url"] as? String))?.trim()
                ?: return mapOf("success" to false, "error" to "Invalid stream mountpoint or URL")

            val url = resolveStreamUrl(mountpoint, parameters)
                ?: return mapOf("success" to false, "error" to "Invalid stream mountpoint or URL")

            remember(activity)
            playlist.clear()
            shuffledOrder = mutableListOf()
            playlistIndex = -1
            isStream = true
            streamMountpoint = mountpoint
            metaDurationMs = null
            currentUrl = url

            metaTitle = parameters["title"] as? String
            metaArtist = parameters["artist"] as? String
            metaAlbum = parameters["album"] as? String
            metaArtworkSource = parameters["artwork"] as? String
            metaClip = null
            @Suppress("UNCHECKED_CAST")
            metaMetadata = parameters["metadata"] as? Map<String, Any>

            startPlayer(activity, url, autoStart = true) {
                sendEvent("PlaybackStarted", statePayload())
            }
            loadArtworkAsync()
            return mapOf("success" to true)
        }

        internal fun setPlaylist(activity: FragmentActivity, parameters: Map<String, Any>): Map<String, Any> {
            remember(activity)
            @Suppress("UNCHECKED_CAST")
            val items = parameters["items"] as? List<Map<String, Any>> ?: return mapOf("success" to false)
            playlist.clear()
            playlist.addAll(items)
            playlistIndex = -1
            val startIdx = (parameters["startIndex"] as? Number)?.toInt() ?: 0
            rebuildOrder(anchor = startIdx)
            sendEvent("PlaylistSet", mapOf("total" to playlist.size))
            if (parameters["autoPlay"] as? Boolean != false) {
                // With shuffle on, the start track was moved to the front of the play order.
                playTrackAtInternal(if (shuffleMode) 0 else startIdx)
            }
            return mapOf("success" to true)
        }

        internal fun appendTrack(parameters: Map<String, Any>): Map<String, Any> {
            @Suppress("UNCHECKED_CAST")
            val track = parameters["track"] as? Map<String, Any> ?: return mapOf("success" to false)
            playlist.add(track)
            val newIndex = playlist.size - 1
            when {
                shuffledOrder.size != newIndex -> rebuildOrder()
                // Insert somewhere after the current track so it plays later in this shuffle.
                shuffleMode && playlistIndex >= 0 -> shuffledOrder.add((playlistIndex + 1..shuffledOrder.size).random(), newIndex)
                else -> shuffledOrder.add(newIndex)
            }
            updateSessionState()
            return mapOf("success" to true)
        }

        internal fun removeTrack(parameters: Map<String, Any>): Map<String, Any> {
            val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
            if (idx !in playlist.indices) return mapOf("success" to false)
            val current = currentTrackIndex()
            playlist.removeAt(idx)
            shuffledOrder = shuffledOrder.filter { it != idx }.map { if (it > idx) it - 1 else it }.toMutableList()
            if (current == idx) {
                stopInternal()
                playlistIndex = -1
            } else if (current >= 0) {
                playlistIndex = shuffledOrder.indexOf(if (current > idx) current - 1 else current)
            }
            updateSessionState()
            return mapOf("success" to true)
        }

        internal fun setShuffleMode(parameters: Map<String, Any>): Map<String, Any> {
            shuffleMode = parameters["shuffle"] as? Boolean ?: false
            rebuildOrder()
            sendEvent("PlaylistShuffleChanged", mapOf("shuffle" to shuffleMode))
            return mapOf("success" to true)
        }

        internal fun setRepeatMode(parameters: Map<String, Any>): Map<String, Any> {
            val mode = parameters["mode"] as? String ?: "none"
            repeatMode = if (mode in listOf("none", "one", "all")) mode else "none"
            sendEvent("PlaylistRepeatModeChanged", mapOf("mode" to repeatMode))
            return mapOf("success" to true)
        }

        internal fun getTrack(parameters: Map<String, Any>): Map<String, Any> {
            val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
            val track = playlist.getOrNull(effectiveIndex(idx)) ?: return mapOf("success" to false)
            return mapOf("track" to track)
        }

        internal fun getActiveTrack(): Map<String, Any> =
            if (playlistIndex >= 0 || isStream) mapOf("track" to trackPayload()) else emptyMap()

        internal fun getActiveTrackIndex(): Map<String, Any> =
            if (playlistIndex >= 0) mapOf("index" to playlistIndex) else emptyMap()

        internal fun getPlaylist(): Map<String, Any> = mapOf(
            "items" to playlist.toList(),
            "index" to playlistIndex,
            "total" to playlist.size,
            "repeatMode" to repeatMode,
            "shuffleMode" to shuffleMode
        )

        internal fun getState(activity: FragmentActivity): Map<String, Any> {
            remember(activity)
            return statePayload()
        }

        internal fun drainEvents(activity: FragmentActivity): Map<String, Any> {
            remember(activity)
            val events = JSONArray()
            pendingEvents.forEach { events.put(JSONObject(it)) }
            pendingEvents.clear()
            return mapOf("events" to events)
        }

        internal fun setSleepTimer(parameters: Map<String, Any>): Map<String, Any> {
            val seconds = (parameters["seconds"] as? Number)?.toDouble() ?: 0.0
            cancelSleepTimerInternal()
            if (seconds > 0) {
                val handler = Handler(Looper.getMainLooper())
                val runnable = Runnable {
                    sleepTimerRunnable = null
                    sleepTimerHandler = null
                    stopInternal()
                    sendEvent("SleepTimerExpired", emptyMap())
                }
                sleepTimerHandler = handler
                sleepTimerRunnable = runnable
                handler.postDelayed(runnable, (seconds * 1000).toLong())
            }
            return mapOf("success" to true)
        }

        internal fun cancelSleepTimer(): Map<String, Any> {
            cancelSleepTimerInternal()
            return mapOf("success" to true)
        }

        internal fun setProgressInterval(parameters: Map<String, Any>): Map<String, Any> {
            val seconds = max((parameters["seconds"] as? Number)?.toDouble() ?: 1.0, 0.1)
            progressIntervalMs = (seconds * 1000).toLong()
            if (isPlayingNow()) startProgressTimer()
            return mapOf("success" to true)
        }

        internal fun pause(): Map<String, Any> { pauseInternal(); return mapOf("success" to true) }
        internal fun resume(): Map<String, Any> { resumeInternal(); return mapOf("success" to true) }
        internal fun stop(): Map<String, Any> { stopInternal(); return mapOf("success" to true) }
        internal fun next(): Map<String, Any> { nextTrackInternal(); return mapOf("success" to true) }
        internal fun previous(): Map<String, Any> { previousTrackInternal(); return mapOf("success" to true) }

        internal fun playTrackAt(parameters: Map<String, Any>): Map<String, Any> {
            val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
            if (idx !in playlist.indices) return mapOf("success" to false)
            playTrackAtInternal(idx)
            return mapOf("success" to true)
        }

        internal fun seek(parameters: Map<String, Any>): Map<String, Any> {
            val seconds = (parameters["seconds"] as? Number)?.toDouble() ?: return mapOf("success" to false)
            seekInternal(seconds)
            return mapOf("success" to true)
        }

        internal fun setVolume(parameters: Map<String, Any>): Map<String, Any> {
            val level = (parameters["level"] as? Number)?.toFloat() ?: return mapOf("success" to false)
            userVolume = min(max(level, 0f), 1f)
            applyVolume()
            return mapOf("success" to true)
        }

        internal fun setPlaybackRate(parameters: Map<String, Any>): Map<String, Any> {
            val rate = (parameters["rate"] as? Number)?.toFloat() ?: return mapOf("success" to false)
            playbackRate = min(max(rate, 0.25f), 4.0f)
            applyRate()
            updateSessionState()
            return mapOf("success" to true)
        }

        internal fun getDuration(): Map<String, Any> = mapOf("duration" to durationSeconds())
        internal fun getCurrentPosition(): Map<String, Any> = mapOf("position" to positionSeconds())

        internal fun setMetadata(activity: FragmentActivity, parameters: Map<String, Any>): Map<String, Any> {
            remember(activity)
            applyMetadata(parameters)
            loadArtworkAsync()
            refreshSession()
            notifyStateChanged(metadataChanged = true)
            return mapOf("success" to true)
        }

        internal fun updateStreamMetadata(activity: FragmentActivity, parameters: Map<String, Any>): Map<String, Any> {
            remember(activity)
            applyMetadata(parameters, includeClassicalFields = false)
            loadArtworkAsync()
            refreshSession()
            sendEvent("StreamMetadataChanged", mapOf("track" to trackPayload(), "metadata" to parameters))
            return mapOf("success" to true)
        }
    }

    // ── Bridge functions (registered in nativephp.json) ──────────────────────

    class Play(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { play(activity, parameters, autoStart = true) }
    }

    class Load(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { play(activity, parameters, autoStart = false) }
    }

    class PlayStream(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { playStream(activity, parameters) }
    }

    class PlayTrackAt(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { playTrackAt(parameters) }
    }

    class SkipTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { playTrackAt(parameters) }
    }

    class GetState(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getState(activity) }
    }

    class SetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setPlaylist(activity, parameters) }
    }

    class GetTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getTrack(parameters) }
    }

    class GetActiveTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getActiveTrack() }
    }

    class GetActiveTrackIndex(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getActiveTrackIndex() }
    }

    class GetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getPlaylist() }
    }

    class SetRepeatMode(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setRepeatMode(parameters) }
    }

    class SetShuffleMode(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setShuffleMode(parameters) }
    }

    class AppendTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { appendTrack(parameters) }
    }

    class RemoveTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { removeTrack(parameters) }
    }

    class DrainEvents(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { drainEvents(activity) }
    }

    class SetSleepTimer(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setSleepTimer(parameters) }
    }

    class CancelSleepTimer(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { cancelSleepTimer() }
    }

    class SetProgressInterval(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setProgressInterval(parameters) }
    }

    class Pause(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { pause() }
    }

    class Resume(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { resume() }
    }

    class Stop(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { stop() }
    }

    class NextTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { next() }
    }

    class PreviousTrack(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { previous() }
    }

    class Seek(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { seek(parameters) }
    }

    class SetVolume(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setVolume(parameters) }
    }

    class SetPlaybackRate(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setPlaybackRate(parameters) }
    }

    class GetDuration(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getDuration() }
    }

    class GetCurrentPosition(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { getCurrentPosition() }
    }

    class SetMetadata(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { setMetadata(activity, parameters) }
    }

    class UpdateStreamMetadata(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = onMain { updateStreamMetadata(activity, parameters) }
    }
}
