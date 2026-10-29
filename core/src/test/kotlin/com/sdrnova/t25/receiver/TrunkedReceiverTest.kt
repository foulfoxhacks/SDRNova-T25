package com.sdrnova.t25.receiver

import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.p25.EncryptionSync
import com.sdrnova.t25.p25.GroupVoiceUser
import com.sdrnova.t25.p25.Ldu
import com.sdrnova.t25.p25.P25
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.p25.SignalGenerator
import com.sdrnova.t25.p25.TsbkBuilder
import com.sdrnova.t25.p25.phase2.Phase2Generator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class TrunkedReceiverTest {
    private val rate = 960_000
    private val centerHz = 851_000_000L
    private val controlHz = 850_850_000L
    private val trafficHz = 851_250_000L
    private val nac = 0x293

    /** Band 1: 850 MHz base, 12.5 kHz steps, so the control channel is 68 and the voice channel 100. */
    private fun bandPlan() = TsbkBuilder(opcode = 0x3D, lastBlock = false)
        .set(16, 4, 1).set(20, 9, 100).set(30, 8, 180).set(38, 10, 100).set(48, 32, 850_000_000L / 5)
        .build()

    private fun grant(group: Int, source: Int, channel: Int) = TsbkBuilder(opcode = 0x00)
        .set(24, 4, 1).set(28, 12, channel).set(40, 16, group).set(56, 24, source)
        .build()

    private fun groupVoiceUser(group: Int, source: Int): ByteArray {
        val bits = ByteArray(9)
        com.sdrnova.t25.p25.Bits.setField(bits, 1, 1, 1) // standard format
        com.sdrnova.t25.p25.Bits.setField(bits, 32, 16, group.toLong())
        com.sdrnova.t25.p25.Bits.setField(bits, 48, 24, source.toLong())
        return bits
    }

    private class FollowResult(
        val started: List<TrunkedReceiver.TrafficChannel>,
        val voice: List<Ldu>,
        val ended: List<String>,
        val log: List<String>,
        val frames: List<ByteArray>,
    )

    /** A control channel granting TG 1234 a voice channel, and that voice channel carrying the call. */
    private fun follow(policy: CallPolicy = CallPolicy.ALL): FollowResult {
        val random = Random(21)
        val control = ArrayList<Int>()
        repeat(60) { control += random.nextInt(4) }
        for (frame in 0 until 40) {
            val blocks = if (frame % 4 == 0) listOf(bandPlan(), grant(1234, 5678, 100)) else listOf(grant(1234, 5678, 100))
            SignalGenerator.tsduFrame(nac, blocks).forEach { control += it }
        }

        val frames = List(9) { ByteArray(18).also(random::nextBytes) }
        val traffic = ArrayList<Int>()
        repeat(600) { traffic += random.nextInt(4) }
        repeat(4) {
            val lc = Ldu.linkControlWords(groupVoiceUser(1234, 5678))
            val es = Ldu.encryptionWords(EncryptionSync(EncryptionSync.CLEAR, 0, 0))
            SignalGenerator.lduFrame(nac, P25.DUID_LDU1, Ldu.encode(P25.DUID_LDU1, frames, lc)).forEach { traffic += it }
            SignalGenerator.lduFrame(nac, P25.DUID_LDU2, Ldu.encode(P25.DUID_LDU2, frames, es)).forEach { traffic += it }
        }
        SignalGenerator.tduFrame(nac).forEach { traffic += it }
        repeat(300) { traffic += 0 }

        val iq = SignalGenerator.combine(
            listOf(
                SignalGenerator.carrier(SignalGenerator.deviation(control.toIntArray()), rate, (controlHz - centerHz) + 150.0),
                SignalGenerator.carrier(SignalGenerator.deviation(traffic.toIntArray()), rate, (trafficHz - centerHz) - 300.0),
            ),
            rate, snrDb = 18.0, amplitude = 0.25, random = random,
        )

        val started = ArrayList<TrunkedReceiver.TrafficChannel>()
        val ended = ArrayList<String>()
        val voice = ArrayList<Ldu>()
        val log = ArrayList<String>()
        val receiver = TrunkedReceiver(rate, centerHz, controlHz, Mode.P25_PHASE1, maxTrafficChannels = 2,
            listener = object : TrunkedReceiver.Listener {
                override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                    started += channel
                }

                override fun onTrafficVoice(channel: TrunkedReceiver.TrafficChannel, ldu: Ldu) {
                    voice += ldu
                }

                override fun onTrafficEnd(channel: TrunkedReceiver.TrafficChannel, reason: String) {
                    ended += reason
                }

                override fun onLog(text: String) {
                    log += text
                }

                override fun onControlEvent(event: P25Event) = Unit
            })
        receiver.policy = policy

        val floats = FloatArray(32_768)
        var offset = 0
        while (offset < iq.size) {
            val length = minOf(32_768, iq.size - offset)
            val count = SampleFormat.UNSIGNED_8.toFloat(iq.copyOfRange(offset, offset + length), length, floats)
            receiver.process(floats, count)
            offset += length
        }
        println("started=${started.size} voice=${voice.size} ended=$ended log=${log.take(4)}")
        return FollowResult(started, voice, ended, log, frames)
    }

    @Test
    fun `follows a voice grant onto a second channel in the same tuner window`() {
        val result = follow()
        val started = result.started
        val voice = result.voice
        val ended = result.ended
        val frames = result.frames
        assertEquals(1, started.size, "one traffic channel should open")
        assertEquals(trafficHz, started[0].frequencyHz)
        assertEquals(1234, started[0].talkgroup)
        assertTrue(voice.size >= 6, "decoded ${voice.size} of 8 LDUs")
        val lc = voice.first { it.duid == P25.DUID_LDU1 }.linkControl as GroupVoiceUser
        assertEquals(1234, lc.group)
        assertEquals(5678, lc.source)
        assertEquals(false, voice.first { it.duid == P25.DUID_LDU2 }.encryption!!.encrypted)
        assertArrayEquals(frames[4], voice.first().voiceFrames[4])
        assertEquals(listOf("end of transmission"), ended)
    }

    @Test
    fun `does not follow a locked-out talkgroup`() {
        val result = follow(object : CallPolicy {
            override fun follow(talkgroup: Int, unitToUnit: Boolean) = talkgroup != 1234
        })
        assertEquals(0, result.started.size)
        assertTrue(result.log.any { "locked out" in it }, "log: ${result.log}")
    }

    @Test
    fun `plays a conventional P25 call on the main channel`() {
        val random = Random(5)
        val frames = List(9) { ByteArray(18).also(random::nextBytes) }
        val dibits = ArrayList<Int>()
        repeat(600) { dibits += random.nextInt(4) }
        repeat(3) {
            val lc = Ldu.linkControlWords(groupVoiceUser(4321, 99))
            val es = Ldu.encryptionWords(EncryptionSync(EncryptionSync.CLEAR, 0, 0))
            SignalGenerator.lduFrame(nac, P25.DUID_LDU1, Ldu.encode(P25.DUID_LDU1, frames, lc)).forEach { dibits += it }
            SignalGenerator.lduFrame(nac, P25.DUID_LDU2, Ldu.encode(P25.DUID_LDU2, frames, es)).forEach { dibits += it }
        }
        SignalGenerator.tduFrame(nac).forEach { dibits += it }
        repeat(300) { dibits += 0 }
        val iq = SignalGenerator.combine(
            listOf(SignalGenerator.carrier(SignalGenerator.deviation(dibits.toIntArray()), rate, (controlHz - centerHz) + 200.0)),
            rate, snrDb = 18.0, amplitude = 0.25, random = random,
        )

        val started = ArrayList<TrunkedReceiver.TrafficChannel>()
        val ended = ArrayList<String>()
        var voice = 0
        var busyDuringCall = false
        lateinit var receiver: TrunkedReceiver
        receiver = TrunkedReceiver(rate, centerHz, controlHz, Mode.P25_PHASE1, maxTrafficChannels = 0,
            listener = object : TrunkedReceiver.Listener {
                override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                    started += channel
                }

                override fun onTrafficVoice(channel: TrunkedReceiver.TrafficChannel, ldu: Ldu) {
                    voice++
                    busyDuringCall = busyDuringCall || receiver.busy
                }

                override fun onTrafficEnd(channel: TrunkedReceiver.TrafficChannel, reason: String) {
                    ended += reason
                }
            })
        val floats = FloatArray(32_768)
        var offset = 0
        while (offset < iq.size) {
            val length = minOf(32_768, iq.size - offset)
            val count = SampleFormat.UNSIGNED_8.toFloat(iq.copyOfRange(offset, offset + length), length, floats)
            receiver.process(floats, count)
            offset += length
        }

        assertEquals(1, started.size)
        assertEquals(controlHz, started[0].frequencyHz)
        assertEquals(4321, started[0].talkgroup)
        assertTrue(voice >= 5, "decoded $voice of 6 LDUs")
        assertTrue(busyDuringCall)
        assertEquals(listOf("end of transmission"), ended)
        assertEquals(false, receiver.busy)
    }

    @Test
    fun `follows a Phase 2 grant onto its TDMA timeslot`() {
        val random = Random(31)
        // Band 2: 2-slot TDMA (channel type 3), 851 MHz base, 12.5 kHz carriers. Channel 41 is carrier 20, slot 2.
        val tdmaBand = TsbkBuilder(opcode = 0x33, lastBlock = false)
            .set(16, 4, 2).set(20, 4, 3).set(38, 10, 100).set(48, 32, 851_000_000L / 5).build()
        val network = TsbkBuilder(opcode = 0x3B).set(16, 8, 1).set(24, 20, 0xBEE00).set(44, 12, 0x1C7).build()
        val grant = TsbkBuilder(opcode = 0x00)
            .set(24, 4, 2).set(28, 12, 41).set(40, 16, 2222).set(56, 24, 7777).build()
        val control = ArrayList<Int>()
        repeat(60) { control += random.nextInt(4) }
        for (frame in 0 until 60) {
            val blocks = if (frame % 4 == 0) listOf(tdmaBand, network) else listOf(grant)
            SignalGenerator.tsduFrame(nac, blocks).forEach { control += it }
        }

        val scrambler = com.sdrnova.t25.p25.phase2.Scrambler(
            com.sdrnova.t25.p25.phase2.ScrambleParameters(0xBEE00, 0x1C7, nac),
        )

        val ambe = List(4) { ByteArray(9).also(random::nextBytes) }
        val idle = Phase2Generator.Facch(Phase2Generator.idlePdu())
        val traffic = ArrayList<Int>()
        repeat(600) { traffic += random.nextInt(4) }
        for (superframe in 0 until 5) {
            for (sequence in 0..2) {
                val channel2 = when {
                    superframe < 4 -> List(2) { Phase2Generator.Voice4(ambe) }
                    sequence == 0 -> listOf(Phase2Generator.Facch(Phase2Generator.endPttPdu(2222, 7777)), idle)
                    else -> listOf(idle, idle)
                }
                Phase2Generator.fragment(sequence, listOf(idle, idle), channel2, scrambler).forEach { traffic += it }
            }
        }

        val iq = SignalGenerator.combine(
            listOf(
                SignalGenerator.carrier(SignalGenerator.deviation(control.toIntArray()), rate, (controlHz - centerHz) + 150.0),
                Phase2Generator.modulate(traffic.toIntArray(), rate, (trafficHz - centerHz) + 200.0),
            ),
            rate, snrDb = 20.0, amplitude = 0.25, random = random,
        )

        val started = ArrayList<TrunkedReceiver.TrafficChannel>()
        val ended = ArrayList<String>()
        var ambeFrames = 0
        var lastFrame: ByteArray? = null
        val log = ArrayList<String>()
        val receiver = TrunkedReceiver(rate, centerHz, controlHz, Mode.P25_PHASE1, maxTrafficChannels = 2,
            listener = object : TrunkedReceiver.Listener {
                override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                    started += channel
                }

                override fun onTrafficAmbe(channel: TrunkedReceiver.TrafficChannel, frames: List<ByteArray>) {
                    ambeFrames += frames.size
                    lastFrame = frames[1]
                }

                override fun onTrafficEnd(channel: TrunkedReceiver.TrafficChannel, reason: String) {
                    ended += reason
                }

                override fun onLog(text: String) {
                    log += text
                }
            })
        val floats = FloatArray(32_768)
        var offset = 0
        while (offset < iq.size) {
            val length = minOf(32_768, iq.size - offset)
            val count = SampleFormat.UNSIGNED_8.toFloat(iq.copyOfRange(offset, offset + length), length, floats)
            receiver.process(floats, count)
            offset += length
        }
        println("phase2 follow: started=${started.map { "${it.frequencyHz}/TS${it.timeslot}" }} ambe=$ambeFrames ended=$ended")
        println(log.filter { "Phase 2" in it || "Waiting" in it }.take(3))

        assertEquals(1, started.size, "one Phase 2 call should open")
        assertEquals(trafficHz, started[0].frequencyHz)
        assertEquals(2, started[0].timeslot)
        assertEquals(2222, started[0].talkgroup)
        assertTrue(ambeFrames >= 40, "decoded $ambeFrames AMBE frames")
        assertArrayEquals(ambe[1], lastFrame)
        assertEquals(listOf("end of transmission"), ended)
    }

    @Test
    fun `link control and encryption sync survive word errors`() {
        val random = Random(3)
        val frames = List(9) { ByteArray(18).also(random::nextBytes) }
        val bits = groupVoiceUser(4321, 0x123456)
        val body = Ldu.encode(P25.DUID_LDU1, frames, Ldu.linkControlWords(bits))
        // Break three of the 24 Hamming words beyond single-bit repair; Reed-Solomon (24,12) fixes up to six.
        for (word in intArrayOf(2, 9, 17)) {
            for (bit in 0 until 3) com.sdrnova.t25.p25.Bits.flip(body, Ldu.WORD_STARTS[word] + bit)
        }
        val lc = Ldu(P25.DUID_LDU1, body).linkControl as GroupVoiceUser
        assertEquals(4321, lc.group)
        assertEquals(0x123456, lc.source)

        val sync = EncryptionSync(0x84, 0x1234, 0x0123456789ABCDEFL)
        val es = Ldu(P25.DUID_LDU2, Ldu.encode(P25.DUID_LDU2, frames, Ldu.encryptionWords(sync))).encryption
        assertEquals(sync, es)
        assertTrue(es!!.encrypted)
    }
}
