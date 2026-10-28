package com.sdrnova.t25.p25

/** 72-bit link control word carried in LDU1 (and TDULC). Field offsets follow sdrtrunk's LinkControlWord classes. */
sealed interface LinkControl {
    val bits: ByteArray
    val opcode: Int get() = Bits.field(bits, 2, 6).toInt()
    val vendor: Int get() = if (Bits.get(bits, 1) == 1) 0 else Bits.field(bits, 8, 8).toInt()
}

data class GroupVoiceUser(override val bits: ByteArray, val options: ServiceOptions, val group: Int, val source: Int) :
    LinkControl

data class UnitToUnitVoiceUser(override val bits: ByteArray, val target: Int, val source: Int) : LinkControl

data class CallTermination(override val bits: ByteArray, val address: Int) : LinkControl

data class OtherLinkControl(override val bits: ByteArray) : LinkControl

/** LDU2 encryption sync: 72-bit message indicator, algorithm and key. Algorithm 0x80 means clear. */
data class EncryptionSync(val algorithm: Int, val keyId: Int, val messageIndicator: Long) {
    val encrypted: Boolean get() = algorithm != CLEAR

    companion object {
        const val CLEAR = 0x80
    }
}

/**
 * One logical link data unit after the NID: nine 144-bit IMBE voice frames with link control (LDU1) or encryption
 * sync (LDU2) spread between them as 24 Hamming(10,6)-protected words, Reed-Solomon coded across the set.
 * Frames are passed on raw (still interleaved and FEC coded), exactly as sdrtrunk hands them to JMBE.
 */
class Ldu(val duid: Int, val body: ByteArray) {
    /** Nine raw 144-bit voice frames, MSB-first. */
    val voiceFrames: List<ByteArray> by lazy { FRAME_STARTS.map { Bits.slice(body, it, FRAME_BITS) } }

    var correctedSymbols = 0
        private set

    /** Null when the Reed-Solomon code couldn't repair the words (or this is an LDU2). */
    val linkControl: LinkControl? by lazy { if (duid == P25.DUID_LDU1) decodeLinkControl() else null }

    val encryption: EncryptionSync? by lazy { if (duid == P25.DUID_LDU2) decodeEncryption() else null }

    private fun words(): IntArray? {
        val copy = body.copyOf()
        val words = IntArray(24) { Hamming10.decode(copy, WORD_STARTS[it]) }
        // An uncorrectable Hamming word becomes a Reed-Solomon erasure-by-error; keep the received data bits.
        for (k in 0 until 24) if (words[k] < 0) words[k] = Bits.field(body, WORD_STARTS[k], 6).toInt()
        return words
    }

    private fun decodeCodeword(dataWords: Int): IntArray? {
        val words = words() ?: return null
        // Transmit order word k is the coefficient of x^(23 - k): data first, parity last.
        val codeword = IntArray(24) { words[23 - it] }
        val result = ReedSolomon64(24 - dataWords).decode(codeword)
        if (result < 0) return null
        correctedSymbols = result
        return IntArray(dataWords) { codeword[23 - it] }
    }

    private fun decodeLinkControl(): LinkControl? {
        val data = decodeCodeword(12) ?: return null
        val bits = ByteArray(9)
        data.forEachIndexed { index, word -> Bits.setField(bits, index * 6, 6, word.toLong()) }
        return parseLinkControl(bits)
    }

    private fun decodeEncryption(): EncryptionSync? {
        val data = decodeCodeword(16) ?: return null
        val bits = ByteArray(12)
        data.forEachIndexed { index, word -> Bits.setField(bits, index * 6, 6, word.toLong()) }
        return EncryptionSync(
            algorithm = Bits.field(bits, 72, 8).toInt(),
            keyId = Bits.field(bits, 80, 16).toInt(),
            messageIndicator = Bits.field(bits, 0, 64),
        )
    }

    companion object {
        const val BODY_BITS = 1568
        const val FRAME_BITS = 144
        val FRAME_STARTS = intArrayOf(0, 144, 328, 512, 696, 880, 1064, 1248, 1424)

        /** Start of each 10-bit Hamming word, in transmit order. */
        val WORD_STARTS = intArrayOf(
            288, 298, 308, 318, 472, 482, 492, 502, 656, 666, 676, 686,
            840, 850, 860, 870, 1024, 1034, 1044, 1054, 1208, 1218, 1228, 1238,
        )

        fun parseLinkControl(bits: ByteArray): LinkControl {
            val standard = Bits.get(bits, 1) == 1 || Bits.field(bits, 8, 8) == 0L
            if (!standard) return OtherLinkControl(bits)
            return when (Bits.field(bits, 2, 6).toInt()) {
                0x00 -> GroupVoiceUser(
                    bits, ServiceOptions(Bits.field(bits, 16, 8).toInt()),
                    group = Bits.field(bits, 32, 16).toInt(), source = Bits.field(bits, 48, 24).toInt(),
                )
                0x03 -> UnitToUnitVoiceUser(bits, target = Bits.field(bits, 24, 24).toInt(), source = Bits.field(bits, 48, 24).toInt())
                0x0F -> CallTermination(bits, address = Bits.field(bits, 48, 24).toInt())
                else -> OtherLinkControl(bits)
            }
        }

        /** Builds an LDU body around [frames], used by tests and as executable documentation of the layout. */
        fun encode(duid: Int, frames: List<ByteArray>, dataWords: IntArray, lowSpeedData: Int = 0): ByteArray {
            require(frames.size == 9)
            val body = ByteArray(BODY_BITS / 8)
            frames.forEachIndexed { index, frame ->
                for (bit in 0 until FRAME_BITS) Bits.set(body, FRAME_STARTS[index] + bit, Bits.get(frame, bit))
            }
            val codeword = IntArray(24)
            dataWords.forEachIndexed { index, word -> codeword[23 - index] = word }
            ReedSolomon64(24 - dataWords.size).encode(codeword)
            for (k in 0 until 24) Bits.setField(body, WORD_STARTS[k], 10, Hamming10.encode(codeword[23 - k]).toLong())
            Bits.setField(body, 1392, 8, (lowSpeedData ushr 8).toLong())
            Bits.setField(body, 1408, 8, (lowSpeedData and 0xFF).toLong())
            return body
        }

        fun linkControlWords(bits: ByteArray): IntArray = IntArray(12) { Bits.field(bits, it * 6, 6).toInt() }

        fun encryptionWords(sync: EncryptionSync): IntArray {
            val bits = ByteArray(12)
            Bits.setField(bits, 0, 64, sync.messageIndicator)
            Bits.setField(bits, 72, 8, sync.algorithm.toLong())
            Bits.setField(bits, 80, 16, sync.keyId.toLong())
            return IntArray(16) { Bits.field(bits, it * 6, 6).toInt() }
        }
    }
}
