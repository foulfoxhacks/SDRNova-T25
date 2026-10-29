package com.sdrnova.t25.receiver

import com.sdrnova.t25.p25.CallTermination
import com.sdrnova.t25.p25.Channel
import com.sdrnova.t25.p25.GroupVoiceGrant
import com.sdrnova.t25.p25.GroupVoiceGrantUpdate
import com.sdrnova.t25.p25.GroupVoiceGrantUpdateExplicit
import com.sdrnova.t25.p25.GroupVoiceUser
import com.sdrnova.t25.p25.Ldu
import com.sdrnova.t25.p25.LinkControl
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.p25.TsbkMessage
import com.sdrnova.t25.p25.UnitToUnitGrant
import com.sdrnova.t25.p25.UnitToUnitVoiceUser
import com.sdrnova.t25.p25.phase2.EndPushToTalk
import com.sdrnova.t25.p25.phase2.MacOther
import com.sdrnova.t25.p25.phase2.MacParser
import com.sdrnova.t25.p25.phase2.MacVoiceUser
import com.sdrnova.t25.p25.phase2.Phase2Event
import com.sdrnova.t25.p25.phase2.PushToTalk
import com.sdrnova.t25.p25.phase2.ScrambleParameters
import com.sdrnova.t25.trunking.TrunkingTracker
import kotlin.math.abs

/**
 * One tuner, one control channel, and up to [maxTrafficChannels] voice channels decoded at the same time, as
 * sdrtrunk does: every grant for a channel inside the tuner's bandwidth gets its own [ChannelReceiver] until the
 * call's terminator or a quiet timeout. Grants outside the bandwidth are logged, not followed.
 *
 * Timeouts run on stream time (samples processed), so behaviour doesn't depend on how fast the CPU keeps up.
 */
