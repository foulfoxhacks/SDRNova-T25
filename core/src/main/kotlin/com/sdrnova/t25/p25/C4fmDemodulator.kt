package com.sdrnova.t25.p25

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns channel-filtered complex baseband into P25 Phase 1 dibits.
 *
 * Modeled on sdrtrunk's C4FM symbol processor. The FM discriminator output is integrated over one symbol (the
 * receive half of the TIA C4FM filter pair), so each symbol becomes the phase the carrier rotated: ideally ±π/4 or
 * ±3π/4. Every sample is correlated against the 24-symbol frame sync; a confirmed sync fixes the symbol timing, the
 * DC balance (carrier offset) and the gain. Between syncs a Gardner detector keeps the symbol clock aligned.
 */
class C4fmDemodulator(sampleRate: Double, private val sink: Sink) {
    interface Sink {
        /** A frame sync ended at the most recent symbol. [frequencyErrorHz] is the carrier offset measured on it. */
        fun onSync(bitErrors: Int, frequencyErrorHz: Double)

        fun onDibit(dibit: Int)

        /** Whether a new sync may interrupt the frame currently being assembled. */
        fun isSyncAllowed(): Boolean
    }

    private val samplesPerSymbol = sampleRate / P25.SYMBOL_RATE
    private val dumpLength = max(1, samplesPerSymbol.roundToInt())
    private val dumpScale = (samplesPerSymbol / dumpLength).toFloat()
    private val dump = FloatArray(dumpLength)
    private var dumpIndex = 0
    private var dumpSum = 0f
    private var sinceDumpRefresh = 0

    private val syncOffsets = IntArray(24) { ((23 - it) * samplesPerSymbol).roundToInt() }
    private val ring: FloatArray
    private val ringMask: Long
    private var sampleIndex = 0L

    private var previousI = 0f
    private var previousQ = 0f

    private var locked = false
    private var nextSymbolTime = 0.0
    private var balance = 0f
    private var gain = 1f
    private var lastSoft = 0f

    private val confirmDelay = max(2, (samplesPerSymbol / 2).toInt())
    private var candidateScore = 0f
    private var candidateIndex = -1L
    private var holdoffUntil = 0L

    init {
        var size = 64
        while (size < syncOffsets[0] + 4 * dumpLength + 64) size = size shl 1
        ring = FloatArray(size)
        ringMask = (size - 1).toLong()
    }

    fun reset() {
        locked = false
        candidateIndex = -1
        candidateScore = 0f
        balance = 0f
        gain = 1f
    }

    /** Processes [count] complex samples of interleaved I/Q. */
    fun process(iq: FloatArray, count: Int) {
        for (k in 0 until count) {
            val i = iq[2 * k]
            val q = iq[2 * k + 1]
            val phase = atan2(q * previousI - i * previousQ, i * previousI + q * previousQ)
            previousI = i
            previousQ = q

            dumpSum += phase - dump[dumpIndex]
            dump[dumpIndex] = phase
            if (++dumpIndex == dumpLength) dumpIndex = 0
            if (++sinceDumpRefresh == 8192) {
                sinceDumpRefresh = 0
                dumpSum = dump.sum()
            }

            val n = sampleIndex++
            ring[(n and ringMask).toInt()] = dumpSum * dumpScale
            if (locked) clockSymbols(n)
            if (n >= syncOffsets[0] + 1) searchSync(n)
        }
    }

    private fun sampleAt(time: Double): Float {
        val whole = floor(time).toLong()
        val fraction = (time - whole).toFloat()
        val a = ring[(whole and ringMask).toInt()]
        val b = ring[((whole + 1) and ringMask).toInt()]
        return a + (b - a) * fraction
    }

    private fun clockSymbols(newest: Long) {
        while (nextSymbolTime + 1.0 <= newest) {
            val time = nextSymbolTime
            val soft = (sampleAt(time) - balance) * gain
            val dibit = P25.slice(soft)
            // Gardner timing error from the point halfway back to the previous symbol: negative means sampling late.
            val middle = (sampleAt(time - samplesPerSymbol / 2) - balance) * gain
            val timingError = (middle * (lastSoft - soft)).coerceIn(-4f, 4f)
            // Follow slow carrier drift between syncs.
            balance += BALANCE_TRACKING * (soft - P25.idealPhase(dibit)) / gain
            lastSoft = soft
            nextSymbolTime = time + samplesPerSymbol + TIMING_GAIN * timingError
            sink.onDibit(dibit)
        }
    }

    private fun correlate(end: Long): Float {
        var score = 0f
        for (k in 0 until 24) score += P25.SYNC_SYMBOLS[k] * ring[((end - syncOffsets[k]) and ringMask).toInt()]
        return score
    }

    private fun searchSync(n: Long) {
        if (candidateIndex >= 0 && n - candidateIndex >= confirmDelay) {
            val peak = candidateIndex
            candidateIndex = -1
            candidateScore = 0f
            confirm(peak)
        }
        if (n < holdoffUntil || !sink.isSyncAllowed()) return
        // Magnitude, so an inverted signal (negative correlation) is still found.
        val score = abs(correlate(n))
        if (score > SYNC_THRESHOLD && score > candidateScore) {
            candidateScore = score
            candidateIndex = n
        }
    }

    private fun confirm(peak: Long) {
        val left = abs(correlate(peak - 1))
        val center = abs(correlate(peak))
        val right = abs(correlate(peak + 1))
        val curvature = left - 2 * center + right
        val offset = if (curvature < 0f) (0.5f * (left - right) / curvature).coerceIn(-0.5f, 0.5f) else 0f
        val end = peak + offset.toDouble()

        val values = FloatArray(24)
        var plus = 0f
        var minus = 0f
        var plusCount = 0
        var minusCount = 0
        for (k in 0 until 24) {
            val value = sampleAt(end - (23 - k) * samplesPerSymbol)
            values[k] = value
            if (P25.SYNC_SYMBOLS[k] > 0) {
                plus += value
                plusCount++
            } else {
                minus += value
                minusCount++
            }
        }
        plus /= plusCount
        minus /= minusCount
        val spread = (plus - minus) / 2
        if (abs(spread) < MIN_SYNC_SPREAD) return
        val newBalance = (plus + minus) / 2
        // A negative gain undoes an inverted signal.
        val newGain = P25.OUTER_PHASE / spread
        if (abs(newGain) !in 0.5f..2f) return

        var bitErrors = 0
        for (k in 0 until 24) {
            bitErrors += (P25.slice((values[k] - newBalance) * newGain) xor P25.SYNC_DIBITS[k]).countOneBits()
        }
        if (bitErrors > MAX_SYNC_BIT_ERRORS) return

        balance = newBalance
        gain = newGain
        lastSoft = (values[23] - balance) * gain
        nextSymbolTime = end + samplesPerSymbol
        locked = true
        holdoffUntil = peak + (4 * samplesPerSymbol).toLong()
        sink.onSync(bitErrors, balance * P25.SYMBOL_RATE / (2 * PI))
    }

    private companion object {
        /** Ideal score is 24 x (3π/4)² ≈ 133; sdrtrunk detects at 80. */
        const val SYNC_THRESHOLD = 80f
        const val MAX_SYNC_BIT_ERRORS = 6
        const val MIN_SYNC_SPREAD = 0.5f
        const val TIMING_GAIN = 0.01
        const val BALANCE_TRACKING = 0.002f
    }
}
