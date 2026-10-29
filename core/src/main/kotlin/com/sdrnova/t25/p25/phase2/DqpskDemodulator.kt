package com.sdrnova.t25.p25.phase2

import com.sdrnova.t25.p25.P25
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Differential demodulator for P25 Phase 2 H-DQPSK (6000 symbols/s), using the same dibit-to-phase mapping as
 * Phase 1 (±π/4, ±3π/4).
 *
 * Samples go through a root-raised-cosine matched filter (alpha 0.2), then each sample is multiplied by the
 * conjugate of the sample one symbol earlier, giving the phase change across a symbol as a complex number.
 * Correlating those against the 20-symbol S-ISCH sync gives timing, and the correlation's angle gives the carrier
 * offset directly, so acquisition doesn't depend on being close to frequency the way a phase-unwrapping detector
 * would. sdrtrunk uses a coherent Costas-loop demodulator here instead; this trades a little sensitivity for
 * simplicity.
 */
class DqpskDemodulator(sampleRate: Double, private val sink: Sink) {
    interface Sink {
        fun onSync(bitErrors: Int, frequencyErrorHz: Double)
        fun onDibit(dibit: Int)
    }

    private val samplesPerSymbol = sampleRate / SYMBOL_RATE
    private val matched = rootRaisedCosine(samplesPerSymbol, 0.2, 8)
    private val filterI = FloatArray(matched.size * 2)
    private val filterQ = FloatArray(matched.size * 2)
    private var filterNewest = 0

    private val ringSize: Int
    private val ringMask: Long
    private val yI: FloatArray
    private val yQ: FloatArray
    private val zI: FloatArray
    private val zQ: FloatArray
    private var sampleIndex = 0L

    private val syncOffsets = IntArray(SYNC_LENGTH) { ((SYNC_LENGTH - 1 - it) * samplesPerSymbol).roundToInt() }
    private val syncI = FloatArray(SYNC_LENGTH) { cos(P25.idealPhase(SYNC_DIBITS[it]).toDouble()).toFloat() }
    private val syncQ = FloatArray(SYNC_LENGTH) { sin(P25.idealPhase(SYNC_DIBITS[it]).toDouble()).toFloat() }

    private var locked = false
    private var nextSymbolTime = 0.0
    private var rotation = 0f
    private val confirmDelay = max(2, (samplesPerSymbol / 2).toInt())
    private var candidateScore = 0f
    private var candidateIndex = -1L
    private var holdoffUntil = 0L

    private var lastSync = 0L

    init {
        // Room to replay most of a fragment once the first sync fixes timing and carrier offset.
        var size = 64
        while (size < (BACKLOG_SYMBOLS + SYNC_LENGTH + 4) * samplesPerSymbol + 64) size = size shl 1
        ringSize = size
        ringMask = (size - 1).toLong()
        yI = FloatArray(size)
        yQ = FloatArray(size)
        zI = FloatArray(size)
        zQ = FloatArray(size)
    }

    fun reset() {
        locked = false
        candidateIndex = -1
        candidateScore = 0f
        rotation = 0f
    }

    fun process(iq: FloatArray, count: Int) {
        val taps = matched.size
        for (k in 0 until count) {
            filterNewest = if (filterNewest == 0) taps - 1 else filterNewest - 1
            filterI[filterNewest] = iq[2 * k]
            filterI[filterNewest + taps] = iq[2 * k]
            filterQ[filterNewest] = iq[2 * k + 1]
            filterQ[filterNewest + taps] = iq[2 * k + 1]
            var accI = 0f
            var accQ = 0f
            for (t in 0 until taps) {
                accI += matched[t] * filterI[filterNewest + t]
                accQ += matched[t] * filterQ[filterNewest + t]
            }
            val n = sampleIndex++
            val slot = (n and ringMask).toInt()
            yI[slot] = accI
            yQ[slot] = accQ
            // Phase change across one symbol, as y(n) * conj(y(n - T)).
            val pastI = interpolate(yI, n - samplesPerSymbol)
            val pastQ = interpolate(yQ, n - samplesPerSymbol)
            zI[slot] = accI * pastI + accQ * pastQ
            zQ[slot] = accQ * pastI - accI * pastQ
            if (locked && n - lastSync > UNLOCK_SYMBOLS * samplesPerSymbol) locked = false
            if (locked) clockSymbols(n)
            if (n > syncOffsets[0] + 2 * samplesPerSymbol) searchSync(n)
        }
    }

    private fun interpolate(ring: FloatArray, time: Double): Float {
        val whole = floor(time).toLong()
        val fraction = (time - whole).toFloat()
        val a = ring[(whole and ringMask).toInt()]
        val b = ring[((whole + 1) and ringMask).toInt()]
        return a + (b - a) * fraction
    }

    private fun clockSymbols(newest: Long) {
        while (nextSymbolTime + 1.0 <= newest) {
            val time = nextSymbolTime
            val phase = symbolPhase(time)
            val dibit = P25.slice(phase)
            // Track residual carrier offset, then Gardner timing on the matched-filter output.
            rotation += ROTATION_TRACKING * wrap(phase - P25.idealPhase(dibit))
            val curI = interpolate(yI, time)
            val curQ = interpolate(yQ, time)
            val prevI = interpolate(yI, time - samplesPerSymbol)
            val prevQ = interpolate(yQ, time - samplesPerSymbol)
            val midI = interpolate(yI, time - samplesPerSymbol / 2)
            val midQ = interpolate(yQ, time - samplesPerSymbol / 2)
            val power = (curI * curI + curQ * curQ + prevI * prevI + prevQ * prevQ) / 2 + 1e-12f
            val error = ((midI * (prevI - curI) + midQ * (prevQ - curQ)) / power).coerceIn(-1f, 1f)
            nextSymbolTime = time + samplesPerSymbol + TIMING_GAIN * error
            sink.onDibit(dibit)
        }
    }

