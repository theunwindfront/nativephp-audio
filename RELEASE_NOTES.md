# Release Notes

## [1.1.1] - 2026-04-12
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