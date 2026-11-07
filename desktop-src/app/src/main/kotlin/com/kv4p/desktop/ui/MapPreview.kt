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
    /** Aerial imagery tiles (OpenStreetMap itself has no aerial layer). {z}/{y}/{x}. */
    const val TILE_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d"
    const val ATTRIBUTION = "© Esri"
    const val ZOOM = 16

    /** Opened when the preview is clicked. */
    fun linkFor(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://mapy.com/fnc/v1/showmap?mapset=aerial&center=%.5f,%.5f&zoom=17&marker=true", lon, lat)
}

/** Downloads map tiles once and keeps them in memory and in ~/.kv4p-desktop/tiles. */
private object TileCache {
    private const val TAG = "MapTiles"
    private const val MAX_IN_MEMORY = 150
    private val dir = File(System.getProperty("user.home"), ".kv4p-desktop/tiles")
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MAX_IN_MEMORY
    }
    @Volatile private var failedAt = 0L

    suspend fun tile(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        synchronized(memory) { memory[key] }?.let { return@withContext it }
        val file = File(dir, "$key.jpg")
        val bytes = if (file.isFile) file.readBytes() else download(z, x, y)?.also {
            runCatching { file.parentFile.mkdirs(); file.writeBytes(it) }
        }
        val image = bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
        if (image != null) synchronized(memory) { memory[key] = image }
        image
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? {
        // After a failure (offline), don't retry for every row in the list.
        if (System.currentTimeMillis() - failedAt < 60_000) return null
        return try {
            val conn = URI(String.format(Locale.US, MapConfig.TILE_URL, z, y, x)).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "kv4p-ht-desktop")
            try {
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Map tile $z/$x/$y: ${e.message}")
            failedAt = System.currentTimeMillis()
            null
        }
    }
}

private class Placed(val image: ImageBitmap?, val offset: IntOffset)

/**
 * Small aerial map centred on [lat]/[lon] with a marker; a click opens [MapConfig.linkFor]
 * in the browser. Tiles are drawn 1 tile pixel per dp.
 */
@Composable
fun MapPreview(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val widthDp = 176
    val heightDp = 96
    val z = MapConfig.ZOOM
    // Web Mercator position of the station, in tile pixels at zoom z.
    val world = 256.0 * (1 shl z)
    val latRad = lat * PI / 180
    val cx = (lon + 180) / 360 * world
    val cy = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * world
    val left = cx - widthDp / 2.0
    val top = cy - heightDp / 2.0

    val tiles by produceState<List<Placed>?>(null, lat, lon) {
        value = coroutineScope {
            val xs = floor(left / 256).toInt()..floor((left + widthDp) / 256).toInt()
            val ys = floor(top / 256).toInt()..floor((top + heightDp) / 256).toInt()
            xs.flatMap { x -> ys.map { y -> x to y } }.map { (x, y) ->
                async {
                    Placed(TileCache.tile(z, x, y), IntOffset((x * 256 - left).toInt(), (y * 256 - top).toInt()))
                }
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
