package com.kv4p.desktop

import android.util.Log
import com.vagell.kv4pht.radio.DesktopRadioSession
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sound through command-line players instead of Java Sound. Java Sound only sees raw ALSA
 * hardware, which PipeWire or PulseAudio usually holds open (the Cardputer Zero's ES8389 is
 * "busy"), and ALSA's "default" there doesn't lead to PipeWire either. So this prefers the
 * sound server's own tools and falls back to ALSA:
 *   pacat (PulseAudio / pipewire-pulse)  ->  pw-cat (PipeWire)  ->  aplay/arecord (ALSA)
 *
 * -Dkv4p.audio=alsa forces it (the Cardputer framebuffer mode does), -Dkv4p.audio=javasound
 * turns it off; -Dkv4p.audioTool=pacat|pw-cat|alsa picks the tool, -Dkv4p.alsaDevice the ALSA device.
 */
object AlsaAudio {
    private const val TAG = "AlsaAudio"

    val device: String get() = System.getProperty("kv4p.alsaDevice") ?: "default"

    private fun exists(name: String) = listOf("/usr/bin/$name", "/bin/$name").any { File(it).canExecute() }

    /** Which tool is used: "pacat", "pw-cat" or "alsa"; null when none is installed. */
    val tool: String? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return@lazy null
        if (System.getProperty("kv4p.audio") == "javasound") return@lazy null
        System.getProperty("kv4p.audioTool")?.let { return@lazy it }
        when {
            exists("pacat") -> "pacat"
            exists("pw-cat") -> "pw-cat"
            exists("aplay") -> "alsa"
            else -> null
        }
    }

    val available: Boolean get() = tool != null

    val preferred: Boolean get() = available && System.getProperty("kv4p.audio") == "alsa"

    private fun args(record: Boolean): List<String> {
        val rate = DesktopRadioSession.AUDIO_SAMPLE_RATE.toString()
        // ~120 ms buffer for playback: low latency, still smooth on a slow CPU.
        return when (tool) {
            "pacat" -> listOf(
                "pacat", if (record) "--record" else "--playback", "--raw", "--format=s16le", "--rate=$rate",
                "--channels=1", "--latency-msec=" + if (record) "40" else "120", "--client-name=kv4p HT",
                "--stream-name=" + if (record) "microphone" else "radio",
            )
            "pw-cat" -> listOf(
                "pw-cat", if (record) "--record" else "--playback", "--raw", "--format", "s16", "--rate", rate,
                "--channels", "1", "--latency", if (record) "40ms" else "120ms", "-",
            )
            else -> listOf(
                if (record) "arecord" else "aplay", "-q", "-D", device, "-t", "raw", "-f", "S16_LE", "-c", "1",
                "-r", rate, "--buffer-time=120000",
            )
        }
    }

    val description: String get() = if (tool == "alsa") "aplay/arecord on $device" else tool.orEmpty()

    fun start(record: Boolean): Process? = try {
        ProcessBuilder(args(record))
            .redirectError(ProcessBuilder.Redirect.appendTo(File(AppLog.dir, "audio-tool.log")))
            .start()
    } catch (e: Exception) {
        Log.e(TAG, "Could not start $tool: ${e.message}")
        null
    }

    /** Recorder writing 48 kHz mono S16_LE to its stdout. */
    fun record(): Process? = start(record = true)
}

/**
 * Feeds the player (pacat, pw-cat or aplay) from its own thread, so a stalled player never blocks the radio thread;
 * frames are dropped when it falls behind.
 * (Works with any of the players in [AlsaAudio].)
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
            val p = AlsaAudio.start(record = false) ?: return null
            Log.i("AlsaAudio", "Audio output via ${AlsaAudio.description}")
            return AlsaPlayer(p)
        }
    }
}
