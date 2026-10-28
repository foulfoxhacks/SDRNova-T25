package com.sdrnova.t25.receiver

import com.sdrnova.t25.p25.GroupVoiceGrant
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.p25.SignalGenerator
import com.sdrnova.t25.p25.TsbkBuilder
import com.sdrnova.t25.p25.TsbkMessage
import com.sdrnova.t25.trunking.TrunkingTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ReceiverTest {
    private val inputRate = 960_000
    private val channelOffset = -150_000.0

    /** Band 1: 851.00625 MHz base, 6.25 kHz steps (IDEN_UP). */
    private fun bandPlan(): ByteArray = TsbkBuilder(opcode = 0x3D, lastBlock = false)
        .set(16, 4, 1)
        .set(20, 9, 100)
        .set(29, 1, 0)
        .set(30, 8, 180)
        .set(38, 10, 50)
        .set(48, 32, 851_006_250L / 5)
        .build()

    private fun grant(group: Int, channel: Int, source: Int, last: Boolean = true): ByteArray =
        TsbkBuilder(opcode = 0x00, lastBlock = last)
            .set(24, 4, 1)
            .set(28, 12, channel)
            .set(40, 16, group)
            .set(56, 24, source)
            .build()

    private fun rfss(last: Boolean = true): ByteArray = TsbkBuilder(opcode = 0x3A, lastBlock = last)
        .set(16, 8, 0x01).set(28, 12, 0x3AB).set(40, 8, 2).set(48, 8, 7).set(56, 4, 1).set(60, 12, 8)
        .build()

    private fun run(bytes: ByteArray, mode: Mode = Mode.P25_PHASE1, chunk: Int = 32_768): Result {
        val result = Result()
        val receiver = ChannelReceiver(inputRate, mode, object : ChannelReceiver.Listener {
            override fun onP25Event(event: P25Event) {
                when (event) {
                    is P25Event.Sync -> result.syncs++
                    is P25Event.Message -> result.messages += event.message
                    is P25Event.CrcFailed -> result.crcFailures++
                    else -> Unit
                }
            }

            override fun onAudio(samples: ShortArray, count: Int) {
                for (k in 0 until count) result.audio += samples[k]
            }
        })
        receiver.channelOffsetHz = channelOffset
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunk, bytes.size - offset)
            receiver.process(bytes.copyOfRange(offset, offset + length), length)
            offset += length
        }
        result.afcHz = receiver.afcHz
        return result
    }

    class Result {
        var syncs = 0
        var crcFailures = 0
        val messages = ArrayList<TsbkMessage>()
        val audio = ArrayList<Short>()
        var afcHz = 0.0
    }

    private fun controlChannel(frames: Int, random: Random): Pair<IntArray, Int> {
        val dibits = ArrayList<Int>()
        repeat(120) { dibits += random.nextInt(4) }
        var tsbks = 0
        for (frame in 0 until frames) {
            val blocks = when (frame % 3) {
                0 -> listOf(bandPlan(), rfss())
                1 -> listOf(grant(1000 + frame, 2 * frame, 0x10000 + frame))
                else -> listOf(
                    grant(2000 + frame, frame, 0x20000 + frame, last = false),
                    rfss(last = false),
                    grant(3000 + frame, frame + 1, 0x30000 + frame),
                )
            }
            tsbks += blocks.size
            SignalGenerator.tsduFrame(0x293, blocks).forEach { dibits += it }
        }
        return dibits.toIntArray() to tsbks
    }

    @Test
    fun `decodes a P25 control channel from raw I-Q`() {
        val random = Random(42)
        val (dibits, sent) = controlChannel(frames = 30, random = random)
        val iq = SignalGenerator.modulate(dibits, inputRate, channelOffset + 250.0, snrDb = 20.0, random = random)
        val result = run(iq)
        println("syncs=${result.syncs} messages=${result.messages.size}/$sent crcFailures=${result.crcFailures} afc=${result.afcHz}")
        assertTrue(result.messages.size >= sent - 3, "decoded ${result.messages.size} of $sent TSBKs")
        assertEquals(0, result.crcFailures)
        assertTrue(result.afcHz in 150.0..350.0, "AFC should converge on the 250 Hz offset, got ${result.afcHz}")

        val tracker = TrunkingTracker()
        result.messages.forEach { tracker.onMessage(it) }
        val grant = result.messages.filterIsInstance<GroupVoiceGrant>().first { it.group == 1004 }
        assertEquals(851_006_250L + 8 * 6_250L, tracker.frequencyOf(grant.channel))
        assertEquals(7, tracker.site.site)
    }

    @Test
    fun `decodes at lower SNR`() {
        val random = Random(5)
        val (dibits, sent) = controlChannel(frames = 30, random = random)
        val iq = SignalGenerator.modulate(dibits, inputRate, channelOffset - 600.0, snrDb = 12.0, random = random)
        val result = run(iq)
        println("12 dB: syncs=${result.syncs} messages=${result.messages.size}/$sent crcFailures=${result.crcFailures}")
        assertTrue(result.messages.size >= sent * 0.8, "decoded ${result.messages.size} of $sent TSBKs at 12 dB")
    }

    @Test
    fun `decodes an AMBTC group voice grant`() {
        val header = TsbkBuilder(opcode = 0)
            .set(0, 8, 0x57) // A/N 0, outbound, format 0x17 (AMBTC)
            .set(10, 6, 61) // trunking control SAP
            .set(24, 24, 0x0BEEF1) // source
            .set(48, 8, 0x81) // FMF, one block to follow
            .set(58, 6, 0x00) // GRP_V_CH_GRANT
            .set(64, 8, 0x40) // encrypted
            .build()
        val block = ByteArray(12)
        com.sdrnova.t25.p25.Bits.setField(block, 16, 4, 2)
        com.sdrnova.t25.p25.Bits.setField(block, 20, 12, 0x155)
        com.sdrnova.t25.p25.Bits.setField(block, 32, 4, 2)
        com.sdrnova.t25.p25.Bits.setField(block, 36, 12, 0x255)
        com.sdrnova.t25.p25.Bits.setField(block, 48, 16, 2468)

        val random = Random(17)
        val dibits = ArrayList<Int>()
        repeat(120) { dibits += random.nextInt(4) }
        repeat(6) {
            SignalGenerator.pduFrame(0x293, listOf(header, block)).forEach { dibits += it }
            SignalGenerator.tsduFrame(0x293, listOf(rfss())).forEach { dibits += it }
        }
        val iq = SignalGenerator.modulate(dibits.toIntArray(), inputRate, channelOffset, snrDb = 18.0, random = random)
        val grants = run(iq).messages.filterIsInstance<GroupVoiceGrant>()
        assertTrue(grants.size >= 4, "decoded ${grants.size} of 6 AMBTC grants")
        val grant = grants.first()
        assertEquals(2468, grant.group)
        assertEquals(0x0BEEF1, grant.source)
        assertEquals(com.sdrnova.t25.p25.Channel(2, 0x155), grant.channel)
        assertTrue(grant.options.encrypted)
    }

    @Test
    fun `noise alone produces no messages`() {
        val result = run(SignalGenerator.noise(inputRate * 2, Random(3), sigma = 0.2))
        assertEquals(0, result.messages.size)
    }

    @Test
    fun `NBFM demodulates a 1 kHz tone`() {
        val random = Random(9)
        val deviation = DoubleArray(48_000) { 2_500.0 * sin(2 * PI * 1_000.0 * it / 48_000) }
        val iq = SignalGenerator.toIq(deviation, inputRate, channelOffset, snrDb = 25.0, random = random)
        val audio = run(iq, Mode.NBFM).audio.drop(4_800).map { it.toDouble() }
        val rms = sqrt(audio.sumOf { it * it } / audio.size)
        assertTrue(rms > 3_000, "audio RMS $rms")
        assertTrue(goertzel(audio, 1_000.0) > 20 * goertzel(audio, 2_000.0), "1 kHz should dominate")
    }

    @Test
    fun `NBFM squelch stays closed on noise`() {
        val audio = run(SignalGenerator.noise(inputRate / 2, Random(4), sigma = 0.2), Mode.NBFM).audio
        assertTrue(audio.drop(4_800).all { it.toInt() == 0 })
    }

    private fun goertzel(samples: List<Double>, hz: Double): Double {
        val coefficient = 2 * cos(2 * PI * hz / 48_000)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s = x + coefficient * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }
}
