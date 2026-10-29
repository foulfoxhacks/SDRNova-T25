package com.sdrnova.t25.p25.phase2

import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.SignalGenerator
import com.sdrnova.t25.receiver.ChannelReceiver
import com.sdrnova.t25.receiver.Mode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class Phase2Test {
    @Test
    fun `CRC-12 matches sdrtrunk's FACCH and SACCH syndrome tables`() {
        for ((length, table) in listOf(144 to intArrayOf(0x256, 0x12B, 0xCDE, 0x66F, 0xF7C), 168 to intArrayOf(0x47B, 0xE76, 0x73B, 0xFD6, 0x7EB))) {
            val zero = Crc12.compute(ByteArray(24), length)
            table.forEachIndexed { bit, expected ->
                val bits = ByteArray(24)
                Bits.set(bits, bit, 1)
                assertEquals(expected, Crc12.compute(bits, length) xor zero, "length $length bit $bit")
            }
        }
    }

    @Test
    fun `ISCH codewords decode with up to 7 bit errors`() {
        val random = Random(1)
        for (sequence in 0..2) for (channel in 1..2) {
            val info = ((channel - 1) shl 5) or (sequence shl 3)
            var word = Isch.encode(info)
            val flips = HashSet<Int>()
            while (flips.size < 7) flips += random.nextInt(40)
            flips.forEach { word = word xor (1L shl it) }
            val decoded = Isch.decode(word, channel)
            assertNotNull(decoded)
            assertEquals(sequence, decoded!!.sequence)
        }
    }

    @Test
    fun `FACCH survives symbol errors and checks its CRC`() {
        val pdu = Phase2Generator.pttPdu(group = 4321, source = 0x0ABCDE, algorithm = 0x84)
        val timeslot = ByteArray(40)
        SignallingCode.FACCH.encode(pdu, timeslot)
        // Corrupt four symbols; with nine punctured symbols that's still within the 14-symbol budget.
        for (symbol in intArrayOf(1, 10, 30, 40)) Bits.flip(timeslot, SignallingCode.FACCH.symbolBits[symbol * 6 + 2])
        val decoded = SignallingCode.FACCH.decode(timeslot)
        assertNotNull(decoded)
        val ptt = MacParser.parse(decoded!!, 144).single() as PushToTalk
        assertEquals(4321, ptt.group)
        assertEquals(0x0ABCDE, ptt.source)
        assertTrue(ptt.encrypted)
    }

    @Test
    fun `decodes a scrambled Phase 2 carrier from raw I-Q`() {
        val random = Random(77)
        val parameters = ScrambleParameters(wacn = 0xBEE00, system = 0x1C7, nac = 0x1C1)
        val scrambler = Scrambler(parameters)
        val frames = List(4) { ByteArray(9).also(random::nextBytes) }
        val dibits = ArrayList<Int>()
        repeat(400) { dibits += random.nextInt(4) }
        repeat(3) { superframe ->
            for (sequence in 0..2) {
                val first = superframe == 0 && sequence == 0
                val channel1 = listOf(
                    if (first) Phase2Generator.Facch(Phase2Generator.pttPdu(1234, 5678)) else Phase2Generator.Voice4(frames),
                    Phase2Generator.Voice4(frames),
                )
                val channel2 = List(2) { Phase2Generator.Facch(Phase2Generator.idlePdu()) }
                Phase2Generator.fragment(sequence, channel1, channel2, scrambler).forEach { dibits += it }
            }
        }
        repeat(200) { dibits += random.nextInt(4) }

        val rate = 960_000
        val offset = -150_000.0
        val iq = SignalGenerator.combine(
            listOf(Phase2Generator.modulate(dibits.toIntArray(), rate, offset + 400.0)), rate, snrDb = 20.0, amplitude = 0.25, random = random,
        )
        val events = ArrayList<Phase2Event>()
        val receiver = ChannelReceiver(rate, Mode.P25_PHASE2, object : ChannelReceiver.Listener {
            override fun onPhase2Event(event: Phase2Event) {
                events += event
            }
        })
        receiver.channelOffsetHz = offset
        receiver.setScrambleParameters(parameters)
        var position = 0
        while (position < iq.size) {
            val length = minOf(32_768, iq.size - position)
            receiver.process(iq.copyOfRange(position, position + length), length)
            position += length
        }
        val voice = events.filterIsInstance<Phase2Event.Voice>()
        val macs = events.filterIsInstance<Phase2Event.Mac>()
        val failures = events.filterIsInstance<Phase2Event.SignallingFailed>().size
        println("phase2: syncs=${events.count { it is Phase2Event.Sync }} voice=${voice.size} mac=${macs.size} failed=$failures afc=${receiver.afcHz}")

        assertTrue(voice.size >= 12, "decoded ${voice.size} voice bursts")
        assertTrue(voice.all { it.channel == 1 })
        assertArrayEquals(frames[2], voice.last().frames[2])
        assertTrue(macs.any { mac -> mac.messages.any { it is PushToTalk && it.group == 1234 && it.source == 5678 } })
        assertTrue(macs.filter { it.channel == 2 }.size >= 10, "channel 2 idle signalling")
    }
}
