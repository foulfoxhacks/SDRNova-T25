package com.sdrnova.t25.playlist

import com.sdrnova.t25.receiver.Mode
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** What a channel decodes, mapped from sdrtrunk's decode_configuration types. [mode] is null where SDRNova can't. */
enum class ChannelProtocol(val label: String, val mode: Mode?, val decoderType: String) {
    P25("P25 Phase 1", Mode.P25_PHASE1, "decodeConfigP25Phase1"),
    P25_PHASE2("P25 Phase 2", null, "decodeConfigP25Phase2"),
    DMR("DMR", Mode.DMR, "decodeConfigDMR"),
    NBFM("NBFM", Mode.NBFM, "decodeConfigNBFM"),
    AM("AM", null, "decodeConfigAM"),
    OTHER("Other", null, ""),
    ;

    val supported: Boolean get() = mode != null

    companion object {
        fun of(mode: Mode): ChannelProtocol = entries.firstOrNull { it.mode == mode } ?: OTHER
    }
}

data class PlaylistChannel(
    val name: String,
    val system: String,
    val site: String,
    /** sdrtrunk starts enabled channels on launch; SDRNova scans them. */
    val enabled: Boolean,
    /** First is the primary; P25 control channels may list alternates. */
    val frequencies: List<Long>,
    val protocol: ChannelProtocol,
    val decoderType: String,
    /** P25 modulation: C4FM or LSM (simulcast). */
    val modulation: String?,
    val aliasList: String?,
) {
    /** Tells channels apart for scan lists, since sdrtrunk doesn't require unique names. */
    val key: String get() = "$system/$site/$name/${frequencies.firstOrNull() ?: 0}"

    /** P25 simulcast sites transmit LSM (CQPSK), which wants a different demodulator. */
    val simulcast: Boolean get() = modulation.equals("LSM", ignoreCase = true)
}

data class PlaylistAlias(
    val name: String,
    val list: String?,
    val group: String?,
    /** sdrtrunk stores colours as signed ARGB ints. */
    val color: Int?,
    val talkgroups: List<IntRange>,
    val radios: List<IntRange>,
    /** sdrtrunk priority: 1 is highest, 100 lowest, -1 means do not monitor. */
    val priority: Int?,
    val record: Boolean,
    /** sdrtrunk's identifier protocol (APCO25, DMR, ...), kept so an export imports back the same way. */
    val protocol: String? = null,
) {
    val lockedOut: Boolean get() = priority == DO_NOT_MONITOR

    fun matchesTalkgroup(id: Int) = talkgroups.any { id in it }

    companion object {
        const val DO_NOT_MONITOR = -1
    }
}

data class Playlist(val channels: List<PlaylistChannel>, val aliases: List<PlaylistAlias>) {
    fun aliasesFor(channel: PlaylistChannel?): List<PlaylistAlias> = aliasesIn(channel?.aliasList)

    /** The aliases in [list]; every alias when no list is named. */
    fun aliasesIn(list: String?): List<PlaylistAlias> =
        if (list.isNullOrEmpty()) aliases else aliases.filter { it.list == list }

    companion object {
        val EMPTY = Playlist(emptyList(), emptyList())
    }
}

/**
 * Reads sdrtrunk playlist XML (playlist_v2 / v4: `<playlist><alias/>...<channel/>...</playlist>`), keeping what
 * SDRNova T25 can use. Element and attribute names follow sdrtrunk's Jackson annotations; the polymorphic `type`
 * attribute is namespaced (xsi:type), so it's matched by local name.
 */
object PlaylistImporter {
    fun parse(input: InputStream): Playlist {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        require(root.localName == "playlist" || root.nodeName == "playlist") { "Not an sdrtrunk playlist (root is <${root.nodeName}>)" }
        val aliases = children(root, "alias").map(::alias)
        val channels = children(root, "channel").map(::channel)
        return Playlist(channels, aliases)
    }

