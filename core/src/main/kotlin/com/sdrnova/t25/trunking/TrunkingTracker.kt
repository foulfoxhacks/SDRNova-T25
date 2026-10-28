package com.sdrnova.t25.trunking

import com.sdrnova.t25.p25.AdjacentSite
import com.sdrnova.t25.p25.Channel
import com.sdrnova.t25.p25.GroupAffiliationResponse
import com.sdrnova.t25.p25.GroupVoiceGrant
import com.sdrnova.t25.p25.GroupVoiceGrantUpdate
import com.sdrnova.t25.p25.GroupVoiceGrantUpdateExplicit
import com.sdrnova.t25.p25.IdentifierUpdate
import com.sdrnova.t25.p25.NetworkStatus
import com.sdrnova.t25.p25.OtherTsbk
import com.sdrnova.t25.p25.RfssStatus
import com.sdrnova.t25.p25.SecondaryControlChannel
import com.sdrnova.t25.p25.ServiceOptions
import com.sdrnova.t25.p25.TsbkMessage
import com.sdrnova.t25.p25.UnitRegistrationResponse
import com.sdrnova.t25.p25.UnitToUnitGrant

data class SiteInfo(
    val wacn: Int? = null,
    val system: Int? = null,
    val rfss: Int? = null,
    val site: Int? = null,
    val lra: Int? = null,
    val controlChannelHz: Long? = null,
)

data class Call(
    val talkgroup: Int,
    val source: Int?,
    val frequencyHz: Long?,
    val channel: String,
    val slot: Int?,
    val emergency: Boolean,
    val encrypted: Boolean,
    val unitToUnit: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
)

data class Neighbor(val rfss: Int, val site: Int, val system: Int, val frequencyHz: Long?, val lastSeen: Long)

/**
 * Follows a P25 control channel the way sdrtrunk's traffic channel manager does, minus the tuning: it learns the band
 * plan from identifier updates, keeps the site identity, and turns channel grants into a list of calls in progress.
 * Not thread-safe; feed and read it from one thread.
 */
class TrunkingTracker(private val now: () -> Long = System::currentTimeMillis) {
    private val bands = HashMap<Int, IdentifierUpdate>()
    private val active = LinkedHashMap<Long, Call>()
    private val history = ArrayDeque<Call>()
    private val neighbors = LinkedHashMap<Int, Neighbor>()

    var site = SiteInfo()
        private set

    fun frequencyOf(channel: Channel): Long? = bands[channel.band]?.downlinkHz(channel.number)

    fun bandPlan(): List<IdentifierUpdate> = bands.values.sortedBy { it.identifier }

    fun neighbors(): List<Neighbor> = neighbors.values.toList()

    /** Updates state from [message] and returns a one-line description for the message log. */
    fun onMessage(message: TsbkMessage): String = when (message) {
        is GroupVoiceGrant -> {
            noteCall(message.group, message.source, message.channel, message.options, unitToUnit = false)
            "Grant TG ${message.group} from ${unit(message.source)} on ${describe(message.channel)}${flags(message.options)}"
        }
        is GroupVoiceGrantUpdate -> {
            message.updates.forEach { noteCall(it.group, null, it.channel, null, unitToUnit = false) }
            "Update " + message.updates.joinToString(", ") { "TG ${it.group} on ${describe(it.channel)}" }
        }
        is GroupVoiceGrantUpdateExplicit -> {
            noteCall(message.group, null, message.channel, message.options, unitToUnit = false)
            "Update TG ${message.group} on ${describe(message.channel)}${flags(message.options)}"
        }
        is UnitToUnitGrant -> {
            noteCall(message.target, message.source, message.channel, null, unitToUnit = true)
            "Private call ${unit(message.source)} to ${unit(message.target)} on ${describe(message.channel)}"
        }
        is IdentifierUpdate -> {
            bands[message.identifier] = message
            val tdma = if (message.slotsPerCarrier > 1) " TDMA x${message.slotsPerCarrier}" else ""
            "Band ${message.identifier}: base ${mhz(message.baseHz)} step ${message.spacingHz / 1000.0} kHz$tdma"
        }
        is RfssStatus -> {
            site = site.copy(
                system = message.system, rfss = message.rfss, site = message.site, lra = message.lra,
                controlChannelHz = frequencyOf(message.channel) ?: site.controlChannelHz,
            )
            "Site RFSS ${message.rfss} site ${message.site} sys %03X, control ${describe(message.channel)}"
                .format(message.system)
        }
        is NetworkStatus -> {
            site = site.copy(wacn = message.wacn, system = message.system)
            "Network WACN %05X sys %03X".format(message.wacn, message.system)
        }
        is AdjacentSite -> {
            val key = (message.rfss shl 8) or message.site
            neighbors[key] = Neighbor(message.rfss, message.site, message.system, frequencyOf(message.channel), now())
            "Neighbor RFSS ${message.rfss} site ${message.site} on ${describe(message.channel)}"
        }
        is SecondaryControlChannel ->
            "Secondary control " + message.channels.joinToString(", ") { describe(it) }
        is GroupAffiliationResponse -> "Affiliation ${unit(message.target)} to TG ${message.group}"
        is UnitRegistrationResponse -> "Registration ${unit(message.sourceAddress)}"
        is OtherTsbk -> message.name
    }

    /** Calls heard within the last few seconds; older ones move to [recentCalls]. */
    fun activeCalls(): List<Call> {
        val cutoff = now() - CALL_TIMEOUT_MS
        val iterator = active.values.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            if (call.lastSeen < cutoff) {
                iterator.remove()
                history.addFirst(call)
                while (history.size > HISTORY_SIZE) history.removeLast()
            }
        }
        return active.values.sortedByDescending { it.lastSeen }
    }

    fun recentCalls(): List<Call> = history.toList()

    private fun noteCall(id: Int, source: Int?, channel: Channel, options: ServiceOptions?, unitToUnit: Boolean) {
        val key = (if (unitToUnit) 1L shl 32 else 0L) or id.toLong()
        val time = now()
        val band = bands[channel.band]
        val slot = band?.takeIf { it.slotsPerCarrier > 1 }?.let { channel.number % it.slotsPerCarrier }
        val existing = active[key]
        active[key] = Call(
            talkgroup = id,
            source = source ?: existing?.source,
            frequencyHz = band?.downlinkHz(channel.number) ?: existing?.frequencyHz,
            channel = channel.toString(),
            slot = slot,
            emergency = options?.emergency ?: existing?.emergency ?: false,
            encrypted = options?.encrypted ?: existing?.encrypted ?: false,
            unitToUnit = unitToUnit,
            firstSeen = existing?.firstSeen ?: time,
            lastSeen = time,
        )
    }

    private fun describe(channel: Channel): String {
        val band = bands[channel.band] ?: return "ch $channel"
        val slot = if (band.slotsPerCarrier > 1) " TS${channel.number % band.slotsPerCarrier + 1}" else ""
        return mhz(band.downlinkHz(channel.number)) + slot
    }

    private fun flags(options: ServiceOptions): String =
        (if (options.emergency) " EMERGENCY" else "") + (if (options.encrypted) " ENCRYPTED" else "")

    private fun unit(id: Int) = if (id == 0) "?" else id.toString()

    companion object {
        const val CALL_TIMEOUT_MS = 4_000L
        const val HISTORY_SIZE = 100

        fun mhz(hz: Long): String = "%.5f MHz".format(hz / 1e6)
    }
}
