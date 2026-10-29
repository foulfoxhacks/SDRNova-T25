package com.sdrnova.t25.playlist

import com.sdrnova.t25.receiver.CallPolicy

/**
 * Talkgroup and radio lookups for the radio: names, priority, lockout and recording, from playlist aliases plus the
 * quick "id,name" entries typed in Setup (which win when both name the same talkgroup).
 */
class AliasBook(aliases: List<PlaylistAlias>, names: Map<Int, String> = emptyMap()) : CallPolicy {
    private val talkgroups = HashMap<Int, PlaylistAlias>()
    private val talkgroupRanges = ArrayList<Pair<IntRange, PlaylistAlias>>()
    private val radios = HashMap<Int, PlaylistAlias>()
    private val radioRanges = ArrayList<Pair<IntRange, PlaylistAlias>>()

    init {
        for (alias in aliases) {
            for (range in alias.talkgroups) {
                if (range.first == range.last) talkgroups.putIfAbsent(range.first, alias) else talkgroupRanges += range to alias
            }
            for (range in alias.radios) {
                if (range.first == range.last) radios.putIfAbsent(range.first, alias) else radioRanges += range to alias
            }
        }
        for ((id, name) in names) {
            talkgroups[id] = talkgroups[id]?.copy(name = name)
                ?: PlaylistAlias(name, null, null, null, listOf(id..id), emptyList(), priority = null, record = false)
        }
    }

    val isEmpty: Boolean get() = talkgroups.isEmpty() && talkgroupRanges.isEmpty() && radios.isEmpty() && radioRanges.isEmpty()

    fun alias(talkgroup: Int): PlaylistAlias? =
        talkgroups[talkgroup] ?: talkgroupRanges.firstOrNull { talkgroup in it.first }?.second

    fun name(talkgroup: Int): String? = alias(talkgroup)?.name

    fun radioName(radio: Int): String? = (radios[radio] ?: radioRanges.firstOrNull { radio in it.first }?.second)?.name

    /** sdrtrunk's "do not monitor": never followed or played, though grants still show in the log. */
    fun lockedOut(talkgroup: Int): Boolean = alias(talkgroup)?.lockedOut == true

    /** 1 is highest; talkgroups without a priority rank below every listed one. */
    fun priority(talkgroup: Int): Int = alias(talkgroup)?.priority?.takeIf { it > 0 } ?: UNLISTED_PRIORITY

    fun record(talkgroup: Int): Boolean = alias(talkgroup)?.record == true

    override fun follow(talkgroup: Int, unitToUnit: Boolean): Boolean = unitToUnit || !lockedOut(talkgroup)

    override fun priority(talkgroup: Int, unitToUnit: Boolean): Int =
        if (unitToUnit) UNLISTED_PRIORITY else priority(talkgroup)

    companion object {
        const val UNLISTED_PRIORITY = 101
        val EMPTY = AliasBook(emptyList())
    }
}
