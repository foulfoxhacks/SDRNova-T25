package com.sdrnova.t25.dmr

import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.Crc16

/**
 * Slot type: color code and data type (8 bits) in shortened Golay(24,12) (20 bits). With 256 codewords a nearest
 * match is cheap. Parity table from sdrtrunk's Golay24 (11 Golay parity bits, then overall even parity).
 */
object Golay20 {
    private val CHECKSUMS = intArrayOf(0x63A, 0x31D, 0x7B4, 0x3DA, 0x1ED, 0x6CC, 0x366, 0x1B3, 0x6E3, 0x54B, 0x49F, 0x475)

    /** 20-bit codeword for 8 data bits, laid out as transmitted. */
    fun encode(data: Int): Int {
        // As a 24-bit word the data sits in bits 4-11 behind four zero bits.
        var parity = 0
        for (bit in 0 until 8) if ((data ushr (7 - bit)) and 1 == 1) parity = parity xor CHECKSUMS[bit + 4]
        val word23 = (data shl 11) or parity
        val overall = Integer.bitCount(word23) and 1
        return (word23 shl 1) or overall
    }

    private val CODEWORDS = IntArray(256) { encode(it) }

    /** Returns the 8 data bits, or -1 beyond 3 bit errors. */
    fun decode(word: Int): Int {
        var best = -1
        var bestDistance = 4
        for (data in 0 until 256) {
            val distance = Integer.bitCount(CODEWORDS[data] xor word)
            if (distance < bestDistance) {
                bestDistance = distance
                best = data
            }
        }
        return best
    }
}

/**
 * The CACH ahead of each outbound burst: 24 interleaved bits whose first 7 (after deinterleaving) are the TACT,
 * Hamming(7,4) protected. Interleave and parity from sdrtrunk's CACH.
 */
object Cach {
    private val INTERLEAVE = intArrayOf(0, 4, 8, 12, 14, 18, 22, 1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 15, 16, 17, 19, 20, 21, 23)
    private val CHECKSUMS = intArrayOf(5, 7, 6, 3)

    private fun encodeTact(data: Int): Int {
        var parity = 0
        for (bit in 0 until 4) if ((data ushr (3 - bit)) and 1 == 1) parity = parity xor CHECKSUMS[bit]
        return (data shl 3) or parity
    }

    private val TACTS = IntArray(16) { encodeTact(it) }

    /** Timeslot (1 or 2) of the burst after this CACH, or null if the TACT can't be repaired. */
    fun timeslot(burst: ByteArray): Int? {
        var tact = 0
        for (x in 0 until 7) tact = (tact shl 1) or Bits.get(burst, INTERLEAVE[x])
        val data = (0 until 16).firstOrNull { Integer.bitCount(TACTS[it] xor tact) <= 1 } ?: return null
        // TACT bits: access type, timeslot, LC start/stop (2).
        return if ((data ushr 2) and 1 == 1) 2 else 1
    }

    /** Writes a CACH for [timeslot] into the first 24 bits of [burst] (payload bits zero). */
    fun write(burst: ByteArray, timeslot: Int) {
        val tact = encodeTact(if (timeslot == 2) 0b0100 else 0)
        for (x in 0 until 24) Bits.set(burst, INTERLEAVE[x], if (x < 7) (tact ushr (6 - x)) and 1 else 0)
    }
}

/**
 * Block product turbo code (196,96): a 13 x 15 matrix (rows Hamming(15,11), columns Hamming(13,9)) behind a
 * x181 mod 196 interleave. Layout and parity tables from sdrtrunk's BPTC_196_96, Hamming15 and Hamming13.
 */
object Bptc196 {
    private val ROW_CHECKSUMS = intArrayOf(0x9, 0xD, 0xF, 0xE, 0x7, 0xA, 0x5, 0xB, 0xC, 0x6, 0x3, 0x8, 0x4, 0x2, 0x1)
    private val COLUMN_CHECKSUMS = intArrayOf(0xF, 0xE, 0x7, 0xA, 0x5, 0xB, 0xC, 0x6, 0x3, 0x8, 0x4, 0x2, 0x1)

    private fun index(row: Int, column: Int) = row * 15 + column + 1

    /** Data positions in the deinterleaved matrix: row 0 columns 3-10 (0-2 are reserved), rows 1-8 columns 0-10. */
    private val DATA: List<Int> = (0 until 9).flatMap { row -> ((if (row == 0) 3 else 0)..10).map { index(row, it) } }

