# NativePHP Audio Player Plugin

A professional NativePHP plugin for audio playback on mobile devices, supporting background play, playlists, and remote controls.

## Features

- **Full Playback Control** - Play, Pause, Resume, Stop, and Seek.
- **Professional Playlists** - Native queueing with auto-advance, Shuffle, and Repeat modes.
- **MediaSession Support** - Rich metadata (title, artist, artwork) on lock screens and OS control centers.
- **Remote Commands** - Handle commands from headphones, car Bluetooth, and system widgets.
- **Background Stability** - Dedicated foreground service for Android and background modes for iOS.
- **Advanced State** - Get comprehensive player/playlist state in a single call.
- **Sleep Timer & Rate** - Schedule playback to stop or adjust playback speed (0.5x to 2.0x).

## Installation

```bash
# Install the package
composer require theunwindfront/nativephp-audio

# Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin
php artisan native:plugin:register theunwindfront/nativephp-audio
```

## Usage

### PHP (Laravel/Livewire)

```php
use Theunwindfront\Audio\Facades\Audio;

// Play a URL with metadata
Audio::play('https://example.com/audio.mp3', [
    'title' => 'Song Title',
    'artist' => 'Artist Name',
    'artwork' => 'https://example.com/cover.jpg'
]);

// Basic controls
Audio::pause();
Audio::resume();
Audio::stop();
Audio::seek(45.5); // Seek to 45.5 seconds
Audio::setVolume(0.8); // 0.0 to 1.0
```

### Advanced Features

```php
// Get full player state
$state = Audio::getState();
// Returns: ['url' => '...', 'position' => 30.5, 'isPlaying' => true, 'repeatMode' => 'all', ...]

// Set progress update frequency (e.g., every 0.1 seconds for smooth sliders)
Audio::setProgressInterval(0.1);

// Set Playback speed
Audio::setPlaybackRate(1.5);

// Set a sleep timer (in seconds)
Audio::setSleepTimer(1800);
```

### Professional Playlists

The native OS handles track transitions, ensuring the next song plays instantly even if the app is in the background.

```php
$tracks = [
    [
        'url' => 'https://example.com/song1.mp3',
        'title' => 'Song 1',
        'artist' => 'Artist 1',
        'artwork' => 'https://example.com/art1.jpg'
    ],
    [
        'url' => 'https://example.com/song2.mp3',
        'title' => 'Song 2',
        'artist' => 'Artist 2'
    ]
];

// Start a playlist queue
Audio::setPlaylist($tracks);

// Add to queue live without stopping playback
Audio::appendTrack([
    'url' => 'https://example.com/song3.mp3',
    'title' => 'Song 3'
]);

// Navigation
Audio::next();
Audio::previous();

// Modes
Audio::setRepeatMode('all'); // none, one, all
Audio::setShuffleMode(true);
```

### Events

You can listen for native audio events in your JavaScript code:

```javascript
window.addEventListener('Theunwindfront\\Audio\\Events\\PlaybackProgressUpdated', (event) => {
    const { position, duration } = event.detail;
    // Update your progress bar
});

window.addEventListener('Theunwindfront\\Audio\\Events\\PlaybackBuffering', () => {
    // Show loading spinner
});
```

## API Reference

| Method | Returns | Description |
|--------|---------|-------------|
| `play(string $url, array $metadata)` | `bool` | Play an audio file with metadata |
| `pause()` | `bool` | Pause playback |
| `resume()` | `bool` | Resume playback |
| `stop()` | `bool` | Stop playback |
| `seek(float $seconds)` | `bool` | Seek to position |
| `setVolume(float $level)` | `bool` | Set volume (0.0-1.0) |
| `getState()` | `array` | Get full player state |
| `setProgressInterval(float $seconds)` | `bool` | Set progress update frequency |
| `setPlaylist(array $tracks)` | `bool` | Set native playlist queue |
| `appendTrack(array $track)` | `bool` | Add track to queue |
| `removeTrack(int $index)` | `bool` | Remove track from queue |
| `next()` | `bool` | Skip to next track |
| `previous()` | `bool` | Skip to previous track |
| `setRepeatMode(string $mode)` | `bool` | Set repeat mode (none, one, all) |
| `setShuffleMode(bool $enabled)` | `bool` | Toggle shuffle |
| `setSleepTimer(int $seconds)` | `bool` | Stop audio after X seconds |
| `setPlaybackRate(float $rate)` | `bool` | Change playback speed (0.5-2.0) |

## Version Support

| Platform | Minimum Version |
|----------|----------------|
| Android  | 5.0 (API 21)   |
| iOS      | 13.0            |

## Support

For questions or issues, contact **pansuriya.sagar94@gmail.com**

## License

The MIT License (MIT). Please see [License File](LICENSE) for more information.
