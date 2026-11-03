package com.sdrnova.t25.trunking

/**
 * DSD+-style control channel hunting. Sites move their control channel (and simulcast sites rotate it), so when the
 * one being monitored goes quiet, try the others the site has announced, or the playlist lists, in turn until one has
 * sync, then stay. Times are stream time.
 */
class ControlChannelHunter(
    home: Long,
    /** Silence on a channel that had sync before giving up on it. */
    var quietMs: Long = 4_000,
    /** How long to wait for a first sync on a channel just tried. */
    var tryMs: Long = 3_000,
) {
    private val candidates = arrayListOf(home)
    private var arrivedMs = 0L
    private var lastSyncMs: Long? = null

    var current: Long = home
        private set

    val known: List<Long> get() = candidates.toList()

    /** Adds control channels from the playlist or the site's broadcasts; order of discovery is kept. */
    fun learn(frequencies: Collection<Long>) {
        for (frequency in frequencies) if (frequency > 0 && frequency !in candidates) candidates += frequency
    }

    /** Call once the radio is listening to [current]. */
    fun arrived(nowMs: Long) {
        arrivedMs = nowMs
        lastSyncMs = null
    }

    fun sync(nowMs: Long) {
        lastSyncMs = nowMs
    }

    /** The next control channel to try, or null to stay where it is. */
    fun update(nowMs: Long): Long? {
        if (candidates.size < 2) return null
        val last = lastSyncMs
        val quiet = if (last != null) nowMs - last >= quietMs else nowMs - arrivedMs >= tryMs
        if (!quiet) return null
        current = candidates[(candidates.indexOf(current) + 1) % candidates.size]
        return current
    }
}
