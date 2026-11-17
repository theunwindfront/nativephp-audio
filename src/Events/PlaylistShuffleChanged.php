<?php

namespace Theunwindfront\Audio\Events;

class PlaylistShuffleChanged extends AudioEvent
{
    public ?bool $shuffle = null;
}
