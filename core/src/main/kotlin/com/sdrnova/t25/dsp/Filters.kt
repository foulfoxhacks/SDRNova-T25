package com.sdrnova.t25.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Windowed-sinc FIR design, using a Kaiser window sized from the transition band and stopband attenuation. */
object FirDesign {
    fun lowPass(sampleRate: Double, passEdgeHz: Double, stopEdgeHz: Double, attenuationDb: Double = 60.0): FloatArray {
        require(stopEdgeHz > passEdgeHz) { "stop edge must be above pass edge" }
        val transition = 2 * PI * (stopEdgeHz - passEdgeHz) / sampleRate
        var taps = ceil((attenuationDb - 7.95) / (2.285 * transition)).toInt() + 1
        if (taps % 2 == 0) taps++
        val beta = when {
            attenuationDb > 50 -> 0.1102 * (attenuationDb - 8.7)
            attenuationDb >= 21 -> 0.5842 * (attenuationDb - 21).pow(0.4) + 0.07886 * (attenuationDb - 21)
            else -> 0.0
        }
        val cutoff = (passEdgeHz + stopEdgeHz) / 2 / sampleRate
        val middle = (taps - 1) / 2.0
        val normalizer = besselI0(beta)
        val h = DoubleArray(taps) { n ->
            val x = n - middle
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val r = if (middle == 0.0) 0.0 else x / middle
            sinc * besselI0(beta * sqrt(max(0.0, 1 - r * r))) / normalizer
        }
        val gain = h.sum()
        return FloatArray(taps) { (h[it] / gain).toFloat() }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-12 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}

/**
 * FIR filter over interleaved I/Q floats that emits one output for every [decimation] inputs. Input and output may
 * be the same array: an output is only written after the input at that position has been consumed.
 */
class ComplexFirDecimator(private val taps: FloatArray, private val decimation: Int) {
    private val length = taps.size
    // History is stored twice so the dot product never wraps.
    private val historyI = FloatArray(length * 2)
    private val historyQ = FloatArray(length * 2)
    private var newest = 0
    private var phase = 0

    /** Filters [count] complex samples from [input] into [output] and returns how many complex samples it wrote. */
    fun process(input: FloatArray, count: Int, output: FloatArray): Int {
        var written = 0
        for (k in 0 until count) {
            val i = input[2 * k]
            val q = input[2 * k + 1]
            newest = if (newest == 0) length - 1 else newest - 1
            historyI[newest] = i
            historyI[newest + length] = i
            historyQ[newest] = q
            historyQ[newest + length] = q
            if (++phase == decimation) {
                phase = 0
                var accI = 0f
                var accQ = 0f
                for (t in 0 until length) {
                    val c = taps[t]
                    accI += c * historyI[newest + t]
                    accQ += c * historyQ[newest + t]
                }
                output[2 * written] = accI
                output[2 * written + 1] = accQ
                written++
            }
        }
        return written
    }
}

/** Real-valued FIR filter, in place. */
class RealFir(private val taps: FloatArray) {
    private val length = taps.size
    private val history = FloatArray(length * 2)
    private var newest = 0

    fun process(samples: FloatArray, count: Int) {
        for (k in 0 until count) {
            newest = if (newest == 0) length - 1 else newest - 1
            history[newest] = samples[k]
            history[newest + length] = samples[k]
            var acc = 0f
            for (t in 0 until length) acc += taps[t] * history[newest + t]
            samples[k] = acc
        }
    }
}
