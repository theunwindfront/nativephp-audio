# NativePHP Audio Player Plugin

[![Latest Version on Packagist](https://img.shields.io/packagist/v/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)
[![Total Downloads](https://img.shields.io/packagist/dt/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)
[![License](https://img.shields.io/packagist/l/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)

A premium NativePHP plugin for professional audio playback on mobile devices (Android & iOS). This plugin provides deep integration with native OS features like MediaSession, background services, and remote controls.

## ✨ Features

- **🏆 Native Media Integration** - Full support for OS Lock Screen controls, Bluetooth devices, and Android Auto/CarPlay.
- **📱 Background Excellence** - Reliable background playback using Foreground Services (Android) and specialized Audio Sessions (iOS).
- **🎶 Advanced Playlist Management** - Natively managed queues with Shuffle and Repeat modes.
- **📻 Live Radio Streaming** - First-class support for live audio streams with real-time metadata updates.
- **🎧 Audio Focus Intelligence** - Gracefully handles interruptions (phone calls, notifications, Siri) with auto-ducking and resuming.
- **🕒 Sleep Timers** - Programmatic sleep timers that safely release native resources.
- **📊 Detailed Analytics Events** - Granular event reporting for playback progress, track changes, buffering, and remote commands.
- **🖼 Rich Metadata** - Support for high-quality artwork, titles, artists, and arbitrary custom metadata.

## 🚀 Installation

```bash
# Install via Composer
composer require theunwindfront/nativephp-audio

# Publish the plugins provider (if not already done)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin with NativePHP
php artisan native:plugin:register theunwindfront/nativephp-audio
```

## 📖 Usage

### PHP Interface (Livewire / Controller)

```php
use Theunwindfront\Audio\Facades\Audio;

// 1. Play a single track with metadata
Audio::play('https://example.com/song.mp3', [
    'title'    => 'Midnight City',
    'artist'   => 'M83',
    'artwork'  => 'https://example.com/artwork.jpg',
]);

// 2. Play a Local File (Mobile Storage)
// Raw paths from storage_path() are natively supported
Audio::play(storage_path('app/public/recordings/audio.mp3'), [
    'title'  => 'Voice Note',
    'artist' => 'Recorded Local'
]);

// 3. Manage a Playlist (Natively handled auto-advance)
Audio::setPlaylist([
    [
        'url'   => 'https://example.com/track1.mp3',
        'title' => 'Track 01',
    ],
    // ... more tracks
], autoPlay: true, startIndex: 0);

// 4. Playback Controls
Audio::pause();
Audio::resume();
Audio::next();
Audio::previous();
Audio::skipTo(5); // Skip to index 5 in playlist

// 5. State & Settings
$state = Audio::getState(); 
Audio::setVolume(0.8);
Audio::setPlaybackRate(1.5);
Audio::setShuffleMode(true);
Audio::setRepeatMode('all'); // 'none', 'one', 'all'

// 6. Sleep Timer
Audio::setSleepTimer(1800); // 30 minutes
```

### 📻 Live Radio Streaming

Play live audio streams (Icecast, Shoutcast, or any HTTP audio stream) with dedicated streaming APIs:

```php
use Theunwindfront\Audio\Facades\Audio;

// Play a stream by direct URL
Audio::playStream('https://stream.example.com/live.mp3', [
    'title'   => 'My Radio Station',
    'artist'  => 'Live DJ Set',
    'artwork' => 'https://example.com/radio-logo.jpg',
]);

// Play a stream by mountpoint (resolved against a server URL)
Audio::playStream('/live', [
    'serverUrl' => 'https://stream.example.com',
    'title'     => 'Main Channel',
    'artist'    => 'Now Playing',
]);

// Update metadata in real-time (e.g., when the current song changes)
Audio::updateStreamMetadata([
    'title'   => 'New Song Title',
    'artist'  => 'New Artist',
    'artwork' => 'https://example.com/new-artwork.jpg',
]);
```

> **Note:** `playStream` automatically marks the session as a live stream, sets duration to `null`, disables auto-advance on completion, and flags the lock screen / Now Playing info as a live broadcast.

### ⚡ JavaScript Bridge

If you are building a SPA (Inertia/Vue/React) or using Alpine.js, you can use the JavaScript bridge directly.

First, include the bridge in your layout:
```html
@include('audio::bridge')
```

Then, use the `audio` helper:
```javascript
import audio from './resources/js/audio.js';

// Play immediately
await audio.play('https://server.com/song.mp3', { title: 'My Song' });

// Play a live stream
await audio.playStream('https://stream.example.com/live.mp3', {
    title: 'Live Radio',
    artist: 'DJ Mix'
});

// Update stream metadata in real-time
await audio.updateStreamMetadata({
    title: 'New Track Playing',
    artist: 'Featured Artist'
});

// Listen for native events on the window
window.addEventListener('audio:playback-progress-updated', (event) => {
    const { position, duration } = event.detail;
    console.log(`Playing: ${position} / ${duration}`);
});

// Listen for stream metadata changes
window.addEventListener('audio:stream-metadata-changed', (event) => {
    const { track, metadata } = event.detail;
    console.log(`Now playing: ${track.title} by ${track.artist}`);
});
```

### 📡 Event Synchronization

This plugin dispatches powerful Laravel events that you can listen to in your application:

| Event | Description |
|-------|-------------|
| `PlaybackStarted` | Fired when audio actually begins playing. |
| `PlaybackProgressUpdated` | Heartbeat event with `position` and `duration`. |
| `PlaylistTrackChanged` | Fired on auto-advance or manual track skip. |
| `AudioFocusLost` | Fired when another app takes over audio (e.g. phone call). |
| `RemotePlayReceived` | Fired when the user hits 'Play' on headphones/lockscreen. |
| `SleepTimerExpired` | Fired when the scheduled sleep timer hits zero. |
| `StreamMetadataChanged` | Fired when live stream metadata is updated (e.g. new song starts). |

## 🛠 Advanced Features

### Background Sync
When your app returns from the background, you can "drain" any missed events that occurred while the PHP process was suspended:

```php
$missedEvents = Audio::drainEvents();
```

### Absolute Local Paths
Unlike standard web players, this plugin has direct filesystem access. On Android, it even requests `READ_MEDIA_AUDIO` permissions automatically.

```php
Audio::play('/storage/emulated/0/Download/my-song.mp3');
```

## 📋 API Reference

| Method | Parameters | Description |
|--------|------------|-------------|
| `play` | `string $url, array $options` | Play/Restart audio |
| `playStream` | `string $mountpoint, array $options` | Play a live radio stream |
| `load` | `string $url, array $options` | Prepare audio without playing |
| `setPlaylist` | `array $tracks, bool $autoPlay, int $idx` | Set native queue |
| `next / previous` | - | Navigate playlist |
| `skipTo` | `int $index` | Jump to specific track |
| `setVolume` | `float $level` (0.0 - 1.0) | Set player volume |
| `setPlaybackRate`| `float $rate` (0.25 - 4.0) | Set playback speed |
| `setMetadata` | `array $metadata` | Set track metadata |
| `updateStreamMetadata`| `array $metadata` | Update live stream metadata without replacing track |
| `setSleepTimer` | `int $seconds` | Schedule a shutdown |
| `cancelSleepTimer`| - | Stop the active timer |
| `getState` | - | Get full status object |
| `getPlaylist` | - | Get full playlist state |
| `drainEvents` | - | Get background events |

## 📱 Version Support

- **Android**: 5.0 (API 21) or higher.
- **iOS**: 13.0 or higher.

## 👥 Credits

- **[Sagar Pansuriya](https://github.com/theunwindfront)** - Lead Developer
- [All Contributors](../../contributors)

## 🤝 Support

For questions or issues, contact **pansuriya.sagar94@gmail.com** or open a [GitHub Issue](https://github.com/theunwindfront/nativephp-audio/issues).

## 📄 License

The MIT License (MIT). Please see [License File](LICENSE) for more information.
