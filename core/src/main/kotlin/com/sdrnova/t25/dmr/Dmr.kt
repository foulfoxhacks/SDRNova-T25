package com.sdrnova.t25.dmr

import com.sdrnova.t25.p25.Bits

/** Labels for DMR control messages, from sdrtrunk's CSBK Opcode enum. */
object Dmr {
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
        // Tier III voice grants (sdrtrunk's ChannelGrant): logical channel, timeslot, target and source.
        if (csbk.opcode in 0x30..0x32) {
            val lcn = Bits.field(csbk.bits, 16, 12)
            val slot = Bits.get(csbk.bits, 28) + 1
            val target = Bits.field(csbk.bits, 32, 24)
            val source = Bits.field(csbk.bits, 56, 24)
            val who = if (csbk.opcode == 0x30) "radio $target" else "TG $target"
            return "$name: $who from $source on LCN $lcn TS$slot"
        }
        return name
    }
}
