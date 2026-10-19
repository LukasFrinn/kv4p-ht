package com.kv4p.desktop

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Collects log lines from the shared radio code (via the android.util.Log stub)
 * into a rolling file the user can send when something doesn't work:
 *   ~/.kv4p-desktop/kv4p.log  (previous run: kv4p.log.1)
 */
object AppLog {
    val dir: File = File(System.getProperty("user.home"), ".kv4p-desktop")
    val file: File = File(dir, "kv4p.log")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var writer: PrintWriter? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun init() {
        if (writer != null) return
        dir.mkdirs()
        if (file.exists()) {
            file.copyTo(File(dir, "kv4p.log.1"), overwrite = true)
        }
        writer = PrintWriter(file.bufferedWriter(), true)
        Log.addSink { priority, tag, message, error ->
            if (priority >= Log.DEBUG) {
                line(Log.priorityLabel(priority), tag, message, error)
            }
        }
        Log.i("App", "kv4p HT desktop starting; " +
            "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
            "Java ${System.getProperty("java.version")}")
    }

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    @Synchronized
    private fun line(level: String, tag: String, message: String, error: Throwable?) {
        val text = buildString {
            append(LocalTime.now().format(timeFormat)).append(' ')
            append(level).append('/').append(tag).append(": ").append(message)
            if (error != null) {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                append('\n').append(sw.toString().trimEnd())
            }
        }
        writer?.println(text)
        listeners.forEach { it(text) }
    }
}