    private fun symbolPhase(time: Double): Float {
        val i = interpolate(zI, time)
        val q = interpolate(zQ, time)
        return wrap(atan2(q, i) - rotation)
    }

    /** Normalized correlation magnitude against the sync pattern, and its angle (carrier rotation per symbol). */
    private fun correlate(end: Long, angle: FloatArray? = null): Float {
        var sumI = 0f
        var sumQ = 0f
        var energy = 0f
        for (k in 0 until SYNC_LENGTH) {
            val slot = ((end - syncOffsets[k]) and ringMask).toInt()
            val i = zI[slot]
            val q = zQ[slot]
            // z * conj(pattern)
            sumI += i * syncI[k] + q * syncQ[k]
            sumQ += q * syncI[k] - i * syncQ[k]
            energy += hypot(i, q)
        }
        angle?.set(0, atan2(sumQ, sumI))
        return if (energy > 0f) hypot(sumI, sumQ) / energy else 0f
    }

    private fun searchSync(n: Long) {
        if (candidateIndex >= 0 && n - candidateIndex >= confirmDelay) {
            val peak = candidateIndex
            candidateIndex = -1
            candidateScore = 0f
            confirm(peak)
        }
        if (n < holdoffUntil) return
        val score = correlate(n)
        if (score > SYNC_THRESHOLD && score > candidateScore) {
            candidateScore = score
            candidateIndex = n
        }
    }

    private fun confirm(peak: Long) {
        val left = correlate(peak - 1)
        val angle = FloatArray(1)
        val center = correlate(peak, angle)
        val right = correlate(peak + 1)
        val curvature = left - 2 * center + right
        val offset = if (curvature < 0f) (0.5f * (left - right) / curvature).coerceIn(-0.5f, 0.5f) else 0f
        val end = peak + offset.toDouble()
        val newRotation = angle[0]

        var bitErrors = 0
        for (k in 0 until SYNC_LENGTH) {
            val time = end - (SYNC_LENGTH - 1 - k) * samplesPerSymbol
            val phase = wrap(atan2(interpolate(zQ, time), interpolate(zI, time)) - newRotation)
            bitErrors += (P25.slice(phase) xor SYNC_DIBITS[k]).countOneBits()
        }
        if (bitErrors > MAX_SYNC_BIT_ERRORS) return

        rotation = newRotation
        holdoffUntil = peak + (4 * samplesPerSymbol).toLong()
        lastSync = peak
        sink.onSync(bitErrors, rotation * SYMBOL_RATE / (2 * PI))
        if (!locked) {
            // First lock: replay the symbols still in the history, up to and including this sync, so the framer
            // sees the start of the fragment the sync sits in.
            val oldest = sampleIndex - ringSize + 2 * samplesPerSymbol + 2
            val available = ((end - oldest) / samplesPerSymbol).toInt().coerceIn(SYNC_LENGTH - 1, BACKLOG_SYMBOLS)
            for (k in available downTo 0) sink.onDibit(P25.slice(symbolPhase(end - k * samplesPerSymbol)))
        }
        nextSymbolTime = end + samplesPerSymbol
        locked = true
    }

    private fun wrap(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a <= -PI) a += (2 * PI).toFloat()
        return a
    }

    companion object {
        const val SYMBOL_RATE = 6000.0
        const val SYNC_LENGTH = 20

        /** S-ISCH sync from sdrtrunk's P25P2SyncPattern: +3 is dibit 01, -3 is dibit 11. */
        val SYNC_DIBITS = intArrayOf(1, 1, 1, 3, 1, 1, 3, 1, 1, 1, 1, 3, 3, 3, 1, 3, 3, 3, 3, 3)

        private const val SYNC_THRESHOLD = 0.8f
        private const val MAX_SYNC_BIT_ERRORS = 5
        private const val TIMING_GAIN = 0.05
        private const val ROTATION_TRACKING = 0.01f
        private const val BACKLOG_SYMBOLS = 740
        /** Two superframes without a sync means the carrier is gone. */
        private const val UNLOCK_SYMBOLS = 4320

        /** Root-raised-cosine taps spanning [span] symbols, normalized to unit peak response. */
        fun rootRaisedCosine(samplesPerSymbol: Double, alpha: Double, span: Int): FloatArray {
            var length = (span * samplesPerSymbol).roundToInt()
            if (length % 2 == 0) length++
            val middle = (length - 1) / 2
            val taps = DoubleArray(length) { n ->
                val t = (n - middle) / samplesPerSymbol
                when {
                    t == 0.0 -> 1 - alpha + 4 * alpha / PI
                    kotlin.math.abs(kotlin.math.abs(4 * alpha * t) - 1) < 1e-9 ->
                        alpha / sqrt(2.0) * ((1 + 2 / PI) * sin(PI / (4 * alpha)) + (1 - 2 / PI) * cos(PI / (4 * alpha)))
                    else -> (sin(PI * t * (1 - alpha)) + 4 * alpha * t * cos(PI * t * (1 + alpha))) /
                        (PI * t * (1 - (4 * alpha * t) * (4 * alpha * t)))
                }
            }
            val energy = sqrt(taps.sumOf { it * it })
            return FloatArray(length) { (taps[it] / energy).toFloat() }
        }
    }
}
