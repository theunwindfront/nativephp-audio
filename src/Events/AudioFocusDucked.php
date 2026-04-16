<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class AudioFocusDucked
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $state
    ) {
    }
}
