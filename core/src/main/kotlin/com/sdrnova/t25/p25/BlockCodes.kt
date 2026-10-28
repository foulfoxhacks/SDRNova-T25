package com.sdrnova.t25.p25

/** MSB-first bit access on byte arrays: bit 0 is the most significant bit of byte 0, the first bit received. */
object Bits {
    fun get(bytes: ByteArray, index: Int): Int = (bytes[index ushr 3].toInt() ushr (7 - (index and 7))) and 1

    fun set(bytes: ByteArray, index: Int, value: Int) {
        val mask = 1 shl (7 - (index and 7))
        val current = bytes[index ushr 3].toInt()
        bytes[index ushr 3] = (if (value != 0) current or mask else current and mask.inv()).toByte()
    }

    fun flip(bytes: ByteArray, index: Int) = set(bytes, index, get(bytes, index) xor 1)

    fun field(bytes: ByteArray, start: Int, length: Int): Long {
        var value = 0L
        for (i in start until start + length) value = (value shl 1) or get(bytes, i).toLong()
        return value
    }

    fun setField(bytes: ByteArray, start: Int, length: Int, value: Long) {
        for (i in 0 until length) set(bytes, start + i, ((value ushr (length - 1 - i)) and 1L).toInt())
    }

    fun slice(bytes: ByteArray, start: Int, length: Int): ByteArray {
        val out = ByteArray((length + 7) / 8)
        for (i in 0 until length) set(out, i, get(bytes, start + i))
        return out
    }
}

/**
 * Shortened Hamming(10,6,3) protecting each 6-bit link control word in LDU1/LDU2: data bits 0-5, then four parity
 * bits. Parity table from sdrtrunk's Hamming10.
 */
object Hamming10 {
    private val CHECKSUMS = intArrayOf(0xE, 0xD, 0xB, 0x7, 0x3, 0xC)

    /** Syndrome to the bit offset it points at; -1 for syndromes no single-bit error produces. */
    private val CORRECTION = IntArray(16) { -1 }.also { table ->
        CHECKSUMS.forEachIndexed { bit, checksum -> table[checksum] = bit }
        for (parity in 0 until 4) table[1 shl parity] = 9 - parity
    }

    fun encode(data: Int): Int {
        var parity = 0
        for (bit in 0 until 6) if ((data ushr (5 - bit)) and 1 == 1) parity = parity xor CHECKSUMS[bit]
        return (data shl 4) or parity
    }

    /** Corrects a single bit error in place; returns the 6 data bits, or -1 when the word can't be corrected. */
    fun decode(bytes: ByteArray, start: Int): Int {
        var calculated = 0
        for (bit in 0 until 6) if (Bits.get(bytes, start + bit) == 1) calculated = calculated xor CHECKSUMS[bit]
        val syndrome = calculated xor Bits.field(bytes, start + 6, 4).toInt()
        if (syndrome != 0) {
            val position = CORRECTION[syndrome]
            if (position < 0) return -1
            Bits.flip(bytes, start + position)
        }
        return Bits.field(bytes, start, 6).toInt()
    }
}

/**
 * Reed-Solomon over GF(64) with field polynomial x^6 + x + 1 and generator roots α^1..α^(2t), the code P25 Phase 1
 * uses for link control and encryption sync (as in sdrtrunk's ReedSolomon_63_P25).
 *
 * Codewords are coefficient arrays: index i holds the coefficient of x^i. Parity symbols occupy indices
 * 0 until [paritySymbols] and data the indices above, so the first transmitted symbol is the highest index.
 */
class ReedSolomon64(private val paritySymbols: Int) {
    private val generator: IntArray

    init {
        var g = intArrayOf(1)
        for (root in 1..paritySymbols) {
            val next = IntArray(g.size + 1)
            for (i in g.indices) {
                next[i + 1] = next[i + 1] xor g[i]
                next[i] = next[i] xor multiply(g[i], EXP[root])
            }
            g = next
        }
        generator = g
    }

