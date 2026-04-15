<?php

namespace Theunwindfront\Audio;

class Audio
{
    /**
     * Play an audio file from a URL or local path
     */
    public function play(string $url, array $metadata = []): bool
    {
        if (function_exists('nativephp_call')) {
            $params = array_merge(['url' => $url], $metadata);
            $result = nativephp_call('Audio.play', json_encode($params));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Play a specific track from the current playlist by its index
     */
    public function playTrackAt(int $index): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.playTrackAt', json_encode(['index' => $index]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Load an audio file without starting playback immediately
     */
    public function load(string $url, array $metadata = []): bool
    {
        if (function_exists('nativephp_call')) {
            $params = array_merge(['url' => $url], $metadata);
            $result = nativephp_call('Audio.load', json_encode($params));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
            $result = nativephp_call('Audio.seek', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
            $result = nativephp_call('Audio.setVolume', json_encode(['level' => $level]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
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
                $decoded = json_decode($result);

                return $decoded->duration ?? null;
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
                $decoded = json_decode($result);

                return $decoded->position ?? null;
            }
        }

        return null;
    }

    /**
     * Set track metadata (title, artist, album, artwork, duration)
     */
    public function setMetadata(array $metadata): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setMetadata', json_encode($metadata));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Set the playlist queue natively so tracks auto-advance
     */
    public function setPlaylist(array $tracks): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaylist', json_encode(['tracks' => $tracks]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Append a track to the end of the current playlist
     */
    public function appendTrack(array $track): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.appendTrack', json_encode($track));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Remove a track from the playlist by its index
     */
    public function removeTrack(int $index): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.removeTrack', json_encode(['index' => $index]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Skip to the next track in the playlist
     */
    public function next(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.nextTrack', '{}');

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Skip to the previous track in the playlist
     */
    public function previous(): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.previousTrack', '{}');

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Set repeat mode: none, one, all
     */
    public function setRepeatMode(string $mode): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setRepeatMode', json_encode(['mode' => $mode]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Enable or disable shuffle mode
     */
    public function setShuffleMode(bool $enabled): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setShuffleMode', json_encode(['enabled' => $enabled]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Stop playback after a set number of seconds
     */
    public function setSleepTimer(int $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setSleepTimer', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Change the audio playback speed
     */
    public function setPlaybackRate(float $rate): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setPlaybackRate', json_encode(['rate' => $rate]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Set how often progress update events fire (in seconds)
     */
    public function setProgressInterval(float $seconds): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.setProgressInterval', json_encode(['seconds' => $seconds]));

            if ($result) {
                $decoded = json_decode($result);

                return $decoded->success ?? false;
            }
        }

        return false;
    }

    /**
     * Get the full current state of the player and playlist
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
     * Clear and return all events queued while the app was in the background
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
}
