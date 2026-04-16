<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackSeeked
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public float $from,
        public float $to
    ) {
    }
}
