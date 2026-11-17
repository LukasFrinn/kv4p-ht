package com.kv4p.desktop

import android.util.Log
import com.vagell.kv4pht.radio.DesktopRadioSession
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sound through the `aplay` / `arecord` commands (alsa-utils) instead of Java Sound.
 * Java Sound only sees raw ALSA hardware, which PipeWire or PulseAudio usually holds open;
 * the commands go through ALSA's "default" device and so through PipeWire like any other app.
 *
 * -Dkv4p.audio=alsa forces it (the Cardputer framebuffer mode does), -Dkv4p.audio=javasound
 * turns it off; -Dkv4p.alsaDevice picks another ALSA device.
 */
object AlsaAudio {
    private const val TAG = "AlsaAudio"

    val device: String get() = System.getProperty("kv4p.alsaDevice") ?: "default"

    val available: Boolean by lazy {
        System.getProperty("os.name").lowercase().contains("linux") &&
            System.getProperty("kv4p.audio") != "javasound" &&
            listOf("/usr/bin/aplay", "/bin/aplay").any { File(it).canExecute() }
    }

    val preferred: Boolean get() = available && System.getProperty("kv4p.audio") == "alsa"

    private fun args(tool: String) = listOf(
        tool, "-q", "-D", device, "-t", "raw", "-f", "S16_LE", "-c", "1",
        "-r", DesktopRadioSession.AUDIO_SAMPLE_RATE.toString(),
        // ~120 ms buffer: low latency, still smooth on a slow CPU.
        "--buffer-time=120000",
    )

    fun start(tool: String): Process? = try {
        ProcessBuilder(args(tool))
            .redirectError(ProcessBuilder.Redirect.appendTo(File(AppLog.dir, "alsa.log")))
            .start()
    } catch (e: Exception) {
        Log.e(TAG, "Could not start $tool: ${e.message}")
        null
    }

    /** arecord writing 48 kHz mono S16_LE to its stdout. */
    fun record(): Process? = start("arecord")
}

/**
 * Feeds `aplay` from its own thread, so a stalled player never blocks the radio thread;
 * frames are dropped when it falls behind.
 */
class AlsaPlayer private constructor(private val process: Process) {
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    @Volatile private var running = true
    private val thread = Thread({ pump() }, "kv4p-aplay").apply { isDaemon = true; start() }

    fun offer(frame: ByteArray) {
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    private fun pump() {
        val out = process.outputStream
        try {
            while (running) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(frame)
                out.flush()
            }
        } catch (e: Exception) {
            if (running) Log.w("AlsaAudio", "aplay stopped: ${e.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { process.outputStream.close() }
        process.destroy()
        thread.join(300)
    }

    companion object {
        fun start(): AlsaPlayer? {
            val p = AlsaAudio.start("aplay") ?: return null
            Log.i("AlsaAudio", "Audio output via aplay (${AlsaAudio.device})")
            return AlsaPlayer(p)
        }
    }
}
