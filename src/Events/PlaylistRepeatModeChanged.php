<?php

namespace Theunwindfront\Audio\Events;

class PlaylistRepeatModeChanged extends AudioEvent
{
    public ?string $mode = null;
}
