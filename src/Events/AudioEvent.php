<?php

namespace Theunwindfront\Audio\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Base class for every event the native audio player sends back to PHP.
 *
 * NativePHP builds the event from the native payload using named arguments,
 * so the constructor accepts any keys. Keys that match a declared property are
 * copied onto it; the full payload is always available as $payload.
 */
abstract class AudioEvent implements DispatchableFromNative
{
    use Dispatchable, SerializesModels;

    /** The raw payload sent by the native side. */
    public array $payload = [];

    /** Alias of $payload, kept for listeners written against v1.3 and earlier. */
    public array $state = [];

    public ?array $track = null;
    public ?string $url = null;
    public ?float $position = null;
    public ?float $duration = null;
    public ?bool $isPlaying = null;
    public ?bool $isBuffering = null;
    public ?float $playbackRate = null;
    public ?string $repeatMode = null;
    public ?bool $shuffleMode = null;
    public ?int $playlistIndex = null;
    public ?int $playlistTotal = null;
    public ?bool $isStream = null;
    public ?string $streamType = null;
    public ?string $mountpoint = null;

    public function __construct(mixed ...$payload)
    {
        $payload = array_filter($payload, 'is_string', ARRAY_FILTER_USE_KEY);

        $this->payload = $payload;
        $this->state = $payload;

        foreach ($payload as $key => $value) {
            if (in_array($key, ['payload', 'state'], true) || ! property_exists($this, $key)) {
                continue;
            }

            try {
                $this->{$key} = $value;
            } catch (\TypeError) {
                // Leave the property at its default; the value is still in $payload.
            }
        }

        if ($this->url === null && is_string($this->track['url'] ?? null)) {
            $this->url = $this->track['url'];
        }
    }
}
