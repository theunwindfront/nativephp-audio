# Release Notes

## [1.5.0] - 2026-11-17
### Fixed
- **Background events**: Events queued while the app was in the background were never delivered and the queue grew without limit (one progress event per second). Queued events are now delivered automatically when the app returns to the foreground, progress updates aren't queued, and the queue is capped at 200 events.
- **Shuffle**: Turning shuffle on/off or adding/removing tracks reshuffled the whole queue, so the current index pointed at a different song. The current track now stays put; with shuffle on it moves to the front of the new order. `setPlaylist()` with shuffle on starts on `startIndex` instead of a random track.
- **Repeat one**: Pressing next with repeat set to `one` now moves to the next track; the track only repeats when it finishes on its own.
- **Android**: The notification's play/pause button did nothing, its icon never changed, and the title didn't update with new metadata.
- **Android**: The service could restart after the process was killed and show a notification with no player behind it.
- **Android**: Playback could stop with the screen off: the player now holds a wake lock, and a Wi-Fi lock while streaming.
- **Android**: Calling `pause()`, `stop()`, `seek()` or `next()` from the app no longer fires the `Remote*Received` events; those now only fire for lock screen, notification, headset and Bluetooth controls.
- **Android**: Volume set with `setVolume()` was lost on the next track, and restoring after ducking always went back to full volume.
- **Android**: A playback error also triggered "completed" and skipped to the next track; an invalid file path crashed the call; querying state while a track was loading could put the player into an error state; `getDuration()` returned a negative value for streams.
- **Android**: Bridge calls now run on the main thread, like the player callbacks.
- **iOS**: Observers for finished tracks were never removed (one leaked per track); playback failures now fire `PlaybackFailed`.
- **iOS**: `load()` no longer plays a short burst of audio and now fires `PlaybackLoaded` instead of `PlaybackStarted`.
- **iOS**: Events were queued after merely pulling down Control Center and stayed queued until the app was backgrounded and reopened.
- **iOS**: After a phone call, playback only resumes if it was playing before the call.

### Added
- **Both**: Playback pauses when headphones or a Bluetooth device are disconnected.
- **Both**: `PlaybackStateChanged` is now sent whenever playing/buffering changes (and with `metadataChanged: true` after `setMetadata()`).
- **iOS**: Now sends `PlaybackStopped`, `PlaybackSeeked`, `PlaybackFailed`, `PlaybackLoaded`, `PlaylistSet`, `PlaylistRepeatModeChanged`, `PlaylistShuffleChanged`, `RemoteSeekReceived` and `RemoteStopReceived`, matching Android.
- **iOS**: Headphone play/pause toggle and remote stop are supported; next/previous are disabled on the lock screen for streams and single tracks.
- **iOS**: Artwork is cached and downloaded with a timeout; the lock screen shows the file name when no title is set.
- **Android**: `PlaylistTrackChanged` now includes `reason` (`user`, `auto` or `repeat`), matching iOS.
- **Android**: Artwork downloads have a timeout and aren't repeated for the same image.

### Changed
- `setRepeatMode()` ignores unknown modes (treated as `none`); `setVolume()`, `setPlaybackRate()` and `setProgressInterval()` clamp their values on both platforms.
- `PlaybackStarted` on iOS now carries the full playback state, like Android.
- iOS deactivates the audio session on `stop()`, so other apps' audio can resume.

## [1.4.1] - 2026-11-17
### Fixed
- **Events**: Native events never reached PHP listeners. NativePHP only dispatches event classes that implement `DispatchableFromNative`; every event now does (with a polyfill for older `nativephp/mobile` releases).
- **Events**: Event constructors didn't match the payloads sent by iOS/Android (e.g. `PlaybackStarted` required `url` but received `track`), so building the event failed. All events now extend `AudioEvent`, which accepts any payload, fills matching typed properties (`track`, `url`, `position`, `duration`, `isPlaying`, …) and keeps the raw data in `$payload`. `$url` is filled from `track.url`, and `$state` is kept as an alias of `$payload`.
- **Blade bridge**: `@nativeAudioBridge` listened for `nativephp_event`, but NativePHP fires `native-event`, so `audio:*` window events never fired.
- **JS library**: `audio.js` called `window.nativephp.call`, which doesn't exist. It now calls NativePHP's `/_native/api/call` endpoint, resolves with the native result and throws on errors. `bridgeCall` is also exported.
- **iOS**: `setupAudioSession()` was never called, so background playback, the silent-switch override and interruption handling didn't work. It's now registered as the plugin's `ios.init_function`; the audio session is activated when playback starts rather than at app launch, so opening the app doesn't stop other apps' audio.

