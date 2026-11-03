package com.sdrnova.t25.dmr

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Where a DMR trunked system's logical channels are. Grants only give a logical channel number (LCN), so following
 * them needs this table, as in sdrtrunk's DMR timeslot map and DSD+'s frequencies file. LCNs are 1-based and each
 * covers both timeslots of one repeater.
 */
object DmrChannelMap {
    /**
     * Reads "LCN, frequency" lines: `1, 451.1125`, `2 = 451.6125 MHz` or `3 452137500`. Frequencies with a decimal
     * point are MHz, whole numbers are Hz. Blank lines and `;` or `#` comments are skipped, as are lines that don't fit.
     */
    fun parse(text: String): Map<Int, Long> {
        val map = LinkedHashMap<Int, Long>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(';').substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.lowercase(Locale.US).replace("mhz", " ").split(',', '=', ' ', '\t').filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val lcn = parts[0].toIntOrNull() ?: continue
            val hz = frequency(parts[1]) ?: continue
            if (lcn > 0) map[lcn] = hz
        }
        return map
    }

    fun format(map: Map<Int, Long>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { (lcn, hz) -> "$lcn, %.5f".format(Locale.US, hz / 1e6) }

    private fun frequency(text: String): Long? {
        val value = text.toDoubleOrNull() ?: return null
        val hz = if ('.' in text || value < 10_000) value * 1e6 else value
        return hz.roundToLong().takeIf { it in 24_000_000L..1_800_000_000L }
    }
}
