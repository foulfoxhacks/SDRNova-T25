package com.sdrnova.t25.p25

/**
 * The BCH(63,16,23) code that protects the NID (12-bit NAC + 4-bit DUID). With only 16 information bits the decoder
 * can afford a maximum-likelihood search: it tries the tracked NAC first, then every NAC with every valid DUID.
 */
object Bch63 {
    /** Generator polynomial from TIA-102.BAAA, octal 6331 1413 6723 5453. */
    const val GENERATOR = 0xCD930BDD3B2BL
    const val PARITY_BITS = 47
    const val MAX_ERRORS = 11
    private const val TRACKED_NAC_MAX_ERRORS = 8

    data class Result(val nac: Int, val duid: Int, val bitErrors: Int)

    /** Systematic codeword: NAC and DUID in bits 62..47, parity in bits 46..0. Bit 62 is sent first. */
    fun encode(nac: Int, duid: Int): Long {
        val info = (((nac and 0xFFF) shl 4) or (duid and 0xF)).toLong()
        var remainder = info shl PARITY_BITS
        for (bit in 62 downTo PARITY_BITS) {
            if ((remainder ushr bit) and 1L == 1L) {
                remainder = remainder xor (GENERATOR shl (bit - PARITY_BITS))
            }
        }
        return (info shl PARITY_BITS) or remainder
    }

    private val codewords: LongArray by lazy { LongArray(65536) { encode(it ushr 4, it and 0xF) } }

    /** Decodes the 63 code bits (bit 62 first-received); returns null when no codeword is close enough. */
    fun decode(received: Long, trackedNac: Int = -1): Result? {
        val word = received and 0x7FFFFFFFFFFFFFFFL
        if (trackedNac in 0..0xFFF) {
            val best = search(word, trackedNac, trackedNac)
            if (best != null && best.bitErrors <= TRACKED_NAC_MAX_ERRORS) return best
        }
        return search(word, 0, 0xFFF)?.takeIf { it.bitErrors <= MAX_ERRORS }
    }

    private fun search(word: Long, firstNac: Int, lastNac: Int): Result? {
        val table = codewords
        var bestDistance = Int.MAX_VALUE
        var bestInfo = -1
        for (nac in firstNac..lastNac) {
            for (duid in P25.VALID_DUIDS) {
                val info = (nac shl 4) or duid
                val distance = (table[info] xor word).countOneBits()
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestInfo = info
                }
            }
        }
        return if (bestInfo < 0) null else Result(bestInfo ushr 4, bestInfo and 0xF, bestDistance)
    }
}

/**
 * The 1/2 rate trellis code and 49-symbol block interleave used for TSBKs and other P25 Phase 1 data blocks.
 * Transition table from sdrtrunk's P25_1_2_Node; each 4-bit output is two dibits, high dibit sent first.
 */
object Trellis12 {
    private val TRANSITIONS = arrayOf(
        intArrayOf(2, 12, 1, 15),
        intArrayOf(14, 0, 13, 3),
        intArrayOf(9, 7, 10, 4),
        intArrayOf(5, 11, 6, 8),
    )

    /** The interleaver sends deinterleaved symbols 0,4,8..48 first, then 1,5..45, then 2,6..46, then 3,7..47. */
    private val INTERLEAVE_START = intArrayOf(0, 13, 25, 37)

    private fun transmitPosition(symbol: Int) = INTERLEAVE_START[symbol % 4] + symbol / 4

    class Decoded(val bytes: ByteArray, val bitErrors: Int)

    /** Encodes 96 bits (12 bytes) into 98 dibits in transmit order. */
    fun encode(bytes: ByteArray): IntArray {
        require(bytes.size == 12)
        val symbols = IntArray(49)
        var state = 0
        for (step in 0 until 49) {
            val input = if (step < 48) dibitOf(bytes, step) else 0
            symbols[transmitPosition(step)] = TRANSITIONS[state][input]
            state = input
        }
        return IntArray(98) { if (it % 2 == 0) symbols[it / 2] shr 2 else symbols[it / 2] and 3 }
    }

    /** Deinterleaves and Viterbi-decodes 98 received dibits back into 96 bits. */
    fun decode(dibits: IntArray): Decoded {
        require(dibits.size >= 98)
        val received = IntArray(49) {
            val position = transmitPosition(it)
            (dibits[2 * position] shl 2) or dibits[2 * position + 1]
        }
        var metrics = intArrayOf(0, UNREACHABLE, UNREACHABLE, UNREACHABLE)
        val predecessors = Array(49) { IntArray(4) }
        for (step in 0 until 49) {
            val next = IntArray(4) { UNREACHABLE }
            for (state in 0..3) {
                if (metrics[state] >= UNREACHABLE) continue
                for (input in 0..3) {
                    val metric = metrics[state] + (TRANSITIONS[state][input] xor received[step]).countOneBits()
                    if (metric < next[input]) {
                        next[input] = metric
                        predecessors[step][input] = state
                    }
                }
            }
            metrics = next
        }
        // The final (flush) input is always zero, so trace back from state 0.
        val inputs = IntArray(49)
        var state = 0
        for (step in 48 downTo 0) {
            inputs[step] = state
            state = predecessors[step][state]
        }
        val bytes = ByteArray(12)
        for (step in 0 until 48) {
            val shift = 6 - 2 * (step % 4)
            bytes[step / 4] = (bytes[step / 4].toInt() or (inputs[step] shl shift)).toByte()
        }
        return Decoded(bytes, metrics[0])
    }

    private fun dibitOf(bytes: ByteArray, index: Int): Int =
        (bytes[index / 4].toInt() ushr (6 - 2 * (index % 4))) and 3

    private const val UNREACHABLE = Int.MAX_VALUE / 2
}

object Crc16 {
    /** CRC-CCITT (x^16 + x^12 + x^5 + 1) over the first [bitCount] bits, inverted the way P25 transmits it. */
    fun ccitt(bytes: ByteArray, bitCount: Int): Int {
        var crc = 0
        for (i in 0 until bitCount) {
            val bit = (bytes[i / 8].toInt() ushr (7 - i % 8)) and 1
            val feedback = ((crc ushr 15) and 1) xor bit
            crc = (crc shl 1) and 0xFFFF
            if (feedback == 1) crc = crc xor 0x1021
        }
        return crc xor 0xFFFF
    }
}
