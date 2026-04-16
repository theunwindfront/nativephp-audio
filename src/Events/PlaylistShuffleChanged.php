<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistShuffleChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public bool $shuffle
    ) {
    }
}
