<?php

namespace Theunwindfront\Audio\Events;

class PlaybackStateChanged extends AudioEvent
{
    public ?bool $metadataChanged = null;
}
