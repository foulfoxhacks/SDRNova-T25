package com.sdrnova.t25.dmr

import com.sdrnova.t25.p25.Bits

/** A Tier III voice channel grant (sdrtrunk's ChannelGrant): logical channel and timeslot, target and source. */
data class DmrGrant(val lcn: Int, val timeslot: Int, val target: Int, val source: Int, val privateCall: Boolean)

/** Labels for DMR control messages, from sdrtrunk's CSBK Opcode enum. */
object Dmr {
    /** The voice grant in [csbk], or null if it isn't a standard (ETSI Tier III) private or talkgroup voice grant. */
    fun grant(csbk: DmrEvent.Csbk): DmrGrant? {
        if (csbk.vendor != 0 || csbk.opcode !in 0x30..0x32) return null
        return DmrGrant(
            lcn = Bits.field(csbk.bits, 16, 12).toInt(),
            timeslot = Bits.get(csbk.bits, 28) + 1,
            target = Bits.field(csbk.bits, 32, 24).toInt(),
            source = Bits.field(csbk.bits, 56, 24).toInt(),
            privateCall = csbk.opcode == 0x30,
        )
    }

    private val STANDARD_CSBK = mapOf(
        0x04 to "unit-to-unit voice request",
        0x05 to "unit-to-unit voice response",
        0x07 to "channel timing",
        0x19 to "Aloha",
        0x1C to "Ahoy",
        0x20 to "acknowledge",
        0x26 to "negative acknowledge",
        0x28 to "announcement",
        0x2A to "maintenance",
        0x2E to "clear",
        0x2F to "protect",
        0x30 to "private voice grant",
        0x31 to "talkgroup voice grant",
        0x32 to "broadcast talkgroup voice grant",
        0x33 to "private data grant",
        0x34 to "talkgroup data grant",
        0x35 to "duplex private voice grant",
        0x3D to "preamble",
    )

    fun describe(csbk: DmrEvent.Csbk): String {
        if (csbk.vendor != 0) {
            val vendor = when (csbk.vendor) {
                0x10 -> "Motorola"
                0x68 -> "Hytera"
                else -> "vendor 0x%02X".format(csbk.vendor)
            }
            return "$vendor CSBK 0x%02X".format(csbk.opcode)
        }
        val name = STANDARD_CSBK[csbk.opcode] ?: "CSBK 0x%02X".format(csbk.opcode)
        grant(csbk)?.let { grant ->
            val who = if (grant.privateCall) "radio ${grant.target}" else "TG ${grant.target}"
            return "$name: $who from ${grant.source} on LCN ${grant.lcn} TS${grant.timeslot}"
        }
        return name
    }
}