### Changed
- Event properties are now nullable, and constructors take named payload keys instead of positional arguments.

### Added
- PHPUnit setup (`composer test`), event tests, and a GitHub Actions workflow that runs them.

## [1.4.0] - 2026-11-17
### Added
- **Compatibility**: Support for NativePHP Mobile v4 (`nativephp/mobile: ^3.0|^4.0`).

### Fixed
- **iOS**: Events were sent through `NativePHPBridge.dispatchEvent`, which doesn't exist in NativePHP Mobile v3 or v4. Events now go through `LaravelBridge.shared.send`.

## [1.3.2] - 2026-11-17
### Fixed
- **iOS**: Build failure in `PluginBridgeFunctionRegistration.swift` ("argument type '[String : Any]' does not conform to expected type 'BridgeFunction'"). NativePHP registers iOS bridge functions as `AudioFunctions.<Name>()`, which needs a `BridgeFunction` type, but the plugin only exposed static methods. Each bridge function is now a nested `BridgeFunction` class that forwards to the existing static handler (renamed to `handle<Name>`).
- **iOS**: Bridge calls now run on the main thread, since AVPlayer, Now Playing info and remote commands expect main-thread access.

## [1.3.1] - 2026-06-09
### Added
- **Live Radio Streaming**: New `PlayStream` bridge function for both Android and iOS, enabling first-class live audio stream playback (Icecast, Shoutcast, HTTP streams).
- **PHP**: `Audio::playStream($mountpoint, $options)` for starting live streams from PHP.
- **PHP**: `Audio::updateStreamMetadata($metadata)` for real-time metadata updates during live streams.
- **JS Bridge**: `audio.playStream()` and `audio.updateStreamMetadata()` methods.
- **Facade**: Added `@method` annotations for `playStream` and `updateStreamMetadata`.
- **Events**: `StreamMetadataChanged` event fired when live stream metadata is updated.
- **Android**: Stream URL resolution from mountpoint + server base URL, stream state tracking in payloads.
- **iOS**: Stream URL resolution, mountpoint support, `isLiveStream` flag on Now Playing info.

### Fixed
- **iOS**: Critical compile error — `applyMetadata` returned `[String: Any]` from a `Void` function.
- **iOS**: `SetMetadata` bridge function was accidentally removed during refactoring; re-added as `@objc` wrapper.
- **iOS**: `UpdateStreamMetadata` bridge function was missing entirely; added with `StreamMetadataChanged` event dispatch.
- **iOS**: Duplicate `isStream`/`streamMountpoint` assignments in `trackPayload()` and `resetPlayer()`.
- **iOS**: `SetMetadata` no longer incorrectly fires `StreamMetadataChanged` events.
- **Android**: `PlayStream` bridge class was registered in `nativephp.json` but not implemented; now fully implemented.
- **Android**: `trackPayload()` and `statePayload()` now include `isStream`, `streamType`, and `mountpoint` fields (feature parity with iOS).
- **Android**: Stream state (`isStream`, `streamMountpoint`) is properly reset when stopping or switching to playlist tracks.
- **Android**: Stream duration correctly reports `0.0` instead of `-1` for live streams.

## [1.2.2] - 2026-05-11
### Added
- **Android**: Added `MediaControllerCompat` import and `getMediaController()` helper for improved cross-device media session control.

### Improved
- **Compatibility**: Extended `illuminate/support` requirement to include Laravel `^13.0`.

## [1.2.1] - 2026-04-16
### Fixed
- **iOS**: Achieved full feature parity with Android by implementing missing bridge functions (`Seek`, `SetVolume`, `SetMetadata`, `SetPlaybackRate`, `AppendTrack`, `RemoveTrack`, and `PlayTrackAt`).

