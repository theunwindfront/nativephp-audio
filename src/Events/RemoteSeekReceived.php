<?php

namespace Theunwindfront\Audio\Events;

class RemoteSeekReceived extends AudioEvent
{
    public ?float $from = null;
    public ?float $to = null;
}
