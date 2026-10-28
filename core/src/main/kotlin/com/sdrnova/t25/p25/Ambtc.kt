package com.sdrnova.t25.p25

/**
 * Alternate multi-block trunking control: a PDU whose header carries a TSBK-style opcode and whose data blocks carry
 * the rest, used for grants that need explicit (uplink and downlink) channels. Field offsets follow sdrtrunk's
 * AMBTCHeader and AMBTC message classes.
 */
object Ambtc {
    /** PDU header format value for AMBTC. */
    const val FORMAT = 0x17

    fun format(header: Tsbk): Int = header.field(3, 5)

    fun blocksToFollow(header: Tsbk): Int = header.field(49, 7)

    fun opcode(header: Tsbk): Int = header.field(58, 6)

    /** Unlike a TSBK, a PDU header carries its manufacturer ID in the third octet (the second holds the SAP). */
    fun vendor(header: Tsbk): Int = header.field(16, 8)

    /** [header] is the CRC-checked header block; [blocks] are the decoded 12-byte data blocks. */
    fun parse(header: Tsbk, blocks: List<ByteArray>): TsbkMessage {
        val opcode = opcode(header)
        val vendor = vendor(header)
        if (vendor != 0) {
            return OtherTsbk(header, "AMBTC ${TsbkParser.vendorName(vendor)} opcode 0x%02X".format(opcode))
        }
        return when {
            opcode == 0x00 && blocks.isNotEmpty() -> {
                val block = blocks[0]
                GroupVoiceGrant(
                    header,
                    ServiceOptions(header.field(64, 8)),
                    Channel(Bits.field(block, 16, 4).toInt(), Bits.field(block, 20, 12).toInt()),
                    group = Bits.field(block, 48, 16).toInt(),
                    source = header.field(24, 24),
                )
            }
            else -> OtherTsbk(header, "AMBTC ${TsbkParser.OPCODE_NAMES[opcode]}")
        }
    }
}