## [1.2.0] - 2026-04-16
### Added
- **Storage**: Support for local audio files using absolute paths (e.g., `storage_path()`).
- **Android**: Automatically requests `READ_EXTERNAL_STORAGE` and `READ_MEDIA_AUDIO` permissions.
- **iOS**: Integrated `URL(fileURLWithPath:)` for seamless local file resolution.
- **Bridge**: Dynamic `audio:` prefixed events dispatched to the `window`.
- **JS**: Comprehensive `audio.js` helper with all native bridge methods.
- **Events**: 15 new Laravel events including `PlaybackResumed`, `PlaylistTrackChanged`, and `AudioFocusLost`.

### Improved
- **Bridge**: `bridge.blade.php` now uses a dynamic event mapper for easier extensibility.
- **Stability**: Fixed potential variable scope issues in Swift and Kotlin bridge functions.

## [1.1.5] - 2026-04-16
### Added
- **Initial Mobile Features**: Expanded background service and native playlist logic.

## [1.1.4] - 2026-04-12
### Added
- **API**: Added `playTrackAt($index)` to specifically play a track by its position in the native playlist.
- **Bridge**: Registered `Audio.playTrackAt` in `nativephp.json`.

### Fixed
- **Android**: Critical hotfix for corrupted Kotlin bridge file.
- **Android**: Ensured proper nested class visibility for bridge functions.
### Added
- **Events**: Added `PlaybackFailed` and `PlaybackStateChanged` events.

## [1.1.2] - 2026-04-12 (Hotfix / Internal)
- **Events**: Added new playback events for better UI synchronization.
- **Android**: Fixed bridge function visibility and static access.

### Fixed
- **Android**: Fixed activity reference leaks using WeakReference.
- **Android**: Improved stability of the background service and media session handling.
- **Android**: Added `MediaControllerCompat` support for better cross-device compatibility.

## [1.1.0] - 2026-04-11
### Added
- **Native Shuffle & Repeat**: Added `setShuffleMode` and `setRepeatMode` (none, one, all).
- **Dynamic Playlists**: Added `appendTrack` and `removeTrack` to modify queues live.
- **Advanced State Retrieval**: Added `getState()` to get full player/playlist/metadata status in one call.
- **Custom Progress Frequency**: Added `setProgressInterval()` to control how often progress events fire.
- **Background Event Draining**: Added `drainEvents()` to sync the UI when returning from the background.
- **Buffering Detection**: New `PlaybackBuffering` and `PlaybackReady` events.
- **Track Loading**: Added `load()` to pre-buffer audio without playing.

## [1.0.4] - 2026-04-11

### Added
- **MediaSession Support**: Full track metadata (artist, title, album, artwork) for lock screen and OS control centers.
- **Remote Controls**: Handle play/pause/next/previous from headphones, Bluetooth, and lock screen buttons.
- **Audio Focus**: Automated pausing/ducking during phone calls or when other apps play audio.
- **Background Playback**: Improved reliability with Android foreground service and iOS background modes.
- **Native Playlists**: Added `setPlaylist`, `next()`, and `previous()` for reliable native track transitions.
- **Sleep Timer**: Schedule automatic stopping of playback.
- **Playback Rate**: Adjust audio speed (0.5x to 2x).

- **MediaSession Support**: Full track metadata (artist, title, album, etc.) on Bluetooth devices and OS media controls.
- **Remote Control Commands**: Handle play/pause/prev/next from headphones and lock screens.
- **Improved Background Playback**: Better stability for long-running audio sessions.

## 🎵 NativePHP Audio Player v1.0.3

### Fixes
- **iOS**: Updates to `AudioFunctions.swift` implementation.

## 🎵 NativePHP Audio Player v1.0.2


### Fixes
- **Android**: Fixed incorrect `BridgeFunction` import in `AudioFunctions.kt`.

## 🎵 NativePHP Audio Player v1.0.1

### Fixes
- **Package Naming**: Corrected package name to `theunwindfront/nativephp-audio` for better composer integration.

## 🎵 NativePHP Audio Player v1.0.0

### Features & Improvements
- **Core Audio Playback**: Native audio playback for iOS (Swift) and Android (Kotlin).
- **Controls**: Play, pause, stop, and seek controls with volume adjustment.
- **JavaScript Bridge**: Comprehensive Vue/React/Inertia support with full API and event listeners.
- **Unified API**: Simplified PHP API for cross-platform audio management.
- **CI/CD**: Automated GitHub Releases on tag push.