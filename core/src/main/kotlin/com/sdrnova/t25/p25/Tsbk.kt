package com.sdrnova.t25.p25

/**
 * One 96-bit trunking signalling block: last-block flag, protected flag, 6-bit opcode, 8-bit manufacturer ID,
 * 64 bits of arguments and a CRC-16. Field offsets below count from the first transmitted bit, as in sdrtrunk.
 */
class Tsbk(val bytes: ByteArray, val correctedBits: Int = 0) {
    val lastBlock: Boolean get() = bit(0)
    val isProtected: Boolean get() = bit(1)
    val opcode: Int get() = field(2, 6)
    val vendor: Int get() = field(8, 8)
    /** Like sdrtrunk, accepts the CRC with or without the final inversion. */
    val crcValid: Boolean get() = (Crc16.ccitt(bytes, 80) xor field(80, 16)).let { it == 0 || it == 0xFFFF }

    fun bit(index: Int): Boolean = (bytes[index / 8].toInt() ushr (7 - index % 8)) and 1 == 1

    fun field(start: Int, length: Int): Int = fieldLong(start, length).toInt()

    fun fieldLong(start: Int, length: Int): Long {
        var value = 0L
        for (i in start until start + length) value = (value shl 1) or (if (bit(i)) 1L else 0L)
        return value
    }

    fun hex(): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/** A 4-bit frequency band identifier plus 12-bit channel number, resolved through identifier updates. */
data class Channel(val band: Int, val number: Int) {
    override fun toString() = "$band-$number"
}

data class ServiceOptions(val value: Int) {
    val emergency: Boolean get() = value and 0x80 != 0
    val encrypted: Boolean get() = value and 0x40 != 0
    val priority: Int get() = value and 0x07
}

sealed interface TsbkMessage {
    val tsbk: Tsbk
}

data class GroupVoiceGrant(
    override val tsbk: Tsbk,
    val options: ServiceOptions,
    val channel: Channel,
    val group: Int,
    val source: Int,
) : TsbkMessage

data class GrantUpdate(val channel: Channel, val group: Int)

data class GroupVoiceGrantUpdate(override val tsbk: Tsbk, val updates: List<GrantUpdate>) : TsbkMessage

data class GroupVoiceGrantUpdateExplicit(
    override val tsbk: Tsbk,
    val options: ServiceOptions,
    val channel: Channel,
    val group: Int,
) : TsbkMessage

data class UnitToUnitGrant(
    override val tsbk: Tsbk,
    val channel: Channel,
    val target: Int,
    val source: Int,
) : TsbkMessage

data class IdentifierUpdate(
    override val tsbk: Tsbk,
    val identifier: Int,
    val baseHz: Long,
    val spacingHz: Long,
    val transmitOffsetHz: Long,
    val bandwidthHz: Int,
    val slotsPerCarrier: Int,
) : TsbkMessage {
    fun downlinkHz(channelNumber: Int): Long = baseHz + spacingHz * (channelNumber / slotsPerCarrier)
}

data class RfssStatus(
    override val tsbk: Tsbk,
    val lra: Int,
    val system: Int,
    val rfss: Int,
    val site: Int,
    val channel: Channel,
    val serviceClass: Int,
) : TsbkMessage

data class NetworkStatus(
    override val tsbk: Tsbk,
    val lra: Int,
    val wacn: Int,
    val system: Int,
    val channel: Channel,
    val serviceClass: Int,
) : TsbkMessage

data class AdjacentSite(
    override val tsbk: Tsbk,
    val lra: Int,
    val system: Int,
    val rfss: Int,
    val site: Int,
    val channel: Channel,
    val serviceClass: Int,
) : TsbkMessage

data class SecondaryControlChannel(
    override val tsbk: Tsbk,
    val rfss: Int,
    val site: Int,
    val channels: List<Channel>,
) : TsbkMessage

data class GroupAffiliationResponse(
    override val tsbk: Tsbk,
    val response: Int,
    val group: Int,
    val target: Int,
) : TsbkMessage

data class UnitRegistrationResponse(
    override val tsbk: Tsbk,
    val response: Int,
    val system: Int,
    val sourceId: Int,
    val sourceAddress: Int,
) : TsbkMessage

data class OtherTsbk(override val tsbk: Tsbk, val name: String) : TsbkMessage

object TsbkParser {
    fun parse(t: Tsbk): TsbkMessage =
        if (t.vendor == 0) parseStandard(t) else OtherTsbk(t, "${vendorName(t.vendor)} opcode 0x%02X".format(t.opcode))

