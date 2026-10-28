package com.sdrnova.t25.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts RTL-SDR unsigned 8-bit I/Q to floats and frequency-shifts it in one pass. The oscillator is a rotating
 * unit phasor, renormalized periodically so rounding error never grows its amplitude.
 */
class Mixer(private val sampleRate: Double) {
    private var stepI = 1f
    private var stepQ = 0f
    private var phasorI = 1f
    private var phasorQ = 0f
    private var sinceNormalize = 0

    var frequencyHz: Double = 0.0
        set(value) {
            field = value
            val radians = 2 * PI * value / sampleRate
            stepI = cos(radians).toFloat()
            stepQ = sin(radians).toFloat()
        }

    /** Writes [length] bytes of interleaved u8 I/Q from [bytes] as shifted floats into [out]; returns sample count. */
    fun mixBytes(bytes: ByteArray, length: Int, out: FloatArray): Int {
        val count = length / 2
        var pI = phasorI
        var pQ = phasorQ
        for (k in 0 until count) {
            val i = ((bytes[2 * k].toInt() and 0xFF) - 127.5f) * SCALE
            val q = ((bytes[2 * k + 1].toInt() and 0xFF) - 127.5f) * SCALE
            out[2 * k] = i * pI - q * pQ
            out[2 * k + 1] = i * pQ + q * pI
            val nextI = pI * stepI - pQ * stepQ
            pQ = pI * stepQ + pQ * stepI
            pI = nextI
            if (++sinceNormalize == 1024) {
                sinceNormalize = 0
                val magnitude = sqrt(pI * pI + pQ * pQ)
                pI /= magnitude
                pQ /= magnitude
            }
        }
        phasorI = pI
        phasorQ = pQ
        return count
    }

    private companion object {
        const val SCALE = 1f / 127.5f
    }
}
