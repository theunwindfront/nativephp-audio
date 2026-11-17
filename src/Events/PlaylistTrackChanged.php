<?php

namespace Theunwindfront\Audio\Events;

class PlaylistTrackChanged extends AudioEvent
{
    public ?int $index = null;
    public ?string $reason = null;
    public ?int $lastIndex = null;
    public ?float $lastPosition = null;
    public ?array $lastTrack = null;
}
