package com.sdrnova.t25.playlist

/** The live channel list, shared by the app screens, the radio and the web dashboard. */
interface PlaylistAccess {
    val playlist: Playlist

    /** Applies [transform] atomically, saves the result and returns it. */
    fun update(transform: (Playlist) -> Playlist): Playlist
}

/**
 * Adds [other]'s channels and aliases to this playlist, skipping ones already present, so the same RadioReference
 * system or sdrtrunk file can be imported twice without doubling up.
 */
fun Playlist.merge(other: Playlist): Playlist {
    val channelKeys = channels.mapTo(HashSet()) { it.key }
    val aliasKeys = aliases.mapTo(HashSet()) { it.mergeKey }
    return Playlist(
        channels + other.channels.filter { channelKeys.add(it.key) },
        aliases + other.aliases.filter { aliasKeys.add(it.mergeKey) },
    )
}

private val PlaylistAlias.mergeKey: String get() = "$list/$name/$talkgroups/$radios"

/** "1201, 9000-9099" as ranges; blank or malformed parts are skipped. */
fun parseIdRanges(text: String): List<IntRange> = text.split(',', ' ', ';').mapNotNull { part ->
    val trimmed = part.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val dash = trimmed.indexOf('-', startIndex = 1)
    if (dash > 0) {
        val low = trimmed.substring(0, dash).trim().toIntOrNull()
        val high = trimmed.substring(dash + 1).trim().toIntOrNull()
        if (low != null && high != null && low <= high) low..high else null
    } else {
        trimmed.toIntOrNull()?.let { it..it }
    }
}

fun formatIdRanges(ranges: List<IntRange>): String =
    ranges.joinToString(", ") { if (it.first == it.last) it.first.toString() else "${it.first}-${it.last}" }
