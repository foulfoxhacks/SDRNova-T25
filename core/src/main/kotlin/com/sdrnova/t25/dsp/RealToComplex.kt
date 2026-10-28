package com.sdrnova.t25.dsp

import kotlin.math.abs

/**
 * Turns the Airspy's real 12-bit sample stream (at twice the output rate, signal centred on fs/4) into complex
 * samples, the way sdrtrunk's SampleBufferIterator and libairspy's iqconverter do: even samples become I (delayed to
 * match the filter), odd samples pass through a Hilbert filter to become Q, and every other output is negated to
 * shift fs/4 down to DC.
 */
class RealToComplex {
    private val iDelay = FloatArray(I_DELAY)
    private var iPosition = 0
    private val qHistory = FloatArray(TAPS.size * 2)
    private var qNewest = 0
    private var odd = false
    private var dc = 0f

    /**
     * Converts [count] pairs of raw unsigned 12-bit samples ([raw] holds 2 * [count] values) into [count] complex
     * samples written to [out] as interleaved I/Q.
     */
    fun convert(raw: ShortArray, count: Int, out: FloatArray) {
        val length = TAPS.size
        var dcSum = 0f
        for (k in 0 until count) {
            val iRaw = (raw[2 * k] - 2048) * SCALE
            val qRaw = (raw[2 * k + 1] - 2048) * SCALE
            dcSum += iRaw + qRaw

            val delayedI = iDelay[iPosition]
            iDelay[iPosition] = iRaw - dc
            iPosition = (iPosition + 1) % I_DELAY

            // History runs oldest to newest from qNewest+1, doubled so the dot product never wraps.
            qNewest = (qNewest + 1) % length
            qHistory[qNewest] = qRaw - dc
            qHistory[qNewest + length] = qRaw - dc
            var q = 0f
            val oldest = qNewest + 1
            for (t in 0 until length) q += TAPS[t] * qHistory[oldest + t]

            if (odd) {
                out[2 * k] = -delayedI
                out[2 * k + 1] = -q
            } else {
                out[2 * k] = delayedI
                out[2 * k + 1] = q
            }
            odd = !odd
        }
        if (count > 0) dc += 0.05f * (dcSum / (2 * count) - dc)
    }

    companion object {
        private const val SCALE = 1f / 2048f
        private const val I_DELAY = 11

        /** Half-band filter from sdrtrunk's HilbertTransform (47 taps; every other one is zero). */
        private val HALF_BAND = floatArrayOf(
            -0.000998606272947510f, 0.0f, 0.001695637278417295f, 0.0f,
            -0.003054430179754289f, 0.0f, 0.005055504379767936f, 0.0f,
            -0.007901319195893647f, 0.0f, 0.011873357051047719f, 0.0f,
            -0.017411159379930066f, 0.0f, 0.025304817427568772f, 0.0f,
            -0.037225225204559217f, 0.0f, 0.057533286997004301f, 0.0f,
            -0.102327462004259350f, 0.0f, 0.317034472508947400f, 0.5f,
            0.317034472508947400f, 0.0f, -0.102327462004259350f, 0.0f,
            0.057533286997004301f, 0.0f, -0.037225225204559217f, 0.0f,
            0.025304817427568772f, 0.0f, -0.017411159379930066f, 0.0f,
            0.011873357051047719f, 0.0f, -0.007901319195893647f, 0.0f,
            0.005055504379767936f, 0.0f, -0.003054430179754289f, 0.0f,
            0.001695637278417295f, 0.0f, -0.000998606272947510f,
        )

        /** sdrtrunk's convertHalfBandToHilbert: the even taps, negative before the centre and positive after. */
        val TAPS = FloatArray(HALF_BAND.size / 2 + 1).also { hilbert ->
            val middle = HALF_BAND.size / 2
            for (x in HALF_BAND.indices step 2) {
                if (x < middle) hilbert[x / 2] = -2f * abs(HALF_BAND[x])
                else if (x > middle) hilbert[x / 2] = 2f * abs(HALF_BAND[x])
            }
        }
    }
}
