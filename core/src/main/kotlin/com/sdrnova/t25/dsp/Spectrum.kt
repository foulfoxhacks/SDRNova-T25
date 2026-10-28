package com.sdrnova.t25.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. */
class Fft(val size: Int) {
    private val bits = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { -sin(2 * PI * it / size).toFloat() }

    init {
        require(size >= 2 && Integer.bitCount(size) == 1) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val stride = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * stride]
                    val wi = sinTable[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }
    }
}

/** Power spectrum of raw u8 I/Q, in dBFS with the tuner centre frequency in the middle bin. */
class SpectrumAnalyzer(val size: Int = 1024) {
    private val fft = Fft(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val windowGain = window.sum()
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /** Returns null when [length] holds fewer than [size] complex samples. */
    fun compute(bytes: ByteArray, length: Int): FloatArray? {
        if (length < size * 2) return null
        for (k in 0 until size) {
            re[k] = ((bytes[2 * k].toInt() and 0xFF) - 127.5f) / 127.5f * window[k]
            im[k] = ((bytes[2 * k + 1].toInt() and 0xFF) - 127.5f) / 127.5f * window[k]
        }
        fft.transform(re, im)
        val out = FloatArray(size)
        for (k in 0 until size) {
            val bin = (k + size / 2) % size
            val power = (re[bin] * re[bin] + im[bin] * im[bin]) / (windowGain * windowGain)
            out[k] = (10 * log10(power + 1e-12)).toFloat()
        }
        return out
    }
}
