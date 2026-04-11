# NativePHP Audio Player Plugin

A NativePHP plugin for audio playback on mobile devices.

## Features

- **Play/Pause/Resume** - Full control over audio playback
- **Stop** - Stop and reset playback position
- **Seek** - Jump to any position in the audio
- **Volume Control** - Set volume programmatically
- **Duration/Position** - Get audio duration and current position

## 🚀 Future Roadmap

I'm actively working on the following features and will update the package soon:

- **Playback Rate** - Change audio speed (0.5x to 2x).


... and more!


## Installation


```bash
# Install the package
composer require theunwindfront/nativephp-audio

# Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin
php artisan native:plugin:register theunwindfront/nativephp-audio

# Verify registration
php artisan native:plugin:list
```

## Usage

### PHP (Livewire/Blade)

```php
use Theunwindfront\Audio\Facades\Audio;

// Play an audio file (using a sample open-source link for testing)
Audio::play('https://www.w3schools.com/html/horse.mp3');

// Pause
Audio::pause();

// Resume
Audio::resume();

// Seek to 30 seconds
Audio::seek(30.0);

// Set volume (0.0 to 1.0)
Audio::setVolume(0.8);

// Get info
$duration = Audio::getDuration();
$position = Audio::getCurrentPosition();
```

### JavaScript (Vue/React/Inertia)

```javascript
import { audioPlayer } from '@theunwindfront/nativephp-audio';

// Play an audio file
await audioPlayer.play('https://www.w3schools.com/html/horse.mp3');

// Pause/Resume
await audioPlayer.pause();
await audioPlayer.resume();

// Stop and reset
await audioPlayer.stop();

// Seek to 30 seconds
await audioPlayer.seek(30.0);

// Volume Control (0.0 to 1.0)
await audioPlayer.setVolume(1.0);

// Get Duration and Current Position
const duration = await audioPlayer.getDuration();
const position = await audioPlayer.getCurrentPosition();

// Event Listeners
window.addEventListener('audio-started', (e) => console.log('Started:', e.detail.url));
window.addEventListener('audio-paused', () => console.log('Paused'));
window.addEventListener('audio-stopped', () => console.log('Stopped'));
window.addEventListener('audio-completed', (e) => console.log('Completed:', e.detail.url));
```

## API Reference

| Method | Returns | Description |

|--------|---------|-------------|
| `play(string $url)` | `bool` | Play an audio file |
| `pause()` | `bool` | Pause playback |
| `resume()` | `bool` | Resume playback |
| `stop()` | `bool` | Stop playback |
| `seek(float $seconds)` | `bool` | Seek to position |
| `setVolume(float $level)` | `bool` | Set volume (0.0-1.0) |
| `getDuration()` | `?float` | Get audio duration |
| `getCurrentPosition()` | `?float` | Get current position |
| `setMetadata(array $data)` | `bool` | Set track metadata (lock screen info) |
| `setPlaylist(array $tracks)` | `bool` | Set native playlist queue |
| `next()` | `bool` | Skip to next track |
| `previous()` | `bool` | Skip to previous track |
| `setSleepTimer(int $seconds)` | `bool` | Stop audio after X seconds |

### Playlists


You can pass an array of tracks to the native player. The native OS will handle auto-advancing to the next song, which is much more reliable for background playback than handling it in JavaScript:

```php
Audio::setPlaylist([
    [
        'url' => 'https://example.com/song1.mp3',
        'title' => 'Song One',
        'artist' => 'Artist A',
        'artwork' => 'https://example.com/cover1.jpg'
    ],
    [
        'url' => 'https://example.com/song2.mp3',
        'title' => 'Song Two',
        'artist' => 'Artist B'
    ]
]);

// Navigate manually
Audio::next();
Audio::previous();

// Set a sleep timer (e.g., 30 minutes)
Audio::setSleepTimer(1800);
```



### Remote Controls & Events

The plugin automatically handles remote playback commands (lock screen buttons, headphone controls, Bluetooth devices). You can listen for these events in your application:


```javascript
window.addEventListener('audio-remote-play', () => console.log('Remote Play'));
window.addEventListener('audio-remote-pause', () => console.log('Remote Pause'));
window.addEventListener('audio-remote-next', () => console.log('Remote Next'));
window.addEventListener('audio-remote-previous', () => console.log('Remote Previous'));
```

### Audio Focus & Background Support

- **Audio Focus**: Automatically pauses or ducks (lowers volume) when other apps play audio or during phone calls.
- **Background Playback**: Full support for background playback on both Android (via Foreground Service) and iOS.



## Version Support

| Platform | Minimum Version |
|----------|----------------|
| Android  | 5.0 (API 21)   |
| iOS      | 13.0            |

## Support

For questions or issues, email pansuriya.sagar94@gmail.com

## License

The MIT License (MIT). Please see [License File](LICENSE) for more information.
