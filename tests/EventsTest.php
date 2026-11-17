<?php

namespace Theunwindfront\Audio\Tests;

use Native\Mobile\Events\Contracts\DispatchableFromNative;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Theunwindfront\Audio\Events;

class EventsTest extends TestCase
{
    /**
     * Every event listed in nativephp.json, paired with a class name.
     */
    public static function manifestEvents(): array
    {
        $manifest = json_decode(file_get_contents(__DIR__.'/../nativephp.json'), true);

        return collect($manifest['events'])
            ->mapWithKeys(fn (string $class) => [class_basename($class) => [$class]])
            ->all();
    }

    #[DataProvider('manifestEvents')]
    public function test_event_can_be_dispatched_from_native(string $class): void
    {
        $this->assertTrue(class_exists($class), "{$class} does not exist");
        $this->assertTrue(
            is_subclass_of($class, DispatchableFromNative::class),
            "{$class} must implement DispatchableFromNative or NativePHP will refuse to dispatch it"
        );

        // NativePHP unpacks the payload as named arguments.
        $event = new $class(...$this->statePayload(), ...['unknownFutureKey' => 'ignored']);

        $this->assertSame('https://example.com/song.mp3', $event->url);
        $this->assertSame(12.5, $event->position);
        $this->assertSame('ignored', $event->payload['unknownFutureKey']);
    }

    #[DataProvider('manifestEvents')]
    public function test_event_can_be_built_without_payload(string $class): void
    {
        $event = new $class();

        $this->assertSame([], $event->payload);
        $this->assertNull($event->url);
    }

    public function test_playlist_track_changed_maps_its_fields(): void
    {
        $event = new Events\PlaylistTrackChanged(...[
            'index' => 2,
            'reason' => 'auto',
            'track' => ['url' => 'https://example.com/b.mp3', 'title' => 'B'],
            'lastIndex' => 1,
            'lastPosition' => 180,
            'lastTrack' => ['url' => 'https://example.com/a.mp3'],
        ]);

        $this->assertSame(2, $event->index);
        $this->assertSame('auto', $event->reason);
        $this->assertSame('https://example.com/b.mp3', $event->url);
        $this->assertSame(1, $event->lastIndex);
        $this->assertSame(180.0, $event->lastPosition);
        $this->assertSame('https://example.com/a.mp3', $event->lastTrack['url']);
    }

    public function test_android_playlist_track_changed_without_reason_still_builds(): void
    {
        $event = new Events\PlaylistTrackChanged(...['index' => 0, 'track' => ['url' => 'x.mp3']]);

        $this->assertSame(0, $event->index);
        $this->assertNull($event->reason);
    }

    public function test_specific_event_fields(): void
    {
        $failed = new Events\PlaybackFailed(...['track' => ['url' => 'x.mp3'], 'error' => 'what: 1 extra: -1004']);
        $this->assertSame('x.mp3', $failed->url);
        $this->assertSame('what: 1 extra: -1004', $failed->error);

        $seeked = new Events\PlaybackSeeked(...['from' => 10, 'to' => 42.5]);
        $this->assertSame(10.0, $seeked->from);
        $this->assertSame(42.5, $seeked->to);

        $this->assertSame(3, (new Events\PlaylistSet(...['total' => 3]))->total);
        $this->assertSame('all', (new Events\PlaylistRepeatModeChanged(...['mode' => 'all']))->mode);
        $this->assertTrue((new Events\PlaylistShuffleChanged(...['shuffle' => true]))->shuffle);

        $meta = new Events\StreamMetadataChanged(...['track' => ['url' => 's'], 'metadata' => ['title' => 'Now']]);
        $this->assertSame(['title' => 'Now'], $meta->metadata);
    }

    public function test_mismatched_types_do_not_break_the_event(): void
    {
        $event = new Events\PlaybackProgressUpdated(...['position' => ['not' => 'a number'], 'duration' => 200]);

        $this->assertNull($event->position);
        $this->assertSame(200.0, $event->duration);
        $this->assertSame(['not' => 'a number'], $event->payload['position']);
    }

    public function test_state_alias_is_kept_for_existing_listeners(): void
    {
        $event = new Events\AudioFocusDucked(...$this->statePayload());

        $this->assertSame($event->payload, $event->state);
    }

    private function statePayload(): array
    {
        return [
            'track' => ['url' => 'https://example.com/song.mp3', 'title' => 'Song'],
            'position' => 12.5,
            'duration' => 200,
            'isPlaying' => true,
            'isBuffering' => false,
            'playbackRate' => 1,
            'repeatMode' => 'none',
            'shuffleMode' => false,
            'playlistIndex' => 0,
            'playlistTotal' => 1,
            'isStream' => false,
        ];
    }
}