    private fun channel(element: Element): PlaylistChannel {
        val source = children(element, "source_configuration").firstOrNull()
        val frequencies = buildList {
            source?.getAttribute("frequency")?.toLongOrNull()?.let(::add)
            source?.let { children(it, "frequency").mapNotNull { f -> f.textContent.trim().toLongOrNull() } }?.let(::addAll)
        }.filter { it > 0 }
        val decode = children(element, "decode_configuration").firstOrNull()
        val decoderType = decode?.let(::typeOf) ?: ""
        return PlaylistChannel(
            name = element.getAttribute("name"),
            system = element.getAttribute("system"),
            site = element.getAttribute("site"),
            enabled = element.getAttribute("enabled") != "false",
            frequencies = frequencies,
            protocol = protocolOf(decoderType),
            decoderType = decoderType,
            modulation = decode?.getAttribute("modulation")?.takeIf { it.isNotEmpty() },
            aliasList = children(element, "alias_list_name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun protocolOf(type: String): ChannelProtocol {
        val t = type.lowercase()
        return when {
            "p25phase2" in t -> ChannelProtocol.P25_PHASE2
            "p25" in t -> ChannelProtocol.P25
            "dmr" in t -> ChannelProtocol.DMR
            "nbfm" in t -> ChannelProtocol.NBFM
            t.endsWith("am") -> ChannelProtocol.AM
            else -> ChannelProtocol.OTHER
        }
    }

    private fun alias(element: Element): PlaylistAlias {
        val talkgroups = ArrayList<IntRange>()
        val radios = ArrayList<IntRange>()
        var priority: Int? = null
        var record = false
        var protocol: String? = null
        for (id in children(element, "id")) {
            if (protocol == null) protocol = id.getAttribute("protocol").takeIf { it.isNotEmpty() }
            when (typeOf(id)) {
                "talkgroup", "talkgroupID" -> id.getAttribute("value").toIntOrNull()?.let { talkgroups += it..it }
                "talkgroupRange" -> {
                    val min = id.getAttribute("min").toIntOrNull()
                    val max = id.getAttribute("max").toIntOrNull()
                    if (min != null && max != null) talkgroups += min..max
                }
                "radio" -> id.getAttribute("value").toIntOrNull()?.let { radios += it..it }
                "radioRange" -> {
                    val min = id.getAttribute("min").toIntOrNull()
                    val max = id.getAttribute("max").toIntOrNull()
                    if (min != null && max != null) radios += min..max
                }
                "priority" -> priority = id.getAttribute("priority").toIntOrNull()
                "record" -> record = true
            }
        }
        return PlaylistAlias(
            name = element.getAttribute("name"),
            list = element.getAttribute("list").takeIf { it.isNotEmpty() },
            group = element.getAttribute("group").takeIf { it.isNotEmpty() },
            color = element.getAttribute("color").toIntOrNull(),
            talkgroups = talkgroups,
            radios = radios,
            priority = priority,
            record = record,
            protocol = protocol,
        )
    }

    /** The `type` attribute, in whatever namespace prefix the writer used. */
    private fun typeOf(element: Element): String {
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            val local = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (local == "type") return attribute.nodeValue
        }
        return ""
    }

    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.nodeName) == name) out += node
            node = node.nextSibling
        }
        return out
    }
}

/**
 * Writes a playlist in sdrtrunk's format, so channels added on the phone can go back to the desktop. Only what
 * [Playlist] models is written; sdrtrunk fills in defaults for the rest.
 */
object PlaylistWriter {
    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"

    fun write(playlist: Playlist): String = buildString {
        append("<?xml version='1.0' encoding='UTF-8'?>\n")
        append("<playlist version=\"4\" xmlns:xsi=\"$XSI\">\n")
        for (alias in playlist.aliases) writeAlias(alias)
        for ((index, channel) in playlist.channels.withIndex()) writeChannel(channel, index)
        append("</playlist>\n")
    }

    private fun StringBuilder.writeAlias(alias: PlaylistAlias) {
        append("  <alias")
        attribute("name", alias.name)
        alias.list?.let { attribute("list", it) }
        alias.group?.let { attribute("group", it) }
        alias.color?.let { attribute("color", it.toString()) }
        append(">\n")
        val protocol = alias.protocol ?: "APCO25"
        for (range in alias.talkgroups) {
            if (range.first == range.last) {
                id("talkgroup", "value" to range.first, "protocol" to protocol)
            } else {
                id("talkgroupRange", "min" to range.first, "max" to range.last, "protocol" to protocol)
            }
        }
        for (range in alias.radios) {
            if (range.first == range.last) {
                id("radio", "value" to range.first, "protocol" to protocol)
            } else {
                id("radioRange", "min" to range.first, "max" to range.last, "protocol" to protocol)
            }
        }
        alias.priority?.let { id("priority", "priority" to it) }
        if (alias.record) id("record")
        append("  </alias>\n")
    }

    private fun StringBuilder.writeChannel(channel: PlaylistChannel, order: Int) {
        append("  <channel")
        attribute("system", channel.system)
        attribute("site", channel.site)
        attribute("name", channel.name)
        attribute("enabled", channel.enabled.toString())
        attribute("order", (order + 1).toString())
        append(">\n")
        channel.aliasList?.let { append("    <alias_list_name>").append(escape(it)).append("</alias_list_name>\n") }
        if (channel.frequencies.size == 1) {
            append("    <source_configuration xsi:type=\"sourceConfigTuner\" source_type=\"TUNER\"")
            attribute("frequency", channel.frequencies[0].toString())
            append("/>\n")
        } else {
            append("    <source_configuration xsi:type=\"sourceConfigTunerMultipleFrequency\" ")
            append("source_type=\"TUNER_MULTIPLE_FREQUENCIES\" frequency_rotation_delay=\"400\">\n")
            for (frequency in channel.frequencies) append("      <frequency>").append(frequency).append("</frequency>\n")
            append("    </source_configuration>\n")
        }
        append("    <decode_configuration")
        attribute("xsi:type", channel.decoderType.ifEmpty { channel.protocol.decoderType })
        channel.modulation?.let { attribute("modulation", it) }
        append("/>\n")
        append("  </channel>\n")
    }

    private fun StringBuilder.id(type: String, vararg values: Pair<String, Any>) {
        append("    <id")
        attribute("xsi:type", type)
        for ((name, value) in values) attribute(name, value.toString())
        append("/>\n")
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"").append(escape(value)).append('"')
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
