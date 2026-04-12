<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class SleepTimerExpired
{
    use Dispatchable, SerializesModels;

    public function __construct()
    {
    }
}
