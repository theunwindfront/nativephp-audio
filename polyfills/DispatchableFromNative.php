<?php

namespace Native\Mobile\Events\Contracts;

// NativePHP Mobile only dispatches native events into PHP when the event class
// implements this marker. Declare it when an older nativephp/mobile release
// (or a test environment without the package) doesn't provide it.
if (! interface_exists(DispatchableFromNative::class)) {
    interface DispatchableFromNative
    {
    }
}
