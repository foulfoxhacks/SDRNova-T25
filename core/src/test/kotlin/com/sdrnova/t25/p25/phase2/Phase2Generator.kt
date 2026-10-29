package com.sdrnova.t25.p25.phase2

import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.P25
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Builds P25 Phase 2 TDMA carriers for tests: superframe fragments, then H-DQPSK with RRC shaping. */
object Phase2Generator {
    private const val BASE_RATE = 48_000
    private const val SPS = 8

    fun pttPdu(group: Int, source: Int, algorithm: Int = 0x80): ByteArray {
        val pdu = ByteArray(20)
        Bits.setField(pdu, 0, 3, MacParser.PTT.toLong())
        Bits.setField(pdu, 80, 8, algorithm.toLong())
        Bits.setField(pdu, 104, 24, source.toLong())
        Bits.setField(pdu, 128, 16, group.toLong())
        return pdu
    }

    fun endPttPdu(group: Int, source: Int): ByteArray {
        val pdu = ByteArray(20)
        Bits.setField(pdu, 0, 3, MacParser.END_PTT.toLong())
        Bits.setField(pdu, 104, 24, source.toLong())
        Bits.setField(pdu, 128, 16, group.toLong())
        return pdu
    }

    fun idlePdu(): ByteArray = ByteArray(20).also { Bits.setField(it, 0, 3, MacParser.IDLE.toLong()) }

    sealed interface Slot
    data class Voice4(val frames: List<ByteArray>) : Slot
    data class Facch(val pdu: ByteArray) : Slot

    /** A 320-bit timeslot: payload, scrambled with segment [segment] if a scrambler is given, then the DUID. */
    fun timeslot(slot: Slot, scrambler: Scrambler?, segment: Int): ByteArray {
        val bits = ByteArray(40)
        val type = when (slot) {
            is Voice4 -> {
                slot.frames.forEachIndexed { index, frame ->
                    for (b in 0 until 72) Bits.set(bits, P25P2Framer.VOICE_4_STARTS[index] + b, Bits.get(frame, b))
                }
                TimeslotType.VOICE_4
            }
            is Facch -> {
                SignallingCode.FACCH.encode(slot.pdu, bits)
                if (scrambler != null) TimeslotType.FACCH_SCRAMBLED else TimeslotType.FACCH
            }
        }
        if (type.scrambled && scrambler != null) {
            val sequence = scrambler.segment(segment)
            for (b in bits.indices) bits[b] = (bits[b].toInt() xor sequence[b].toInt()).toByte()
        }
        TimeslotType.write(bits, type.encoded)
        return bits
    }

    /**
     * One 720-dibit fragment. [channel1] and [channel2] give each logical channel's two timeslots in this fragment;
     * the third fragment of a superframe swaps the order of the last two timeslots, as the decoder expects.
     */
    fun fragment(sequence: Int, channel1: List<Slot>, channel2: List<Slot>, scrambler: Scrambler?): IntArray {
        val bits = ByteArray(Phase2.FRAGMENT_BITS / 8)
        for ((position, channel) in listOf(0 to 1, 360 to 2)) {
            val info = ((channel - 1) shl 5) or (sequence shl 3)
            Bits.setField(bits, position, 40, Isch.encode(info))
        }
        for (position in intArrayOf(720, 1080)) {
            DqpskDemodulator.SYNC_DIBITS.forEachIndexed { k, dibit -> Bits.setField(bits, position + 2 * k, 2, dibit.toLong()) }
        }
        val final = sequence == 2
        val order = listOf(channel1[0], channel2[0], if (final) channel2[1] else channel1[1], if (final) channel1[1] else channel2[1])
        order.forEachIndexed { index, slot ->
            val timeslot = timeslot(slot, scrambler, sequence * 4 + index)
            for (b in 0 until Phase2.TIMESLOT_BITS) Bits.set(bits, Phase2.TIMESLOT_STARTS[index] + b, Bits.get(timeslot, b))
        }
        return IntArray(720) { (Bits.get(bits, 2 * it) shl 1) or Bits.get(bits, 2 * it + 1) }
    }

    /** H-DQPSK: each dibit turns the carrier phase by ±π/4 or ±3π/4, pulse-shaped with RRC (alpha 0.2). */
    fun modulate(dibits: IntArray, outputRate: Int, carrierHz: Double, amplitude: Double = 0.25): FloatArray {
        val rrc = DqpskDemodulator.rootRaisedCosine(SPS.toDouble(), 0.2, 8)
        val gain = 1.0 / rrc.sum() * SPS
        val baseI = DoubleArray(dibits.size * SPS + rrc.size)
        val baseQ = DoubleArray(dibits.size * SPS + rrc.size)
        var phase = 0.0
        for ((k, dibit) in dibits.withIndex()) {
            phase += P25.idealPhase(dibit)
            for (t in rrc.indices) {
                baseI[k * SPS + t] += cos(phase) * rrc[t] * gain
                baseQ[k * SPS + t] += sin(phase) * rrc[t] * gain
            }
        }
        val ratio = outputRate / BASE_RATE
        val count = (baseI.size - 1) * ratio
        val out = FloatArray(count * 2)
        for (n in 0 until count) {
            val t = n.toDouble() / ratio
            val whole = floor(t).toInt()
            val fraction = t - whole
            val i = baseI[whole] + (baseI[whole + 1] - baseI[whole]) * fraction
            val q = baseQ[whole] + (baseQ[whole + 1] - baseQ[whole]) * fraction
            val angle = 2 * PI * carrierHz * n / outputRate
            out[2 * n] = (amplitude * (i * cos(angle) - q * sin(angle))).toFloat()
            out[2 * n + 1] = (amplitude * (i * sin(angle) + q * cos(angle))).toFloat()
        }
        return out
    }
}
