package com.kv4p.desktop

import android.util.Log
import com.vagell.kv4pht.radio.DesktopRadioSession
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

/**
 * Captures the default microphone at 48 kHz mono and hands 40 ms frames to
 * [onFrame] while running. Runs on its own thread, started on PTT down and
 * stopped on PTT up.
 *
 * macOS asks for microphone permission the first time; if it was denied,
 * the line opens but delivers silence - [onLevel] then stays at 0.
 */
class MicInput(
    private val onFrame: (FloatArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private val format = AudioFormat(DesktopRadioSession.AUDIO_SAMPLE_RATE.toFloat(), 16, 1, true, false)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var line: TargetDataLine? = null
    private var process: Process? = null

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (AlsaAudio.preferred) {
            val p = AlsaAudio.record() ?: return false
            process = p
            running = true
            thread = Thread({ captureStream(p.inputStream) }, "kv4p-mic").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(TAG, "Microphone opened via arecord (${AlsaAudio.device})")
            return true
        }
        val l = try {
            AudioSystem.getTargetDataLine(format).also {
                it.open(format, DesktopRadioSession.AUDIO_FRAME_SAMPLES * 2 * 4)
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the microphone", e)
            return false
        }
        line = l
        running = true
        thread = Thread({ capture(l) }, "kv4p-mic").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Microphone opened: ${l.format}")
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        process?.destroy()
        process = null
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        line = null
        thread?.join(500)
        thread = null
        onLevel(0f)
    }

    private fun capture(l: TargetDataLine) = captureFrom { b, off, len -> l.read(b, off, len) }

    private fun captureStream(input: java.io.InputStream) {
        try {
            captureFrom { b, off, len -> input.read(b, off, len) }
        } catch (e: java.io.IOException) {
            if (running) Log.w(TAG, "arecord stopped: ${e.message}")
        }
    }

    private fun captureFrom(source: (ByteArray, Int, Int) -> Int) {
        val frame = FloatArray(DesktopRadioSession.AUDIO_FRAME_SAMPLES)
        val bytes = ByteArray(frame.size * 2)
        var silentFrames = 0
        while (running) {
            var read = 0
            while (read < bytes.size && running) {
                val n = source(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            if (!running || read < bytes.size) break
            var peak = 0f
            for (i in frame.indices) {
                val s = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
                frame[i] = s / 32768f
                peak = maxOf(peak, abs(frame[i]))
            }
            onLevel(peak)
            silentFrames = if (peak == 0f) silentFrames + 1 else 0
            if (silentFrames == 50) { // 2 s of digital silence
                Log.w(TAG, "Microphone delivers only silence. On macOS check System Settings > " +
                    "Privacy & Security > Microphone for kv4p-ht-desktop.")
            }
            onFrame(frame)
        }
    }

    private companion object {
        const val TAG = "MicInput"
    }
}
