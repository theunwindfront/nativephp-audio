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

        class GetState(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
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
                    "artist" to (metaArtist ?: ""),
                    "album" to (metaAlbum ?: "")
                )
            }
        }

        class SetPlaylist(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                
                // Decode tracks - they might come as a JSON string or a List
                val tracksObj = parameters["tracks"]
                val tracksList = when (tracksObj) {
                    is JSONArray -> {
                        val list = mutableListOf<Map<String, Any>>()
                        for (i in 0 until tracksObj.length()) {
                            val obj = tracksObj.getJSONObject(i)
                            val map = mutableMapOf<String, Any>()
                            obj.keys().forEach { key -> map[key] = obj.get(key) }
                            list.add(map)
                        }
                        list
                    }
                    is List<*> -> tracksObj.filterIsInstance<Map<String, Any>>()
                    else -> emptyList()
                }

                if (tracksList.isEmpty()) return mapOf("success" to false)

                playlist.clear()
                playlist.addAll(tracksList)
                regenerateShuffleOrder()
                
                return mapOf("success" to true)
            }
        }

        class NextTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playNext()
                return mapOf("success" to true)
            }
        }
        class PreviousTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playPrevious()
                return mapOf("success" to true)
            }
        }
        class AppendTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playlist.add(parameters)
                regenerateShuffleOrder()
                return mapOf("success" to true)
            }
        }

        class RemoveTrack(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val index = (parameters["index"] as? Number)?.toInt() ?: return mapOf("success" to false)
                if (index in playlist.indices) {
                    playlist.removeAt(index)
                    regenerateShuffleOrder()
                }
                return mapOf("success" to true)
            }
        }

        class SetRepeatMode(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                repeatMode = parameters["mode"] as? String ?: "none"
                return mapOf("success" to true)
            }
        }

        class SetShuffleMode(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                shuffleMode = parameters["enabled"] as? Boolean ?: false
                regenerateShuffleOrder()
                return mapOf("success" to true)
            }
        }

        class SetProgressInterval(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val seconds = (parameters["seconds"] as? Number)?.toDouble() ?: 1.0
                progressIntervalMs = (seconds * 1000).toLong()
                if (mediaPlayer?.isPlaying == true) startProgressTimer()
                return mapOf("success" to true)
            }
        }

        class DrainEvents(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val events = JSONArray()
                pendingEvents.forEach { events.put(JSONObject(it)) }
                pendingEvents.clear()
                return mapOf("events" to events)
            }
        }

        // Standard controls
        class Pause(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                togglePlay(false); return mapOf("success" to true)
            }
        }
        class Resume(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                togglePlay(true); return mapOf("success" to true)
            }
        }
        class Stop(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                stopPlayback(); return mapOf("success" to true)
            }
        }
        class Seek(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val sec = (parameters["seconds"] as? Number)?.toDouble() ?: 0.0
                seekTo(sec); return mapOf("success" to true)
            }
        }
        class SetVolume(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                val lv = (parameters["level"] as? Number)?.toFloat() ?: 1.0f
                
                // Set MediaPlayer volume
                mediaPlayer?.setVolume(lv, lv)
                
                // Set System Volume (Media Stream)
                val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (lv * maxVolume).toInt()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
                
                return mapOf("success" to true)
            }
        }
        class SetPlaybackRate(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                playbackRate = (parameters["rate"] as? Number)?.toFloat() ?: 1.0f
                
                if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        // We must preserve existing params or create new ones correctly
                        val params = mediaPlayer?.playbackParams ?: PlaybackParams()
                        params.speed = playbackRate
                        mediaPlayer?.playbackParams = params
                    } catch (e: Exception) {}
                }
                
                updateSessionState()
                return mapOf("success" to true)
            }
        }
        class GetDuration(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                return mapOf("duration" to (mediaPlayer?.duration ?: 0) / 1000.0)
            }
        }
        class GetCurrentPosition(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
                return mapOf("position" to (mediaPlayer?.currentPosition ?: 0) / 1000.0)
            }
        }
        class SetSleepTimer(private val activity: FragmentActivity) : BridgeFunction {
            override fun execute(parameters: Map<String, Any>): Map<String, Any> {
                activityRef = WeakReference(activity)
                appContext = activity.applicationContext
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
