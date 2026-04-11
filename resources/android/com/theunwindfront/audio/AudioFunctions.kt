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
 * AudioFunctions handles all bridge calls for audio playback on Android.
 * It manages a MediaPlayer, MediaSession, and AudioFocus natively to ensure
 * stable background playback and integration with OS media controls.
 */
class AudioFunctions {
    companion object {
        private var mediaPlayer: MediaPlayer? = null
        private var mediaSession: MediaSessionCompat? = null
        private var activityRef: WeakReference<FragmentActivity>? = null
        private var appContext: Context? = null

        // ── State Management ──────────────────────────────────────────────────
        private var currentUrl: String = ""
        private var metaTitle: String? = null
        private var metaArtist: String? = null
        private var metaAlbum: String? = null
        private var metaDurationMs: Long? = null
        private var metaArtworkSource: String? = null
        internal var currentArtwork: Bitmap? = null

        // Playlist state
        private val playlist: MutableList<Map<String, Any>> = mutableListOf()
        private var playlistIndex: Int = -1
        private var repeatMode: String = "none" // none, one, all
        private var shuffleMode: Boolean = false
        private var shuffledOrder: MutableList<Int> = mutableListOf()

        // Sync & Buffering
        private var isBuffering: Boolean = false
        private var isInBackground: Boolean = false
        private val pendingEvents: MutableList<Map<String, Any>> = mutableListOf()
        private var playbackRate: Float = 1.0f
        private var progressIntervalMs: Long = 1000L // 1 second default

        // Timers & Audio Focus
        private var progressHandler: Handler? = null
        private var progressRunnable: Runnable? = null
        private var sleepTimerHandler: Handler? = null
        private var sleepTimerRunnable: Runnable? = null
        private var audioManager: AudioManager? = null
        private var audioFocusRequest: AudioFocusRequest? = null
        private var pausedByFocusLoss = false

        init {
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { isInBackground = false }
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_PAUSE) { isInBackground = true }
        }

        // ── Bridge Support ────────────────────────────────────────────────────

