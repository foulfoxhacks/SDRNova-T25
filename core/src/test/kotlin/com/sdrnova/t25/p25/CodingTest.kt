package com.sdrnova.t25.p25

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class CodingTest {
    @Test
    fun `NID generator divides x^63 + 1`() {
        // A cyclic (63,16) code's generator must divide x^63 - 1, i.e. x^63 = 1 mod g(x).
        var remainder = 1L
        repeat(63) {
            remainder = remainder shl 1
            if ((remainder ushr 47) and 1L == 1L) remainder = remainder xor Bch63.GENERATOR
        }
        assertEquals(1L, remainder)
    }

    @Test
    fun `NID code has minimum distance 23`() {
        var minimum = Int.MAX_VALUE
        for (info in 1 until 65536) {
            minimum = minOf(minimum, Bch63.encode(info ushr 4, info and 0xF).countOneBits())
        }
        assertEquals(23, minimum)
    }

    @Test
    fun `NID decoder corrects 11 bit errors`() {
        val random = Random(7)
        repeat(200) {
            val nac = random.nextInt(4096)
            val duid = P25.VALID_DUIDS[random.nextInt(P25.VALID_DUIDS.size)]
            var word = Bch63.encode(nac, duid)
            val flipped = HashSet<Int>()
            while (flipped.size < 11) flipped += random.nextInt(63)
            flipped.forEach { word = word xor (1L shl it) }
            assertEquals(Bch63.Result(nac, duid, 11), Bch63.decode(word))
        }
    }

    @Test
    fun `CRC matches sdrtrunk's CCITT-80 syndrome table`() {
        // First entries of CRCP25.CCITT_80_CHECKSUMS. sdrtrunk stores each single-bit syndrome complemented (and so
        // accepts a residual of 0 or 0xFFFF), so complement them back before comparing.
        val table = intArrayOf(0x1BCB, 0x8DE5, 0xC6F2, 0x6B69, 0xB5B4, 0x52CA, 0x2175, 0x90BA, 0x404D)
        val zero = Crc16.ccitt(ByteArray(12), 80)
        for ((bit, entry) in table.withIndex()) {
            val bytes = ByteArray(12)
            bytes[bit / 8] = (1 shl (7 - bit % 8)).toByte()
            assertEquals(entry xor 0xFFFF, Crc16.ccitt(bytes, 80) xor zero, "bit $bit")
        }
    }

    @Test
    fun `trellis round trip is exact`() {
        val random = Random(13)
        repeat(50) {
            val bytes = ByteArray(12).also(random::nextBytes)
            val decoded = Trellis12.decode(Trellis12.encode(bytes))
            assertArrayEquals(bytes, decoded.bytes)
            assertEquals(0, decoded.bitErrors)
        }
    }

    @Test
    fun `trellis decoder corrects isolated symbol errors`() {
        val random = Random(11)
        repeat(200) {
            val bytes = ByteArray(12).also(random::nextBytes)
            val dibits = Trellis12.encode(bytes)
            // Two corrupted dibits, half a block apart in the deinterleaved (decoding) order.
            val symbol = random.nextInt(49)
            val first = 2 * transmitPosition(symbol) + random.nextInt(2)
            val second = 2 * transmitPosition((symbol + 24) % 49)
            dibits[first] = dibits[first] xor (1 + random.nextInt(3))
            dibits[second] = dibits[second] xor 1
            val decoded = Trellis12.decode(dibits)
            assertArrayEquals(bytes, decoded.bytes, "errors at $first and $second")
            assertTrue(decoded.bitErrors in 2..3)
        }
    }

    private fun transmitPosition(symbol: Int) = intArrayOf(0, 13, 25, 37)[symbol % 4] + symbol / 4

    @Test
    fun `group voice grant fields parse`() {
        val bytes = TsbkBuilder(opcode = 0x00)
            .set(16, 8, 0xC0)
            .set(24, 4, 1)
            .set(28, 12, 0x123)
            .set(40, 16, 4660)
            .set(56, 24, 0xABCDE)
            .build()
        val tsbk = Tsbk(bytes)
        assertTrue(tsbk.crcValid)
        val grant = TsbkParser.parse(tsbk) as GroupVoiceGrant
        assertEquals(Channel(1, 0x123), grant.channel)
        assertEquals(4660, grant.group)
        assertEquals(0xABCDE, grant.source)
        assertTrue(grant.options.emergency && grant.options.encrypted)
    }
}
