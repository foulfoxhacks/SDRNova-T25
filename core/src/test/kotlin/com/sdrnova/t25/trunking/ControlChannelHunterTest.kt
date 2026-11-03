package com.sdrnova.t25.trunking

import com.sdrnova.t25.p25.Tsbk
import com.sdrnova.t25.p25.TsbkBuilder
import com.sdrnova.t25.p25.TsbkParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ControlChannelHunterTest {
    @Test
    fun `stays while there's sync and moves on when it's quiet`() {
        val hunter = ControlChannelHunter(851_000_000L, quietMs = 4_000, tryMs = 3_000)
        hunter.arrived(0)
        assertNull(hunter.update(10_000), "nothing else to try yet")
        hunter.learn(listOf(851_250_000L, 851_000_000L, 851_500_000L))
        assertEquals(listOf(851_000_000L, 851_250_000L, 851_500_000L), hunter.known)
        hunter.sync(1_000)
        assertNull(hunter.update(4_999))
        assertEquals(851_250_000L, hunter.update(5_000))
        hunter.arrived(5_000)
        assertNull(hunter.update(7_999), "a channel just tried gets its try time")
        assertEquals(851_500_000L, hunter.update(8_000))
        hunter.arrived(8_000)
        hunter.sync(8_200)
        assertNull(hunter.update(12_000))
        assertEquals(851_000_000L, hunter.update(12_200), "wraps back to the first")
    }

    @Test
    fun `tracker lists the site's control channels from its broadcasts`() {
        // Band 1: 850 MHz base, 12.5 kHz steps.
        val bandPlan = TsbkBuilder(opcode = 0x3D, lastBlock = false)
            .set(16, 4, 1).set(20, 9, 100).set(30, 8, 180).set(38, 10, 100).set(48, 32, 850_000_000L / 5)
            .build()
        val rfss = TsbkBuilder(opcode = 0x3A)
            .set(16, 8, 0x01).set(28, 12, 0x3AB).set(40, 8, 2).set(48, 8, 7).set(56, 4, 1).set(60, 12, 8)
            .build()
        val secondary = TsbkBuilder(opcode = 0x39)
            .set(16, 8, 2).set(24, 8, 7).set(32, 4, 1).set(36, 12, 20).set(56, 4, 1).set(60, 12, 30)
            .build()
        val tracker = TrunkingTracker()
        for (bytes in listOf(bandPlan, rfss, secondary)) tracker.onMessage(TsbkParser.parse(Tsbk(bytes)))
        assertEquals(listOf(850_100_000L, 850_250_000L, 850_375_000L), tracker.controlChannels())
    }
}
