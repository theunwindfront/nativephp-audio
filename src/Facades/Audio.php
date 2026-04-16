<?php

namespace Theunwindfront\Audio\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static bool play(string $url, array $options = [])
 * @method static bool load(string $url, array $options = [])
 * @method static bool pause()
 * @method static bool resume()
 * @method static bool stop()
 * @method static bool seek(float $seconds)
 * @method static bool setVolume(float $level)
 * @method static bool setPlaybackRate(float $rate)
 * @method static float|null getDuration()
 * @method static float|null getCurrentPosition()
 * @method static array getState()
 * @method static bool setMetadata(array $metadata)
 * @method static array drainEvents()
 * @method static bool setPlaylist(array $tracks, bool $autoPlay = true, int $startIndex = 0)
 * @method static bool next()
 * @method static bool previous()
 * @method static bool skipTo(int $index)
 * @method static bool playTrackAt(int $index)
 * @method static array|null getTrack(int $index)
 * @method static array|null getActiveTrack()
 * @method static int|null getActiveTrackIndex()
 * @method static array getPlaylist()
 * @method static bool setRepeatMode(string $mode)
 * @method static bool setShuffleMode(bool $enabled)
 * @method static bool appendTrack(array $track)
 * @method static bool removeTrack(int $index)
 * @method static bool setSleepTimer(int $seconds)
 * @method static bool cancelSleepTimer()
 * @method static bool setProgressInterval(float $seconds)
 *
 * @see \Theunwindfront\Audio\Audio
 */
class Audio extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Theunwindfront\Audio\Audio::class;
    }
}
