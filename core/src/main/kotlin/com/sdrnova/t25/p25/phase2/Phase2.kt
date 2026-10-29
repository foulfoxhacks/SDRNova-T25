package com.sdrnova.t25.p25.phase2

import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.ReedSolomon64
import com.sdrnova.t25.p25.ServiceOptions

/**
 * P25 Phase 2 TDMA framing and signalling, following sdrtrunk's phase2 package: superframe fragments, ISCH, the
 * WACN/system/NAC scrambling sequence, timeslot DUIDs, FACCH/SACCH Reed-Solomon and CRC-12, and MAC PDUs.
 */
object Phase2 {
    const val FRAGMENT_BITS = 1440
    const val TIMESLOT_BITS = 320

    /** Timeslot starts within a fragment; I-ISCH precede A and B, the S-ISCH sync precedes C and D. */
    val TIMESLOT_STARTS = intArrayOf(40, 400, 760, 1120)
}

/** WACN, system and NAC: the seed of the scrambling sequence on a Phase 2 channel. */
data class ScrambleParameters(val wacn: Int, val system: Int, val nac: Int)

/** The 4320-bit superframe scrambling sequence (sdrtrunk's LinearFeedbackShiftRegister), cut into 12 timeslots. */
class Scrambler(val parameters: ScrambleParameters) {
    private val segments: List<ByteArray>

    init {
        var register = ((parameters.wacn.toLong() and 0xFFFFF) shl 24) or
            ((parameters.system.toLong() and 0xFFF) shl 12) or (parameters.nac.toLong() and 0xFFF)
        if (register == 0L) register = MASK
        val sequence = ByteArray(4320 / 8)
        for (bit in 0 until 4320) {
            val output = (register ushr 43) and 1L
            var feedback = output
            for (tap in intArrayOf(33, 19, 14, 8, 3)) feedback = feedback xor ((register ushr tap) and 1L)
            register = ((register shl 1) and MASK) or feedback
            Bits.set(sequence, bit, output.toInt())
        }
        segments = List(12) { Bits.slice(sequence, 20 + 360 * it, Phase2.TIMESLOT_BITS) }
    }

    fun segment(timeslot: Int): ByteArray = segments[timeslot]

    private companion object {
        const val MASK = 0xFFFFFFFFFFFL
    }
}

/** Inter-slot signalling channel: 9 bits in a 40-bit codeword. Table from sdrtrunk's ISCHDecoder. */
object Isch {
    data class Info(val channel: Int, val sequence: Int, val bits: Int)

    private val GENERATOR = longArrayOf(
        0x8816CE36D7L, 0x201DFD4F64L, 0x100F4B1758L, 0x0C00DED18EL, 0x020807F7FFL,
        0x09048D9B72L, 0x009DA3A171L, 0x0058CBAA4EL, 0x00343D8597L,
    )
    private const val OFFSET = 0x184229D461L

    fun encode(info: Int): Long {
        var codeword = 0L
        for (bit in 0 until 9) if ((info ushr (8 - bit)) and 1 == 1) codeword = codeword xor GENERATOR[bit]
        return codeword xor OFFSET
    }

    private val CODEWORDS: List<Pair<Long, Info>> = (0 until 512).mapNotNull { info ->
        val sequence = (info ushr 3) and 3
        if (sequence == 3) null else encode(info) to Info(channel = ((info ushr 5) and 3) + 1, sequence = sequence, bits = info)
    }

    /** Nearest valid codeword for [expectedChannel], or null beyond 7 bit errors. */
    fun decode(word: Long, expectedChannel: Int): Info? {
        var best: Info? = null
        var bestDistance = 8
        for ((codeword, info) in CODEWORDS) {
            if (info.channel != expectedChannel) continue
            val distance = (codeword xor word).countOneBits()
            if (distance < bestDistance) {
                bestDistance = distance
                best = info
            }
        }
        return best
    }
}

