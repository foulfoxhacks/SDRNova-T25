package com.sdrnova.t25.receiver

/** Which calls a [TrunkedReceiver] follows, and which matter most when its voice channels are all busy. */
interface CallPolicy {
    /** False for locked-out talkgroups: they're logged but never take a voice channel. */
    fun follow(talkgroup: Int, unitToUnit: Boolean): Boolean = true

    /** Lower is more important. A grant takes the channel of a less important call when none is free. */
    fun priority(talkgroup: Int, unitToUnit: Boolean): Int = DEFAULT_PRIORITY

    companion object {
        const val DEFAULT_PRIORITY = 100
        val ALL = object : CallPolicy {}
    }
}
