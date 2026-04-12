<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaybackStateChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public bool $isPlaying,
        public bool $isBuffering,
        public bool $metadataChanged = false
    ) {
    }
}
