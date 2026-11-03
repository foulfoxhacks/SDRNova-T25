package com.sdrnova.t25.web

import com.sdrnova.t25.playlist.ChannelProtocol
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistAccess
import com.sdrnova.t25.playlist.PlaylistAlias
import com.sdrnova.t25.playlist.PlaylistChannel
import com.sdrnova.t25.playlist.PlaylistImporter
import com.sdrnova.t25.playlist.PlaylistWriter
import com.sdrnova.t25.playlist.formatIdRanges
import com.sdrnova.t25.playlist.merge
import com.sdrnova.t25.playlist.parseIdRanges
import java.math.BigDecimal

/**
 * The browser dashboard for editing channels and aliases from a computer on the same Wi-Fi. Every API call needs the
 * PIN shown on the phone; after too many wrong guesses the API stops answering for a minute.
 */
class Dashboard(
    private val playlists: PlaylistAccess,
    val pin: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun handle(request: HttpRequest): HttpResponse {
        if (request.method == "GET" && (request.path == "/" || request.path == "/index.html")) return HttpResponse.html(PAGE)
        if (!request.path.startsWith("/api/")) return HttpResponse.text("Not found", 404)
        authorize(request)?.let { return it }
        return try {
            route(request)
        } catch (e: IllegalArgumentException) {
            HttpResponse.json(error(e.message ?: "That didn't look right."), 400)
        }
    }

    private fun authorize(request: HttpRequest): HttpResponse? = synchronized(this) {
        val now = clock()
        if (now < lockedUntil) return HttpResponse.json(error("Too many wrong PINs. Wait a minute and try again."), 429)
        val given = request.headers["x-pin"] ?: request.query["pin"]
        if (given == pin) {
            failures = 0
            return null
        }
        if (++failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = now + LOCKOUT_MS
        }
        HttpResponse.json(error("Enter the PIN shown in SDRNova on your phone."), 401)
    }

    private fun route(request: HttpRequest): HttpResponse = when (request.method to request.path) {
        "GET" to "/api/state" -> HttpResponse.json(state(playlists.playlist))
        "GET" to "/api/export" -> HttpResponse(
            200, "application/xml; charset=utf-8", PlaylistWriter.write(playlists.playlist).toByteArray(),
            mapOf("Content-Disposition" to "attachment; filename=\"playlist_v4.xml\""),
        )
        "POST" to "/api/channels" -> changed { it.copy(channels = it.channels + channelFrom(request.form())) }
        "POST" to "/api/channels/update" -> {
            val form = request.form()
            changed { playlist ->
                val index = indexOf(form, playlist.channels.map { it.key })
                playlist.copy(channels = playlist.channels.toMutableList().also { it[index] = channelFrom(form) })
            }
        }
        "POST" to "/api/channels/toggle" -> {
            val form = request.form()
            changed { playlist ->
                val index = indexOf(form, playlist.channels.map { it.key })
                playlist.copy(channels = playlist.channels.mapIndexed { i, c -> if (i == index) c.copy(enabled = !c.enabled) else c })
            }
        }
        "POST" to "/api/channels/delete" -> {
            val form = request.form()
            changed { playlist ->
                val index = indexOf(form, playlist.channels.map { it.key })
                playlist.copy(channels = playlist.channels.filterIndexed { i, _ -> i != index })
            }
        }
        "POST" to "/api/aliases" -> changed { it.copy(aliases = it.aliases + aliasFrom(request.form())) }
        "POST" to "/api/aliases/delete" -> {
            val form = request.form()
            changed { playlist ->
                val index = indexOf(form, playlist.aliases.map { aliasKey(it) })
                playlist.copy(aliases = playlist.aliases.filterIndexed { i, _ -> i != index })
            }
        }
        "POST" to "/api/import" -> {
            val imported = runCatching { PlaylistImporter.parse(request.body.inputStream()) }
                .getOrElse { throw IllegalArgumentException("That isn't an sdrtrunk playlist: ${it.message}") }
            val merge = request.query["merge"] == "1"
            changed { if (merge) it.merge(imported) else imported }
        }
        else -> HttpResponse.text("Not found", 404)
    }

    private fun changed(transform: (Playlist) -> Playlist): HttpResponse = HttpResponse.json(state(playlists.update(transform)))

    /** Index plus a key, so an edit made from a stale page fails instead of hitting the wrong row. */
    private fun indexOf(form: Map<String, String>, keys: List<String>): Int {
        val index = form["index"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing row.")
        require(index in keys.indices && keys[index] == form["key"]) { "The list changed on the phone. Reload and try again." }
        return index
    }

    private fun channelFrom(form: Map<String, String>): PlaylistChannel {
        val frequencies = (form["frequencies"] ?: "").split(',', ' ', ';').filter { it.isNotBlank() }.map { text ->
            val mhz = text.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("\"$text\" isn't a frequency in MHz.")
            require(mhz > BigDecimal(24) && mhz < BigDecimal(1800)) { "$text MHz is outside what the dongles tune (24 to 1,766 MHz)." }
            mhz.movePointRight(6).toLong()
        }
        require(frequencies.isNotEmpty()) { "Enter at least one frequency." }
        val protocol = when (form["protocol"]) {
            "DMR" -> ChannelProtocol.DMR
            "NBFM" -> ChannelProtocol.NBFM
            else -> ChannelProtocol.P25
        }
        val name = form["name"].orEmpty().trim().ifEmpty { "%.4f MHz".format(frequencies[0] / 1e6) }
        return PlaylistChannel(
            name = name.take(80),
            system = form["system"].orEmpty().trim().ifEmpty { "Web" }.take(80),
            site = form["site"].orEmpty().trim().take(80),
            enabled = form["scan"] == "1",
            frequencies = frequencies,
            protocol = protocol,
            decoderType = protocol.decoderType,
            modulation = if (protocol == ChannelProtocol.P25) (if (form["lsm"] == "1") "LSM" else "C4FM") else null,
            aliasList = form["aliasList"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
        )
    }

    private fun aliasFrom(form: Map<String, String>): PlaylistAlias {
        val name = form["name"].orEmpty().trim()
        require(name.isNotEmpty()) { "Give the alias a name." }
        val talkgroups = parseIdRanges(form["talkgroups"].orEmpty())
        val radios = parseIdRanges(form["radios"].orEmpty())
        require(talkgroups.isNotEmpty() || radios.isNotEmpty()) { "Enter a talkgroup or radio ID." }
        val priority = when {
            form["lockout"] == "1" -> PlaylistAlias.DO_NOT_MONITOR
            else -> form["priority"]?.trim()?.toIntOrNull()?.coerceIn(1, 100)
        }
        return PlaylistAlias(
            name = name.take(80),
            list = form["list"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
            group = form["group"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
            color = null,
            talkgroups = talkgroups,
            radios = radios,
            priority = priority,
            record = form["record"] == "1",
            protocol = if (form["protocol"] == "DMR") "DMR" else "APCO25",
        )
    }

    private fun aliasKey(alias: PlaylistAlias) = "${alias.list}/${alias.name}/${formatIdRanges(alias.talkgroups)}"

    private fun state(playlist: Playlist): String = buildString {
        append("{\"channels\":[")
        playlist.channels.forEachIndexed { index, channel ->
            if (index > 0) append(',')
            append("{\"index\":").append(index)
            field("key", channel.key)
            field("name", channel.name)
            field("system", channel.system)
            field("site", channel.site)
            append(",\"frequencies\":[").append(channel.frequencies.joinToString(",")).append(']')
            field("protocol", channel.protocol.name)
            field("label", channel.protocol.label)
            append(",\"supported\":").append(channel.protocol.supported)
            append(",\"lsm\":").append(channel.simulcast)
            append(",\"scan\":").append(channel.enabled)
            field("aliasList", channel.aliasList.orEmpty())
            append('}')
        }
        append("],\"aliases\":[")
        playlist.aliases.forEachIndexed { index, alias ->
            if (index > 0) append(',')
            append("{\"index\":").append(index)
            field("key", aliasKey(alias))
            field("name", alias.name)
            field("list", alias.list.orEmpty())
            field("group", alias.group.orEmpty())
            field("talkgroups", formatIdRanges(alias.talkgroups))
            field("radios", formatIdRanges(alias.radios))
            append(",\"priority\":").append(alias.priority?.toString() ?: "null")
            append(",\"lockout\":").append(alias.lockedOut)
            append(",\"record\":").append(alias.record)
            append('}')
        }
        append("],\"aliasLists\":[")
        append(playlist.aliases.mapNotNull { it.list }.distinct().joinToString(",") { quote(it) })
        append("]}")
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(",\"").append(name).append("\":").append(quote(value))
    }

    private fun error(message: String) = "{\"error\":${quote(message)}}"

    companion object {
        const val MAX_FAILURES = 10
        const val LOCKOUT_MS = 60_000L

        /** A fresh 6-digit PIN each time the dashboard starts. */
        fun newPin(random: java.util.Random = java.security.SecureRandom()): String = "%06d".format(random.nextInt(1_000_000))

        private val PAGE: String by lazy {
            Dashboard::class.java.getResourceAsStream("/com/sdrnova/t25/web/dashboard.html")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        }

        internal fun quote(text: String): String = buildString(text.length + 2) {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' || c == '<' || c == '>' || c == '&' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
