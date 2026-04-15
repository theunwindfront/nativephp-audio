<?php

namespace Theunwindfront\Audio;

class Audio
{
    /**
     * Play an audio file from a URL or local path.
     * Fires PlaybackStarted once playing, PlaybackBuffering while fetching.
     *
     * @param  string       $url      URL or local path of the audio file
     * @param  array        $options  Optional: title, artist, album, artwork, duration, clip, metadata
     */
    public function play(string $url, array $options = []): bool
    {
        if (function_exists('nativephp_call')) {
            $params = array_merge(['url' => $url], $options);
            $result = nativephp_call('Audio.play', json_encode($params));

            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Load an audio file without starting playback immediately.
     */
    public function load(string $url, array $options = []): bool
    {
        if (function_exists('nativephp_call')) {
            $params = array_merge(['url' => $url], $options);
            $result = nativephp_call('Audio.load', json_encode($params));

            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Pause the current audio playback
     */
    public function pause(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.pause', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Resume the paused audio playback
     */
    public function resume(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.resume', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Stop the audio playback and reset the position
     */
    public function stop(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.stop', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Seek to a specific position in the audio (in seconds)
     */
    public function seek(float $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.seek', json_encode(['seconds' => max(0, $seconds)]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Set the audio volume (0.0 to 1.0)
     */
    public function setVolume(float $level): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setVolume', json_encode(['level' => max(0, min(1, $level))]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Set the playback speed multiplier.
     */
    public function setPlaybackRate(float $rate): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaybackRate', json_encode(['rate' => max(0.25, min(4.0, $rate))]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Get the duration of the current audio in seconds
     */
    public function getDuration(): ?float
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getDuration', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return isset($decoded['duration']) ? (float) $decoded['duration'] : null;
            }
        }
        return null;
    }

    /**
     * Get the current playback position in seconds
     */
    public function getCurrentPosition(): ?float
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getCurrentPosition', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return isset($decoded['position']) ? (float) $decoded['position'] : null;
            }
        }
        return null;
    }

    /**
     * Get the full current playback state.
     */
    public function getState(): array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getState', '{}');
            if ($result) {
                return json_decode($result, true) ?? [];
            }
        }
        return [];
    }

    /**
     * Set track metadata (lock screen / media center info).
     */
    public function setMetadata(array $metadata): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setMetadata', json_encode($metadata));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Drain background events (useful when app resumes).
     */
    public function drainEvents(): array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.drainEvents', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return $decoded['events'] ?? [];
            }
        }
        return [];
    }

    /**
     * Set the playlist natively.
     */
    public function setPlaylist(array $tracks, bool $autoPlay = true, int $startIndex = 0): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaylist', json_encode([
                'items' => $tracks,
                'autoPlay' => $autoPlay,
                'startIndex' => $startIndex
            ]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Skip to the next track in the playlist.
     */
    public function next(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.nextTrack', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Skip to the previous track in the playlist.
     */
    public function previous(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.previousTrack', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Skip to a specific track by index.
     */
    public function skipTo(int $index): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.skipTrack', json_encode(['index' => $index]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Get a track from the playlist by index.
     */
    public function getTrack(int $index): ?array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getTrack', json_encode(['index' => $index]));
            if ($result) {
                $decoded = json_decode($result, true);
                return $decoded['track'] ?? null;
            }
        }
        return null;
    }

    /**
     * Get the active track.
     */
    public function getActiveTrack(): ?array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getActiveTrack', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return $decoded['track'] ?? null;
            }
        }
        return null;
    }

    /**
     * Get the current track index.
     */
    public function getActiveTrackIndex(): ?int
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getActiveTrackIndex', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return isset($decoded['index']) ? (int) $decoded['index'] : null;
            }
        }
        return null;
    }

    /**
     * Get current playlist state.
     */
    public function getPlaylist(): array
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.getPlaylist', '{}');
            if ($result) {
                return json_decode($result, true) ?? [];
            }
        }
        return [];
    }

    /**
     * Set repeat mode.
     */
    public function setRepeatMode(string $mode): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setRepeatMode', json_encode(['mode' => $mode]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Set shuffle mode.
     */
    public function setShuffleMode(bool $enabled): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setShuffleMode', json_encode(['shuffle' => $enabled]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Append track to playlist.
     */
    public function appendTrack(array $track): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.appendTrack', json_encode(['track' => $track]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Remove track by index.
     */
    public function removeTrack(int $index): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.removeTrack', json_encode(['index' => $index]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Set sleep timer.
     */
    public function setSleepTimer(int $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setSleepTimer', json_encode(['seconds' => $seconds]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Cancel sleep timer.
     */
    public function cancelSleepTimer(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.cancelSleepTimer', '{}');
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }

    /**
     * Set progress update interval.
     */
    public function setProgressInterval(float $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setProgressInterval', json_encode(['seconds' => max(0.5, min(60, $seconds))]));
            if ($result) {
                $decoded = json_decode($result, true);
                return (bool) ($decoded['success'] ?? false);
            }
        }
        return false;
    }
}
