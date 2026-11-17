package com.kv4p.desktop.cardputer

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A Linux framebuffer in 16-bit RGB565 (the Cardputer Zero's built-in LCD is /dev/fb0,
 * panel-mipi-dbi, 320x170). Frames narrower than the screen are drawn in the top-left corner.
 */
class Framebuffer private constructor(
    val path: String,
    val width: Int,
    val height: Int,
    private val stride: Int,
    private val channel: FileChannel,
) : AutoCloseable {
    private var last: ByteArray? = null

    /** Writes a frame of [frameWidth] x [frameHeight] RGB565 pixels (2 bytes each, little-endian). */
    fun write(pixels: ByteArray, frameWidth: Int, frameHeight: Int, force: Boolean = false) {
        // The LCD sits on SPI: skip frames that did not change.
        if (!force && last?.contentEquals(pixels) == true) return
        last = pixels.copyOf()
        val rowBytes = frameWidth * 2
        if (rowBytes == stride && frameHeight <= height) {
            channel.write(ByteBuffer.wrap(pixels, 0, rowBytes * frameHeight), 0)
            return
        }
        val rows = minOf(frameHeight, height)
        val bytes = minOf(rowBytes, stride)
        for (y in 0 until rows) {
            channel.write(ByteBuffer.wrap(pixels, y * rowBytes, bytes), y.toLong() * stride)
        }
    }

    fun clear() {
        channel.write(ByteBuffer.wrap(ByteArray(stride * height)), 0)
        last = null
    }

    override fun close() = channel.close()

    companion object {
        private const val TAG = "Framebuffer"

        /**
         * Opens [requested], or the Cardputer's LCD (/dev/fb_lcd, else the first fb of a small panel),
         * reading size and format from /sys/class/graphics.
         */
        fun open(requested: String?): Framebuffer {
            val path = requested ?: listOf("/dev/fb_lcd", "/dev/fb0").first { File(it).exists() }
            val name = File(path).canonicalFile.name // fb_lcd -> fb0
            val sys = File("/sys/class/graphics/$name")
            fun read(f: String) = File(sys, f).takeIf { it.isFile }?.readText()?.trim()
            val bpp = read("bits_per_pixel")?.toIntOrNull() ?: 16
            require(bpp == 16) { "$path uses $bpp bits per pixel; only 16-bit (RGB565) is supported" }
            val size = read("virtual_size")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            val width = size?.getOrNull(0) ?: 320
            val height = size?.getOrNull(1) ?: 170
            val stride = read("stride")?.toIntOrNull() ?: (width * 2)
            val channel = RandomAccessFile(path, "rw").channel
            Log.i(TAG, "$path ($name ${read("name").orEmpty()}): ${width}x$height, stride $stride")
            return Framebuffer(path, width, height, stride, channel)
        }
    }
}
