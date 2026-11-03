package com.sdrnova.t25.web

import com.sdrnova.t25.playlist.ChannelProtocol
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistAccess
import com.sdrnova.t25.playlist.PlaylistImporter
import com.sdrnova.t25.playlist.PlaylistWriter
import com.sdrnova.t25.playlist.merge
import com.sdrnova.t25.playlist.parseIdRanges
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

class DashboardTest {
    private class Memory : PlaylistAccess {
        @Volatile
        override var playlist = Playlist.EMPTY
        var saves = 0

        @Synchronized
        override fun update(transform: (Playlist) -> Playlist): Playlist {
            playlist = transform(playlist)
            saves++
            return playlist
        }
    }

    private val memory = Memory()
    private var now = 0L
    private val dashboard = Dashboard(memory, pin = "123456", clock = { now })
    private val server = MiniHttpServer(InetAddress.getLoopbackAddress(), 0, dashboard::handle).start()

    @AfterEach
    fun close() = server.close()

    private class Reply(val status: Int, val body: String, val type: String?)

    private fun call(method: String, path: String, body: String? = null, pin: String? = "123456", type: String = "application/x-www-form-urlencoded"): Reply {
        val connection = URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        pin?.let { connection.setRequestProperty("X-Pin", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        return Reply(status, stream?.use { String(it.readBytes()) } ?: "", connection.contentType)
    }

    private fun form(vararg fields: Pair<String, String>) =
        fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    @Test
    fun `serves the page without a PIN but guards the API`() {
        val page = call("GET", "/", pin = null)
        assertEquals(200, page.status)
        assertTrue("SDRNova T25" in page.body)
        assertEquals(401, call("GET", "/api/state", pin = null).status)
        assertEquals(401, call("GET", "/api/state", pin = "000000").status)
        assertEquals(200, call("GET", "/api/state").status)
    }

    @Test
    fun `locks the API after too many wrong PINs`() {
        repeat(Dashboard.MAX_FAILURES) { call("GET", "/api/state", pin = "999999") }
        assertEquals(429, call("GET", "/api/state").status)
        now += Dashboard.LOCKOUT_MS + 1
        assertEquals(200, call("GET", "/api/state").status)
    }

    @Test
    fun `adds, toggles and removes channels`() {
        val added = call(
            "POST", "/api/channels",
            form("name" to "Fire <Dispatch>", "frequencies" to "851.0125, 851.2625", "protocol" to "P25", "system" to "County", "lsm" to "1", "scan" to "1"),
        )
        assertEquals(200, added.status)
        val channel = memory.playlist.channels.single()
        assertEquals(listOf(851_012_500L, 851_262_500L), channel.frequencies)
        assertEquals(ChannelProtocol.P25, channel.protocol)
        assertTrue(channel.simulcast)
        assertTrue(channel.enabled)
        // Names are JSON-escaped so the page can't be fed markup.
        assertTrue("Fire \\u003cDispatch\\u003e" in added.body)

        assertEquals(400, call("POST", "/api/channels", form("frequencies" to "abc")).status)
        assertEquals(400, call("POST", "/api/channels/toggle", form("index" to "0", "key" to "stale")).status)
        assertEquals(200, call("POST", "/api/channels/toggle", form("index" to "0", "key" to channel.key)).status)
        assertEquals(false, memory.playlist.channels.single().enabled)
        assertEquals(200, call("POST", "/api/channels/delete", form("index" to "0", "key" to channel.key)).status)
        assertTrue(memory.playlist.channels.isEmpty())
    }

    @Test
    fun `adds aliases with ranges, priority and lockout`() {
        call("POST", "/api/aliases", form("name" to "Interop", "talkgroups" to "9000-9099, 1201", "list" to "County", "priority" to "2"))
        call("POST", "/api/aliases", form("name" to "Encrypted PD", "talkgroups" to "2201", "lockout" to "1"))
        val (interop, pd) = memory.playlist.aliases
        assertEquals(listOf(9000..9099, 1201..1201), interop.talkgroups)
        assertEquals(2, interop.priority)
        assertEquals("County", interop.list)
        assertTrue(pd.lockedOut)
        assertEquals(400, call("POST", "/api/aliases", form("name" to "Nothing")).status)
    }

    @Test
    fun `imports and exports sdrtrunk playlists`() {
        val xml = PlaylistWriter.write(
            Playlist(
                listOf(
                    com.sdrnova.t25.playlist.PlaylistChannel(
                        "Simplex", "Ham", "", true, listOf(146_520_000L), ChannelProtocol.NBFM, "", null, null,
                    ),
                ),
                emptyList(),
            ),
        )
        assertEquals(200, call("POST", "/api/import?merge=0", xml, type = "application/xml").status)
        assertEquals(1, memory.playlist.channels.size)
        call("POST", "/api/import?merge=1", xml, type = "application/xml")
        assertEquals(1, memory.playlist.channels.size, "merging the same file again adds nothing")
        assertEquals(400, call("POST", "/api/import", "<html/>", type = "application/xml").status)

        val exported = call("GET", "/api/export?pin=123456", pin = null)
        assertEquals(200, exported.status)
        assertEquals(memory.playlist, PlaylistImporter.parse(exported.body.byteInputStream()))
    }

    @Test
    fun `parses id ranges and merges playlists`() {
        assertEquals(listOf(1..1, 5..9, 12..12), parseIdRanges("1, 5-9 ;12, x, 9-5"))
        val a = Playlist(emptyList(), emptyList())
        assertEquals(a, a.merge(Playlist.EMPTY))
        assertTrue(Dashboard.newPin().matches(Regex("\\d{6}")))
    }
}
