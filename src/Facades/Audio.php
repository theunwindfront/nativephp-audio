<?php

namespace Theunwindfront\Audio\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static bool play(string $url)
 * @method static bool pause()
 * @method static bool resume()
 * @method static bool stop()
 * @method static bool seek(float $seconds)
 * @method static bool setVolume(float $level)
 * @method static float|null getDuration()
 * @method static float|null getCurrentPosition()
 * @method static bool setMetadata(array $metadata)
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
