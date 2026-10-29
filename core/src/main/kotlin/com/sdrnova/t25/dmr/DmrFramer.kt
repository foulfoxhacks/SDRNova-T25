package com.sdrnova.t25.dmr

import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.C4fmDemodulator
import com.sdrnova.t25.p25.ServiceOptions

/** DMR sync patterns (sdrtrunk's DMRSyncPattern), as 24 dibits each. */
enum class DmrSync(val pattern: Long, val voice: Boolean, val directTimeslot: Int?) {
    BS_DATA(0xDFF57D75DF5DL, false, null),
    BS_VOICE(0x755FD7DF75F7L, true, null),
    MS_DATA(0xD5D7F77FD757L, false, null),
    MS_VOICE(0x7F7D5DD57DFDL, true, null),
    DIRECT_DATA_1(0xF7FDD5DDFD55L, false, 1),
    DIRECT_DATA_2(0xD7557F5FF7F5L, false, 2),
    DIRECT_VOICE_1(0x5D577F7757FFL, true, 1),
    DIRECT_VOICE_2(0x7DFFD5F55D5FL, true, 2);

    val dibits = IntArray(24) { ((pattern ushr (46 - 2 * it)) and 3L).toInt() }

    companion object {
        val DIBITS: List<IntArray> = entries.map { it.dibits }
    }
}

/** 72-bit full link control: FLCO 0 is a group call, 3 a private call. Offsets from sdrtrunk's FullLCMessage. */
data class DmrLinkControl(
    val opcode: Int,
    val vendor: Int,
    val options: ServiceOptions,
    val target: Int,
    val source: Int,
) {
    val group: Boolean get() = opcode == 0
    val privateCall: Boolean get() = opcode == 3
    val isVoice: Boolean get() = group || privateCall

    companion object {
        fun parse(bits: ByteArray) = DmrLinkControl(
            opcode = Bits.field(bits, 2, 6).toInt(),
            vendor = Bits.field(bits, 8, 8).toInt(),
            options = ServiceOptions(Bits.field(bits, 16, 8).toInt()),
            target = Bits.field(bits, 24, 24).toInt(),
            source = Bits.field(bits, 48, 24).toInt(),
        )

        fun encode(opcode: Int, target: Int, source: Int, options: Int = 0): ByteArray {
            val bits = ByteArray(12)
            Bits.setField(bits, 2, 6, opcode.toLong())
            Bits.setField(bits, 16, 8, options.toLong())
            Bits.setField(bits, 24, 24, target.toLong())
            Bits.setField(bits, 48, 24, source.toLong())
            return bits
        }
    }
}

sealed interface DmrEvent {
    val timeslot: Int

    data class Sync(val bitErrors: Int, val frequencyErrorHz: Double) : DmrEvent {
        override val timeslot = 0
    }

    /** Voice LC header (call start) or terminator with LC (call end). */
    data class LinkControl(override val timeslot: Int, val terminator: Boolean, val colorCode: Int, val lc: DmrLinkControl) : DmrEvent

    data class Csbk(override val timeslot: Int, val colorCode: Int, val opcode: Int, val vendor: Int, val bits: ByteArray) : DmrEvent

    /** Three raw 72-bit AMBE+2 frames (MSB-first) from one voice burst. */
    data class Voice(override val timeslot: Int, val frames: List<ByteArray>) : DmrEvent

    data class Idle(override val timeslot: Int) : DmrEvent

    data class Data(override val timeslot: Int, val dataType: Int) : DmrEvent
}

/**
 * Cuts the DMR dibit stream into 30 ms slots, modeled on sdrtrunk's DMRMessageFramer: 12 CACH dibits, 54 payload,
 * 24 sync or embedded signalling, 54 payload. A sync fixes the slot grid; the grid then carries on through the
 * voice bursts B-F, which carry embedded signalling instead of a sync.
 */
class DmrFramer(private val listener: (DmrEvent) -> Unit) : C4fmDemodulator.Sink {
    private val ring = IntArray(RING)
    private var count = 0L
    private var pendingSync = -1
    private var pendingCheck = -1L
    private var slotEnd = -1L
    private var lastSyncEnd = -1L
    private var lastTimeslot = 2
    private val voiceFrame = IntArray(3) { -1 }

    override fun isSyncAllowed() = true

    override fun onSync(pattern: Int, bitErrors: Int, frequencyErrorHz: Double) {
        listener(DmrEvent.Sync(bitErrors, frequencyErrorHz))
        // Locate the sync exactly once a couple more dibits are in: the clock may or may not have emitted its last one.
        pendingSync = pattern
        pendingCheck = count + 2
    }

    override fun onDibit(dibit: Int) {
        ring[(count and MASK).toInt()] = dibit
        count++
        if (pendingSync >= 0 && count >= pendingCheck) {
            alignTo(DmrSync.entries[pendingSync])
            pendingSync = -1
        }
        if (slotEnd > 0 && count == slotEnd) {
            process(slotEnd - SLOT_DIBITS)
            slotEnd += SLOT_DIBITS
            // Without a sync for a few seconds the grid is no longer trustworthy.
            if (slotEnd - lastSyncEnd > LOST_DIBITS) slotEnd = -1
        }
    }

