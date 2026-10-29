package com.sdrnova.t25.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max

/**
 * Scrolling waterfall: each spectrum frame becomes one row at the top, coloured from the noise floor (dark) up to
 * the strongest signals (red to white). The level range follows the signal slowly so the picture doesn't flicker.
 */
class WaterfallImage(val width: Int = 512, val height: Int = 240) {
    private val pixels = IntArray(width * height) { PALETTE[0] }
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Bumped on every new row; read it while drawing so the canvas redraws. */
    var version by mutableIntStateOf(0)
        private set

    var floorDb = -90f
        private set
    var topDb = -40f
        private set

    fun push(spectrum: FloatArray) {
        val sorted = spectrum.sorted()
        val floor = sorted[sorted.size / 5] - 2f
        val top = max(sorted.last() + 3f, floor + 25f)
        floorDb += 0.15f * (floor - floorDb)
        topDb += 0.15f * (top - topDb)

        System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
        val binsPerPixel = spectrum.size / width
        val span = topDb - floorDb
        for (x in 0 until width) {
            var peak = Float.NEGATIVE_INFINITY
            for (b in 0 until binsPerPixel) peak = max(peak, spectrum[x * binsPerPixel + b])
            val level = ((peak - floorDb) / span).coerceIn(0f, 1f)
            pixels[x] = PALETTE[(level * (PALETTE.size - 1)).toInt()]
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        version++
    }

    private companion object {
        /** Navy noise floor, through blue, cyan and yellow, to red and white for strong carriers. */
        val PALETTE: IntArray = run {
            val stops = listOf(
                0.00f to 0xFF050814.toInt(),
                0.25f to 0xFF0B2A6B.toInt(),
                0.45f to 0xFF1F78D1.toInt(),
                0.62f to 0xFF3FD8C8.toInt(),
                0.78f to 0xFFF6E05E.toInt(),
                0.92f to 0xFFE5533E.toInt(),
                1.00f to 0xFFFFFFFF.toInt(),
            )
            IntArray(256) { index ->
                val t = index / 255f
                val upper = stops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
                val (t0, c0) = stops[upper - 1]
                val (t1, c1) = stops[upper]
                val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
                fun channel(shift: Int) = ((c0 ushr shift and 0xFF) * (1 - f) + (c1 ushr shift and 0xFF) * f).toInt()
                (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
    }
}
