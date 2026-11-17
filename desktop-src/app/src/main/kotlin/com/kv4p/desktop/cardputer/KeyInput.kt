package com.kv4p.desktop.cardputer

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One key change from the keyboard. [char] is the typed character for printable keys. */
data class KeyPress(
    val code: Int,
    val down: Boolean,
    val repeat: Boolean,
    val char: Char?,
    val ctrl: Boolean,
    val fn: Boolean,
) {
    val pressed get() = down || repeat
}

/** Linux input key codes used by the compact UI (linux/input-event-codes.h). */
object Keys {
    const val ESC = 1
    const val BACKSPACE = 14
    const val TAB = 15
    const val ENTER = 28
    const val LEFT_CTRL = 29
    const val LEFT_SHIFT = 42
    const val RIGHT_SHIFT = 54
    const val SPACE = 57
    const val RIGHT_CTRL = 97
    const val UP = 103
    const val LEFT = 105
    const val RIGHT = 106
    const val DOWN = 108
    const val DELETE = 111
    const val FN = 0x1d0
    const val SEMICOLON = 39
    const val COMMA = 51
    const val DOT = 52
    const val SLASH = 53
    const val GRAVE = 41
    const val F = 33
    const val Z = 44
    const val X = 45
    const val C = 46
    const val MUTE = 113
    const val VOLUME_DOWN = 114
    const val VOLUME_UP = 115
}

/**
 * Reads a keyboard straight from /dev/input/event* (no X11/Wayland) on its own thread and
 * hands every key change to [onKey].
 */
class KeyInput(private val device: String, private val onKey: (KeyPress) -> Unit) {
    @Volatile private var running = true
    private var shift = false
    private var ctrl = false
    private var fn = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "kv4p-keys").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
    }

    private fun loop() {
        // struct input_event: timeval (2 longs), u16 type, u16 code, s32 value.
        val is64 = System.getProperty("os.arch").let { it.contains("64") }
        val size = if (is64) 24 else 16
        val buf = ByteArray(size)
        try {
            FileInputStream(device).use { input ->
                Log.i(TAG, "Reading keys from $device")
                while (running) {
                    var n = 0
                    while (n < size) {
                        val r = input.read(buf, n, size - n)
                        if (r < 0) return
                        n += r
                    }
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    val type = bb.getShort(size - 8).toInt() and 0xffff
                    val code = bb.getShort(size - 6).toInt() and 0xffff
                    val value = bb.getInt(size - 4)
                    if (type != EV_KEY) continue
                    handle(code, value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keyboard $device: ${e.message}")
        }
    }

    private fun handle(code: Int, value: Int) {
        val down = value == 1
        val held = value != 0
        when (code) {
            Keys.LEFT_SHIFT, Keys.RIGHT_SHIFT -> shift = held
            Keys.LEFT_CTRL, Keys.RIGHT_CTRL -> ctrl = held
            Keys.FN -> fn = held
        }
        val ch = if (held) charFor(code, shift) else null
        onKey(KeyPress(code, down, value == 2, ch, ctrl, fn))
    }

    companion object {
        private const val TAG = "KeyInput"
        private const val EV_KEY = 1

        private val plain = mapOf(
            2 to '1', 3 to '2', 4 to '3', 5 to '4', 6 to '5', 7 to '6', 8 to '7', 9 to '8', 10 to '9', 11 to '0',
            12 to '-', 13 to '=', 16 to 'q', 17 to 'w', 18 to 'e', 19 to 'r', 20 to 't', 21 to 'y', 22 to 'u',
            23 to 'i', 24 to 'o', 25 to 'p', 26 to '[', 27 to ']', 30 to 'a', 31 to 's', 32 to 'd', 33 to 'f',
            34 to 'g', 35 to 'h', 36 to 'j', 37 to 'k', 38 to 'l', 39 to ';', 40 to '\'', 41 to '`', 43 to '\\',
            44 to 'z', 45 to 'x', 46 to 'c', 47 to 'v', 48 to 'b', 49 to 'n', 50 to 'm', 51 to ',', 52 to '.',
            53 to '/', 57 to ' ',
        )
        private const val SHIFTED_DIGITS = "!@#$%^&*()"
        private val shifted = mapOf(
            '-' to '_', '=' to '+', '[' to '{', ']' to '}', ';' to ':', '\'' to '"', '`' to '~',
            '\\' to '|', ',' to '<', '.' to '>', '/' to '?',
        )

        fun charFor(code: Int, shift: Boolean): Char? {
            val c = plain[code] ?: return null
            if (!shift) return c
            if (c.isLetter()) return c.uppercaseChar()
            if (c.isDigit()) return SHIFTED_DIGITS[(c - '1').let { if (it < 0) 9 else it }]
            return shifted[c] ?: c
        }

        /** The Cardputer Zero's keyboard, else the first device that reports keys. */
        fun findKeyboard(requested: String?): String? {
            requested?.let { return it }
            File("/dev/input/cardputer-zero-internal").takeIf { it.exists() }?.let { return it.path }
            val devices = runCatching { File("/proc/bus/input/devices").readText() }.getOrNull() ?: return null
            val blocks = devices.split("\n\n")
            val pick = blocks.firstOrNull { it.contains("tca8418", ignoreCase = true) }
                ?: blocks.firstOrNull { it.contains("kbd") && it.contains("EV=1") }
                ?: blocks.firstOrNull { it.contains(" kbd ") }
            val event = pick?.let { Regex("event\\d+").find(it)?.value } ?: return null
            return "/dev/input/$event"
        }
    }
}
