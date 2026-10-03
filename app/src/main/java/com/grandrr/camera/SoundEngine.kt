package com.grandrr.camera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tiny synth: soft bell/piano-ish notes on a pentatonic scale, triggered by motion. */
class SoundEngine {
    @Volatile var enabled = true
    private val rate = 22050
    private val scale = doubleArrayOf(261.63, 293.66, 329.63, 392.0, 440.0, 523.25, 587.33, 659.25, 784.0)
    private val cache = arrayOfNulls<ShortArray>(scale.size)

    private fun note(i: Int): ShortArray = cache[i] ?: run {
        val f = scale[i]
        val n = (rate * 1.4).toInt()
        val s = ShortArray(n) {
            val t = it / rate.toDouble()
            val v = sin(2 * PI * f * t) * exp(-3.0 * t) + 0.35 * sin(2 * PI * f * 2 * t) * exp(-5.0 * t) +
                    0.12 * sin(2 * PI * f * 3.01 * t) * exp(-8.0 * t)
            (v * 9000).toInt().toShort()
        }
        cache[i] = s
        s
    }

    fun play(pos: Float, vol: Float) {
        if (!enabled) return
        val i = (pos * scale.size).toInt().coerceIn(0, scale.size - 1)
        thread {
            try {
                val data = note(i)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(data, 0, data.size)
                track.setVolume(0.25f + 0.6f * vol)
                track.play()
                Thread.sleep(1500)
                track.release()
            } catch (_: Exception) {}
        }
    }
}
