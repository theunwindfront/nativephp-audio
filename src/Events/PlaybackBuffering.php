<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackBuffering
{
    use Dispatchable, SerializesModels;

    public function __construct(public string $url)
    {
    }
}