/** Timeslot data unit IDs: 4 bits plus 4 parity bits, spread over bit pairs 0-1, 74-75, 244-245 and 318-319. */
enum class TimeslotType(val encoded: Int, val scrambled: Boolean) {
    VOICE_4(0x00, true),
    SACCH_SCRAMBLED(0x39, true),
    VOICE_2(0x65, true),
    FACCH_SCRAMBLED(0x9A, true),
    DATCH_SCRAMBLED(0xA3, true),
    SACCH(0xC6, false),
    LCCH(0xD1, false),
    FACCH(0xFF, false),
    RESERVED(-1, false);

    companion object {
        private val POSITIONS = intArrayOf(0, 1, 74, 75, 244, 245, 318, 319)
        private val CODES = intArrayOf(0x00, 0x17, 0x2E, 0x39, 0x4B, 0x5C, 0x65, 0x72, 0x8D, 0x9A, 0xA3, 0xB4, 0xC6, 0xD1, 0xE8, 0xFF)

        fun read(timeslot: ByteArray): Int {
            var value = 0
            for (position in POSITIONS) value = (value shl 1) or Bits.get(timeslot, position)
            return value
        }

        fun write(timeslot: ByteArray, encoded: Int) {
            POSITIONS.forEachIndexed { index, position -> Bits.set(timeslot, position, (encoded ushr (7 - index)) and 1) }
        }

        /** Nearest code (corrects one bit error); reserved codes map to [RESERVED]. */
        fun decode(timeslot: ByteArray): TimeslotType? {
            val received = read(timeslot)
            val nearest = CODES.minBy { (it xor received).countOneBits() }
            if ((nearest xor received).countOneBits() > 1) return null
            return entries.firstOrNull { it.encoded == nearest } ?: RESERVED
        }
    }
}

/** CRC-12 (x^12+x^11+x^7+x^4+x^2+x+1, inverted) used on FACCH and SACCH MAC PDUs. */
object Crc12 {
    fun compute(bits: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            val feedback = ((crc ushr 11) and 1) xor Bits.get(bits, i)
            crc = (crc shl 1) and 0xFFF
            if (feedback == 1) crc = crc xor 0x897
        }
        return crc xor 0xFFF
    }

    fun check(bits: ByteArray, length: Int): Boolean = compute(bits, length) == Bits.field(bits, length, 12).toInt()
}

/**
 * FACCH and SACCH both carry a MAC PDU in Reed-Solomon (63,35) codewords, shortened and punctured differently.
 * Symbol positions skip the DUID bit pairs, and FACCH also skips bits 138-179.
 */
enum class SignallingCode(val infoSymbols: Int, val paritySymbols: Int, val dataBits: Int) {
    FACCH(26, 19, 144),
    SACCH(30, 22, 168);

    /** Bit offset of each 6-bit symbol in transmit order: info symbols first, then parity. */
    val symbolBits: IntArray = run {
        val gaps = if (this.name == "FACCH") setOf(74, 75, 244, 245) + (138..179) else setOf(74, 75, 244, 245)
        val positions = (2 until Phase2.TIMESLOT_BITS - 2).filter { it !in gaps }
        IntArray((infoSymbols + paritySymbols) * 6) { positions[it] }
    }

    /** The MAC PDU (data bits plus CRC-12), or null if Reed-Solomon or the CRC fails. */
    fun decode(timeslot: ByteArray): ByteArray? {
        val total = infoSymbols + paritySymbols
        // Codeword index of transmit symbol k: info from the top down, then parity; low indices are punctured.
        val codeword = IntArray(infoSymbols + 28)
        for (k in 0 until total) {
            var symbol = 0
            for (b in 0 until 6) symbol = (symbol shl 1) or Bits.get(timeslot, symbolBits[k * 6 + b])
            codeword[codeword.size - 1 - k] = symbol
        }
        if (RS.decode(codeword) < 0) return null
        val pdu = ByteArray((infoSymbols * 6 + 7) / 8)
        for (k in 0 until infoSymbols) Bits.setField(pdu, k * 6, 6, codeword[codeword.size - 1 - k].toLong())
        return if (Crc12.check(pdu, dataBits)) pdu else null
    }

