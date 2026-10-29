package com.sdrnova.t25.p25

sealed interface P25Event {
    data class Sync(val bitErrors: Int, val frequencyErrorHz: Double) : P25Event
    data class Nid(val nac: Int, val duid: Int, val bitErrors: Int) : P25Event
    data object NidFailed : P25Event
    data class Message(val message: TsbkMessage) : P25Event
    data class CrcFailed(val tsbk: Tsbk) : P25Event

    /** A voice superframe half: nine IMBE frames plus link control (LDU1) or encryption sync (LDU2). */
    data class Voice(val nac: Int, val ldu: Ldu) : P25Event

    /** TDU or TDULC: the transmission ended. */
    data class Terminator(val nac: Int, val duid: Int) : P25Event
}

/**
 * Assembles demodulated dibits into P25 Phase 1 frames, modeled on sdrtrunk's P25P1MessageFramer.
 *
 * After each sync it strips the status dibit sent after every 35 data dibits (counting from the first sync dibit),
 * decodes the NID, then decodes up to three TSBKs for a TSDU or the 1568-bit body of an LDU.
 */
class P25P1Framer(private val listener: (P25Event) -> Unit) : C4fmDemodulator.Sink {
    private enum class State { IDLE, NID, TSDU, LDU, PDU, SKIP }

    private var state = State.IDLE
    private var frameDibit = 0
    private var nidBits = 0L
    private var nidCount = 0
    private val block = IntArray(98)
    private var blockCount = 0
    private var blocksDone = 0
    private var lastBlockFailed = false
    private var skipRemaining = 0
    private var lduDuid = 0
    private var lduNac = 0
    private var lduBody = ByteArray(Ldu.BODY_BITS / 8)
    private var lduBits = 0
    private var pduHeader: Tsbk? = null
    private val pduBlocks = ArrayList<ByteArray>()
    private val nacTracker = NacTracker()

    val nac: Int get() = nacTracker.nac

    override fun isSyncAllowed(): Boolean = when (state) {
        State.IDLE, State.SKIP -> true
        State.NID, State.LDU, State.PDU -> false
        // Once a block fails its CRC we can't tell whether more follow, so let the next sync take over.
        State.TSDU -> lastBlockFailed
    }

    override fun onSync(pattern: Int, bitErrors: Int, frequencyErrorHz: Double) {
        listener(P25Event.Sync(bitErrors, frequencyErrorHz))
        state = State.NID
        frameDibit = 24
        nidBits = 0
        nidCount = 0
    }

    override fun onDibit(dibit: Int) {
        if (state == State.IDLE) return
        val index = frameDibit++
        if (index % 36 == 35) return
        when (state) {
            State.NID -> {
                nidBits = (nidBits shl 2) or dibit.toLong()
                if (++nidCount == 32) decodeNid()
            }
            State.TSDU -> {
                block[blockCount++] = dibit
                if (blockCount == 98) decodeBlock()
            }
            State.PDU -> {
                block[blockCount++] = dibit
                if (blockCount == 98) decodePduBlock()
            }
            State.LDU -> {
                Bits.set(lduBody, lduBits++, dibit ushr 1)
                Bits.set(lduBody, lduBits++, dibit and 1)
                if (lduBits == Ldu.BODY_BITS) {
                    state = State.IDLE
                    listener(P25Event.Voice(lduNac, Ldu(lduDuid, lduBody)))
                    lduBody = ByteArray(Ldu.BODY_BITS / 8)
                }
            }
            State.SKIP -> if (--skipRemaining <= 0) state = State.IDLE
            State.IDLE -> Unit
        }
    }

    private fun decodeNid() {
        // The 64th NID bit is a parity bit outside the BCH codeword.
        val result = Bch63.decode(nidBits ushr 1, nacTracker.nac)
        if (result == null) {
            listener(P25Event.NidFailed)
            state = State.IDLE
            return
        }
        nacTracker.add(result.nac)
        listener(P25Event.Nid(result.nac, result.duid, result.bitErrors))
        when (result.duid) {
            P25.DUID_TSDU -> {
                state = State.TSDU
                blockCount = 0
                blocksDone = 0
                lastBlockFailed = false
            }
            P25.DUID_LDU1, P25.DUID_LDU2 -> {
                state = State.LDU
                lduDuid = result.duid
                lduNac = result.nac
                lduBits = 0
            }
            P25.DUID_HDU -> skip(324)
            P25.DUID_TDULC -> {
                listener(P25Event.Terminator(result.nac, result.duid))
                skip(144)
            }
            P25.DUID_TDU -> {
                listener(P25Event.Terminator(result.nac, result.duid))
                state = State.IDLE
            }
            P25.DUID_PDU -> {
                state = State.PDU
                blockCount = 0
                pduHeader = null
                pduBlocks.clear()
            }
            else -> state = State.IDLE
        }
    }

    /**
     * PDU blocks are 98 dibits each. The header uses the TSBK code and CRC; only AMBTC data blocks (also 1/2 rate)
     * are kept, other PDUs (packet data, which may use the 3/4 rate code) are skipped block by block.
     */
    private fun decodePduBlock() {
        blockCount = 0
        val decoded = Trellis12.decode(block)
        val header = pduHeader
        if (header == null) {
            val candidate = Tsbk(decoded.bytes, decoded.bitErrors)
            if (!candidate.crcValid) {
                listener(P25Event.CrcFailed(candidate))
                state = State.IDLE
                return
            }
            val blocks = Ambtc.blocksToFollow(candidate)
            when {
                Ambtc.format(candidate) == Ambtc.FORMAT && blocks in 1..3 -> pduHeader = candidate
                blocks > 0 -> skip(blocks * 98)
                else -> state = State.IDLE
            }
            return
        }
        // Data blocks carry their own CRC-32 across the packet; the Viterbi metric screens out garbled blocks.
        if (decoded.bitErrors > MAX_AMBTC_BLOCK_ERRORS) {
            state = State.IDLE
            return
        }
        pduBlocks += decoded.bytes
        if (pduBlocks.size == Ambtc.blocksToFollow(header)) {
            state = State.IDLE
            listener(P25Event.Message(Ambtc.parse(header, pduBlocks.toList())))
        }
    }

    private companion object {
        const val MAX_AMBTC_BLOCK_ERRORS = 4
    }

    private fun skip(dibits: Int) {
        state = State.SKIP
        skipRemaining = dibits
    }

    private fun decodeBlock() {
        blockCount = 0
        blocksDone++
        val decoded = Trellis12.decode(block)
        val tsbk = Tsbk(decoded.bytes, decoded.bitErrors)
        if (tsbk.crcValid) {
            lastBlockFailed = false
            listener(P25Event.Message(TsbkParser.parse(tsbk)))
            if (tsbk.lastBlock || blocksDone == 3) state = State.IDLE
        } else {
            lastBlockFailed = true
            listener(P25Event.CrcFailed(tsbk))
            if (blocksDone == 3) state = State.IDLE
        }
    }
}

/** Remembers the most common NAC so the NID decoder can check it first. */
class NacTracker {
    private val counts = HashMap<Int, Int>()

    var nac = -1
        private set

    fun add(value: Int) {
        val count = (counts[value] ?: 0) + 1
        counts[value] = count
        if (count >= 3 && (nac < 0 || count > (counts[nac] ?: 0))) nac = value
        if (counts.size > 32) counts.entries.removeIf { it.key != nac && it.value < 2 }
    }
}