    /** [bits196] is the payload as received (interleaved). Returns 96 data bits, or null if uncorrectable. */
    fun decode(bits196: BooleanArray): ByteArray? {
        val matrix = BooleanArray(196) { bits196[(it * 181) % 196] }
        repeat(3) {
            for (row in 0 until 13) correct(matrix, IntArray(15) { index(row, it) }, ROW_CHECKSUMS, if (row < 9) 11 else 0)
            for (column in 0 until 15) correct(matrix, IntArray(13) { index(it, column) }, COLUMN_CHECKSUMS, 9)
        }
        for (row in 0 until 9) if (syndrome(matrix, IntArray(15) { index(row, it) }, ROW_CHECKSUMS, 11) != 0) return null
        for (column in 0 until 15) if (syndrome(matrix, IntArray(13) { index(it, column) }, COLUMN_CHECKSUMS, 9) != 0) return null
        val out = ByteArray(12)
        DATA.forEachIndexed { k, position -> Bits.set(out, k, if (matrix[position]) 1 else 0) }
        return out
    }

    fun encode(data: ByteArray): BooleanArray {
        val matrix = BooleanArray(196)
        DATA.forEachIndexed { k, position -> matrix[position] = Bits.get(data, k) == 1 }
        for (row in 0 until 9) fillParity(matrix, IntArray(15) { index(row, it) }, ROW_CHECKSUMS, 11)
        for (column in 0 until 15) fillParity(matrix, IntArray(13) { index(it, column) }, COLUMN_CHECKSUMS, 9)
        return BooleanArray(196).also { out -> for (x in 0 until 196) out[(x * 181) % 196] = matrix[x] }
    }

    private fun syndrome(matrix: BooleanArray, positions: IntArray, checksums: IntArray, dataBits: Int): Int {
        if (dataBits == 0) return 0
        var calculated = 0
        for (i in 0 until dataBits) if (matrix[positions[i]]) calculated = calculated xor checksums[i]
        var received = 0
        for (i in dataBits until positions.size) received = (received shl 1) or (if (matrix[positions[i]]) 1 else 0)
        return calculated xor received
    }

    private fun correct(matrix: BooleanArray, positions: IntArray, checksums: IntArray, dataBits: Int) {
        val syndrome = syndrome(matrix, positions, checksums, dataBits)
        if (syndrome == 0) return
        val bit = checksums.indexOf(syndrome)
        if (bit >= 0) matrix[positions[bit]] = !matrix[positions[bit]]
    }

    private fun fillParity(matrix: BooleanArray, positions: IntArray, checksums: IntArray, dataBits: Int) {
        var parity = 0
        for (i in 0 until dataBits) if (matrix[positions[i]]) parity = parity xor checksums[i]
        val parityBits = positions.size - dataBits
        for (i in 0 until parityBits) matrix[positions[dataBits + i]] = (parity ushr (parityBits - 1 - i)) and 1 == 1
    }
}

/**
 * Reed-Solomon (12,9) over GF(256) (x^8+x^4+x^3+x^2+1, roots α..α³) protecting full link control; the parity
 * bytes are XOR-masked by burst type (voice header 0x96, terminator 0x99). Parameters from sdrtrunk's RS_12_9_DMR.
 * Used here to check, not correct: the BPTC has already repaired bit errors.
 */
object Rs129 {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)
    private val GENERATOR = intArrayOf(0x40, 0x38, 0x0E, 0x01)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            LOG[value] = i
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun multiply(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Parity for 9 data bytes (first byte highest degree), unmasked. */
    fun parity(data: ByteArray): IntArray {
        val remainder = IntArray(3)
        for (i in 0 until 9) {
            val feedback = (data[i].toInt() and 0xFF) xor remainder[0]
            remainder[0] = remainder[1] xor multiply(feedback, GENERATOR[2])
            remainder[1] = remainder[2] xor multiply(feedback, GENERATOR[1])
            remainder[2] = multiply(feedback, GENERATOR[0])
        }
        return remainder
    }

    fun check(lc: ByteArray, mask: Int): Boolean {
        val expected = parity(lc)
        return (0 until 3).all { (lc[9 + it].toInt() and 0xFF) xor mask == expected[it] }
    }
}

object DmrCrc {
    const val CSBK_MASK = 0xA5A5

    /** CRC-CCITT over 80 bits, masked; accepts it with or without the final inversion, as sdrtrunk does. */
    fun checkCsbk(block: ByteArray): Boolean {
        val residual = Crc16.ccitt(block, 80) xor CSBK_MASK xor Bits.field(block, 80, 16).toInt()
        return residual == 0 || residual == 0xFFFF
    }

    fun fillCsbk(block: ByteArray) = Bits.setField(block, 80, 16, (Crc16.ccitt(block, 80) xor CSBK_MASK).toLong())
}
