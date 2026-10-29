package com.sdrnova.t25.scan

/**
 * Conventional scanning with one tuner: visit each channel for [dwellMs]; stay while it's busy (a call, or open
 * squelch) and for [hangMs] after, so replies on the same channel aren't missed, then move on. A digital channel that
 * shows sync but no call yet gets [syncDwellMs] to start one. Times are stream time, so a slow phone doesn't skip.
 */
class Scanner<T>(
    channels: List<T>,
    var dwellMs: Long = 400,
    var hangMs: Long = 2_000,
    var syncDwellMs: Long = 1_500,
    start: Int = 0,
) {
    private val list = channels.toList()
    private var arrivedMs = 0L
    private var lastBusyMs: Long? = null
    private var sawSync = false

    init {
        require(list.isNotEmpty()) { "Nothing to scan" }
    }

    var index = start.coerceIn(0, list.lastIndex)
        private set
    val current: T get() = list[index]
    val size: Int get() = list.size

    /** Stays on [current] until released. */
    var hold = false

    /** Call once the radio is listening to [current]. */
    fun arrived(nowMs: Long) {
        arrivedMs = nowMs
        lastBusyMs = null
        sawSync = false
    }

    /** The next channel when it's time to leave [current], or null to stay. */
    fun update(nowMs: Long, busy: Boolean, sync: Boolean): T? {
        if (busy) lastBusyMs = nowMs
        if (sync) sawSync = true
        if (hold || list.size == 1 || busy) return null
        val dwellUntil = arrivedMs + if (sawSync) syncDwellMs else dwellMs
        val hangUntil = lastBusyMs?.let { it + hangMs } ?: Long.MIN_VALUE
        return if (nowMs < maxOf(dwellUntil, hangUntil)) null else next()
    }

    /** Moves on regardless; the caller tunes and then calls [arrived]. */
    fun next(): T {
        index = (index + 1) % list.size
        return current
    }
}
