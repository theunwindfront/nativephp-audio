<?php

namespace Theunwindfront\Audio;

class Audio
{
    /**
     * Play an audio file from a URL or local path
     */
    public function play(string $url): bool
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Audio.play', json_encode(['url' => $url]));

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
     * Set the audio volume
     *
     * @param  float  $level  Volume level from 0.0 (mute) to 1.0 (maximum)
     */
    public function setVolume(float $level): bool
    {
        $level = max(0.0, min(1.0, $level));

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

                return isset($decoded->duration) ? (float) $decoded->duration : null;
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

                return isset($decoded->position) ? (float) $decoded->position : null;
            }
        }

        return null;
    }

    /**
     * Set track metadata for lock screens and media centers
     *
     * @param  array{title: string, artist?: string, album?: string, artwork?: string, duration?: float}  $metadata
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
     * Set the playlist queue natively
     *
     * @param  array<int, array{url: string, title?: string, artist?: string, album?: string, artwork?: string, duration?: float}>  $tracks
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
}


