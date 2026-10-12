@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shizington.tv.ui.player

import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Shifts sound against picture. Video frames are timed off the audio clock, so reporting the clock
 * [delayUs] ahead makes the picture run that much earlier, i.e. the sound plays later relative to it.
 * Negative values pull the sound earlier.
 */
class AudioSyncSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile var delayUs: Long = 0

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return (position + delayUs).coerceAtLeast(0)
    }
}
