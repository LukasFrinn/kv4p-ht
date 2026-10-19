package com.kv4p.desktop

import android.util.Log
import com.vagell.kv4pht.radio.DesktopRadioSession
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays received radio audio (48 kHz mono, as decoded from Opus) on the default output device.
 * Never blocks the radio thread: if the device falls behind, frames are dropped.
 */
class AudioOutput {
    private val format = AudioFormat(DesktopRadioSession.AUDIO_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private var line: SourceDataLine? = null
    private val bytes = ByteArray(DesktopRadioSession.AUDIO_FRAME_SAMPLES * 2)

    /** Silences playback while transmitting, like the Android app. */
    @Volatile
    var muted: Boolean = false

    /**
     * 0.0 .. MAX_VOLUME. Above 1.0 the audio is boosted digitally: the radio
     * delivers fairly quiet audio, and desktop speakers are weaker than phones.
     * A soft limiter keeps boosted peaks from clipping harshly.
     */
    @Volatile
    var volume: Float = 0.8f

    @Synchronized
    fun start() {
        if (line != null) return
        try {
            val l = AudioSystem.getSourceDataLine(format)
            // ~250 ms buffer (2 bytes/sample): absorbs USB jitter while keeping latency low.
            l.open(format, DesktopRadioSession.AUDIO_SAMPLE_RATE / 2)
            l.start()
            line = l
            Log.i(TAG, "Audio output opened: ${l.bufferSize} byte buffer")
        } catch (e: Exception) {
            Log.e(TAG, "Could not open audio output", e)
        }
    }

    @Synchronized
    fun stop() {
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        line = null
    }

    /** Called on the radio thread for every decoded frame. */
    fun write(pcm: FloatArray, samples: Int) {
        val l = line ?: return
        if (muted) return
        val gain = volume
        var j = 0
        for (i in 0 until samples) {
            val s = (softLimit(pcm[i] * gain) * 32767f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[j++] = (s and 0xFF).toByte()
            bytes[j++] = ((s shr 8) and 0xFF).toByte()
        }
        if (l.available() >= j) {
            l.write(bytes, 0, j)
        }
    }

    companion object {
        private const val TAG = "AudioOutput"
        const val MAX_VOLUME = 4f // +12 dB

        private const val KNEE = 0.7f

        /** Linear below the knee, then smoothly approaches 1.0 instead of clipping. */
        fun softLimit(x: Float): Float {
            val a = kotlin.math.abs(x)
            if (a <= KNEE) return x
            val over = (a - KNEE) / (1f - KNEE)
            val limited = KNEE + (1f - KNEE) * kotlin.math.tanh(over)
            return if (x < 0) -limited else limited
        }
    }
}
