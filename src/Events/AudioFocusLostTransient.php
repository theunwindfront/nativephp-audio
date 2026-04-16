<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class AudioFocusLostTransient
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $state
    ) {
    }
}
