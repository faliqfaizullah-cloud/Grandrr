package com.grandrr.camera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Small synth: piano, glockenspiel, pad, Karplus-Strong pluck, warm keys and electric crackle, with a light reverb tail. */
class SoundEngine {
    @Volatile var enabled = true
    private val rate = 22050
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<Int, ShortArray>()
    private val active = AtomicInteger(0)
    private val scale = doubleArrayOf(261.63, 293.66, 329.63, 392.0, 440.0, 523.25, 587.33, 659.25, 784.0, 880.0)

    private fun freq(t: Timbre, i: Int): Double = when (t) {
        Timbre.WARM, Timbre.PAD -> scale[i] / 2
        else -> scale[i]
    }

    private fun partials(f: Double, secs: Double, ratios: DoubleArray, amps: DoubleArray, dec: DoubleArray, attack: Double): FloatArray {
        val n = (rate * secs).toInt()
        val out = FloatArray(n)
        for (k in ratios.indices) {
            val fk = f * ratios[k]
            for (i in 0 until n) {
                val tt = i / rate.toDouble()
                out[i] += (amps[k] * sin(2 * PI * fk * tt) * exp(-dec[k] * tt) * min(1.0, tt / attack)).toFloat()
            }
        }
        return out
    }

    private fun render(t: Timbre, f: Double): ShortArray {
        val rnd = Random(f.toLong())
        return when (t) {
            Timbre.PIANO -> {
                val r = DoubleArray(6) { (it + 1) * (1 + 0.0004 * (it + 1) * (it + 1)) }
                val x = partials(f, 2.4, r, doubleArrayOf(1.0, 0.62, 0.38, 0.22, 0.14, 0.08),
                    DoubleArray(6) { 1.4 + 0.9 * it }, 0.004)
                val hn = (rate * 0.02).toInt()
                for (i in 0 until hn) x[i] += (rnd.nextFloat() - 0.5f) * 0.25f * (1f - i / hn.toFloat())
                finish(x, true)
            }
            Timbre.BELL -> finish(partials(f, 2.4, doubleArrayOf(1.0, 2.76, 5.4, 8.93),
                doubleArrayOf(1.0, 0.55, 0.28, 0.12), doubleArrayOf(1.8, 3.0, 4.6, 6.5), 0.002), true)
            Timbre.WARM -> finish(partials(f, 2.2, doubleArrayOf(1.0, 2.0, 3.0, 4.0),
                doubleArrayOf(1.0, 0.45, 0.2, 0.08), doubleArrayOf(1.0, 1.8, 2.6, 3.4), 0.015), true)
            Timbre.PAD -> {
                val n = (rate * 3.0).toInt()
                val x = FloatArray(n) {
                    val tt = it / rate.toDouble()
                    val env = min(1.0, tt / 0.45) * exp(-0.9 * tt)
                    val v = sin(2 * PI * f * tt) + 0.6 * sin(2 * PI * f * 1.004 * tt) +
                            0.5 * sin(2 * PI * f * 0.996 * tt) + 0.25 * sin(2 * PI * 2 * f * tt)
                    (v * env * (0.8 + 0.2 * sin(2 * PI * 4.5 * tt))).toFloat()
                }
                finish(x, true)
            }
            Timbre.PLUCK -> {
                val n = (rate * 2.0).toInt()
                val len = max(2, (rate / f).toInt())
                val buf = FloatArray(len) { rnd.nextFloat() * 2f - 1f }
                for (i in 1 until len) buf[i] = 0.5f * (buf[i] + buf[i - 1])
                val x = FloatArray(n)
                for (i in 0 until n) {
                    val a = i % len; val b = (a + 1) % len
                    x[i] = buf[a]
                    buf[a] = 0.5f * (buf[a] + buf[b]) * 0.9965f
                }
                finish(x, true)
            }
            Timbre.SPARK -> {
                val n = (rate * 0.4).toInt()
                val bursts = FloatArray(7) { rnd.nextFloat() * 0.3f }
                var prev = 0f
                val x = FloatArray(n) {
                    val tt = it / rate.toFloat()
                    val nz = rnd.nextFloat() - 0.5f
                    val hp = nz - prev; prev = nz
                    var env = 0f
                    for (b in bursts) if (tt >= b) env += exp(-(tt - b) * 260f)
                    hp * env + 0.15f * sin(2f * PI.toFloat() * (100f + (f / 8).toFloat()) * tt) * exp(-12f * tt)
                }
                finish(x, false)
            }
        }
    }

    private fun finish(x: FloatArray, reverb: Boolean): ShortArray {
        val extra = if (reverb) (rate * 0.5).toInt() else 0
        val y = FloatArray(x.size + extra)
        val d1 = (rate * 0.19).toInt(); val d2 = (rate * 0.37).toInt()
        for (i in x.indices) {
            y[i] += x[i]
            if (reverb) {
                if (i + d1 < y.size) y[i + d1] += x[i] * 0.32f
                if (i + d2 < y.size) y[i + d2] += x[i] * 0.18f
            }
        }
        var peak = 1e-6f
        for (v in y) peak = max(peak, abs(v))
        val g = 0.7f * 32767f / peak
        val tail = min(1000, y.size)
        return ShortArray(y.size) {
            val fade = if (it >= y.size - tail) (y.size - it) / tail.toFloat() else 1f
            (y[it] * g * fade).toInt().toShort()
        }
    }

    fun play(pos: Float, vol: Float, t: Timbre) {
        if (!enabled || active.get() >= 6) return
        val i = (pos * scale.size).toInt().coerceIn(0, scale.size - 1)
        val key = t.ordinal * 100 + (if (t == Timbre.SPARK) i % 3 else i)
        active.incrementAndGet()
        pool.execute {
            try {
                val data = cache.getOrPut(key) { render(t, freq(t, i)) }
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
                Thread.sleep(data.size * 1000L / rate + 120)
                track.release()
            } catch (_: Exception) {
            } finally { active.decrementAndGet() }
        }
    }
}
