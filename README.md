# NativePHP Audio Player Plugin

[![Latest Version on Packagist](https://img.shields.io/packagist/v/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)
[![Total Downloads](https://img.shields.io/packagist/dt/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)
[![License](https://img.shields.io/packagist/l/theunwindfront/nativephp-audio.svg?style=flat-square)](https://packagist.org/packages/theunwindfront/nativephp-audio)

A premium NativePHP plugin for professional audio playback on mobile devices (Android & iOS). This plugin provides deep integration with native OS features like MediaSession, background services, and remote controls.

## ✨ Features

- **🏆 Native Media Integration** - Full support for OS Lock Screen controls, Bluetooth devices, and Android Auto/CarPlay.
- **📱 Background Excellence** - Reliable background playback using Foreground Services (Android) and specialized Audio Sessions (iOS).
- **🎶 Advanced Playlist Management** - Natively managed queues with Shuffle and Repeat modes.
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
    'album'    => 'Hurry Up, We\'re Dreaming',
    'artwork'  => 'https://example.com/artwork.jpg',
    'duration' => 243.0,
]);

// 2. Manage a Playlist (Natively handled auto-advance)
Audio::setPlaylist([
    [
        'url'   => 'https://example.com/track1.mp3',
        'title' => 'Track 01',
        'artist'=> 'Artist A'
    ],
    [
        'url'   => 'https://example.com/track2.mp3',
        'title' => 'Track 02',
        'artist'=> 'Artist B'
    ],
], autoPlay: true, startIndex: 0);

// 3. Playback Controls
Audio::pause();
Audio::resume();
Audio::next();
Audio::previous();
Audio::skipTo(5); // Skip to index 5 in playlist

// 4. State & Settings
$state = Audio::getState(); 
Audio::setVolume(0.8);
Audio::setPlaybackRate(1.5);
Audio::setShuffleMode(true);
Audio::setRepeatMode('all'); // 'none', 'one', 'all'

// 5. Sleep Timer
Audio::setSleepTimer(1800); // 30 minutes
```

### 📡 Event Synchronization

This plugin dispatches powerful Laravel events that you can listen to in your application:

| Event | Description |
|-------|-------------|
| `PlaybackStarted` | Fired when audio actually begins playing. |
| `PlaybackProgressUpdated` | Heartbeat event with `position` and `duration`. |
| `PlaylistTrackChanged` | Fired on auto-advance or manual track skip. |
| `AudioFocusLost` | Fired when another app takes over audio. |
| `RemotePlayReceived` | Fired when the user hits 'Play' on their headphones/lockscreen. |

**Livewire Example:**

```php
use Theunwindfront\Audio\Events\PlaybackProgressUpdated;
use Livewire\Attributes\On;

#[On('native:Theunwindfront\Audio\Events\PlaybackProgressUpdated')]
public function onProgress($position, $duration)
{
    $this->currentPosition = $position;
    $this->totalDuration = $duration;
}
```

## 🛠 Advanced Features

### Background Sync
When your app returns from the background, you can "drain" any missed events that occurred while the PHP process was suspended:

```php
$missedEvents = Audio::drainEvents();
foreach ($missedEvents as $event) {
    // Sync your local state
}
```

### Sleep Timer
Safely schedule a shutdown. This releases native resources and stops the foreground service:

```php
Audio::setSleepTimer(600); // 10 minutes
// Listen for completion
// Event: Theunwindfront\Audio\Events\SleepTimerExpired
```

## 📋 API Reference

| Method | Parameters | Returns |
|--------|------------|---------|
| `play` | `string $url, array $options` | `bool` |
| `load` | `string $url, array $options` | `bool` |
| `setPlaylist` | `array $tracks, bool $autoPlay, int $startIndex` | `bool` |
| `getState` | - | `array` |
| `setVolume` | `float $level` (0.0 - 1.0) | `bool` |
| `setPlaybackRate` | `float $rate` (0.25 - 4.0) | `bool` |
| `setSleepTimer` | `int $seconds` | `bool` |
| `drainEvents` | - | `array` |

## 📱 Version Support

- **Android**: 5.0 (API 21) or higher.
- **iOS**: 13.0 or higher.

## 🤝 Support

For questions or issues, contact **pansuriya.sagar94@gmail.com**

## 📄 License

The MIT License (MIT). Please see [License File](LICENSE) for more information.
