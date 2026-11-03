package com.sdrnova.t25.playlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeardIdsTest {
    private val groupsFile = """
        ; protocol, networkID, group, priority, override, hits, timestamp, "group alias"
        P25, BEE00.4E1, 1201, 20, Normal, 37, 2026/10/30 18:04, "Fire Dispatch, County"
        P25, BEE00.4E1, 2201, 50, L/O, 4, 2026/10/30 18:10, "PD Encrypted"
        DMR, 1, 9, 50, Normal, 12, 2026/10/29 07:00, ""
    """.trimIndent()

    private val radiosFile = """
        ; protocol, networkID, group, radio, priority, override, hits, timestamp, "radio alias"
        P25, BEE00.4E1, 1201, 1234567, 50, Normal, 9, 2026/10/30 18:04, "Engine 5"
    """.trimIndent()

    @Test
    fun `reads DSD+ groups and radios files`() {
        val ids = HeardIds()
        assertEquals(3, ids.import(groupsFile))
        assertEquals(1, ids.import(radiosFile))
        val snapshot = ids.snapshot()
        val fire = snapshot.groups.first { it.id == 1201 }
        assertEquals("Fire Dispatch, County", fire.alias)
        assertEquals(20, fire.priority)
        assertEquals(37, fire.hits)
        assertEquals("BEE00.4E1", fire.network)
        assertTrue(fire.lastHeard > 0)
        assertTrue(snapshot.groups.first { it.id == 2201 }.lockout)
        assertEquals("DMR", snapshot.groups.first { it.id == 9 }.protocol)
        val engine = snapshot.radios.single()
        assertEquals(1234567, engine.id)
        assertEquals(1201, engine.group)
        assertEquals("Engine 5", engine.alias)
    }

    @Test
    fun `hits accumulate and files read back the same`() {
        val ids = HeardIds()
        ids.import(groupsFile)
        ids.import(radiosFile)
        val now = System.currentTimeMillis()
        ids.heardGroup("P25", "BEE00.4E1", 1201, now)
        ids.heardGroup("P25", "BEE00.4E1", 3333, now + 1_000)
        ids.heardRadio("P25", "BEE00.4E1", 7654321, 3333, now + 1_000)
        assertEquals(38, ids.snapshot().groups.first { it.id == 1201 }.hits)
        assertEquals(3333, ids.snapshot().groups.first().id, "most recently heard first")

        val copy = HeardIds()
        copy.import(ids.groupsFile())
        copy.import(ids.radiosFile())
        assertEquals(ids.snapshot().groups.map { it.key to it.alias }.toSet(), copy.snapshot().groups.map { it.key to it.alias }.toSet())
        assertEquals(ids.snapshot().radios.size, copy.snapshot().radios.size)
        assertTrue(copy.snapshot().groups.first { it.id == 2201 }.lockout)
    }

    @Test
    fun `alias book uses heard names, lockouts and priorities after the playlist`() {
        val ids = HeardIds()
        ids.import(groupsFile)
        ids.import(radiosFile)
        val playlistAlias = PlaylistAlias("FD Main", null, null, null, listOf(1201..1201), emptyList(), priority = 5, record = false)
        val book = AliasBook(listOf(playlistAlias), heard = ids.snapshot())
        assertEquals("FD Main", book.name(1201), "the playlist wins")
        assertEquals(5, book.priority(1201))
        assertEquals("PD Encrypted", book.name(2201))
        assertTrue(book.lockedOut(2201))
        assertFalse(book.follow(2201, unitToUnit = false))
        assertEquals("Engine 5", book.radioName(1234567))
        assertEquals(AliasBook.UNLISTED_PRIORITY, book.priority(9), "DSD+'s default 50 isn't a priority")
    }
}
