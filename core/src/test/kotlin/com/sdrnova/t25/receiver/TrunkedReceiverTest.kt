package com.sdrnova.t25.receiver

import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.p25.EncryptionSync
import com.sdrnova.t25.p25.GroupVoiceUser
import com.sdrnova.t25.p25.Ldu
import com.sdrnova.t25.p25.P25
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.p25.SignalGenerator
import com.sdrnova.t25.p25.TsbkBuilder
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

    @Test
    fun `follows a voice grant onto a second channel in the same tuner window`() {
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

        val floats = FloatArray(32_768)
        var offset = 0
        while (offset < iq.size) {
            val length = minOf(32_768, iq.size - offset)
            val count = SampleFormat.UNSIGNED_8.toFloat(iq.copyOfRange(offset, offset + length), length, floats)
            receiver.process(floats, count)
            offset += length
        }
        println("started=${started.size} voice=${voice.size} ended=$ended log=${log.take(4)}")

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
