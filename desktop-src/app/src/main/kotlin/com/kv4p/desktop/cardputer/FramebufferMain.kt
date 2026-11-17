package com.kv4p.desktop.cardputer

import android.util.Log
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.kv4p.desktop.RadioViewModel
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

/**
 * Runs the compact UI straight on a Linux framebuffer, without X11 or Wayland
 * (the Cardputer Zero's built-in LCD):
 *   kv4p-ht-desktop --framebuffer [--fb /dev/fb0] [--keyboard /dev/input/event3]
 * Everything (keys, radio callbacks, drawing) runs on the Swing event thread, which
 * also works headless.
 */
fun runFramebuffer(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    System.setProperty("java.awt.headless", "true")

    val fb = try {
        Framebuffer.open(arg("--fb"))
    } catch (e: Exception) {
        System.err.println("Cannot open the framebuffer: ${e.message}")
        Log.e(TAG, "Cannot open the framebuffer: ${e.message}")
        exitProcess(2)
    }
    val keyboard = KeyInput.findKeyboard(arg("--keyboard"))
    if (keyboard == null) Log.w(TAG, "No keyboard found in /dev/input")

    SwingUtilities.invokeLater { start(fb, keyboard) }
}

private const val TAG = "Framebuffer"

private fun start(fb: Framebuffer, keyboard: String?) {
    val vm = RadioViewModel()
    var keys: KeyInput? = null
    lateinit var timer: Timer
    fun quit() {
        Log.i(TAG, "Quitting")
        timer.stop()
        keys?.stop()
        vm.shutdown()
        runCatching { fb.clear() }
        fb.close()
        exitProcess(0)
    }

    val ui = CompactUi(vm) { quit() }
    val width = minOf(fb.width, 320)
    val height = minOf(fb.height, 170)
    val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Swing) {
        CompactApp(ui, vm)
    }
    val bitmap = Bitmap().apply { allocPixels(ImageInfo(width, height, ColorType.RGB_565, ColorAlphaType.OPAQUE)) }
    var lastFrame = 0L

    fun draw(force: Boolean) {
        ui.tick()
        if (!force && !scene.hasInvalidations()) return
        val image = scene.render(System.nanoTime())
        image.readPixels(bitmap, 0, 0)
        val pixels = bitmap.readPixels(bitmap.imageInfo, width * 2, 0, 0) ?: return
        fb.write(pixels, width, height, force)
        image.close()
        lastFrame = System.currentTimeMillis()
    }

    keys = keyboard?.let { dev ->
        KeyInput(dev) { k -> SwingUtilities.invokeLater { ui.onKey(k); draw(false) } }.also { it.start() }
    }

    // Connect to the radio on start, and again whenever it drops (e.g. unplugged and plugged back).
    var lastTry = 0L
    fun autoConnect() {
        val now = System.currentTimeMillis()
        val busy = vm.connection in setOf(
            ConnectionState.CONNECTING, ConnectionState.HANDSHAKE, ConnectionState.CONNECTED, ConnectionState.OUTDATED_FIRMWARE,
        )
        if (!ui.autoConnect || busy || now - lastTry < 5000) return
        lastTry = now
        vm.refreshPorts()
        // Only ports that look like a kv4p HT: the Cardputer's own UARTs may be a GPS or a console.
        val port = vm.ports.firstOrNull { it.likelyKv4p } ?: return
        vm.selectedPort = port.systemPath
        vm.connect()
    }

    // ~25 frames a second when something changes; a full redraw every 2 s in case the panel was overwritten.
    timer = Timer(40) {
        autoConnect()
        draw(System.currentTimeMillis() - lastFrame > 2000)
    }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { vm.shutdown() } })
    draw(true)
    timer.start()
    Log.i(TAG, "Compact UI on ${fb.path} (${width}x$height), keys from ${keyboard ?: "nowhere"}")
}
