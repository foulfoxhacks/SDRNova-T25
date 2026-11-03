package com.sdrnova.t25.radioreference

import com.sdrnova.t25.playlist.ChannelProtocol
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistAlias
import com.sdrnova.t25.playlist.PlaylistChannel

/** Turns RadioReference records into playlist channels and aliases, the same shape an sdrtrunk import gives. */
object RrPlaylist {
    /** P25 systems decode; DMR comes in as single frequencies; other trunking types import but don't decode yet. */
    fun protocolForSystem(typeName: String): ChannelProtocol {
        val type = typeName.lowercase()
        return when {
            "project 25" in type || "p25" in type || "apco" in type -> ChannelProtocol.P25
            "dmr" in type || "capacity" in type || "connect plus" in type || "mototrbo" in type -> ChannelProtocol.DMR
            else -> ChannelProtocol.OTHER
        }
    }

    fun protocolForMode(modeName: String): ChannelProtocol {
        val mode = modeName.uppercase()
        return when {
            "P25" in mode || "APCO" in mode -> ChannelProtocol.P25
            "DMR" in mode || "TRBO" in mode -> ChannelProtocol.DMR
            mode == "FM" || mode == "FMN" || mode.startsWith("FMN") || mode == "NFM" -> ChannelProtocol.NBFM
            mode == "AM" -> ChannelProtocol.AM
            else -> ChannelProtocol.OTHER
        }
    }

    /**
     * One channel per site, listing its control channels first and alternates after. Sites in [localCountyId] are
     * ticked for scanning; the rest import unticked, since most systems have far more sites than one tuner can reach.
     */
    fun trunked(
        systemName: String,
        typeName: String,
        sites: List<RrSite>,
        talkgroups: List<RrTalkgroup>,
        categories: List<RrTalkgroupCategory>,
        localCountyId: Int?,
    ): Playlist {
        val protocol = protocolForSystem(typeName)
        val channels = sites.mapNotNull { site ->
            val control = site.frequencies.filter { it.control }
            val alternates = site.frequencies.filter { it.alternate && !it.control }
            val ordered = (control + alternates).ifEmpty { site.frequencies }.map { it.mhz.movePointRight(6).toLong() }.distinct()
            if (ordered.isEmpty()) return@mapNotNull null
            val name = listOf(site.number.takeIf { it > 0 }?.let { "Site $it" }, site.description.takeIf { it.isNotEmpty() })
                .filterNotNull().joinToString(" · ").ifEmpty { "Site ${site.id}" }
            val simulcast = protocol == ChannelProtocol.P25 && SIMULCAST.any { site.modulation.contains(it, ignoreCase = true) }
            PlaylistChannel(
                name = name,
                system = systemName,
                site = site.countyName,
                enabled = protocol.supported && (localCountyId == null || site.countyId == localCountyId),
                frequencies = ordered,
                protocol = protocol,
                decoderType = protocol.decoderType,
                modulation = if (protocol == ChannelProtocol.P25) (if (simulcast) "LSM" else "C4FM") else null,
                aliasList = systemName,
            )
        }
        val categoryNames = categories.associate { it.id to it.name }
        val aliases = talkgroups.filter { it.decimal > 0 }.map { tg ->
            PlaylistAlias(
                name = tg.alpha.ifEmpty { tg.description }.ifEmpty { "TG ${tg.decimal}" },
                list = systemName,
                group = categoryNames[tg.categoryId],
                color = null,
                talkgroups = listOf(tg.decimal..tg.decimal),
                radios = emptyList(),
                priority = null,
                record = false,
                protocol = if (protocol == ChannelProtocol.DMR) "DMR" else "APCO25",
            )
        }
        return Playlist(channels, aliases)
    }

    /** Conventional frequencies from one county subcategory, named by their alpha tags. */
    fun conventional(system: String, site: String, frequencies: List<RrFrequency>, modeName: (String) -> String): Playlist {
        val channels = frequencies.map { f ->
            val protocol = protocolForMode(modeName(f.mode))
            PlaylistChannel(
                name = f.alpha.ifEmpty { f.description }.ifEmpty { "%.4f MHz".format(f.mhz.toDouble()) },
                system = system,
                site = site,
                enabled = protocol.supported && !f.encrypted,
                frequencies = listOf(f.mhz.movePointRight(6).toLong()),
                protocol = protocol,
                decoderType = protocol.decoderType,
                modulation = if (protocol == ChannelProtocol.P25) "C4FM" else null,
                aliasList = null,
            )
        }
        return Playlist(channels, emptyList())
    }

    private val SIMULCAST = listOf("LSM", "CQPSK", "simulcast")
}
