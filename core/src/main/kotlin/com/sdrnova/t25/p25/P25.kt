package com.sdrnova.t25.p25

import kotlin.math.PI

/** Constants shared by the P25 Phase 1 receive chain. */
object P25 {
    const val SYMBOL_RATE = 4800.0

    /** 48-bit frame sync, sent as 24 dibits that are all +3 or -3 symbols. */
    const val SYNC_PATTERN = 0x5575F5FF77FFL
    val SYNC_DIBITS = IntArray(24) { ((SYNC_PATTERN ushr (46 - 2 * it)) and 3L).toInt() }

    /** Phase a symbol turns the carrier through: +3 is 1800 Hz held for one 4800 baud symbol, or 3π/4. */
    val OUTER_PHASE = (3 * PI / 4).toFloat()
    val INNER_PHASE = (PI / 4).toFloat()
    private val BOUNDARY = (PI / 2).toFloat()

    val SYNC_SYMBOLS = FloatArray(24) { idealPhase(SYNC_DIBITS[it]) }

    const val DUID_HDU = 0
    const val DUID_TDU = 3
    const val DUID_LDU1 = 5
    const val DUID_TSDU = 7
    const val DUID_LDU2 = 10
    const val DUID_PDU = 12
    const val DUID_TDULC = 15
    val VALID_DUIDS = intArrayOf(DUID_HDU, DUID_TDU, DUID_LDU1, DUID_TSDU, DUID_LDU2, DUID_PDU, DUID_TDULC)

    /** Dibit values follow TIA-102.BAAA: 01 = +3, 00 = +1, 10 = -1, 11 = -3. */
    fun idealPhase(dibit: Int): Float = when (dibit) {
        0 -> INNER_PHASE
        1 -> OUTER_PHASE
        2 -> -INNER_PHASE
        else -> -OUTER_PHASE
    }

    fun slice(soft: Float): Int = when {
        soft >= BOUNDARY -> 1
        soft >= 0f -> 0
        soft >= -BOUNDARY -> 2
        else -> 3
    }

    fun duidName(duid: Int): String = when (duid) {
        DUID_HDU -> "HDU"
        DUID_TDU -> "TDU"
        DUID_LDU1 -> "LDU1"
        DUID_TSDU -> "TSDU"
        DUID_LDU2 -> "LDU2"
        DUID_PDU -> "PDU"
        DUID_TDULC -> "TDULC"
        else -> "DUID$duid"
    }
}
