package com.sdrnova.t25.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScannerTest {
    @Test
    fun `moves on after the dwell when quiet`() {
        val scanner = Scanner(listOf("a", "b", "c"), dwellMs = 400)
        scanner.arrived(0)
        assertNull(scanner.update(399, busy = false, sync = false))
        assertEquals("b", scanner.update(400, busy = false, sync = false))
        scanner.arrived(400)
        assertEquals("c", scanner.update(800, busy = false, sync = false))
        scanner.arrived(800)
        assertEquals("a", scanner.update(1200, busy = false, sync = false))
    }

    @Test
    fun `stays through a call and its hang time`() {
        val scanner = Scanner(listOf("a", "b"), dwellMs = 400, hangMs = 2_000)
        scanner.arrived(0)
        assertNull(scanner.update(200, busy = true, sync = true))
        assertNull(scanner.update(5_000, busy = true, sync = true))
        assertNull(scanner.update(6_999, busy = false, sync = false))
        assertEquals("b", scanner.update(7_000, busy = false, sync = false))
    }

    @Test
    fun `gives a synced digital channel longer to start a call`() {
        val scanner = Scanner(listOf("a", "b"), dwellMs = 400, syncDwellMs = 1_500)
        scanner.arrived(0)
        assertNull(scanner.update(300, busy = false, sync = true))
        assertNull(scanner.update(1_000, busy = false, sync = false))
        assertEquals("b", scanner.update(1_500, busy = false, sync = false))
    }

    @Test
    fun `hold keeps the channel`() {
        val scanner = Scanner(listOf("a", "b"))
        scanner.arrived(0)
        scanner.hold = true
        assertNull(scanner.update(60_000, busy = false, sync = false))
        scanner.hold = false
        assertEquals("b", scanner.update(60_001, busy = false, sync = false))
    }
}
