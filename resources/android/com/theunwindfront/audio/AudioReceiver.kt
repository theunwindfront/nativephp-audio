package com.theunwindfront.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handle media controls from the notification.
 */
class AudioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = AudioFunctions.mediaSession?.controller ?: return
        
        when (intent.action) {
            AudioService.ACTION_TOGGLE -> {
                if (AudioFunctions.mediaPlayer?.isPlaying == true) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
            AudioService.ACTION_NEXT -> {
                controller.transportControls.skipToNext()
            }
            AudioService.ACTION_PREVIOUS -> {
                controller.transportControls.skipToPrevious()
            }
        }
    }
}
