package com.kv4p.desktop

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Where is this computer? Used by the APRS tab's "Use this computer's location" button.
 * Tries, in order:
 *  1. macOS Location Services, through the kv4p-locate helper bundled in the .app,
 *  2. gpsd on localhost (Linux, e.g. the uConsole's GPS expansion),
 *  3. a rough position from the public IP address (city level).
 */
object PcLocation {
    private const val TAG = "PcLocation"

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Double?, val source: String) {
        fun describe(): String {
            val acc = accuracyM?.let { if (it >= 1000) String.format(Locale.US, " (±%.0f km)", it / 1000) else String.format(Locale.US, " (±%.0f m)", it) } ?: ""
            return "$source$acc"
        }
    }

    class Failure(message: String) : Exception(message)

    /** Blocking; call off the UI thread. Throws [Failure] with a readable reason. */
    fun locate(): Fix {
        val problems = mutableListOf<String>()
        if (isMac()) {
            try {
                macLocation()?.let { return it }
            } catch (e: Failure) {
                problems += e.message.orEmpty()
            }
        }
        try {
            gpsdLocation()?.let { return it }
        } catch (e: Exception) {
            Log.d(TAG, "gpsd: ${e.message}")
        }
        try {
            return ipLocation()
        } catch (e: Exception) {
            problems += "IP lookup failed (${e.message})"
        }
        throw Failure(problems.joinToString("; ").ifEmpty { "No location source available" })
    }

    private fun isMac() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // ---------------------------------------------------------------- macOS

    private fun macHelper(): File? {
        val dir = System.getProperty("compose.application.resources.dir") ?: return null
        return File(dir, "kv4p-locate").takeIf { it.canExecute() }
    }

    private fun macLocation(): Fix? {
        val helper = macHelper() ?: run {
            Log.d(TAG, "kv4p-locate helper not bundled (dev run?)")
            return null
        }
        val proc = ProcessBuilder(helper.absolutePath, "30").redirectErrorStream(true).start()
        if (!proc.waitFor(40, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw Failure("Location Services did not answer")
        }
        val line = proc.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
        Log.i(TAG, "kv4p-locate: $line")
        val parts = line.split(' ')
        if (parts.firstOrNull() == "OK" && parts.size >= 4) {
            return Fix(parts[1].toDouble(), parts[2].toDouble(), parts[3].toDoubleOrNull()?.takeIf { it > 0 }, "Location Services")
        }
        return when (line.removePrefix("ERR ").trim()) {
            "denied" -> throw Failure(
                "Location access denied - allow kv4p-ht-desktop in System Settings → Privacy & Security → Location Services"
            )
            else -> {
                Log.w(TAG, "Location Services: $line")
                null // fall through to the other sources
            }
        }
    }

    // ---------------------------------------------------------------- gpsd

    private fun gpsdLocation(): Fix? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 2947), 300)
            socket.soTimeout = 5000
            socket.getOutputStream().write("?WATCH={\"enable\":true,\"json\":true}\n".toByteArray())
            val reader = socket.getInputStream().bufferedReader()
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if (!line.contains("\"class\":\"TPV\"")) continue
                val mode = number(line, "mode")?.toInt() ?: 0
                if (mode < 2) continue // no fix yet
                val lat = number(line, "lat") ?: continue
                val lon = number(line, "lon") ?: continue
                val acc = listOfNotNull(number(line, "epx"), number(line, "epy")).maxOrNull()
                return Fix(lat, lon, acc, "GPS")
            }
        }
        return null
    }

    // ---------------------------------------------------------------- IP address

    private fun ipLocation(): Fix {
        val conn = URI("https://ipapi.co/json/").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "kv4p-ht-desktop")
        try {
            if (conn.responseCode != 200) throw Failure("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().readText()
            val lat = number(body, "latitude") ?: throw Failure("no position in reply")
            val lon = number(body, "longitude") ?: throw Failure("no position in reply")
            return Fix(lat, lon, 5000.0, "IP address, approximate")
        } finally {
            conn.disconnect()
        }
    }

    /** Reads a numeric JSON field without pulling in a JSON library. */
    private fun number(json: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
}
