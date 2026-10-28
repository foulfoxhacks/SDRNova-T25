package com.sdrnova.t25.dsp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

class RealToComplexTest {
    /** Power of [iq] at [hz] (complex correlation), for a complex rate of [rate]. */
    private fun power(iq: FloatArray, count: Int, hz: Double, rate: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in 0 until count) {
            val angle = -2 * PI * hz * n / rate
            re += iq[2 * n] * cos(angle) - iq[2 * n + 1] * sin(angle)
            im += iq[2 * n] * sin(angle) + iq[2 * n + 1] * cos(angle)
        }
        return hypot(re, im) / count
    }

    @Test
    fun `a tone above fs over 4 becomes a single complex tone with its image rejected`() {
        val complexRate = 2_500_000.0
        val realRate = 2 * complexRate
        val offset = 200_000.0
        val pairs = 20_000
        val raw = ShortArray(pairs * 2) { n ->
            (2048 + 1000 * cos(2 * PI * (realRate / 4 + offset) * n / realRate)).roundToInt().toShort()
        }
        val out = FloatArray(pairs * 2)
        RealToComplex().convert(raw, pairs, out)
        // Skip the filter start-up, then look for the tone at +offset and its mirror at -offset.
        val settled = out.copyOfRange(200, out.size)
        val count = settled.size / 2
        val positive = power(settled, count, offset, complexRate)
        val negative = power(settled, count, -offset, complexRate)
        val louder = maxOf(positive, negative)
        val rejection = 20 * log10(louder / minOf(positive, negative))
        println("tone at fs/4+200k: +200k=%.4f -200k=%.4f image rejection %.1f dB".format(positive, negative, rejection))
        assertTrue(louder > 0.1, "converted tone too weak: $louder")
        assertTrue(rejection > 30, "image rejection only $rejection dB")
    }
}
