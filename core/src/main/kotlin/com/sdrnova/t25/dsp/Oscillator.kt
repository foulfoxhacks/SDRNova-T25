package com.sdrnova.t25.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw sample layouts produced by the supported radios. */
enum class SampleFormat(val bytesPerSample: Int) {
    /** RTL-SDR: unsigned 8-bit I/Q. */
    UNSIGNED_8(2),

    /** HackRF: signed 8-bit I/Q. */
    SIGNED_8(2),

    /** Little-endian signed 16-bit I/Q. */
    SIGNED_16(4);

    /** Converts [length] bytes of interleaved I/Q into floats in [out]; returns the complex sample count. */
    fun toFloat(bytes: ByteArray, length: Int, out: FloatArray): Int {
        val count = length / bytesPerSample
        when (this) {
            UNSIGNED_8 -> for (k in 0 until count * 2) out[k] = ((bytes[k].toInt() and 0xFF) - 127.5f) * (1f / 127.5f)
            SIGNED_8 -> for (k in 0 until count * 2) out[k] = bytes[k] * (1f / 128f)
            SIGNED_16 -> for (k in 0 until count * 2) {
                val value = (bytes[2 * k].toInt() and 0xFF) or (bytes[2 * k + 1].toInt() shl 8)
                out[k] = value * (1f / 32768f)
            }
        }
        return count
    }
}

/**
 * Frequency-shifts interleaved I/Q. The oscillator is a rotating unit phasor, renormalized periodically so rounding
 * error never grows its amplitude.
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

    /** Shifts [count] complex samples from [input] into [out]. */
    fun mix(input: FloatArray, count: Int, out: FloatArray) {
        var pI = phasorI
        var pQ = phasorQ
        for (k in 0 until count) {
            val i = input[2 * k]
            val q = input[2 * k + 1]
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
    }
}