    private fun parseStandard(t: Tsbk): TsbkMessage = when (t.opcode) {
        0x00 -> GroupVoiceGrant(
            t, ServiceOptions(t.field(16, 8)), Channel(t.field(24, 4), t.field(28, 12)),
            group = t.field(40, 16), source = t.field(56, 24),
        )
        0x02 -> {
            val first = GrantUpdate(Channel(t.field(16, 4), t.field(20, 12)), t.field(32, 16))
            val second = GrantUpdate(Channel(t.field(48, 4), t.field(52, 12)), t.field(64, 16))
            val updates = if (second.group != 0 && second.group != first.group) listOf(first, second) else listOf(first)
            GroupVoiceGrantUpdate(t, updates)
        }
        0x03 -> GroupVoiceGrantUpdateExplicit(
            t, ServiceOptions(t.field(16, 8)), Channel(t.field(32, 4), t.field(36, 12)), group = t.field(64, 16),
        )
        0x04 -> UnitToUnitGrant(
            t, Channel(t.field(16, 4), t.field(20, 12)), target = t.field(32, 24), source = t.field(56, 24),
        )
        0x28 -> GroupAffiliationResponse(t, response = t.field(22, 2), group = t.field(40, 16), target = t.field(56, 24))
        0x2C -> UnitRegistrationResponse(
            t, response = t.field(18, 2), system = t.field(20, 12), sourceId = t.field(32, 24),
            sourceAddress = t.field(56, 24),
        )
        0x33 -> identifierUpdateTdma(t)
        0x34 -> identifierUpdateVhfUhf(t)
        0x3D -> identifierUpdate(t)
        0x39 -> SecondaryControlChannel(
            t, rfss = t.field(16, 8), site = t.field(24, 8),
            channels = listOf(Channel(t.field(32, 4), t.field(36, 12)), Channel(t.field(56, 4), t.field(60, 12)))
                .distinct(),
        )
        0x3A -> RfssStatus(
            t, lra = t.field(16, 8), system = t.field(28, 12), rfss = t.field(40, 8), site = t.field(48, 8),
            channel = Channel(t.field(56, 4), t.field(60, 12)), serviceClass = t.field(72, 8),
        )
        0x3B -> NetworkStatus(
            t, lra = t.field(16, 8), wacn = t.field(24, 20), system = t.field(44, 12),
            channel = Channel(t.field(56, 4), t.field(60, 12)), serviceClass = t.field(72, 8),
        )
        0x3C -> AdjacentSite(
            t, lra = t.field(16, 8), system = t.field(28, 12), rfss = t.field(40, 8), site = t.field(48, 8),
            channel = Channel(t.field(56, 4), t.field(60, 12)), serviceClass = t.field(72, 8),
        )
        else -> OtherTsbk(t, OPCODE_NAMES[t.opcode])
    }

    /** IDEN_UP: 800/900 MHz style bands with a 9-bit bandwidth and transmit offset in 250 kHz steps. */
    private fun identifierUpdate(t: Tsbk): IdentifierUpdate {
        val spacing = t.field(38, 10) * 125L
        val offset = t.field(30, 8) * 250_000L
        return IdentifierUpdate(
            t, t.field(16, 4), baseHz = t.fieldLong(48, 32) * 5, spacingHz = spacing,
            transmitOffsetHz = if (t.bit(29)) offset else -offset, bandwidthHz = t.field(20, 9) * 125,
            slotsPerCarrier = 1,
        )
    }