    /** Fills the parity symbols of [codeword] from its data symbols. */
    fun encode(codeword: IntArray) {
        val remainder = codeword.copyOf()
        for (i in 0 until paritySymbols) remainder[i] = 0
        for (degree in remainder.lastIndex downTo paritySymbols) {
            val coefficient = remainder[degree]
            if (coefficient == 0) continue
            for (j in generator.indices) {
                val index = degree - paritySymbols + j
                remainder[index] = remainder[index] xor multiply(coefficient, generator[j])
            }
        }
        for (i in 0 until paritySymbols) codeword[i] = remainder[i]
    }

    /**
     * Corrects up to [paritySymbols]/2 symbol errors in place. Returns the number of symbols corrected, or -1 if the
     * codeword is beyond repair (it is then left unchanged).
     */
    fun decode(codeword: IntArray): Int {
        val n = codeword.size
        val syndromes = IntArray(paritySymbols) { j -> evaluate(codeword, EXP[j + 1]) }
        if (syndromes.all { it == 0 }) return 0

        // Berlekamp-Massey for the error locator Λ(x).
        var locator = intArrayOf(1)
        var previous = intArrayOf(1)
        var length = 0
        var shift = 1
        var previousDiscrepancy = 1
        for (step in 0 until paritySymbols) {
            var discrepancy = syndromes[step]
            for (i in 1..length) if (i < locator.size) discrepancy = discrepancy xor multiply(locator[i], syndromes[step - i])
            if (discrepancy == 0) {
                shift++
                continue
            }
            val scale = divide(discrepancy, previousDiscrepancy)
            val candidate = locator.copyOf(maxOf(locator.size, previous.size + shift))
            for (i in previous.indices) candidate[i + shift] = candidate[i + shift] xor multiply(scale, previous[i])
            if (2 * length <= step) {
                previous = locator
                length = step + 1 - length
                previousDiscrepancy = discrepancy
                shift = 1
            } else {
                shift++
            }
            locator = candidate
        }
        if (length > paritySymbols / 2) return -1

        // Chien search over the (possibly shortened) codeword positions.
        val positions = ArrayList<Int>()
        for (position in 0 until n) {
            if (evaluate(locator, EXP[(63 - position) % 63]) == 0) positions += position
        }
        if (positions.size != length) return -1

        // Forney: e = Ω(X⁻¹) / Λ'(X⁻¹) for roots starting at α^1.
        val omega = IntArray(paritySymbols)
        for (i in syndromes.indices) {
            for (j in locator.indices) if (i + j < paritySymbols) omega[i + j] = omega[i + j] xor multiply(syndromes[i], locator[j])
        }
        val derivative = IntArray(maxOf(1, locator.size - 1)) { i -> if ((i + 1) % 2 == 1) locator.getOrElse(i + 1) { 0 } else 0 }
        val corrected = codeword.copyOf()
        for (position in positions) {
            val inverse = EXP[(63 - position) % 63]
            val denominator = evaluate(derivative, inverse)
            if (denominator == 0) return -1
            corrected[position] = corrected[position] xor divide(evaluate(omega, inverse), denominator)
        }
        if (IntArray(paritySymbols) { j -> evaluate(corrected, EXP[j + 1]) }.any { it != 0 }) return -1
        corrected.copyInto(codeword)
        return positions.size
    }

    private fun evaluate(polynomial: IntArray, x: Int): Int {
        var result = 0
        for (i in polynomial.lastIndex downTo 0) result = multiply(result, x) xor polynomial[i]
        return result
    }

    companion object {
        private val EXP = IntArray(126)
        private val LOG = IntArray(64)

        init {
            var value = 1
            for (i in 0 until 63) {
                EXP[i] = value
                LOG[value] = i
                value = value shl 1
                if (value and 0x40 != 0) value = (value xor 0x43) and 0x3F
            }
            for (i in 63 until 126) EXP[i] = EXP[i - 63]
        }

        fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        fun divide(a: Int, b: Int): Int = if (a == 0) 0 else EXP[(LOG[a] - LOG[b] + 63) % 63]
    }
}
