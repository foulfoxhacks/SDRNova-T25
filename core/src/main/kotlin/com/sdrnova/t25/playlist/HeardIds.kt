package com.sdrnova.t25.playlist

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DSD+-style talkgroup and radio lists that fill themselves in: every group and radio heard gets a line with a hit
 * count and last-heard time, ready to be named, prioritised or locked out. They read and write DSD+'s
 * `DSDPlus.groups` and `DSDPlus.radios` formats, so the lists move between DSD+ on the desktop and the phone:
 *
 *     protocol, networkID, group, priority, override, hits, timestamp, "alias"
 *     protocol, networkID, group, radio, priority, override, hits, timestamp, "alias"
 *
 * Thread-safe: the radio records hits while the screens edit.
 */
class HeardIds {
    data class Entry(
        /** DSD+ protocol name: P25 or DMR. */
        val protocol: String,
        /** DSD+ network ID: WACN.system for trunked P25, the NAC for conventional P25, the colour code for DMR. */
        val network: String,
        /** The talkgroup, or for a radio its ID. */
        val id: Int,
        /** Radios only: the group the radio was last heard on. */
        val group: Int = 0,
        /** Lower is more important, as in DSD+; [DEFAULT_PRIORITY] means none was set. */
        val priority: Int = DEFAULT_PRIORITY,
        val lockout: Boolean = false,
        val hits: Int = 0,
        val lastHeard: Long = 0,
        val alias: String = "",
    ) {
        val key: String get() = "$protocol/$network/$id"
    }

    data class Snapshot(val groups: List<Entry>, val radios: List<Entry>) {
        companion object {
            val EMPTY = Snapshot(emptyList(), emptyList())
        }
    }

    private val groups = LinkedHashMap<String, Entry>()
    private val radios = LinkedHashMap<String, Entry>()

    /** Most recently heard first. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        groups.values.sortedByDescending { it.lastHeard },
        radios.values.sortedByDescending { it.lastHeard },
    )

    /** Counts a call on [group]. */
    @Synchronized
    fun heardGroup(protocol: String, network: String, group: Int, time: Long): Entry {
        val key = "$protocol/$network/$group"
        val entry = (groups[key] ?: Entry(protocol, network, group)).let { it.copy(hits = it.hits + 1, lastHeard = time) }
        groups[key] = entry
        return entry
    }

    /** Counts a transmission by [radio], heard talking on [group]. */
    @Synchronized
    fun heardRadio(protocol: String, network: String, radio: Int, group: Int, time: Long): Entry {
        val key = "$protocol/$network/$radio"
        val entry = (radios[key] ?: Entry(protocol, network, radio)).let {
            it.copy(group = group, hits = it.hits + 1, lastHeard = time)
        }
        radios[key] = entry
        return entry
    }

    /** Edits (or creates) the group line for [id], e.g. to name it or lock it out. */
    @Synchronized
    fun updateGroup(protocol: String, network: String, id: Int, transform: (Entry) -> Entry): Entry {
        val key = "$protocol/$network/$id"
        val entry = transform(groups[key] ?: Entry(protocol, network, id))
        groups[key] = entry
        return entry
    }

    @Synchronized
    fun updateRadio(protocol: String, network: String, id: Int, transform: (Entry) -> Entry): Entry {
        val key = "$protocol/$network/$id"
        val entry = transform(radios[key] ?: Entry(protocol, network, id))
        radios[key] = entry
        return entry
    }

    @Synchronized
    fun removeGroup(key: String) {
        groups.remove(key)
    }

    @Synchronized
    fun removeRadio(key: String) {
        radios.remove(key)
    }

    /**
     * Folds in a DSD+ groups or radios file (either kind; each line says which by its column count). Names, priorities
     * and lockouts from the file win; hit counts and last-heard times keep whichever is larger. Returns lines read.
     */
    @Synchronized
    fun import(text: String): Int {
        var count = 0
        for (line in text.lineSequence()) {
            val parsed = parseLine(line) ?: continue
            val (isRadio, entry) = parsed
            val map = if (isRadio) radios else groups
            val existing = map[entry.key]
            map[entry.key] = if (existing == null) entry else entry.copy(
                hits = maxOf(existing.hits, entry.hits),
                lastHeard = maxOf(existing.lastHeard, entry.lastHeard),
                group = if (entry.group != 0) entry.group else existing.group,
                alias = entry.alias.ifEmpty { existing.alias },
            )
            count++
        }
        return count
    }