        private fun sendEvent(name: String, payload: Map<String, Any>) {
            val eventName = "Theunwindfront\\Audio\\Events\\$name"
            if (isInBackground) {
                pendingEvents.add(mapOf("name" to eventName, "payload" to payload))
                return
            }

            activityRef?.get()?.let { activity ->
                Handler(Looper.getMainLooper()).post {
                    NativeActionCoordinator.dispatchEvent(activity, eventName, JSONObject(payload).toString())
                }
            }
        }

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
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    mediaPlayer?.currentPosition?.toLong() ?: 0L,
                    if (playing) playbackRate else 0.0f
                )
                .build()
            session.setPlaybackState(state)
        }

        private fun syncNowPlaying(context: Context) {
            val session = getOrCreateSession(context)
            session.setMetadata(buildMetadata())
            updateSessionState()
        }

        fun getOrCreateSession(context: Context): MediaSessionCompat {
            mediaSession?.let { return it }
            val session = MediaSessionCompat(context, "NativePHPAudio")
            session.isActive = true
            session.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlay(true) }
                override fun onPause() { togglePlay(false) }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onSeekTo(pos: Long) { seekTo(pos / 1000.0) }
                override fun onStop() { stopPlayback() }
            })
            mediaSession = session
            return session
        }

        fun getSessionToken(context: Context): MediaSessionCompat.Token = getOrCreateSession(context).sessionToken

        // ── Player Commands ───────────────────────────────────────────────────

        private fun togglePlay(play: Boolean) {
            if (play) {
                mediaPlayer?.start()
                applyRate()
                startProgressTimer()
                sendEvent("PlaybackStarted", mapOf("url" to currentUrl))
            } else {
                mediaPlayer?.pause()
                stopProgressTimer()
                sendEvent("PlaybackPaused", emptyMap())
            }
            updateSessionState()
            appContext?.let { AudioService.refreshState(it) }
        }

        private fun seekTo(seconds: Double) {
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            updateSessionState()
        }

        private fun stopPlayback() {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            stopProgressTimer()
            cancelSleepTimer()
            abandonAudioFocus()
            appContext?.let { AudioService.stop(it) }
            sendEvent("PlaybackStopped", emptyMap())
        }

        private fun applyRate() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mediaPlayer?.playbackParams = PlaybackParams().setSpeed(playbackRate)
                } catch (e: Exception) {}
            }
        }

        // ── Audio Focus ───────────────────────────────────────────────────────

        private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        togglePlay(true)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (mediaPlayer?.isPlaying == true) {
                        pausedByFocusLoss = true
                        togglePlay(false)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.2f, 0.2f)
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
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }

        private fun abandonAudioFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(focusChangeListener)
            }
            audioFocusRequest = null
            audioManager = null
        }

        // ── Playlist Logic ────────────────────────────────────────────────────

        private fun getLogicalIndex(index: Int): Int {
            if (!shuffleMode || shuffledOrder.isEmpty()) return index
            return shuffledOrder.getOrNull(index) ?: index
        }

        private fun regenerateShuffleOrder() {
            shuffledOrder = (0 until playlist.size).toMutableList()
            if (shuffleMode) shuffledOrder.shuffle()
        }

        private fun playTrackAt(index: Int) {
            if (index < 0 || index >= playlist.size) return
            playlistIndex = index
            val track = playlist[getLogicalIndex(index)]
            val url = track["url"] as? String ?: return

            // Setup metadata from track
            metaTitle = track["title"] as? String
            metaArtist = track["artist"] as? String
            metaAlbum = track["album"] as? String
            metaDurationMs = (track["duration"] as? Number)?.toLong()?.let { it * 1000 }
            metaArtworkSource = track["artwork"] as? String
            currentArtwork = null

            prepareAndPlay(url)
        }

        fun playNext() {
            if (playlist.isEmpty()) return
            when (repeatMode) {
                "one" -> playTrackAt(playlistIndex)
                "all" -> playTrackAt((playlistIndex + 1) % playlist.size)
                else -> if (playlistIndex < playlist.size - 1) playTrackAt(playlistIndex + 1) else sendEvent("PlaylistEnded", emptyMap())
            }
        }

        fun playPrevious() {
            if (playlist.isNotEmpty() && playlistIndex > 0) playTrackAt(playlistIndex - 1)
        }

        private fun prepareAndPlay(url: String, autoStart: Boolean = true) {
            val context = appContext ?: return
            currentUrl = url
            
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
                        sendEvent("PlaybackStarted", mapOf("url" to url))
                    } else {
                        sendEvent("PlaybackLoaded", mapOf("url" to url))
                    }
                    syncNowPlaying(context)
                    AudioService.updateNotification(context, metaTitle ?: "Now Playing", metaArtist)
                }

                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            isBuffering = true
                            sendEvent("PlaybackBuffering", mapOf("url" to url))
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            isBuffering = false
                            sendEvent("PlaybackReady", mapOf("url" to url))
                        }
                    }
                    true
                }

                setOnCompletionListener {
                    sendEvent("PlaybackCompleted", mapOf("url" to url))
                    playNext()
                }

                setOnErrorListener { _, what, extra ->
                    sendEvent("PlaybackFailed", mapOf("url" to url, "error" to "what: $what, extra: $extra"))
                    false
                }

                prepareAsync()
            }
            
            // Background Artwork Loading
            metaArtworkSource?.let { source ->
                Thread {
                    try {
                        val bitmap = if (source.startsWith("http")) {
                            BitmapFactory.decodeStream(URL(source).openStream())
                        } else {
                            BitmapFactory.decodeFile(source)
                        }
                        Handler(Looper.getMainLooper()).post {
                            currentArtwork = bitmap
                            syncNowPlaying(context)
                            AudioService.refreshState(context)
                        }
                    } catch (e: Exception) {}
                }.start()
            }
        }

        // ── Timers ────────────────────────────────────────────────────────────

        private fun startProgressTimer() {
            stopProgressTimer()
            val handler = Handler(Looper.getMainLooper())
            progressHandler = handler
            val runnable = object : Runnable {
                override fun run() {
                    if (mediaPlayer?.isPlaying == true) {
                        sendEvent("PlaybackProgressUpdated", mapOf(
                            "position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0,
                            "duration" to (mediaPlayer?.duration ?: 0) / 1000.0
                        ))
                    }
                    handler.postDelayed(this, progressIntervalMs)
                }
            }
            progressRunnable = runnable
            handler.postDelayed(runnable, progressIntervalMs)
        }

        private fun stopProgressTimer() {
            progressRunnable?.let { progressHandler?.removeCallbacks(it) }
            progressHandler = null
            progressRunnable = null
        }

        private fun cancelSleepTimer() {
            sleepTimerRunnable?.let { sleepTimerHandler?.removeCallbacks(it) }
            sleepTimerHandler = null
            sleepTimerRunnable = null
        }

        // ── Bridge Functions ──────────────────────────────────────────────────

        class Play(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playlist.clear()
                playlistIndex = -1
                
                val url = parameters["url"] as? String ?: return mapOf("success" to false)
                metaTitle = parameters["title"] as? String
                metaArtist = parameters["artist"] as? String
                metaAlbum = parameters["album"] as? String
                metaDurationMs = (parameters["duration"] as? Number)?.toLong()?.let { it * 1000 }
                metaArtworkSource = parameters["artwork"] as? String
                currentArtwork = null

                prepareAndPlay(url)
                return mapOf("success" to true)
            }
        }

        class Load(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val url = parameters["url"] as? String ?: return mapOf("success" to false)
                prepareAndPlay(url, autoStart = false)
                return mapOf("success" to true)
            }
        }

        class GetState : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                return mapOf(
                    "url" to currentUrl,
                    "position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0,
                    "duration" to (mediaPlayer?.duration ?: 0) / 1000.0,
                    "isPlaying" to (mediaPlayer?.isPlaying ?: false),
                    "isBuffering" to isBuffering,
                    "playbackRate" to playbackRate,
                    "repeatMode" to repeatMode,
                    "shuffleMode" to shuffleMode,
                    "playlistIndex" to playlistIndex,
                    "playlistTotal" to playlist.size,
                    "title" to (metaTitle ?: ""),
                    "artist" to (metaArtist ?: "")
                )
            }
        }

        class SetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                
                val tracksJson = parameters["tracks"] as? JSONArray ?: return mapOf("success" to false)
                playlist.clear()
                for (i in 0 until tracksJson.length()) {
                    val trackObj = tracksJson.getJSONObject(i)
                    val map = mutableMapOf<String, Any>()
                    trackObj.keys().forEach { key -> map[key] = trackObj.get(key) }
                    playlist.add(map)
                }
                regenerateShuffleOrder()
                if (playlist.isNotEmpty()) playTrackAt(0)
                return mapOf("success" to true)
            }
        }

        class AppendTrack : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                playlist.add(parameters)
                regenerateShuffleOrder()
                return mapOf("success" to true)
            }
        }

        class RemoveTrack : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val index = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
                if (index in playlist.indices) {
                    playlist.removeAt(index)
                    regenerateShuffleOrder()
                }
                return mapOf("success" to true)
            }
        }

        class SetRepeatMode : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                repeatMode = parameters["mode"] as? String ?: "none"
                return mapOf("success" to true)
            }
        }

        class SetShuffleMode : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                shuffleMode = parameters["enabled"] as? Boolean ?: false
                regenerateShuffleOrder()
                return mapOf("success" to true)
            }
        }

        class SetProgressInterval : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val seconds = (parameters["seconds"] as? Number)?.toDouble() ?: 1.0
                progressIntervalMs = (seconds * 1000).toLong()
                if (mediaPlayer?.isPlaying == true) startProgressTimer()
                return mapOf("success" to true)
            }
        }

        class DrainEvents : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val events = JSONArray()
                pendingEvents.forEach { events.put(JSONObject(it)) }
                pendingEvents.clear()
                return mapOf("events" to events)
            }
        }

        // Standard controls
        class Pause(private val context: Context) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                togglePlay(false); return mapOf("success" to true)
            }
        }
        class Resume(private val context: Context) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                togglePlay(true); return mapOf("success" to true)
            }
        }
        class Stop(private val context: Context) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                stopPlayback(); return mapOf("success" to true)
            }
        }
        class Seek : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val sec = (parameters["seconds"] as? Number)?.toDouble() ?: 0.0
                seekTo(sec); return mapOf("success" to true)
            }
        }
        class SetVolume : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val lv = (parameters["level"] as? Number)?.toFloat() ?: 1.0f
                mediaPlayer?.setVolume(lv, lv); return mapOf("success" to true)
            }
        }
        class SetPlaybackRate : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                playbackRate = (parameters["rate"] as? Number)?.toFloat() ?: 1.0f
                applyRate(); updateSessionState(); return mapOf("success" to true)
            }
        }
        class GetDuration : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> = mapOf("duration" to (mediaPlayer?.duration ?: 0) / 1000.0)
        }
        class GetCurrentPosition : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> = mapOf("position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0)
        }
        class SetSleepTimer : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                val sec = (parameters["seconds"] as? Number)?.toLong() ?: 0L
                cancelSleepTimer()
                if (sec > 0) {
                    val handler = Handler(Looper.getMainLooper())
                    sleepTimerHandler = handler
                    val runnable = Runnable { stopPlayback(); sendEvent("SleepTimerExpired", emptyMap()) }
                    sleepTimerRunnable = runnable
                    handler.postDelayed(runnable, sec * 1000)
                }
                return mapOf("success" to true)
            }
        }
        class SetMetadata(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                metaTitle = parameters["title"] as? String
                metaArtist = parameters["artist"] as? String
                metaAlbum = parameters["album"] as? String
                metaDurationMs = (parameters["duration"] as? Number)?.toLong()?.let { it * 1000 }
                syncNowPlaying(activity)
                return mapOf("success" to true)
            }
        }
    }
}
