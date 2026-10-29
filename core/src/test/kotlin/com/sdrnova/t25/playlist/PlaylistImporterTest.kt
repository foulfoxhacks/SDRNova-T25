package com.sdrnova.t25.playlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistImporterTest {
    /** Shaped like sdrtrunk's playlist_v4.xml, including Jackson's namespaced type attributes. */
    private val xml = """
        <?xml version='1.0' encoding='UTF-8'?>
        <playlist version="4">
          <alias color="-3355444" group="Fire" iconName="Fire Truck" list="County" name="Fire Dispatch">
            <id xmlns:wstxns1="http://www.w3.org/2001/XMLSchema-instance" wstxns1:type="talkgroup" value="1201" protocol="APCO25"/>
            <id xmlns:wstxns2="http://www.w3.org/2001/XMLSchema-instance" wstxns2:type="priority" priority="1"/>
            <id xmlns:wstxns3="http://www.w3.org/2001/XMLSchema-instance" wstxns3:type="record"/>
          </alias>
          <alias list="County" name="Interop" group="Mutual Aid">
            <id xmlns:wstxns4="http://www.w3.org/2001/XMLSchema-instance" wstxns4:type="talkgroupRange" min="9000" max="9099" protocol="APCO25"/>
          </alias>
          <alias list="County" name="Encrypted PD">
            <id type="talkgroup" value="2201" protocol="APCO25"/>
            <id type="priority" priority="-1"/>
          </alias>
          <alias list="Ham" name="Repeater users">
            <id type="radio" value="3101234" protocol="DMR"/>
          </alias>
          <channel system="County P25" site="Simulcast" name="Control" enabled="true" order="1">
            <alias_list_name>County</alias_list_name>
            <event_log_configuration/>
            <source_configuration xmlns:wstxns5="http://www.w3.org/2001/XMLSchema-instance" wstxns5:type="sourceConfigTunerMultipleFrequency" frequency_rotation_delay="400" source_type="TUNER_MULTIPLE_FREQUENCIES">
              <frequency>851012500</frequency>
              <frequency>851262500</frequency>
            </source_configuration>
            <aux_decode_configuration/>
            <decode_configuration xmlns:wstxns6="http://www.w3.org/2001/XMLSchema-instance" wstxns6:type="decodeConfigP25Phase1" modulation="LSM" traffic_channel_pool_size="20"/>
            <record_configuration/>
          </channel>
          <channel system="Ham" site="Local" name="DMR Repeater" enabled="false">
            <alias_list_name>Ham</alias_list_name>
            <source_configuration type="sourceConfigTuner" frequency="443500000" source_type="TUNER"/>
            <decode_configuration type="decodeConfigDMR"/>
          </channel>
          <channel system="Ham" site="Local" name="Simplex" enabled="true">
            <source_configuration type="sourceConfigTuner" frequency="146520000"/>
            <decode_configuration type="decodeConfigNBFM" bandwidth="BW_12_5"/>
          </channel>
          <channel system="Air" name="Tower" enabled="true">
            <source_configuration type="sourceConfigTuner" frequency="118300000"/>
            <decode_configuration type="decodeConfigAM"/>
          </channel>
        </playlist>
    """.trimIndent()

    @Test
    fun `reads sdrtrunk channels and aliases`() {
        val playlist = PlaylistImporter.parse(xml.byteInputStream())
        assertEquals(4, playlist.channels.size)
        val control = playlist.channels[0]
        assertEquals("Control", control.name)
        assertEquals("County P25", control.system)
        assertEquals(listOf(851_012_500L, 851_262_500L), control.frequencies)
        assertEquals(ChannelProtocol.P25, control.protocol)
        assertEquals("LSM", control.modulation)
        assertEquals("County", control.aliasList)

        assertEquals(ChannelProtocol.DMR, playlist.channels[1].protocol)
        assertEquals(false, playlist.channels[1].enabled)
        assertEquals(listOf(443_500_000L), playlist.channels[1].frequencies)
        assertEquals(ChannelProtocol.NBFM, playlist.channels[2].protocol)
        assertEquals(ChannelProtocol.AM, playlist.channels[3].protocol)

        val county = playlist.aliasesFor(control)
        assertEquals(3, county.size)
        val fire = county.first { it.name == "Fire Dispatch" }
        assertTrue(fire.matchesTalkgroup(1201))
        assertEquals(1, fire.priority)
        assertTrue(fire.record)
        assertEquals(-3355444, fire.color)
        assertTrue(county.first { it.name == "Interop" }.matchesTalkgroup(9042))
        assertTrue(county.first { it.name == "Encrypted PD" }.lockedOut)
        assertEquals(listOf(3101234..3101234), playlist.aliasesFor(playlist.channels[1]).single().radios)
        assertEquals("APCO25", fire.protocol)
        assertTrue(control.simulcast)
    }

    @Test
    fun `written playlists read back the same`() {
        val playlist = PlaylistImporter.parse(xml.byteInputStream())
        val text = PlaylistWriter.write(playlist)
        assertEquals(playlist, PlaylistImporter.parse(text.byteInputStream()))

        val odd = Playlist(
            listOf(PlaylistChannel("A & B <\"1\">", "Sys's", "", true, listOf(460_125_000L), ChannelProtocol.NBFM, "", null, null)),
            emptyList(),
        )
        val back = PlaylistImporter.parse(PlaylistWriter.write(odd).byteInputStream()).channels.single()
        assertEquals("A & B <\"1\">", back.name)
        assertEquals("Sys's", back.system)
        assertEquals(ChannelProtocol.NBFM, back.protocol)
    }

    @Test
    fun `alias book resolves names, ranges, priority and lockout`() {
        val playlist = PlaylistImporter.parse(xml.byteInputStream())
        val book = AliasBook(playlist.aliasesIn("County"), mapOf(1201 to "FD Main", 777 to "Typed"))
        assertEquals("FD Main", book.name(1201))
        assertEquals(1, book.priority(1201))
        assertTrue(book.record(1201))
        assertEquals("Interop", book.name(9000))
        assertEquals("Typed", book.name(777))
        assertTrue(book.lockedOut(2201))
        assertEquals(AliasBook.UNLISTED_PRIORITY, book.priority(5))
        assertEquals(null, book.name(5))
        assertEquals("Repeater users", AliasBook(playlist.aliases).radioName(3101234))
    }
}