    /** Writes [pdu] (data bits; the CRC is filled in here) into [timeslot]'s symbol positions. */
    fun encode(pdu: ByteArray, timeslot: ByteArray) {
        Bits.setField(pdu, dataBits, 12, Crc12.compute(pdu, dataBits).toLong())
        val codeword = IntArray(infoSymbols + 28)
        for (k in 0 until infoSymbols) codeword[codeword.size - 1 - k] = Bits.field(pdu, k * 6, 6).toInt()
        RS.encode(codeword)
        for (k in 0 until infoSymbols + paritySymbols) {
            val symbol = codeword[codeword.size - 1 - k]
            for (b in 0 until 6) Bits.set(timeslot, symbolBits[k * 6 + b], (symbol ushr (5 - b)) and 1)
        }
    }

    private companion object {
        val RS = ReedSolomon64(28)
    }
}

/** MAC PDU contents relevant to following a call. Offsets follow sdrtrunk's PushToTalk and MAC structure classes. */
sealed interface MacMessage {
    val pduType: Int
}

data class PushToTalk(override val pduType: Int, val algorithm: Int, val keyId: Int, val source: Int, val group: Int) : MacMessage {
    val encrypted: Boolean get() = algorithm != 0x80 && algorithm != 0
}

data class EndPushToTalk(override val pduType: Int, val source: Int, val group: Int) : MacMessage

data class MacVoiceUser(
    override val pduType: Int,
    val options: ServiceOptions,
    val group: Int?,
    val target: Int?,
    val source: Int,
) : MacMessage

data class MacOther(override val pduType: Int, val opcode: Int) : MacMessage

object MacParser {
    const val PTT = 1
    const val END_PTT = 2
    const val IDLE = 3
    const val ACTIVE = 4
    const val HANGTIME = 6

    /** Octet lengths of the standard MAC structures this parser understands, from sdrtrunk's MacOpcode. */
    private val LENGTHS = mapOf(0x01 to 7, 0x02 to 8, 0x05 to 16, 0x21 to 14, 0x22 to 15, 0x25 to 15, 0x42 to 9, 0x7B to 11)

    fun parse(pdu: ByteArray, dataBits: Int): List<MacMessage> {
        val type = Bits.field(pdu, 0, 3).toInt()
        return when (type) {
            PTT -> listOf(
                PushToTalk(
                    type, algorithm = field(pdu, 80, 8), keyId = field(pdu, 88, 16),
                    source = field(pdu, 104, 24), group = field(pdu, 128, 16),
                ),
            )
            END_PTT -> listOf(EndPushToTalk(type, source = field(pdu, 104, 24), group = field(pdu, 128, 16)))
            0, IDLE, ACTIVE, HANGTIME -> structures(pdu, type, dataBits)
            else -> listOf(MacOther(type, -1))
        }
    }

    private fun structures(pdu: ByteArray, type: Int, dataBits: Int): List<MacMessage> {
        val messages = ArrayList<MacMessage>()
        var offset = 8
        while (offset + 8 <= dataBits) {
            val opcode = field(pdu, offset, 8)
            if (opcode == 0) break
            val length = LENGTHS[opcode]
            messages += when (opcode) {
                0x01, 0x21 -> MacVoiceUser(
                    type, ServiceOptions(field(pdu, offset + 8, 8)), group = field(pdu, offset + 16, 16), target = null,
                    source = field(pdu, offset + 32, 24),
                )
                0x02, 0x22 -> MacVoiceUser(
                    type, ServiceOptions(field(pdu, offset + 8, 8)), group = null, target = field(pdu, offset + 16, 24),
                    source = field(pdu, offset + 40, 24),
                )
                else -> MacOther(type, opcode)
            }
            if (length == null) break
            offset += length * 8
        }
        return messages
    }

    private fun field(bits: ByteArray, start: Int, length: Int) = Bits.field(bits, start, length).toInt()
}
