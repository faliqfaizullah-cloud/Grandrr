package com.grandrr.camera

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.media.*
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Records exactly what you see (camera + effect) into an MP4: every frame the camera preview and the
 * effect layer are drawn onto a MediaCodec input surface, so the filter is baked into the video.
 * Audio comes from the microphone (AAC). Call [frame] once per display frame on the main thread.
 */
class FxRecorder(
    private val encW: Int,
    private val encH: Int,
    private val srcW: Int,
    private val srcH: Int,
    private val wantAudio: Boolean,
    private val outFile: File,
    private val drawFrame: (Canvas) -> Unit,
    private val onFinished: (Boolean) -> Unit
) {
    private class Sample(val video: Boolean, val data: ByteArray, val pts: Long, val flags: Int)

    private var venc: MediaCodec? = null
    private var surface: Surface? = null
    private var aenc: MediaCodec? = null
    private var arec: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private val lock = Object()
    private val pending = ArrayList<Sample>()
    private var vTrack = -1
    private var aTrack = -1
    private var started = false
    private var muxOk = true
    private var lastV = -1L
    private var lastA = -1L
    private var vBase = -1L
    @Volatile private var running = false
    @Volatile private var firstSubmitUs = -1L
    private var startUs = 0L
    private var lastFrameNs = 0L
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private val audioRate = 44100

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val vf = MediaFormat.createVideoFormat("video/avc", encW, encH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val ve = MediaCodec.createEncoderByType("video/avc")
            ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = ve.createInputSurface()
            ve.start()
            venc = ve

            if (wantAudio) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(audioRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, audioRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 16384))
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        val af = MediaFormat.createAudioFormat("audio/mp4a-latm", audioRate, 1).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                        }
                        val ae = MediaCodec.createEncoderByType("audio/mp4a-latm")
                        ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        ae.start()
                        arec = rec; aenc = ae
                    } else rec.release()
                } catch (_: Exception) { arec = null; aenc = null }
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            startUs = System.nanoTime() / 1000
            running = true
            vThread = thread { drainVideo() }
            if (aenc != null) aThread = thread { audioLoop() }
            return true
        } catch (e: Exception) {
            running = false
            try { venc?.release() } catch (_: Exception) {}
            try { aenc?.release() } catch (_: Exception) {}
            try { arec?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            return false
        }
    }

    /** Main thread, once per display frame. Draws a frame at ~30 fps into the encoder. */
    fun frame(nowNs: Long) {
        if (!running) return
        if (nowNs - lastFrameNs < 31_000_000L) return
        lastFrameNs = nowNs
        val s = surface ?: return
        try {
            val c = s.lockHardwareCanvas()
            try {
                c.drawColor(Color.BLACK)
                c.save()
                c.scale(encW / srcW.toFloat(), encH / srcH.toFloat())
                drawFrame(c)
                c.restore()
            } finally { s.unlockCanvasAndPost(c) }
            if (firstSubmitUs < 0) firstSubmitUs = System.nanoTime() / 1000
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!running) return
        running = false
        try { venc?.signalEndOfInputStream() } catch (_: Exception) {}
        thread {
            try { vThread?.join() } catch (_: Exception) {}
            try { aThread?.join() } catch (_: Exception) {}
            var ok = started && muxOk
            try { if (started) muxer?.stop() } catch (_: Exception) { ok = false }
            try { muxer?.release() } catch (_: Exception) {}
            try { venc?.stop() } catch (_: Exception) {}
            try { venc?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            onFinished(ok)
        }
    }

    // ------------------------------------------------------------------ video output
    private fun drainVideo() {
        val enc = venc ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            val i = try { enc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    vTrack = muxer!!.addTrack(enc.outputFormat); maybeStart()
                }
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        if (vBase < 0) vBase = info.presentationTimeUs
                        val off = max(0L, firstSubmitUs - startUs)
                        write(true, buf, info, info.presentationTimeUs - vBase + off)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        }
    }

    // ------------------------------------------------------------------ audio
    private fun audioLoop() {
        val rec = arec ?: return
        val enc = aenc ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        var samples = 0L
        var aStart = -1L
        try {
            rec.startRecording()
            while (true) {
                val stop = !running
                val n = if (stop) 0 else rec.read(pcm, 0, pcm.size)
                if (n > 0) {
                    if (aStart < 0) aStart = System.nanoTime() / 1000 - startUs - (n / 2) * 1_000_000L / audioRate
                    var off = 0
                    while (off < n) {
                        val ii = enc.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = enc.getInputBuffer(ii)!!
                            ib.clear()
                            val len = min(n - off, ib.capacity())
                            ib.put(pcm, off, len)
                            enc.queueInputBuffer(ii, 0, len, max(0L, aStart) + samples * 1_000_000L / audioRate, 0)
                            samples += len / 2; off += len
                        }
                        drainAudio(enc, info)
                    }
                } else if (!stop) {
                    drainAudio(enc, info)
                }
                if (stop || n < 0) {
                    var queued = false
                    while (!queued) {
                        val ii = enc.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            enc.queueInputBuffer(ii, 0, 0, max(0L, aStart) + samples * 1_000_000L / audioRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            queued = true
                        }
                    }
                    var eos = false
                    while (!eos) eos = drainAudio(enc, info, 10_000)
                    break
                }
            }
        } catch (_: Exception) {
            // keep going without audio rather than failing the whole video
            synchronized(lock) { if (aTrack < 0) { aTrack = -2; maybeStart() } }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long = 0): Boolean {
        while (true) {
            val i = enc.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    aTrack = muxer!!.addTrack(enc.outputFormat); maybeStart()
                }
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) write(false, buf, info, info.presentationTimeUs)
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    // ------------------------------------------------------------------ muxer (shared by both threads)
    private fun maybeStart() {
        if (started || vTrack < 0) return
        if (aenc != null && aTrack == -1) return
        muxer!!.start(); started = true
        pending.sortBy { it.pts }
        for (s in pending) writeNow(s.video, ByteBuffer.wrap(s.data), 0, s.data.size, s.pts, s.flags)
        pending.clear()
    }

    private fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo, ptsIn: Long) {
        synchronized(lock) {
            var pts = max(0L, ptsIn)
            if (video) { if (pts <= lastV) pts = lastV + 1; lastV = pts } else { if (pts <= lastA) pts = lastA + 1; lastA = pts }
            if (!started) {
                val d = ByteArray(info.size)
                val dup = buf.duplicate(); dup.position(info.offset); dup.limit(info.offset + info.size); dup.get(d)
                pending.add(Sample(video, d, pts, info.flags))
            } else writeNow(video, buf, info.offset, info.size, pts, info.flags)
        }
    }

    private fun writeNow(video: Boolean, buf: ByteBuffer, offset: Int, size: Int, pts: Long, flags: Int) {
        val track = if (video) vTrack else aTrack
        if (track < 0) return
        val bi = MediaCodec.BufferInfo()
        bi.set(offset, size, pts, flags)
        try { muxer!!.writeSampleData(track, buf, bi) } catch (_: Exception) { muxOk = false }
    }
}