    @Synchronized
    fun groupsFile(): String = buildString {
        append("; DSDPlus.groups written by SDRNova T25\n")
        append("; protocol, networkID, group, priority, override, hits, timestamp, \"group alias\"\n")
        for (e in groups.values.sortedWith(compareBy({ it.protocol }, { it.network }, { it.id }))) {
            append("${e.protocol}, ${e.network}, ${e.id}, ${e.priority}, ${override(e)}, ${e.hits}, ${stamp(e.lastHeard)}, ")
            append(quote(e.alias)).append('\n')
        }
    }

    @Synchronized
    fun radiosFile(): String = buildString {
        append("; DSDPlus.radios written by SDRNova T25\n")
        append("; protocol, networkID, group, radio, priority, override, hits, timestamp, \"radio alias\"\n")
        for (e in radios.values.sortedWith(compareBy({ it.protocol }, { it.network }, { it.id }))) {
            append("${e.protocol}, ${e.network}, ${e.group}, ${e.id}, ${e.priority}, ${override(e)}, ${e.hits}, ")
            append(stamp(e.lastHeard)).append(", ").append(quote(e.alias)).append('\n')
        }
    }

    companion object {
        /** DSD+ starts every new line at 50. */
        const val DEFAULT_PRIORITY = 50
        private const val LOCKOUT = "L/O"
        private const val NORMAL = "Normal"

        private fun override(entry: Entry) = if (entry.lockout) LOCKOUT else NORMAL

        private fun quote(alias: String) = "\"" + alias.replace("\"", "'") + "\""

        private fun stamp(time: Long): String =
            if (time <= 0) "0000/00/00 00:00" else SimpleDateFormat(STAMP, Locale.US).format(java.util.Date(time))

        private const val STAMP = "yyyy/MM/dd HH:mm"
        private val STAMPS = listOf(STAMP, "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss")

        private fun parseStamp(text: String): Long {
            for (pattern in STAMPS) {
                try {
                    return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)?.time ?: continue
                } catch (_: ParseException) {
                    continue
                }
            }
            return 0
        }

        /** One line of either file: (isRadio, entry), or null for comments, blanks and lines that don't fit. */
        internal fun parseLine(line: String): Pair<Boolean, Entry>? {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith(";") || text.startsWith("#")) return null
            // The alias is quoted and may hold commas, so split it off first.
            val quote = text.indexOf('"')
            val alias = if (quote >= 0) text.substring(quote + 1).substringBeforeLast('"') else ""
            val fields = (if (quote >= 0) text.substring(0, quote) else text).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val isRadio = when (fields.size) {
                7 -> false
                8 -> true
                else -> return null
            }
            val protocol = fields[0].uppercase(Locale.US)
            val network = fields[1]
            val numbers = if (isRadio) fields.subList(2, 5) else fields.subList(2, 4)
            val group = numbers[0].toIntOrNull() ?: return null
            val radio = if (isRadio) numbers[1].toIntOrNull() ?: return null else 0
            val priority = numbers.last().toIntOrNull() ?: DEFAULT_PRIORITY
            val rest = if (isRadio) fields.subList(5, 8) else fields.subList(4, 7)
            val lockout = rest[0].uppercase(Locale.US).let { it.startsWith("L") && it != NORMAL.uppercase(Locale.US) }
            val hits = rest[1].toIntOrNull() ?: 0
            val time = parseStamp(rest[2])
            val entry = if (isRadio) {
                Entry(protocol, network, radio, group, priority, lockout, hits, time, alias)
            } else {
                Entry(protocol, network, group, 0, priority, lockout, hits, time, alias)
            }
            return isRadio to entry
        }
    }
}
