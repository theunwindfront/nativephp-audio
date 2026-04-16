<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class PlaylistTrackChanged
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public int $index,
        public string $reason,
        public array $track,
        public ?int $lastIndex = null,
        public ?float $lastPosition = null,
        public ?array $lastTrack = null
    ) {
    }
}
