<?php

namespace Theunwindfront\Audio\Events;

class StreamMetadataChanged extends AudioEvent
{
    public ?array $metadata = null;
}