class TrunkedReceiver(
    val inputRate: Int,
    val centerHz: Long,
    val controlHz: Long,
    val mode: Mode,
    private val maxTrafficChannels: Int,
    private val listener: Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onControlEvent(event: P25Event) {}
        fun onLog(text: String) {}
        fun onAudio(samples: ShortArray, count: Int) {}
        fun onTrafficStart(channel: TrafficChannel) {}
        fun onTrafficVoice(channel: TrafficChannel, ldu: Ldu) {}

        /** Phase 2: raw 72-bit AMBE+2 frames from one timeslot. */
        fun onTrafficAmbe(channel: TrafficChannel, frames: List<ByteArray>) {}
        fun onTrafficEnd(channel: TrafficChannel, reason: String) {}
    }

    class TrafficChannel internal constructor(
        val id: Int,
        val frequencyHz: Long,
        /** Phase 2 logical channel (1 or 2) on a TDMA carrier; null for a Phase 1 FDMA channel. */
        val timeslot: Int?,
        val unitToUnit: Boolean,
        talkgroup: Int,
        source: Int?,
        val startedMs: Long,
    ) {
        var talkgroup = talkgroup
            internal set
        var source = source
            internal set
        var encrypted = false
            internal set
        var voiceFrames = 0
            internal set
        var linkControl: LinkControl? = null
            internal set
        internal var lastActivityMs = startedMs
        internal lateinit var receiver: ChannelReceiver
    }

    /** One demodulator per Phase 2 carrier, shared by the calls on its two timeslots. */
    private class Carrier(val receiver: ChannelReceiver) {
        val channels = HashMap<Int, TrafficChannel>()
    }

    val tracker = TrunkingTracker(clock)
    val control: ChannelReceiver
    private val traffic = ArrayList<TrafficChannel>()
    private val carriers = HashMap<Long, Carrier>()
    private val notices = HashMap<String, Long>()
    private val cooldownUntil = HashMap<Long, Long>()
    private var samplesProcessed = 0L
    private var nextId = 1

    /** Channels this far from the tuner centre are inside the anti-alias filters with room for a 12.5 kHz signal. */
    val usableHalfBandwidthHz: Long = (inputRate * 0.42).toLong()

    val streamTimeMs: Long get() = samplesProcessed * 1000 / inputRate

    val trafficChannels: List<TrafficChannel> get() = traffic.toList()

    init {
        control = ChannelReceiver(inputRate, mode, object : ChannelReceiver.Listener {
            override fun onP25Event(event: P25Event) = onControl(event)
            override fun onAudio(samples: ShortArray, count: Int) = listener.onAudio(samples, count)
        })
        control.channelOffsetHz = (controlHz - centerHz).toDouble()
    }

    /** Processes [samples] complex samples of interleaved float I/Q from the tuner. */
    fun process(iq: FloatArray, samples: Int) {
        control.process(iq, samples)
        for (channel in traffic.toTypedArray()) {
            if (channel.timeslot == null && channel in traffic) channel.receiver.process(iq, samples)
        }
        for ((frequency, carrier) in carriers.entries.toTypedArray()) {
            if (carriers[frequency] === carrier) carrier.receiver.process(iq, samples)
        }
        samplesProcessed += samples
        val now = streamTimeMs
        for (channel in traffic.toTypedArray()) {
            if (now - channel.lastActivityMs > IDLE_TIMEOUT_MS) release(channel, "no voice for ${IDLE_TIMEOUT_MS / 1000} s")
        }
    }

    private fun onControl(event: P25Event) {
        listener.onControlEvent(event)
        if (event is P25Event.Message) {
            listener.onLog(tracker.onMessage(event.message))
            if (maxTrafficChannels > 0) follow(event.message)
        }
    }

    private fun follow(message: TsbkMessage) {
        when (message) {
            is GroupVoiceGrant -> allocate(message.channel, message.group, message.source, message.options.encrypted, false)
            is GroupVoiceGrantUpdate -> message.updates.forEach { allocate(it.channel, it.group, null, false, false) }
            is GroupVoiceGrantUpdateExplicit -> allocate(message.channel, message.group, null, message.options.encrypted, false)
            is UnitToUnitGrant -> allocate(message.channel, message.target, message.source, false, true)
            else -> Unit
        }
    }

    private fun allocate(channel: Channel, id: Int, source: Int?, encrypted: Boolean, unitToUnit: Boolean) {
        val band = tracker.bandOf(channel) ?: return notice("band${channel.band}", "Grant on band ${channel.band} before its identifier update; waiting")
        val frequency = band.downlinkHz(channel.number)
        val timeslot = if (band.slotsPerCarrier > 1) channel.number % band.slotsPerCarrier + 1 else null
        val label = (if (unitToUnit) "private call to $id" else "TG $id") + (timeslot?.let { " (TDMA slot $it)" } ?: "")
        val existing = traffic.firstOrNull { it.frequencyHz == frequency && it.timeslot == timeslot }
        if (existing != null) {
            existing.talkgroup = id
            if (source != null && source != 0) existing.source = source
            return
        }
        when {
            // Grant updates keep coming briefly after a call's terminator; don't reopen the channel for them.
            streamTimeMs < (cooldownUntil[cooldownKey(frequency, timeslot)] ?: 0L) -> Unit
            band.slotsPerCarrier > 2 -> notice("p2-$id", "$label is on a 4-slot TDMA channel, which isn't decoded yet")
            timeslot != null && scrambleParameters() == null -> notice(
                "scramble", "Waiting for the network status broadcast (WACN and system) before following Phase 2 calls",
            )
            encrypted -> notice("enc-$id", "$label is encrypted; not following")
            abs(frequency - centerHz) > usableHalfBandwidthHz -> notice(
                "range-$frequency",
                "$label on ${TrunkingTracker.mhz(frequency)} is outside the tuner's ±${usableHalfBandwidthHz / 1000} kHz",
            )
            traffic.size >= maxTrafficChannels -> notice("busy-$id", "$label missed: all $maxTrafficChannels voice channels busy")
            else -> start(frequency, timeslot, id, source, unitToUnit)
        }
    }

    private fun scrambleParameters(): ScrambleParameters? {
        val wacn = tracker.site.wacn ?: return null
        val system = tracker.site.system ?: return null
        val nac = control.nac.takeIf { it >= 0 } ?: return null
        return ScrambleParameters(wacn, system, nac)
    }

    private fun cooldownKey(frequency: Long, timeslot: Int?) = frequency * 4 + (timeslot ?: 0)

    private fun start(frequency: Long, timeslot: Int?, id: Int, source: Int?, unitToUnit: Boolean) {
        val channel = TrafficChannel(nextId++, frequency, timeslot, unitToUnit, id, source?.takeIf { it != 0 }, streamTimeMs)
        // The tuner's crystal error scales with frequency, so start from the control channel's correction.
        val afc = control.afcHz * frequency / controlHz
        if (timeslot == null) {
            channel.receiver = ChannelReceiver(inputRate, Mode.P25_PHASE1, object : ChannelReceiver.Listener {
                override fun onP25Event(event: P25Event) = onTraffic(channel, event)
            })
            channel.receiver.channelOffsetHz = (frequency - centerHz).toDouble()
            channel.receiver.presetAfc(afc)
        } else {
            val carrier = carriers.getOrPut(frequency) {
                val receiver = ChannelReceiver(inputRate, Mode.P25_PHASE2, object : ChannelReceiver.Listener {
                    override fun onPhase2Event(event: Phase2Event) = onPhase2(frequency, event)
                })
                receiver.channelOffsetHz = (frequency - centerHz).toDouble()
                receiver.presetAfc(afc)
                receiver.setScrambleParameters(scrambleParameters()!!)
                Carrier(receiver)
            }
            carrier.channels[timeslot] = channel
            channel.receiver = carrier.receiver
        }
        traffic += channel
        listener.onTrafficStart(channel)
    }

    private fun onPhase2(frequency: Long, event: Phase2Event) {
        val carrier = carriers[frequency] ?: return
        val channel = carrier.channels[event.channel] ?: return
        when (event) {
            is Phase2Event.Voice -> {
                channel.lastActivityMs = streamTimeMs
                channel.voiceFrames += event.frames.size
                if (!channel.encrypted) listener.onTrafficAmbe(channel, event.frames)
            }
            is Phase2Event.Mac -> for (message in event.messages) {
                when (message) {
                    is PushToTalk -> {
                        channel.lastActivityMs = streamTimeMs
                        if (message.source != 0) channel.source = message.source
                        if (message.encrypted) channel.encrypted = true
                    }
                    is MacVoiceUser -> {
                        channel.lastActivityMs = streamTimeMs
                        if (message.source != 0) channel.source = message.source
                        if (message.options.encrypted) channel.encrypted = true
                    }
                    is EndPushToTalk -> return release(channel, "end of transmission")
                    is MacOther -> if (message.pduType == MacParser.IDLE) return release(channel, "channel idle")
                }
            }
            else -> Unit
        }
    }

    private fun onTraffic(channel: TrafficChannel, event: P25Event) {
        when (event) {
            is P25Event.Sync -> channel.lastActivityMs = streamTimeMs
            is P25Event.Voice -> {
                channel.lastActivityMs = streamTimeMs
                channel.voiceFrames += 9
                when (val lc = event.ldu.linkControl) {
                    is GroupVoiceUser -> {
                        channel.linkControl = lc
                        if (lc.source != 0) channel.source = lc.source
                        if (lc.options.encrypted) channel.encrypted = true
                    }
                    is UnitToUnitVoiceUser -> {
                        channel.linkControl = lc
                        if (lc.source != 0) channel.source = lc.source
                    }
                    is CallTermination -> return release(channel, "call terminated")
                    else -> Unit
                }
                event.ldu.encryption?.let { channel.encrypted = it.encrypted }
                listener.onTrafficVoice(channel, event.ldu)
            }
            is P25Event.Terminator -> release(channel, "end of transmission")
            else -> Unit
        }
    }

    private fun release(channel: TrafficChannel, reason: String) {
        if (traffic.remove(channel)) {
            cooldownUntil[cooldownKey(channel.frequencyHz, channel.timeslot)] = streamTimeMs + COOLDOWN_MS
            channel.timeslot?.let { slot ->
                val carrier = carriers[channel.frequencyHz]
                carrier?.channels?.remove(slot)
                if (carrier != null && carrier.channels.isEmpty()) carriers.remove(channel.frequencyHz)
            }
            listener.onTrafficEnd(channel, reason)
        }
    }

    /** Logs [text] at most once every half minute per [key], since grant updates repeat every second or so. */
    private fun notice(key: String, text: String) {
        val now = streamTimeMs
        val last = notices[key]
        if (last == null || now - last > 30_000) {
            notices[key] = now
            listener.onLog(text)
        }
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 2_000L
        const val COOLDOWN_MS = 1_500L
    }
}
