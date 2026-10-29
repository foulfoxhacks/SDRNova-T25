package com.sdrnova.t25.p25.phase2

import com.sdrnova.t25.p25.Bits

sealed interface Phase2Event {
    /** Logical channel (1 or 2) the event belongs to; 0 for carrier-wide events. */
    val channel: Int

    data class Sync(val bitErrors: Int, val frequencyErrorHz: Double) : Phase2Event {
        override val channel = 0
    }

    data class Mac(override val channel: Int, val messages: List<MacMessage>) : Phase2Event

    /** Raw 72-bit AMBE+2 frames (MSB-first), four from a 4V burst or two from a 2V burst. */
    data class Voice(override val channel: Int, val frames: List<ByteArray>) : Phase2Event

    data class SignallingFailed(override val channel: Int) : Phase2Event
}

/**
 * Cuts the dibit stream into 720-dibit superframe fragments, modeled on sdrtrunk's P25P2SuperFrameDetector. Each
 * fragment holds two S-ISCH syncs 180 dibits apart (at dibits 360 and 540); once a pair is found the framer follows
 * the 720-dibit grid, re-checking the syncs each time and re-aligning by up to two dibits if timing slipped.
 */
class P25P2Framer(private val listener: (Phase2Event) -> Unit) : DqpskDemodulator.Sink {
    private val ring = IntArray(RING)
    private var count = 0L
    private var fragmentEnd = -1L
    private var misses = 0

    var scrambler: Scrambler? = null

    override fun onSync(bitErrors: Int, frequencyErrorHz: Double) {
        listener(Phase2Event.Sync(bitErrors, frequencyErrorHz))
    }

    override fun onDibit(dibit: Int) {
        ring[(count and MASK).toInt()] = dibit
        count++
        if (fragmentEnd < 0) {
            // Second sync just finished and the first one sits 180 dibits earlier: we're 560 dibits into a fragment.
            if (count >= 560 && syncErrors(count - 20) <= SEARCH_ERRORS && syncErrors(count - 200) <= SEARCH_ERRORS) {
                fragmentEnd = count - 560 + 720
                misses = 0
            }
            return
        }
        // Check two dibits late so a fragment that slipped forward by up to two dibits is complete.
        if (count == fragmentEnd + SLIP) {
            val start = fragmentEnd - 720
            val shift = SHIFTS.firstOrNull { delta ->
                syncErrors(start + 360 + delta) <= TRACK_ERRORS && syncErrors(start + 540 + delta) <= TRACK_ERRORS
            }
            if (shift == null) {
                if (++misses >= 2) fragmentEnd = -1 else fragmentEnd += 720
                return
            }
            misses = 0
            decode(start + shift)
            fragmentEnd = start + shift + 1440
        }
    }

    private fun syncErrors(start: Long): Int {
        var errors = 0
        for (k in 0 until 20) {
            errors += (ring[((start + k) and MASK).toInt()] xor DqpskDemodulator.SYNC_DIBITS[k]).countOneBits()
        }
        return errors
    }

    private fun decode(start: Long) {
        val bits = ByteArray(Phase2.FRAGMENT_BITS / 8)
        for (k in 0 until 720) {
            val dibit = ring[((start + k) and MASK).toInt()]
            Bits.set(bits, 2 * k, dibit ushr 1)
            Bits.set(bits, 2 * k + 1, dibit and 1)
        }
        decodeFragment(bits).forEach(listener)
    }

    /** Decodes one 1440-bit fragment. Exposed for tests. */
    fun decodeFragment(bits: ByteArray): List<Phase2Event> {
        val isch = Isch.decode(Bits.field(bits, 0, 40), 1) ?: Isch.decode(Bits.field(bits, 360, 40), 2)
        val sequence = isch?.sequence ?: 0
        val final = sequence == 2
        val events = ArrayList<Phase2Event>()
        for (index in 0 until 4) {
            val channel = when (index) {
                0 -> 1
                1 -> 2
                2 -> if (final) 2 else 1
                else -> if (final) 1 else 2
            }
            val timeslot = Bits.slice(bits, Phase2.TIMESLOT_STARTS[index], Phase2.TIMESLOT_BITS)
            val type = TimeslotType.decode(timeslot) ?: continue
            if (type.scrambled) {
                val segment = scrambler?.segment(sequence * 4 + index)
                if (segment != null) for (b in timeslot.indices) timeslot[b] = (timeslot[b].toInt() xor segment[b].toInt()).toByte()
            }
            events += when (type) {
                TimeslotType.VOICE_4 -> Phase2Event.Voice(channel, VOICE_4_STARTS.map { Bits.slice(timeslot, it, 72) })
                TimeslotType.VOICE_2 -> Phase2Event.Voice(channel, VOICE_2_STARTS.map { Bits.slice(timeslot, it, 72) })
                TimeslotType.FACCH, TimeslotType.FACCH_SCRAMBLED -> signalling(channel, SignallingCode.FACCH, timeslot)
                TimeslotType.SACCH, TimeslotType.SACCH_SCRAMBLED -> signalling(channel, SignallingCode.SACCH, timeslot)
                else -> continue
            }
        }
        return events
    }

    private fun signalling(channel: Int, code: SignallingCode, timeslot: ByteArray): Phase2Event {
        val pdu = code.decode(timeslot) ?: return Phase2Event.SignallingFailed(channel)
        return Phase2Event.Mac(channel, MacParser.parse(pdu, code.dataBits))
    }

    companion object {
        val VOICE_4_STARTS = intArrayOf(2, 76, 172, 246)
        val VOICE_2_STARTS = intArrayOf(2, 76)
        private const val RING = 2048
        private const val MASK = (RING - 1).toLong()
        private const val SEARCH_ERRORS = 4
        private const val TRACK_ERRORS = 7
        private const val SLIP = 2
        private val SHIFTS = intArrayOf(0, -1, 1, -2, 2)
    }
}
