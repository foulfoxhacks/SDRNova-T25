package com.sdrnova.t25.dmr

import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.p25.Bits
import com.sdrnova.t25.p25.SignalGenerator
import com.sdrnova.t25.playlist.PlaylistImporter
import com.sdrnova.t25.playlist.PlaylistWriter
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.receiver.TrunkedReceiver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class DmrTest {
    private fun slot(timeslot: Int, sync: DmrSync?, payload196: BooleanArray?, slotType: Int?, frames: List<ByteArray>?): IntArray {
        val bits = ByteArray(36)
        Cach.write(bits, timeslot)
        if (payload196 != null) {
            for (b in 0 until 98) Bits.set(bits, 24 + b, if (payload196[b]) 1 else 0)
            for (b in 0 until 98) Bits.set(bits, 190 + b, if (payload196[98 + b]) 1 else 0)
            val type = Golay20.encode(slotType!!)
            for (b in 0 until 10) Bits.set(bits, 122 + b, (type ushr (19 - b)) and 1)
            for (b in 0 until 10) Bits.set(bits, 180 + b, (type ushr (9 - b)) and 1)
        }
        if (frames != null) {
            for (b in 0 until 72) Bits.set(bits, 24 + b, Bits.get(frames[0], b))
            for (b in 0 until 36) Bits.set(bits, 96 + b, Bits.get(frames[1], b))
            for (b in 0 until 36) Bits.set(bits, 180 + b, Bits.get(frames[1], 36 + b))
            for (b in 0 until 72) Bits.set(bits, 216 + b, Bits.get(frames[2], b))
        }
        if (sync != null) sync.dibits.forEachIndexed { k, dibit -> Bits.setField(bits, 132 + 2 * k, 2, dibit.toLong()) }
        else for (k in 0 until 24) Bits.setField(bits, 132 + 2 * k, 2, 0) // embedded signalling, ignored here
        return IntArray(144) { (Bits.get(bits, 2 * it) shl 1) or Bits.get(bits, 2 * it + 1) }
    }

    private fun linkControl(terminator: Boolean, target: Int, source: Int): BooleanArray {
        val lc = DmrLinkControl.encode(0, target, source)
        val parity = Rs129.parity(lc)
        val mask = if (terminator) DmrFramer.TERMINATOR_MASK else DmrFramer.HEADER_MASK
        for (i in 0 until 3) lc[9 + i] = (parity[i] xor mask).toByte()
        return Bptc196.encode(lc)
    }

    private fun csbkGrant(lcn: Int, target: Int, source: Int): BooleanArray {
        val block = ByteArray(12)
        Bits.setField(block, 0, 1, 1)
        Bits.setField(block, 2, 6, 0x31)
        Bits.setField(block, 16, 12, lcn.toLong())
        Bits.setField(block, 32, 24, target.toLong())
        Bits.setField(block, 56, 24, source.toLong())
        DmrCrc.fillCsbk(block)
        return Bptc196.encode(block)
    }

    private class TierThree(
        val calls: List<TrunkedReceiver.TrafficChannel>,
        val ambe: Int,
        val ended: List<String>,
        val log: List<String>,
    )

    /** A Tier III control channel granting TG 4242 on LCN 5 TS1, and that voice channel carrying the call. */
    private fun tierThree(channelMap: Map<Int, Long>): TierThree {
        val random = Random(12)
        val frames = List(3) { ByteArray(9).also(random::nextBytes) }
        val idle = Bptc196.encode(ByteArray(12))
        fun idleSlot(timeslot: Int) = slot(timeslot, DmrSync.BS_DATA, idle, (1 shl 4) or DmrFramer.DATA_IDLE, null).toList()

        val control = ArrayList<Int>()
        repeat(300) { control += random.nextInt(4) }
        repeat(8) { control += idleSlot(if (it % 2 == 0) 2 else 1) }
        repeat(40) {
            control += if (it % 2 == 0) idleSlot(2) else {
                slot(1, DmrSync.BS_DATA, csbkGrant(5, 4242, 777), (1 shl 4) or DmrFramer.DATA_CSBK, null).toList()
            }
        }

        val ts1 = ArrayList<List<Int>>()
        ts1 += slot(1, DmrSync.BS_DATA, linkControl(false, 4242, 1234567), (1 shl 4) or DmrFramer.DATA_VOICE_HEADER, null).toList()
        repeat(2) { for (letter in 0 until 6) ts1 += slot(1, if (letter == 0) DmrSync.BS_VOICE else null, null, null, frames).toList() }
        ts1 += slot(1, DmrSync.BS_DATA, linkControl(true, 4242, 1234567), (1 shl 4) or DmrFramer.DATA_TERMINATOR, null).toList()
        repeat(4) { ts1 += idleSlot(1) }
        val traffic = ArrayList<Int>()
        repeat(300) { traffic += random.nextInt(4) }
        // Idle bursts until well after the grant, so the voice channel's receiver is locked before the call starts.
        repeat(20) { traffic += idleSlot(if (it % 2 == 0) 2 else 1) }
        for (burst in ts1) {
            traffic += idleSlot(2)
            traffic += burst
        }

        val rate = 960_000
        val centerHz = 450_150_000L
        val controlHz = 450_000_000L
        val trafficHz = 450_250_000L
        val iq = SignalGenerator.combine(
            listOf(
                SignalGenerator.carrier(SignalGenerator.deviation(control.toIntArray()), rate, (controlHz - centerHz) + 200.0),
                SignalGenerator.carrier(SignalGenerator.deviation(traffic.toIntArray()), rate, (trafficHz - centerHz) - 250.0),
            ),
            rate, snrDb = 20.0, amplitude = 0.25, random = random,
        )

        val calls = ArrayList<TrunkedReceiver.TrafficChannel>()
        val ended = ArrayList<String>()
        val log = ArrayList<String>()
        var ambe = 0
        val receiver = TrunkedReceiver(rate, centerHz, controlHz, Mode.DMR, maxTrafficChannels = 2,
            listener = object : TrunkedReceiver.Listener {
                override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                    calls += channel
                }

                override fun onTrafficAmbe(channel: TrunkedReceiver.TrafficChannel, frames: List<ByteArray>) {
                    ambe += frames.size
                }

                override fun onTrafficEnd(channel: TrunkedReceiver.TrafficChannel, reason: String) {
                    ended += reason
                }

                override fun onLog(text: String) {
                    log += text
                }
            })
        receiver.dmrChannelMap = channelMap
        val floats = FloatArray(32_768)
        var offset = 0
        while (offset < iq.size) {
            val length = minOf(32_768, iq.size - offset)
            val count = SampleFormat.UNSIGNED_8.toFloat(iq.copyOfRange(offset, offset + length), length, floats)
            receiver.process(floats, count)
            offset += length
        }
        println("tier III: calls=${calls.map { "${it.frequencyHz} TS${it.timeslot} TG${it.talkgroup} src ${it.source}" }} ambe=$ambe ended=$ended")
        return TierThree(calls, ambe, ended, log)
    }

    @Test
    fun `follows a Tier III grant onto its voice channel`() {
        val result = tierThree(mapOf(5 to 450_250_000L))
        assertEquals(1, result.calls.size)
        val call = result.calls[0]
        assertEquals(450_250_000L, call.frequencyHz)
        assertEquals(1, call.timeslot)
        assertEquals(4242, call.talkgroup)
        assertEquals(1234567, call.source, "the voice header's source replaces the grant's")
        assertEquals(36, result.ambe)
        assertEquals(listOf("end of transmission"), result.ended)
    }

    @Test
    fun `logs grants it can't follow without a channel map`() {
        val result = tierThree(emptyMap())
        assertTrue(result.calls.isEmpty())
        assertTrue(result.log.any { "LCN 5 isn't in the DMR channel map" in it }, "log: ${result.log.take(5)}")
    }

    @Test
    fun `reads channel maps typed in or from sdrtrunk playlists`() {
        assertEquals(
            mapOf(1 to 451_112_500L, 2 to 451_612_500L, 3 to 452_137_500L),
            DmrChannelMap.parse("1, 451.1125\n2 = 451.6125 MHz\n; comment\n3 452137500\nbad line"),
        )
        val xml = """
            <playlist version="4">
              <channel system="Biz" name="Tier III" enabled="true">
                <source_configuration type="sourceConfigTuner" frequency="451000000"/>
                <decode_configuration type="decodeConfigDMR" traffic_channel_pool_size="2">
                  <timeslot lsn="1" downlink="451112500" uplink="456112500"/>
                  <timeslot lsn="2" downlink="451612500" uplink="456612500"/>
                </decode_configuration>
              </channel>
            </playlist>
        """.trimIndent()
        val playlist = PlaylistImporter.parse(xml.byteInputStream())
        val channel = playlist.channels.single()
        assertEquals(mapOf(1 to 451_112_500L, 2 to 451_612_500L), channel.dmrChannels)
        val again = PlaylistImporter.parse(PlaylistWriter.write(playlist).byteInputStream())
        assertEquals(channel.dmrChannels, again.channels.single().dmrChannels)
    }

    @Test
    fun `BPTC corrects one error per row and RS(12,9) checks link control`() {
        val random = Random(4)
        val data = ByteArray(12).also(random::nextBytes)
        val coded = Bptc196.encode(data)
        for (position in intArrayOf(3, 50, 120, 190)) coded[position] = !coded[position]
        val decoded = Bptc196.decode(coded)
        assertNotNull(decoded)
        assertArrayEquals(data, decoded)

        val lc = DmrLinkControl.encode(0, 9999, 1234567)
        val parity = Rs129.parity(lc)
        for (i in 0 until 3) lc[9 + i] = (parity[i] xor 0x96).toByte()
        assertTrue(Rs129.check(lc, 0x96))
        lc[2] = (lc[2].toInt() xor 0x10).toByte()
        assertTrue(!Rs129.check(lc, 0x96))
        assertEquals(0x5A, Golay20.decode(Golay20.encode(0x5A) xor 0b10010000100000000000))
    }

    @Test
    fun `decodes a DMR repeater call from raw I-Q`() = decodesRepeaterCall(Mode.DMR)

    @Test
    fun `auto mode recognises and decodes the same DMR call`() = decodesRepeaterCall(Mode.AUTO)

    private fun decodesRepeaterCall(mode: Mode) {
        val random = Random(8)
        val frames = List(3) { ByteArray(9).also(random::nextBytes) }
        val idle = Bptc196.encode(ByteArray(12))
        val slots = ArrayList<IntArray>()
        // TS2 carries idle bursts and one Tier III grant; TS1 carries a call: header, two voice superframes, terminator.
        val ts1 = ArrayList<IntArray>()
        ts1 += slot(1, DmrSync.BS_DATA, linkControl(false, 9999, 1234567), (1 shl 4) or DmrFramer.DATA_VOICE_HEADER, null)
        repeat(2) { for (letter in 0 until 6) ts1 += slot(1, if (letter == 0) DmrSync.BS_VOICE else null, null, null, frames) }
        ts1 += slot(1, DmrSync.BS_DATA, linkControl(true, 9999, 1234567), (1 shl 4) or DmrFramer.DATA_TERMINATOR, null)
        repeat(4) { ts1 += slot(1, DmrSync.BS_DATA, idle, (1 shl 4) or DmrFramer.DATA_IDLE, null) }
        for (index in ts1.indices) {
            slots += if (index == 2) slot(2, DmrSync.BS_DATA, csbkGrant(5, 4242, 777), (1 shl 4) or DmrFramer.DATA_CSBK, null)
            else slot(2, DmrSync.BS_DATA, idle, (1 shl 4) or DmrFramer.DATA_IDLE, null)
            slots += ts1[index]
        }
        val dibits = ArrayList<Int>()
        repeat(300) { dibits += random.nextInt(4) }
        // A few idle slots first so the receiver has locked before the call starts.
        repeat(6) { dibits += slot(if (it % 2 == 0) 2 else 1, DmrSync.BS_DATA, idle, (1 shl 4) or DmrFramer.DATA_IDLE, null).toList() }
        slots.forEach { dibits += it.toList() }

        val rate = 960_000
        val centerHz = 450_150_000L
        val channelHz = 450_000_000L
        val iq = SignalGenerator.modulate(dibits.toIntArray(), rate, (channelHz - centerHz) + 300.0, snrDb = 20.0, random = random)

        val events = ArrayList<DmrEvent>()
        val calls = ArrayList<TrunkedReceiver.TrafficChannel>()
        val ended = ArrayList<String>()
        val log = ArrayList<String>()
        var ambe = 0
        var lastFrame: ByteArray? = null
        val receiver = TrunkedReceiver(rate, centerHz, channelHz, mode, maxTrafficChannels = 0,
            listener = object : TrunkedReceiver.Listener {
                override fun onDmrEvent(event: DmrEvent) {
                    events += event
                }

                override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                    calls += channel
                }

                override fun onTrafficAmbe(channel: TrunkedReceiver.TrafficChannel, frames: List<ByteArray>) {
                    ambe += frames.size
                    lastFrame = frames[2]
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
        val counts = events.groupingBy { it::class.simpleName }.eachCount()
        println("dmr: $counts calls=${calls.map { "TS${it.timeslot} TG${it.talkgroup} src ${it.source}" }} ambe=$ambe ended=$ended")
        println(log)

        assertEquals(1, calls.size)
        assertEquals(1, calls[0].timeslot)
        assertEquals(9999, calls[0].talkgroup)
        assertEquals(1234567, calls[0].source)
        assertEquals(36, ambe, "two superframes of six bursts, three frames each")
        assertArrayEquals(frames[2], lastFrame)
        assertEquals(listOf("end of transmission"), ended)
        assertTrue(log.any { "talkgroup voice grant: TG 4242 from 777 on LCN 5" in it }, "CSBK grant logged")
        assertTrue(events.count { it is DmrEvent.Idle && it.timeslot == 2 } >= 10)
        assertEquals(Mode.DMR, receiver.control.detectedMode)
    }
}
