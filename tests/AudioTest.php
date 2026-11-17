<?php

namespace Theunwindfront\Audio\Tests;

use Theunwindfront\Audio\Audio;
use Theunwindfront\Audio\Facades\Audio as AudioFacade;
use Theunwindfront\Audio\AudioServiceProvider;
use Orchestra\Testbench\TestCase;

class AudioTest extends TestCase
{
    protected function getPackageProviders($app)
    {
        return [
            AudioServiceProvider::class,
        ];
    }

    protected function getPackageAliases($app)
    {
        return [
            'Audio' => AudioFacade::class,
        ];
    }

    public function test_audio_facade_can_be_resolved()
    {
        $this->assertInstanceOf(Audio::class, $this->app->make(Audio::class));
    }

    public function test_play_returns_false_when_native_call_not_available()
    {
        $audio = new Audio();
        $this->assertFalse($audio->play('https://www.w3schools.com/html/horse.mp3'));
    }

    public function test_native_event_endpoint_dispatches_audio_events()
    {
        if (! class_exists(\Native\Mobile\Http\Controllers\DispatchEventFromAppController::class)) {
            $this->markTestSkipped('nativephp/mobile is not installed.');
        }

        \Illuminate\Support\Facades\Event::fake([\Theunwindfront\Audio\Events\PlaybackStarted::class]);

        $request = \Illuminate\Http\Request::create('/_native/api/events', 'POST', [
            'event' => \Theunwindfront\Audio\Events\PlaybackStarted::class,
            'payload' => [
                'track' => ['url' => 'https://example.com/song.mp3'],
                'position' => 0,
                'isPlaying' => true,
            ],
        ]);

        $response = app(\Native\Mobile\Http\Controllers\DispatchEventFromAppController::class)($request);

        $this->assertTrue($response->getData(true)['success']);
        \Illuminate\Support\Facades\Event::assertDispatched(
            \Theunwindfront\Audio\Events\PlaybackStarted::class,
            fn ($event) => $event->url === 'https://example.com/song.mp3' && $event->isPlaying === true
        );
    }
}
