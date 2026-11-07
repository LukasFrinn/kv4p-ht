package com.kv4p.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * APRS station symbols from the aprs.fi set (github.com/hessu/aprs-symbols, free to use with
 * a pointer to the source). Each sprite is 16 x 6 cells of 48 px, for characters '!'..'~'.
 */
private object AprsSymbolSprites {
    const val CELL = 48

    private fun load(table: Int): ImageBitmap? = runCatching {
        Thread.currentThread().contextClassLoader
            .getResourceAsStream("aprs-symbols/aprs-symbols-24-$table@2x.png")!!
            .use { loadImageBitmap(it) }
    }.getOrNull()

    val primary by lazy { load(0) }
    val secondary by lazy { load(1) }
    val overlays by lazy { load(2) }

    /** Top-left of [c]'s cell, or null for characters outside the set. */
    fun cell(c: Char): IntOffset? {
        val index = c.code - '!'.code
        if (index !in 0 until 16 * 6) return null
        return IntOffset(index % 16 * CELL, index / 16 * CELL)
    }
}

/** Whether [table]/[code] is a symbol we can draw. */
fun hasAprsSymbol(table: Char, code: Char): Boolean =
    table != 0.toChar() && AprsSymbolSprites.cell(code) != null

/**
 * The station's symbol: '/' primary table, '\' secondary table, anything else is the
 * secondary symbol with that character drawn over it (e.g. a digipeater with an overlay).
 */
@Composable
fun AprsSymbol(table: Char, code: Char, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val cell = AprsSymbolSprites.cell(code) ?: return
    val sheet = (if (table == '/') AprsSymbolSprites.primary else AprsSymbolSprites.secondary) ?: return
    val overlayCell = if (table != '/' && table != '\\') AprsSymbolSprites.cell(table) else null
    Canvas(modifier.size(size)) {
        drawCell(sheet, cell)
        val overlays = AprsSymbolSprites.overlays
        if (overlayCell != null && overlays != null) drawCell(overlays, overlayCell)
    }
}

private fun DrawScope.drawCell(sheet: ImageBitmap, cell: IntOffset) {
    drawImage(
        sheet,
        srcOffset = cell,
        srcSize = IntSize(AprsSymbolSprites.CELL, AprsSymbolSprites.CELL),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
    )
}
