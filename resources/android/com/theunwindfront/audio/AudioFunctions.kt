package com.theunwindfront.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.net.URL

class AudioFunctions {
    companion object {
        private var mediaPlayer: MediaPlayer? = null
        private var mediaSession: MediaSessionCompat? = null
        private var audioManager: AudioManager? = null
        private var audioFocusRequest: AudioFocusRequest? = null
        
        internal var currentArtwork: Bitmap? = null
        private var metaTitle: String? = null
        private var metaArtist: String? = null
        private var metaAlbum: String? = null
        private var metaDurationMs: Long? = null
        private var currentUrl: String? = null

        private const val EVENT_PREFIX = "Theunwindfront\\Audio\\Events\\"

        fun getSessionToken(context: Context): MediaSessionCompat.Token {
            return getOrCreateSession(context).sessionToken
        }

        fun getMediaController(context: Context): MediaControllerCompat? {
            return mediaSession?.controller
        }

        private fun getOrCreateSession(context: Context): MediaSessionCompat {
            mediaSession?.let { return it }
            
            val session = MediaSessionCompat(context, "NativePHPAudio").apply {
                isActive = true
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        if (requestAudioFocus(context)) {
                            mediaPlayer?.start()
                            updatePlaybackState()
                            AudioService.refreshState(context)
                            sendEvent(context, "RemotePlayReceived", mapOf("url" to (currentUrl ?: "")))
                        }
                    }

                    override fun onPause() {
                        mediaPlayer?.pause()
                        updatePlaybackState()
                        AudioService.refreshState(context)
                        sendEvent(context, "RemotePauseReceived", emptyMap())
                    }

                    override fun onStop() {
                        mediaPlayer?.stop()
                        mediaPlayer?.release()
                        mediaPlayer = null
                        abandonAudioFocus()
                        updatePlaybackState()
                        AudioService.stop(context)
                        sendEvent(context, "PlaybackStopped", emptyMap())
                    }

                    override fun onSeekTo(pos: Long) {
                        mediaPlayer?.seekTo(pos.toInt())
                        updatePlaybackState()
                    }

                    override fun onSkipToNext() {
                        sendEvent(context, "RemoteNextTrackReceived", emptyMap())
                    }

                    override fun onSkipToPrevious() {
                        sendEvent(context, "RemotePreviousTrackReceived", emptyMap())
                    }
                })
            }
            mediaSession = session
            return session
        }

        private fun updatePlaybackState() {
            val session = mediaSession ?: return
            val isPlaying = mediaPlayer?.isPlaying == true
            
            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (mediaPlayer?.currentPosition ?: 0).toLong(),
                    1.0f
                )
                .build()
            session.setPlaybackState(state)
        }

        private fun sendEvent(context: Context, name: String, payload: Map<String, Any>) {
            val json = JSONObject(payload).toString()
            NativeActionCoordinator.dispatchEvent(context, EVENT_PREFIX + name, json)
        }

        private fun applyMetadata(context: Context) {
            val session = getOrCreateSession(context)
            val builder = MediaMetadataCompat.Builder()
            
            metaTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
            metaArtist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            metaAlbum?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            metaDurationMs?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
            currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            
            session.setMetadata(builder.build())
        }

        private fun loadArtworkAsync(context: Context, source: String) {
            val capturedUrl = currentUrl
            Thread {
                try {
                    val bitmap = if (source.startsWith("http")) {
                        BitmapFactory.decodeStream(URL(source).openStream())
                    } else {
                        BitmapFactory.decodeFile(source)
                    }
                    
                    bitmap?.let { 
                        if (currentUrl == capturedUrl) {
                            currentArtwork = it
                            Handler(Looper.getMainLooper()).post {
                                applyMetadata(context)
                                AudioService.refreshState(context)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        // --- Audio Focus Handling ---
        
        private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.let {
                        it.setVolume(1.0f, 1.0f)
                        if (!it.isPlaying) it.start()
                    }
                    updatePlaybackState()
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    mediaPlayer?.pause()
                    updatePlaybackState()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.2f, 0.2f)
                }
            }
        }

        private fun requestAudioFocus(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }

        private fun abandonAudioFocus() {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }

        fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    }

    class Play(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val url = params.optString("url")
            currentUrl = url

            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(context, Uri.parse(url))
                    
                    setOnPreparedListener { mp ->
                        if (requestAudioFocus(context)) {
                            mp.start()
                        }
                        updatePlaybackState()
                        AudioService.start(context, metaTitle ?: "Now Playing", metaArtist)
                        sendEvent(context, "PlaybackStarted", mapOf("url" to url))
                    }
                    
                    setOnCompletionListener {
                        updatePlaybackState()
                        AudioService.stop(context)
                        sendEvent(context, "PlaybackCompleted", mapOf("url" to url))
                    }
                    
                    prepareAsync()
                }
                return mapOf("success" to true)
            } catch (e: Exception) {
                return mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
            }
        }
    }

    class SetMetadata(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            
            metaTitle = params.optString("title").takeIf { it.isNotEmpty() }
            metaArtist = params.optString("artist").takeIf { it.isNotEmpty() }
            metaAlbum = params.optString("album").takeIf { it.isNotEmpty() }
            
            if (params.has("duration")) {
                metaDurationMs = (params.optDouble("duration") * 1000).toLong()
            }
            
            val artworkUrl = params.optString("artwork")
            if (artworkUrl.isNotEmpty()) {
                loadArtworkAsync(context, artworkUrl)
            }
            
            applyMetadata(context)
            if (mediaPlayer?.isPlaying == true) {
                AudioService.updateNotification(context, metaTitle ?: "Now Playing", metaArtist)
            }
            
            return mapOf("success" to true)
        }
    }

    class Pause(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.pause()
            updatePlaybackState()
            AudioService.refreshState(context)
            sendEvent(context, "PlaybackPaused", emptyMap())
            return mapOf("success" to true)
        }
    }

    class Resume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (requestAudioFocus(context)) {
                mediaPlayer?.start()
            }
            updatePlaybackState()
            AudioService.refreshState(context)
            sendEvent(context, "PlaybackStarted", mapOf("url" to (currentUrl ?: "")))
            return mapOf("success" to true)
        }
    }

    class Stop(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            abandonAudioFocus()
            updatePlaybackState()
            AudioService.stop(context)
            sendEvent(context, "PlaybackStopped", emptyMap())
            return mapOf("success" to true)
        }
    }

    class Seek(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val seconds = params.optDouble("seconds", 0.0)
            mediaPlayer?.seekTo((seconds * 1000).toInt())
            updatePlaybackState()
            return mapOf("success" to true)
        }
    }

    class SetVolume(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val params = JSONObject(parameters)
            val level = params.optDouble("level", 1.0).toFloat()
            mediaPlayer?.setVolume(level, level)
            return mapOf("success" to true)
        }
    }

    class GetDuration(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val duration = try { mediaPlayer?.duration ?: 0 } catch(e: Exception) { 0 }
            return mapOf("duration" to duration / 1000.0)
        }
    }

    class GetCurrentPosition(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val position = try { mediaPlayer?.currentPosition ?: 0 } catch(e: Exception) { 0 }
            return mapOf("position" to position / 1000.0)
        }
    }
}
