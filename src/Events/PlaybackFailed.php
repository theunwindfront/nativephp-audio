<?php

namespace Theunwindfront\Audio\Events;

class PlaybackFailed extends AudioEvent
{
    public ?string $error = null;
}
