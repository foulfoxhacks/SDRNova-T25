package com.sdrnova.t25.radioreference

import com.sdrnova.t25.playlist.ChannelProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RadioReferenceTest {
    /** Wraps a result the way RadioReference's PHP SOAP server does (rpc style, SOAP-ENC arrays of "item"). */
    private fun envelope(action: String, result: String) = """
        <?xml version="1.0" encoding="ISO-8859-1"?><SOAP-ENV:Envelope SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"
          xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:SOAP-ENC="http://schemas.xmlsoap.org/soap/encoding/"
          xmlns:tns="http://api.radioreference.com/soap2"><SOAP-ENV:Body><ns1:${action}Response xmlns:ns1="http://api.radioreference.com/soap2">$result</ns1:${action}Response></SOAP-ENV:Body></SOAP-ENV:Envelope>
    """.trimIndent()

    private val responses = mapOf(
        "getZipcodeInfo" to envelope(
            "getZipcodeInfo",
            """<return xsi:type="tns:ZipInfo"><zipCode xsi:type="xsd:int">43215</zipCode><lat>39.96</lat><lon>-83.00</lon><city xsi:type="xsd:string">Columbus</city><stid>39</stid><ctid>2129</ctid></return>""",
        ),
        "getCountyInfo" to envelope(
            "getCountyInfo",
            """<return xsi:type="tns:CountyInfo"><ctid>2129</ctid><countyName>Franklin</countyName><stid>39</stid>
               <cats SOAP-ENC:arrayType="tns:cat[1]" xsi:type="tns:Cats"><item xsi:type="tns:cat"><cid>11</cid><cName>Columbus</cName>
                 <subcats SOAP-ENC:arrayType="tns:subcat[1]"><item><scid>101</scid><scName>Fire Department</scName></item></subcats></item></cats>
               <trsList SOAP-ENC:arrayType="tns:TrsListDef[2]"><item><sid>3505</sid><sName>MARCS-IP</sName><sType>8</sType><sCity>Statewide</sCity></item>
                 <item><sid>777</sid><sName>Ham DMR</sName><sType>20</sType><sCity>Columbus</sCity></item></trsList></return>""",
        ),
        "getTrsType" to envelope(
            "getTrsType",
            """<return SOAP-ENC:arrayType="tns:trsTypeDef[1]"><item><sType>8</sType><sTypeDescr>Project 25 Phase II</sTypeDescr></item></return>""",
        ),
        "getTrsSites" to envelope(
            "getTrsSites",
            """<return SOAP-ENC:arrayType="tns:TrsSite[2]">
               <item><siteId>9001</siteId><siteNumber>12</siteNumber><siteDescr>Franklin Simulcast</siteDescr><rfss>1</rfss><nac>3A1</nac><siteCtid>2129</siteCtid><siteCt>Franklin</siteCt><siteModulation>LSM</siteModulation>
                 <siteFreqs SOAP-ENC:arrayType="tns:TrsSiteFreq[4]"><item><lcn>1</lcn><freq>851.01250000</freq><use>d</use></item><item><lcn>2</lcn><freq>851.26250</freq><use>a</use></item><item><lcn>3</lcn><freq>852.4625</freq><use></use></item><item><lcn>4</lcn><freq>853.1</freq><use/></item></siteFreqs></item>
               <item><siteId>9002</siteId><siteNumber>40</siteNumber><siteDescr>Delaware</siteDescr><siteCtid>2100</siteCtid><siteCt>Delaware</siteCt><siteModulation>C4FM</siteModulation>
                 <siteFreqs><item><lcn>1</lcn><freq>770.30625</freq><use>d</use></item></siteFreqs></item>
               </return>""",
        ),
        "getTrsTalkgroups" to envelope(
            "getTrsTalkgroups",
            """<return SOAP-ENC:arrayType="tns:Talkgroup[3]">
               <item><tgDec>1201</tgDec><tgAlpha>CFD Disp</tgAlpha><tgDescr>Fire Dispatch</tgDescr><tgMode>D</tgMode><enc>0</enc><tgCid>55</tgCid></item>
               <item><tgDec>2201</tgDec><tgAlpha></tgAlpha><tgDescr>Police Tac</tgDescr><tgMode>DE</tgMode><enc>2</enc><tgCid>56</tgCid></item>
               <item><tgDec>0</tgDec><tgAlpha>Bad</tgAlpha></item></return>""",
        ),
        "getTrsTalkgroupCats" to envelope(
            "getTrsTalkgroupCats",
            """<return><item><tgCid>55</tgCid><tgCname>Columbus Fire</tgCname></item><item><tgCid>56</tgCid><tgCname>Columbus Police</tgCname></item></return>""",
        ),
        "getSubcatFreqs" to envelope(
            "getSubcatFreqs",
            """<return SOAP-ENC:arrayType="tns:freq[3]">
               <item><out>154.43000</out><alpha>CFD Tac 2</alpha><descr>Fireground</descr><mode>2</mode><tone>CSQ</tone><enc>0</enc></item>
               <item><out>453.2125</out><alpha>CFD P25</alpha><mode>P25</mode><enc>0</enc></item>
               <item><out>0</out><alpha>Broken</alpha><mode>2</mode></item></return>""",
        ),
        "getMode" to envelope("getMode", """<return><item><mode>2</mode><modeName>FMN</modeName></item></return>"""),
    )

    private val calls = ArrayList<String>()
    private val client = RadioReferenceClient(RrAuth("scanner&fan", "p<w>d", "KEY")) { action, envelope ->
        calls += action
        assertTrue("<username>scanner&amp;fan</username>" in envelope)
        assertTrue("<password>p&lt;w&gt;d</password>" in envelope)
        responses.getValue(action)
    }

    @Test
    fun `looks up a county from a zip code`() {
        val zip = client.zipcode(43215)
        assertEquals(2129, zip.countyId)
        assertEquals("Columbus", zip.city)
        val county = client.county(zip.countyId)
        assertEquals("Franklin", county.name)
        assertEquals(listOf("MARCS-IP", "Ham DMR"), county.systems.map { it.name })
        assertEquals(101, county.categories.single().subcategories.single().id)
        assertEquals("Project 25 Phase II", client.systemTypeName(8))
        client.systemTypeName(8)
        assertEquals(1, calls.count { it == "getTrsType" }, "type names are cached")
    }

    @Test
    fun `builds a trunked playlist with control channels first`() {
        val sites = client.sites(3505)
        val playlist = RrPlaylist.trunked(
            "MARCS-IP", client.systemTypeName(8), sites, client.talkgroups(3505), client.talkgroupCategories(3505), localCountyId = 2129,
        )
        val (franklin, delaware) = playlist.channels
        assertEquals(listOf(851_012_500L, 851_262_500L), franklin.frequencies)
        assertEquals(ChannelProtocol.P25, franklin.protocol)
        assertTrue(franklin.simulcast)
        assertTrue(franklin.enabled, "sites in the county are ticked for scanning")
        assertEquals("Site 12 · Franklin Simulcast", franklin.name)
        assertFalse(delaware.enabled)
        assertFalse(delaware.simulcast)
        assertEquals(2, playlist.aliases.size)
        val fire = playlist.aliases.first()
        assertEquals("CFD Disp", fire.name)
        assertEquals("Columbus Fire", fire.group)
        assertEquals("MARCS-IP", fire.list)
        assertEquals("Police Tac", playlist.aliases[1].name)
    }

    @Test
    fun `builds conventional channels with mode names`() {
        val playlist = RrPlaylist.conventional("Franklin · Columbus", "Fire Department", client.subcategoryFrequencies(101), client::modeName)
        assertEquals(2, playlist.channels.size)
        assertEquals(ChannelProtocol.NBFM, playlist.channels[0].protocol)
        assertEquals(154_430_000L, playlist.channels[0].frequencies.single())
        assertEquals(ChannelProtocol.P25, playlist.channels[1].protocol)
        assertEquals(ChannelProtocol.OTHER, RrPlaylist.protocolForSystem("Motorola Type II Smartzone"))
        assertEquals(ChannelProtocol.DMR, RrPlaylist.protocolForSystem("DMR Tier III"))
    }

    @Test
    fun `reports RadioReference faults`() {
        // The answer the live service gave to a bad login while this was written.
        val fault = """<?xml version="1.0" encoding="ISO-8859-1"?><SOAP-ENV:Envelope SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"
              xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:SOAP-ENC="http://schemas.xmlsoap.org/soap/encoding/">
            <SOAP-ENV:Body><SOAP-ENV:Fault><faultcode xsi:type="xsd:string">AUTH</faultcode><faultstring xsi:type="xsd:string">Invalid Username or Password.  Make sure you are logging in with your username and not your email address.</faultstring><faultactor xsi:type="xsd:string"></faultactor><detail xsi:type="xsd:string"></detail></SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val failing = RadioReferenceClient(RrAuth("x", "x", "x")) { _, _ -> fault }
        val error = assertThrows<RadioReferenceException> { failing.zipcode(10001) }
        assertEquals("AUTH", error.code)
        assertTrue(error.message!!.startsWith("Invalid Username or Password"))
        assertThrows<RadioReferenceException> { RadioReferenceClient(RrAuth("x", "x", "x")) { _, _ -> "<html>" }.zipcode(1) }
    }
}
