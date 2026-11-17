<?php

namespace Theunwindfront\Audio\Events;

class PlaybackSeeked extends AudioEvent
{
    public ?float $from = null;
    public ?float $to = null;
}