    /** IDEN_UP_VU: VHF/UHF bands, transmit offset counted in channel spacings. */
    private fun identifierUpdateVhfUhf(t: Tsbk): IdentifierUpdate {
        val spacing = t.field(38, 10) * 125L
        val offset = t.field(25, 13) * spacing
        val bandwidth = when (t.field(20, 4)) {
            4 -> 6250
            5 -> 12500
            else -> 0
        }
        return IdentifierUpdate(
            t, t.field(16, 4), baseHz = t.fieldLong(48, 32) * 5, spacingHz = spacing,
            transmitOffsetHz = if (t.bit(24)) offset else -offset, bandwidthHz = bandwidth, slotsPerCarrier = 1,
        )
    }

    /** IDEN_UP_TDMA: the channel type sets how many Phase 2 timeslots share each carrier. */
    private fun identifierUpdateTdma(t: Tsbk): IdentifierUpdate {
        val spacing = t.field(38, 10) * 125L
        val offset = t.field(25, 13) * spacing
        val type = t.field(20, 4)
        return IdentifierUpdate(
            t, t.field(16, 4), baseHz = t.fieldLong(48, 32) * 5, spacingHz = spacing,
            transmitOffsetHz = if (t.bit(24)) offset else -offset,
            bandwidthHz = CHANNEL_TYPE_BANDWIDTH.getOrElse(type) { 0 },
            slotsPerCarrier = CHANNEL_TYPE_SLOTS.getOrElse(type) { 1 },
        )
    }

    fun vendorName(vendor: Int): String = when (vendor) {
        0x00, 0x01 -> "Standard"
        0x90 -> "Motorola"
        0xA4 -> "L3Harris"
        0xD8 -> "Tait"
        else -> "Vendor 0x%02X".format(vendor)
    }

    private val CHANNEL_TYPE_SLOTS = intArrayOf(1, 1, 1, 2, 4, 2)
    private val CHANNEL_TYPE_BANDWIDTH = intArrayOf(12500, 12500, 6250, 12500, 25000, 12500)

    /** Outbound standard opcode labels, as named in sdrtrunk's Opcode enum. */
    val OPCODE_NAMES = arrayOf(
        "GRP_VCH_GRANT", "RESERVED_01", "GRP_VCH_GRNT_UPD", "GRP_VCH_GRNT_UPX",
        "UU_VCH_GRANT", "UU_ANS_REQ", "UU_VCH_GRANT_UPD", "RESERVED_07",
        "TEL_INT_VCH_GRNT", "TEL_INT_VCH_GRNU", "TEL_INT_ANS_RQST", "RESERVED_0B",
        "RESERVED_0C", "RESERVED_0D", "RESERVED_0E", "RESERVED_0F",
        "IND_DCH_GRANT", "GRP_DCH_GRANT", "GRP_DCH_ANNOUNCE", "GRP_DCH_ANNC_EXP",
        "SNDCP_DCH_GRANT", "SNDCP_DCH_PAG_RQ", "SNDCP_DCH_ANN_EX", "RESERVED_17",
        "STATUS_UPDATE", "RESERVED_19", "STATUS_QUERY", "RESERVED_1B",
        "MESSAGE_UPDATE", "RADIO_MONITR_CMD", "RADIO_MON_ENH_CMD", "CALL_ALERT",
        "ACK_RESPONSE_FNE", "QUEUED_RESPONSE", "RESERVED_22", "RESERVED_23",
        "EXTNDED_FUNC_CMD", "RESERVED_25", "RESERVED_26", "DENY_RESPONSE",
        "GRP_AFFIL_RESP", "SCCB_CCH_BCST_EX", "GRP_AFFIL_QUERY", "LOCN_REG_RESPONS",
        "UNIT_REG_RESPONS", "UNIT_REG_COMMAND", "AUTH_COMMAND", "DE_REGIST_ACK",
        "TDMA_SYNC_BCST", "AUTH_DEMAND", "AUTH_FNE_RESP", "IDEN_UPDATE_TDMA",
        "IDEN_UPDATE_VUHF", "TIME_DATE_ANNOUN", "ROAM_ADDR_CMD", "ROAM_ADDR_UPDATE",
        "SYS_SVC_BCAST", "SEC_CCH_BROADCST", "RFSS_STATUS_BCST", "NET_STATUS_BCAST",
        "ADJ_SITE_STATUS", "IDEN_UPDATE", "ADJ_STS_BCST_UNC", "RESERVED_3F",
    )
}
