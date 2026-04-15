package com.theunwindfront.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.lifecycle.NativePHPLifecycle
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
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
        internal var currentArtwork: Bitmap? = null

        // Playlist
        private val playlist = mutableListOf<Map<String, Any>>()
        private var playlistIndex = -1
        private var repeatMode = "none"
        private var shuffleMode = false
        private var shuffledOrder = mutableListOf<Int>()

        // Settings & Monitoring
        private var isBuffering = false
        private var isInBackground = false
        private val pendingEvents = mutableListOf<Map<String, Any>>()
        private var playbackRate = 1.0f
        private var progressIntervalMs = 1000L

        // Handlers
        private var progressHandler: Handler? = null
        private var progressRunnable: Runnable? = null
        private var sleepTimerHandler: Handler? = null
        private var sleepTimerRunnable: Runnable? = null
        private var audioManager: AudioManager? = null
        private var audioFocusRequest: AudioFocusRequest? = null
        private var pausedByFocusLoss = false
        private var isDucked = false

        init {
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { isInBackground = false }
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { isInBackground = true }
        }

        // ── Internal Helpers ──────────────────────────────────────────────────

        private const val EVENT_PREFIX = "Theunwindfront\\Audio\\Events\\"

        private fun sendEvent(name: String, payload: Map<String, Any>) {
            val eventFullName = "$EVENT_PREFIX$name"
            if (isInBackground) {
                pendingEvents.add(mapOf("event" to eventFullName, "payload" to payload))
                return
            }
            activityRef?.get()?.let { activity ->
                Handler(Looper.getMainLooper()).post {
                    NativeActionCoordinator.dispatchEvent(activity, eventFullName, JSONObject(payload).toString())
                }
            }
        }

        private fun trackPayload(): Map<String, Any> {
            val map = mutableMapOf<String, Any>("url" to currentUrl)
            metaTitle?.let { map["title"] = it }
            metaArtist?.let { map["artist"] = it }
            metaAlbum?.let { map["album"] = it }
            metaDurationMs?.let { map["duration"] = it / 1000.0 }
            metaArtworkSource?.let { map["artwork"] = it }
            metaClip?.let { map["clip"] = it }
            metaMetadata?.let { map["metadata"] = it }
            return map
        }

        private fun statePayload(): Map<String, Any> = mapOf(
            "track" to trackPayload(),
            "position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0,
            "duration" to (mediaPlayer?.duration ?: 0) / 1000.0,
            "isPlaying" to (mediaPlayer?.isPlaying ?: false),
            "isBuffering" to isBuffering,
            "playbackRate" to playbackRate,
            "repeatMode" to repeatMode,
            "shuffleMode" to shuffleMode,
            "playlistIndex" to playlistIndex,
            "playlistTotal" to playlist.size
        )

        private fun buildMetadata(): MediaMetadataCompat {
            val builder = MediaMetadataCompat.Builder()
            metaTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
            metaArtist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            metaAlbum?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            metaDurationMs?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            return builder.build()
        }

        private fun updateSessionState() {
            val session = mediaSession ?: return
            val playing = mediaPlayer?.isPlaying == true
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    mediaPlayer?.currentPosition?.toLong() ?: 0L,
                    if (playing) playbackRate else 0.0f
                )
                .build()
            session.setPlaybackState(state)
        }

        fun getOrCreateSession(context: Context): MediaSessionCompat {
            mediaSession?.let { return it }
            val session = MediaSessionCompat(context, "NativePHPAudio")
            session.isActive = true
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumeInternal() }
                override fun onPause() { pauseInternal() }
                override fun onSkipToNext() { nextTrackInternal() }
                override fun onSkipToPrevious() { previousTrackInternal() }
                override fun onSeekTo(pos: Long) { seekInternal(pos / 1000.0) }
                override fun onStop() { stopInternal() }
            })
            mediaSession = session
            return session
        }

        fun getSessionToken(context: Context): MediaSessionCompat.Token = getOrCreateSession(context).sessionToken

        // ── Logic Implementation ──────────────────────────────────────────────

        private fun resumeInternal() {
            mediaPlayer?.start()
            applyRate()
            updateSessionState()
            startProgressTimer()
            appContext?.let { AudioService.refreshState(it) }
            sendEvent("PlaybackResumed", statePayload())
            sendEvent("RemotePlayReceived", statePayload())
        }

        private fun pauseInternal() {
            mediaPlayer?.pause()
            updateSessionState()
            stopProgressTimer()
            appContext?.let { AudioService.refreshState(it) }
            sendEvent("PlaybackPaused", statePayload())
            sendEvent("RemotePauseReceived", statePayload())
        }

        private fun stopInternal() {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            stopProgressTimer()
            cancelSleepTimerInternal()
            abandonAudioFocus()
            appContext?.let { AudioService.stop(it) }
            sendEvent("PlaybackStopped", statePayload())
            sendEvent("RemoteStopReceived", statePayload())
        }

        private fun seekInternal(seconds: Double) {
            val from = (mediaPlayer?.currentPosition ?: 0) / 1000.0
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            updateSessionState()
            sendEvent("PlaybackSeeked", mapOf("from" to from, "to" to seconds))
            sendEvent("RemoteSeekReceived", mapOf("from" to from, "to" to seconds))
        }

        private fun applyRate() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mediaPlayer?.playbackParams = PlaybackParams().setSpeed(playbackRate)
                } catch (_: Exception) {}
            }
        }

        private fun startProgressTimer() {
            stopProgressTimer()
            val handler = Handler(Looper.getMainLooper())
            progressHandler = handler
            progressRunnable = object : Runnable {
                override fun run() {
                    if (mediaPlayer?.isPlaying == true) {
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

        private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    isDucked = false
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        resumeInternal()
                    }
                    sendEvent("AudioFocusGained", statePayload())
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (mediaPlayer?.isPlaying == true) {
                        pausedByFocusLoss = true
                        pauseInternal()
                        sendEvent(if (focusChange == AudioManager.AUDIOFOCUS_LOSS) "AudioFocusLost" else "AudioFocusLostTransient", statePayload())
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    isDucked = true
                    mediaPlayer?.setVolume(0.2f, 0.2f)
                    sendEvent("AudioFocusDucked", statePayload())
                }
            }
        }

        private fun requestAudioFocus(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
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
        }

        // ── Playlist Implementation ───────────────────────────────────────────

        private fun effectiveIndex(idx: Int): Int =
            if (shuffleMode && shuffledOrder.isNotEmpty()) shuffledOrder.getOrNull(idx) ?: idx else idx

        internal fun playTrackAtInternal(index: Int, autoStart: Boolean = true) {
            if (index !in playlist.indices) return
            val context = activityRef?.get() ?: appContext ?: return
            
            val lastIdx = playlistIndex
            val lastPos = (mediaPlayer?.currentPosition ?: 0) / 1000.0
            val lastTrack = if (lastIdx >= 0) playlist.getOrNull(effectiveIndex(lastIdx)) else null

            playlistIndex = index
            val track = playlist[effectiveIndex(index)]
            val url = track["url"] as? String ?: return
            
            // Sync metadata
            currentUrl = url
            metaTitle = track["title"] as? String
            metaArtist = track["artist"] as? String
            metaAlbum = track["album"] as? String
            metaDurationMs = (track["duration"] as? Number)?.toLong()?.let { it * 1000 }
            if (track["artwork"] as? String != metaArtworkSource) currentArtwork = null
            metaArtworkSource = track["artwork"] as? String
            metaClip = track["clip"] as? String
            @Suppress("UNCHECKED_CAST")
            metaMetadata = track["metadata"] as? Map<String, Any>

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
                setDataSource(context, Uri.parse(url))
                
                setOnPreparedListener { mp ->
                    if (autoStart) {
                        requestAudioFocus(context)
                        mp.start()
                        applyRate()
                        startProgressTimer()
                        AudioService.start(context, metaTitle ?: "Now Playing", metaArtist)
                        sendEvent("PlaybackStarted", statePayload())
                    } else {
                        sendEvent("PlaybackLoaded", statePayload())
                    }
                    
                    val changedPayload = mutableMapOf<String, Any>(
                        "index" to index,
                        "track" to trackPayload()
                    )
                    if (lastIdx >= 0) {
                        changedPayload["lastIndex"] = lastIdx
                        changedPayload["lastPosition"] = lastPos
                        lastTrack?.let { changedPayload["lastTrack"] = it }
                    }
                    sendEvent("PlaylistTrackChanged", changedPayload)
                    
                    mediaSession?.setMetadata(buildMetadata())
                    updateSessionState()
                }

                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> { isBuffering = true; sendEvent("PlaybackBuffering", statePayload()) }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> { isBuffering = false; sendEvent("PlaybackReady", statePayload()) }
                    }
                    true
                }

                setOnCompletionListener {
                    sendEvent("PlaybackCompleted", statePayload())
                    nextTrackInternal(reason = "auto")
                }

                setOnErrorListener { _, what, extra ->
                    sendEvent("PlaybackFailed", mapOf("track" to trackPayload(), "error" to "what: $what extra: $extra"))
                    false
                }

                prepareAsync()
            }

            // Load Artwork Async
            metaArtworkSource?.let { src ->
                Thread {
                    try {
                        val bitmap = if (src.startsWith("http")) BitmapFactory.decodeStream(URL(src).openStream()) else BitmapFactory.decodeFile(src)
                        Handler(Looper.getMainLooper()).post {
                            currentArtwork = bitmap
                            mediaSession?.setMetadata(buildMetadata())
                            AudioService.refreshState(context)
                        }
                    } catch (_: Exception) {}
                }.start()
            }
        }

        private fun nextTrackInternal(reason: String = "user") {
            if (playlist.isEmpty()) return
            when (repeatMode) {
                "one" -> playTrackAtInternal(playlistIndex)
                "all" -> playTrackAtInternal((playlistIndex + 1) % playlist.size)
                else -> {
                    if (playlistIndex < playlist.size - 1) playTrackAtInternal(playlistIndex + 1)
                    else sendEvent("PlaylistEnded", statePayload())
                }
            }
            if (reason == "user") sendEvent("RemoteNextTrackReceived", statePayload())
        }

        private fun previousTrackInternal() {
            if (playlist.isNotEmpty() && playlistIndex > 0) {
                playTrackAtInternal(playlistIndex - 1)
            }
            sendEvent("RemotePreviousTrackReceived", statePayload())
        }

        private fun cancelSleepTimerInternal() {
            sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
            sleepTimerHandler = null
            sleepTimerRunnable = null
        }

        private fun regenerateShuffleOrder() {
            shuffledOrder = (0 until playlist.size).toMutableList()
            if (shuffleMode) shuffledOrder.shuffle()
        }

        // ── Bridge Functions ──────────────────────────────────────────────────

        class Play(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playlist.clear()
                playlistIndex = -1
                val track = parameters.toMutableMap()
                track["url"] = parameters["url"] as? String ?: return mapOf("success" to false)
                playlist.add(track)
                playTrackAtInternal(0)
                return mapOf("success" to true)
            }
        }

        class Load(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playlist.clear()
                playlistIndex = -1
                val track = parameters.toMutableMap()
                track["url"] = parameters["url"] as? String ?: return mapOf("success" to false)
                playlist.add(track)
                playTrackAtInternal(0, autoStart = false)
                return mapOf("success" to true)
            }
        }

        class GetState(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                return statePayload()
            }
        }

        class SetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val items = parameters["items"] as? List<Map<String, Any>> ?: return mapOf("success" to false)
                playlist.clear()
                playlist.addAll(items)
                regenerateShuffleOrder()
                val startIdx = (parameters["startIndex"] as? Number)?.toInt() ?: 0
                if (parameters["autoPlay"] as? Boolean != false) playTrackAtInternal(startIdx)
                sendEvent("PlaylistSet", mapOf("total" to playlist.size))
                return mapOf("success" to true)
            }
        }

        class SkipTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
                playTrackAtInternal(idx)
                return mapOf("success" to true)
            }
        }

        class GetTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
                val track = playlist.getOrNull(effectiveIndex(idx)) ?: return mapOf("success" to false)
                return mapOf("track" to track)
            }
        }

        class GetActiveTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                return if (playlistIndex >= 0) mapOf("track" to trackPayload()) else emptyMap()
            }
        }

        class GetActiveTrackIndex(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                return if (playlistIndex >= 0) mapOf("index" to playlistIndex) else emptyMap()
            }
        }

        class GetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                return mapOf(
                    "items" to playlist,
                    "index" to playlistIndex,
                    "total" to playlist.size,
                    "repeatMode" to repeatMode,
                    "shuffleMode" to shuffleMode
                )
            }
        }

        class SetRepeatMode(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                repeatMode = parameters["mode"] as? String ?: "none"
                sendEvent("PlaylistRepeatModeChanged", mapOf("mode" to repeatMode))
                return mapOf("success" to true)
            }
        }

        class SetShuffleMode(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                shuffleMode = parameters["shuffle"] as? Boolean ?: false
                regenerateShuffleOrder()
                sendEvent("PlaylistShuffleChanged", mapOf("shuffle" to shuffleMode))
                return mapOf("success" to true)
            }
        }

        class AppendTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val track = parameters["track"] as? Map<String, Any> ?: return mapOf("success" to false)
                playlist.add(track)
                regenerateShuffleOrder()
                return mapOf("success" to true)
            }
        }

        class RemoveTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val idx = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
                if (idx in playlist.indices) {
                    playlist.removeAt(idx)
                    if (playlistIndex == idx) stopInternal()
                    else if (playlistIndex > idx) playlistIndex--
                    regenerateShuffleOrder()
                }
                return mapOf("success" to true)
            }
        }

        class DrainEvents(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                val events = JSONArray()
                pendingEvents.forEach { events.put(JSONObject(it)) }
                pendingEvents.clear()
                return mapOf("events" to events)
            }
        }

        class SetSleepTimer(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val seconds = (parameters["seconds"] as? Number)?.toLong() ?: 0L
                cancelSleepTimerInternal()
                if (seconds > 0) {
                    val handler = Handler(Looper.getMainLooper())
                    sleepTimerHandler = handler
                    sleepTimerRunnable = Runnable { stopInternal(); sendEvent("SleepTimerExpired", emptyMap()) }
                    handler.postDelayed(sleepTimerRunnable!!, seconds * 1000)
                }
                return mapOf("success" to true)
            }
        }

        class CancelSleepTimer(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                cancelSleepTimerInternal()
                return mapOf("success" to true)
            }
        }

        class SetProgressInterval(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val seconds = (parameters["seconds"] as? Number)?.toDouble() ?: 1.0
                progressIntervalMs = (seconds * 1000).toLong()
                if (mediaPlayer?.isPlaying == true) startProgressTimer()
                return mapOf("success" to true)
            }
        }

        // Standard Bridge Wrappers
        class Pause(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { pauseInternal(); return mapOf("success" to true) } }
        class Resume(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { resumeInternal(); return mapOf("success" to true) } }
        class Stop(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { stopInternal(); return mapOf("success" to true) } }
        class NextTrack(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { nextTrackInternal(); return mapOf("success" to true) } }
        class PreviousTrack(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { previousTrackInternal(); return mapOf("success" to true) } }
        class Seek(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { seekInternal((p["seconds"] as? Number)?.toDouble() ?: 0.0); return mapOf("success" to true) } }
        class SetVolume(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(p: Map<String, Any>): Map<String, Any> {
                val lv = (p["level"] as? Number)?.toFloat() ?: 1.0f
                mediaPlayer?.setVolume(lv, lv)
                return mapOf("success" to true)
            }
        }
        class SetPlaybackRate(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(p: Map<String, Any>): Map<String, Any> {
                playbackRate = (p["rate"] as? Number)?.toFloat() ?: 1.0f
                applyRate()
                updateSessionState()
                return mapOf("success" to true)
            }
        }
        class GetDuration(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { return mapOf("duration" to (mediaPlayer?.duration ?: 0) / 1000.0) } }
        class GetCurrentPosition(private val activity: FragmentActivity) : BridgeFunction { override fun execute(p: Map<String, Any>): Map<String, Any> { return mapOf("position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0) } }
        class SetMetadata(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(p: Map<String, Any>): Map<String, Any> {
                metaTitle = p["title"] as? String
                metaArtist = p["artist"] as? String
                metaAlbum = p["album"] as? String
                metaDurationMs = (p["duration"] as? Number)?.toLong()?.let { it * 1000 }
                mediaSession?.setMetadata(buildMetadata())
                return mapOf("success" to true)
            }
        }
    }
}