    private fun alignTo(sync: DmrSync) {
        var bestEnd = -1L
        var bestErrors = MAX_SYNC_ERRORS + 1
        for (back in 1L..6L) {
            val end = count - back
            val errors = syncErrors(end - 23, sync.dibits)
            if (errors < bestErrors) {
                bestErrors = errors
                bestEnd = end
            }
        }
        if (bestEnd < 0) return
        lastSyncEnd = bestEnd
        // The sync's last dibit is slot dibit 89; the slot ends 54 dibits later.
        val end = bestEnd + 55
        if (slotEnd < 0 || slotEnd != end) slotEnd = end
    }

    private fun syncErrors(start: Long, dibits: IntArray): Int {
        var errors = 0
        for (k in 0 until 24) errors += (ring[((start + k) and MASK).toInt()] xor dibits[k]).countOneBits()
        return errors
    }

    private fun process(start: Long) {
        val bits = ByteArray(36)
        for (k in 0 until SLOT_DIBITS) {
            val dibit = ring[((start + k) and MASK).toInt()]
            Bits.set(bits, 2 * k, dibit ushr 1)
            Bits.set(bits, 2 * k + 1, dibit and 1)
        }
        val sync = DmrSync.entries.firstOrNull { syncErrors(start + 66, it.dibits) <= MAX_SYNC_ERRORS }
        val timeslot = sync?.directTimeslot ?: Cach.timeslot(bits) ?: (3 - lastTimeslot)
        lastTimeslot = timeslot
        decodeBurst(bits, sync, timeslot)?.let(listener)
    }

    /** Decodes one 288-bit slot (CACH included). Exposed for tests. */
    fun decodeBurst(bits: ByteArray, sync: DmrSync?, timeslot: Int): DmrEvent? {
        val superframe = voiceFrame[timeslot]
        return when {
            sync != null && sync.voice -> {
                voiceFrame[timeslot] = 0
                DmrEvent.Voice(timeslot, voiceFrames(bits))
            }
            sync != null -> {
                voiceFrame[timeslot] = -1
                decodeData(bits, timeslot)
            }
            // Voice bursts B-F follow A in the same timeslot without a sync.
            superframe in 0..4 -> {
                voiceFrame[timeslot] = superframe + 1
                DmrEvent.Voice(timeslot, voiceFrames(bits))
            }
            else -> null
        }
    }

    private fun voiceFrames(bits: ByteArray): List<ByteArray> {
        val second = ByteArray(9)
        for (b in 0 until 36) Bits.set(second, b, Bits.get(bits, 96 + b))
        for (b in 0 until 36) Bits.set(second, 36 + b, Bits.get(bits, 180 + b))
        return listOf(Bits.slice(bits, 24, 72), second, Bits.slice(bits, 216, 72))
    }

    private fun decodeData(bits: ByteArray, timeslot: Int): DmrEvent? {
        var slotType = 0
        for (b in 0 until 10) slotType = (slotType shl 1) or Bits.get(bits, 122 + b)
        for (b in 0 until 10) slotType = (slotType shl 1) or Bits.get(bits, 180 + b)
        val decoded = Golay20.decode(slotType)
        if (decoded < 0) return null
        val colorCode = decoded ushr 4
        val dataType = decoded and 0xF
        if (dataType == DATA_IDLE) return DmrEvent.Idle(timeslot)
        if (dataType !in setOf(DATA_VOICE_HEADER, DATA_TERMINATOR, DATA_CSBK)) return DmrEvent.Data(timeslot, dataType)

        val payload = BooleanArray(196)
        for (b in 0 until 98) payload[b] = Bits.get(bits, 24 + b) == 1
        for (b in 0 until 98) payload[98 + b] = Bits.get(bits, 190 + b) == 1
        val block = Bptc196.decode(payload) ?: return null
        return when (dataType) {
            DATA_CSBK -> if (DmrCrc.checkCsbk(block)) {
                DmrEvent.Csbk(timeslot, colorCode, Bits.field(block, 2, 6).toInt(), Bits.field(block, 8, 8).toInt(), block)
            } else null
            else -> {
                val terminator = dataType == DATA_TERMINATOR
                if (Rs129.check(block, if (terminator) TERMINATOR_MASK else HEADER_MASK)) {
                    DmrEvent.LinkControl(timeslot, terminator, colorCode, DmrLinkControl.parse(block))
                } else null
            }
        }
    }

    companion object {
        const val SLOT_DIBITS = 144
        const val DATA_VOICE_HEADER = 1
        const val DATA_TERMINATOR = 2
        const val DATA_CSBK = 3
        const val DATA_IDLE = 9
        const val HEADER_MASK = 0x96
        const val TERMINATOR_MASK = 0x99
        private const val RING = 1024
        private const val MASK = (RING - 1).toLong()
        private const val MAX_SYNC_ERRORS = 6
        private const val LOST_DIBITS = 4 * 4800L
    }
}
