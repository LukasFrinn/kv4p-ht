package com.kv4p.desktop.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Where the map pictures come from and where a click goes. Change here to switch services. */
object MapConfig {
    /** Topographic / hiking style tiles built from OpenStreetMap data. */
    private const val TILE_URL = "https://tile.opentopomap.org/%d/%d/%d.png"
    /** Folder name under ~/.kv4p-desktop/tiles, one per tile source. */
    const val CACHE_NAME = "opentopomap"
    const val ATTRIBUTION = "© OpenTopoMap, © OSM"
    /** Zoom of the previews; also the most detailed zoom the offline download fetches. */
    const val ZOOM = 14
    /** Least detailed zoom the offline download fetches. */
    const val OFFLINE_MIN_ZOOM = 6

    fun tileUrl(z: Int, x: Int, y: Int): String = String.format(Locale.US, TILE_URL, z, x, y)

    /** Opened when the preview is clicked. */
    fun linkFor(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://mapy.com/fnc/v1/showmap?mapset=outdoor&center=%.5f,%.5f&zoom=15&marker=true", lon, lat)
}

/** Web Mercator tile maths. */
internal object TileMath {
    /** Position of [lat]/[lon] in tile pixels (256 per tile) at zoom [z]. */
    fun pixel(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val world = 256.0 * (1 shl z)
        val latRad = lat.coerceIn(-85.0, 85.0) * PI / 180
        return (lon + 180) / 360 * world to (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * world
    }
}

/** Downloads map tiles once and keeps them in memory and in ~/.kv4p-desktop/tiles/<source>. */
internal object TileCache {
    private const val TAG = "MapTiles"
    private const val MAX_IN_MEMORY = 150
    val dir = File(System.getProperty("user.home"), ".kv4p-desktop/tiles/${MapConfig.CACHE_NAME}")
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MAX_IN_MEMORY
    }
    @Volatile private var failedAt = 0L

    fun file(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.png")

    /** From memory or disk only; never touches the network. */
    fun cached(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        synchronized(memory) { memory[key] }?.let { return it }
        val f = file(z, x, y)
        if (!f.isFile) return null
        return decode(f.readBytes())?.also { synchronized(memory) { memory[key] = it } }
    }

    suspend fun tile(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(z, x, y)?.let { return@withContext it }
        // After a failure (offline), don't retry for every row in the list.
        if (System.currentTimeMillis() - failedAt < 60_000) return@withContext null
        val bytes = try {
            download(z, x, y)
        } catch (e: Exception) {
            Log.w(TAG, "Map tile $z/$x/$y: ${e.message}")
            failedAt = System.currentTimeMillis()
            null
        } ?: return@withContext null
        save(z, x, y, bytes)
        decode(bytes)?.also { synchronized(memory) { memory["$z/$x/$y"] = it } }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    fun save(z: Int, x: Int, y: Int, bytes: ByteArray) {
        runCatching {
            val f = file(z, x, y)
            f.parentFile.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
    }

    /** One tile from the network; null for a non-200 answer, throws when there is no connection. */
    fun download(z: Int, x: Int, y: Int): ByteArray? {
        val conn = URI(MapConfig.tileUrl(z, x, y)).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "kv4p-ht-desktop")
        try {
            return if (conn.responseCode == 200) conn.inputStream.readBytes() else null
        } finally {
            conn.disconnect()
        }
    }
}

/** A tile image (or the part of a coarser tile that covers it) and where it goes in the preview. */
private class Placed(val image: ImageBitmap?, val src: IntOffset, val srcSize: Int, val offset: IntOffset)

/**
 * The tile at z/x/y; when it is not available (offline, outside the downloaded area) the
 * matching part of a coarser cached tile, scaled up.
 */
private suspend fun tileOrCoarser(z: Int, x: Int, y: Int, offset: IntOffset): Placed {
    TileCache.tile(z, x, y)?.let { return Placed(it, IntOffset.Zero, 256, offset) }
    return withContext(Dispatchers.IO) {
        for (d in 1..6) {
            val image = TileCache.cached(z - d, x shr d, y shr d) ?: continue
            val part = 256 shr d
            return@withContext Placed(image, IntOffset((x - (x shr d shl d)) * part, (y - (y shr d shl d)) * part), part, offset)
        }
        Placed(null, IntOffset.Zero, 256, offset)
    }
}

/**
 * Small topographic map centred on [lat]/[lon] with a marker; a click opens [MapConfig.linkFor]
 * in the browser. Tiles are drawn 1 tile pixel per dp.
 */
@Composable
fun MapPreview(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val widthDp = 176
    val heightDp = 96
    val z = MapConfig.ZOOM
    val (cx, cy) = TileMath.pixel(lat, lon, z)
    val left = cx - widthDp / 2.0
    val top = cy - heightDp / 2.0

    val tiles by produceState<List<Placed>?>(null, lat, lon) {
        value = coroutineScope {
            val xs = floor(left / 256).toInt()..floor((left + widthDp) / 256).toInt()
            val ys = floor(top / 256).toInt()..floor((top + heightDp) / 256).toInt()
            xs.flatMap { x -> ys.map { y -> x to y } }.map { (x, y) ->
                async { tileOrCoarser(z, x, y, IntOffset((x * 256 - left).toInt(), (y * 256 - top).toInt())) }
            }.awaitAll()
        }
    }

    Box(
        modifier
            .size(widthDp.dp, heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Kv4pColors.PrimaryVeryFaint)
            .border(1.dp, Kv4pColors.MediumGray, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { openInBrowser(MapConfig.linkFor(lat, lon)) },
    ) {
        val loaded = tiles
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / widthDp
            loaded?.forEach { t ->
                val image = t.image ?: return@forEach
                drawImage(
                    image,
                    srcOffset = t.src,
                    srcSize = IntSize(t.srcSize, t.srcSize),
                    dstOffset = IntOffset((t.offset.x * scale).toInt(), (t.offset.y * scale).toInt()),
                    dstSize = IntSize((256 * scale).toInt() + 1, (256 * scale).toInt() + 1),
                )
            }
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White, radius = 7 * scale, center = c)
            drawCircle(Kv4pColors.Accent, radius = 5 * scale, center = c)
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 7 * scale, center = c, style = Stroke(1 * scale))
        }
        when {
            loaded == null -> Text("Loading map…", fontSize = 11.sp, color = Kv4pColors.MediumGray,
                modifier = Modifier.align(Alignment.Center))
            loaded.all { it.image == null } -> Text("Map unavailable - click to open", fontSize = 11.sp,
                color = Kv4pColors.PrimaryDeselected, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            MapConfig.ATTRIBUTION,
            fontSize = 9.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 3.dp),
        )
    }
}

/** Opens [url] in the default browser; Linux desktops without AWT support get xdg-open. */
fun openInBrowser(url: String) {
    Thread({
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (e: Exception) {
            Log.w("MapTiles", "Could not open $url: ${e.message}")
        }
    }, "kv4p-browse").apply { isDaemon = true }.start()
}
