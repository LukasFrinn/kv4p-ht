package com.kv4p.desktop.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Downloads the map tiles around a position so the APRS previews work without internet.
 * The tile server is run by volunteers, so this fetches one tile at a time with a short pause
 * and keeps the area modest; tiles already on disk are skipped.
 */
object OfflineMaps {
    private const val TAG = "OfflineMaps"
    /** Radius choices offered in the settings, km. */
    val RADII_KM = listOf(10, 20, 30)
    private const val PAUSE_MS = 120L
    private const val AVG_TILE_KB = 20

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    @Volatile private var cancelled = false

    private class Tile(val z: Int, val x: Int, val y: Int)

    /** Every tile from [MapConfig.OFFLINE_MIN_ZOOM] to [MapConfig.ZOOM] within [radiusKm] of the point. */
    private fun tilesAround(lat: Double, lon: Double, radiusKm: Int): List<Tile> {
        val out = ArrayList<Tile>()
        for (z in MapConfig.OFFLINE_MIN_ZOOM..MapConfig.ZOOM) {
            val (cx, cy) = TileMath.pixel(lat, lon, z)
            // Ground metres per tile pixel at this latitude and zoom.
            val metresPerPixel = 40_075_016.686 * cos(lat * PI / 180) / (256.0 * (1 shl z))
            val r = radiusKm * 1000 / metresPerPixel
            val max = (1 shl z) - 1
            val x0 = floor((cx - r) / 256).toInt().coerceIn(0, max)
            val x1 = floor((cx + r) / 256).toInt().coerceIn(0, max)
            val y0 = floor((cy - r) / 256).toInt().coerceIn(0, max)
            val y1 = floor((cy + r) / 256).toInt().coerceIn(0, max)
            for (x in x0..x1) for (y in y0..y1) out += Tile(z, x, y)
        }
        return out
    }

    /** "about 1,234 tiles, ~25 MB" for the settings line. */
    fun estimate(lat: Double, lon: Double, radiusKm: Int): String {
        val n = tilesAround(lat, lon, radiusKm).size
        return String.format(Locale.US, "about %,d tiles, ~%d MB", n, (n * AVG_TILE_KB + 512) / 1024)
    }

    fun cancel() {
        cancelled = true
    }

    fun download(lat: Double, lon: Double, radiusKm: Int) {
        if (running) return
        running = true
        cancelled = false
        status = "Preparing…"
        Thread({
            val tiles = tilesAround(lat, lon, radiusKm)
            var done = 0
            var fetched = 0
            var failed = 0
            var lastShown = 0L
            for (t in tiles) {
                if (cancelled) break
                done++
                if (!TileCache.file(t.z, t.x, t.y).isFile) {
                    try {
                        val bytes = TileCache.download(t.z, t.x, t.y)
                        if (bytes != null) {
                            TileCache.save(t.z, t.x, t.y, bytes)
                            fetched++
                        } else {
                            failed++
                        }
                    } catch (e: Exception) {
                        failed++
                        Log.w(TAG, "Tile ${t.z}/${t.x}/${t.y}: ${e.message}")
                        if (failed >= 20 && fetched == 0) {
                            status = "No connection to the map server - try again later"
                            running = false
                            return@Thread
                        }
                        Thread.sleep(2000)
                    }
                    Thread.sleep(PAUSE_MS)
                }
                val now = System.currentTimeMillis()
                if (now - lastShown > 300) {
                    lastShown = now
                    status = String.format(Locale.US, "Downloading %,d / %,d tiles (%d %%)", done, tiles.size, done * 100 / tiles.size)
                }
            }
            status = when {
                cancelled -> String.format(Locale.US, "Stopped at %,d / %,d tiles - start again to continue", done, tiles.size)
                failed > 0 -> String.format(Locale.US, "Done, %d km around your position (%,d tiles could not be downloaded)", radiusKm, failed)
                else -> "Done, $radiusKm km around your position is available offline"
            }
            Log.i(TAG, "Offline map $radiusKm km: ${tiles.size} tiles, $fetched downloaded, $failed failed")
            running = false
        }, "kv4p-offline-map").apply { isDaemon = true }.start()
    }
}
