package com.sdrnova.t25.radio

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.sdrnova.t25.dmr.DmrEvent
import com.sdrnova.t25.dsp.SpectrumAnalyzer
import com.sdrnova.t25.p25.IdentifierUpdate
import com.sdrnova.t25.p25.Ldu
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.receiver.ChannelReceiver
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.receiver.TrunkedReceiver
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.RtlTcpSource
import com.sdrnova.t25.source.UsbRadios
import com.sdrnova.t25.trunking.Call
import com.sdrnova.t25.trunking.Neighbor
import com.sdrnova.t25.trunking.SiteInfo
import com.sdrnova.t25.trunking.TrunkingTracker
import com.sdrnova.t25.voice.VocoderLoader
import com.sdrnova.t25.voice.VocoderProvider
import com.sdrnova.t25.voice.VoiceDecoder
import com.sdrnova.t25.voice.VoiceResampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class EngineStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val source: String? = null,
    val error: String? = null,
    val frequencyHz: Long = 0,
    val centerHz: Long = 0,
    val sampleRate: Int = 0,
    val mode: Mode = Mode.P25_PHASE1,
    val vocoder: String? = null,
    val signalDbfs: Float = -100f,
    val afcHz: Double = 0.0,
    val nac: Int = -1,
    val lastSyncAt: Long = 0,
    val syncs: Long = 0,
    val messages: Long = 0,
    val crcErrors: Long = 0,
    val droppedBuffers: Long = 0,
)

/** A voice channel being decoded right now. */
data class FollowedCall(
    val id: Int,
    val frequencyHz: Long,
    val timeslot: Int?,
    val talkgroup: Int,
    val source: Int?,
    val unitToUnit: Boolean,
    val encrypted: Boolean,
    val voiceFrames: Int,
    val playing: Boolean,
)

data class TrunkingView(
    val site: SiteInfo = SiteInfo(),
    val following: List<FollowedCall> = emptyList(),
    val active: List<Call> = emptyList(),
    val recent: List<Call> = emptyList(),
    val neighbors: List<Neighbor> = emptyList(),
    val bands: List<IdentifierUpdate> = emptyList(),
)

data class LogLine(val time: Long, val text: String)

/**
 * Owns the running radio: one thread reads I/Q from the source, another runs the DSP, decoders and trunking, and
 * audio plays on a third. The UI and the foreground service only see the state flows.
 */
class RadioEngine(private val context: Context) {
    private val _status = MutableStateFlow(EngineStatus())
    private val _trunking = MutableStateFlow(TrunkingView())
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    private val _spectrum = MutableStateFlow<FloatArray?>(null)

    val status: StateFlow<EngineStatus> = _status
    val trunking: StateFlow<TrunkingView> = _trunking
    val log: StateFlow<List<LogLine>> = _log
    val spectrum: StateFlow<FloatArray?> = _spectrum

    /** Called on an engine thread once everything has stopped, whether asked to or because of an error. */
    var onStopped: (() -> Unit)? = null

    private val lock = Any()
    private var session: Session? = null

    fun start(config: RadioConfig) {
        synchronized(lock) {
            if (session != null) return
            _status.value = EngineStatus(
                starting = true, frequencyHz = config.frequencyHz, centerHz = config.effectiveCenterHz,
                sampleRate = config.sampleRate, mode = config.mode,
            )
            _trunking.value = TrunkingView()
            _log.value = emptyList()
            _spectrum.value = null
            session = Session(config).also { it.launch() }
        }
    }

    fun stop() {
        synchronized(lock) { session }?.shutdown()
    }

    /** Applies tuner gain (tenths of a dB, or [IqSource.AUTO_GAIN]) to the running radio. */
    fun setGain(tenthsDb: Int) {
        val current = synchronized(lock) { session } ?: return
        Thread({ runCatching { current.source?.setGain(tenthsDb) } }, "radio-gain").start()
    }

    fun setVolumeDb(db: Float) {
        synchronized(lock) { session }?.setVolumeDb(db)
    }

    /** Moves the decoder to [hz] inside the current tuner bandwidth, without restarting. */
    fun tune(hz: Long) {
        synchronized(lock) { session }?.post { receiver ->
            if (receiver.retune(hz)) {
                _status.value = _status.value.copy(frequencyHz = hz, error = null)
            } else {
                _status.value = _status.value.copy(error = "${TrunkingTracker.mhz(hz)} is outside the tuner's bandwidth")
            }
        }
    }

    private fun ended(ended: Session, error: String?) {
        synchronized(lock) { if (session === ended) session = null }
        _status.value = _status.value.copy(running = false, starting = false, error = error)
        onStopped?.invoke()
    }

    private class Chunk(var data: ByteArray, var length: Int = 0)

    private inner class Session(private val config: RadioConfig) {
        private val queue = ArrayBlockingQueue<Chunk>(64)
        private val pool = ArrayBlockingQueue<Chunk>(80)
        private val analyzer = SpectrumAnalyzer(1024)
        private val logLines = ArrayDeque<LogLine>()
        @Volatile
        private var audio: AudioOutput? = null
        private var vocoder: VocoderProvider? = null
        private val decoders = HashMap<Int, VoiceDecoder>()
        private val resampler = VoiceResampler(48_000)
        private var playingChannel: Int? = null

        @Volatile
        private var running = true

        @Volatile
        private var dropped = 0L

        @Volatile
        var source: IqSource? = null
            private set
        private val commands = java.util.concurrent.ConcurrentLinkedQueue<(TrunkedReceiver) -> Unit>()

        /** Runs [command] on the DSP thread before the next block of samples. */
        fun post(command: (TrunkedReceiver) -> Unit) {
            commands.add(command)
        }

        fun setVolumeDb(db: Float) {
            audio?.setVolumeDb(db)
        }

        private var syncs = 0L
        private var messages = 0L
        private var crcErrors = 0L
        private var lastSyncAt = 0L
        private var logChanged = false

        fun launch() {
            Thread(::run, "radio").start()
        }

        fun shutdown() {
            running = false
            source?.stop()
        }

        private fun run() {
            var error: String? = null
            var reader: Thread? = null
            try {
                require(ChannelReceiver.isSupportedRate(config.sampleRate)) { "Unsupported sample rate ${config.sampleRate}" }
                val iq = openSource()
                source = iq
                iq.setGain(config.gainTenthsDb)
                iq.setPpm(config.ppm)
                if (config.biasTee) iq.setBiasTee(true)
                iq.setCenterFrequency(config.effectiveCenterHz)
                if (!running) return

                if (config.mode != Mode.NBFM) vocoder = VocoderLoader.provider
                _status.value = _status.value.copy(
                    running = true, starting = false, source = iq.description, vocoder = vocoder?.name,
                )
                addLog(
                    "Listening on ${TrunkingTracker.mhz(config.frequencyHz)} (${config.mode.label}) via ${iq.description}, " +
                        "%.2f MS/s".format(iq.sampleRate / 1e6),
                )
                if (config.mode != Mode.NBFM) {
                    addLog(vocoder?.let { "Voice audio through ${it.name}" } ?: "No vocoder installed: calls are followed without audio")
                }

                var readerError: Throwable? = null
                reader = Thread({
                    try {
                        iq.stream(::enqueue)
                    } catch (t: Throwable) {
                        readerError = t
                    } finally {
                        running = false
                    }
                }, "radio-reader").apply { start() }

                process(iq)
                reader.join(2_000)
                readerError?.let { throw it }
            } catch (t: Throwable) {
                Log.e(TAG, "radio stopped", t)
                error = t.message ?: t.javaClass.simpleName
            } finally {
                running = false
                source?.stop()
                reader?.join(2_000)
                audio?.stop()
                source?.let {
                    runCatching { it.setBiasTee(false) }
                    runCatching { it.close() }
                }
                ended(this, error)
            }
        }

        private fun openSource(): IqSource = when (config.source) {
            SourceType.RTL_TCP -> RtlTcpSource.connect(config.host, config.port, config.sampleRate)
            SourceType.USB -> {
                val device = UsbRadios.find(context) ?: throw IOException("No supported SDR is plugged in")
                val manager = context.getSystemService(UsbManager::class.java)
                if (!manager.hasPermission(device)) throw IOException("USB permission for the radio was not granted")
                UsbRadios.open(context, device, config.sampleRate)
            }
        }

        private fun enqueue(data: ByteArray, length: Int) {
            val chunk = pool.poll() ?: Chunk(ByteArray(length))
            if (chunk.data.size < length) chunk.data = ByteArray(length)
            System.arraycopy(data, 0, chunk.data, 0, length)
            chunk.length = length
            if (!queue.offer(chunk)) {
                dropped++
                pool.offer(chunk)
            }
        }

        private fun process(iq: IqSource) {
            val receiver = TrunkedReceiver(
                inputRate = iq.sampleRate,
                centerHz = config.effectiveCenterHz,
                controlHz = config.frequencyHz,
                mode = config.mode,
                maxTrafficChannels = if (config.mode == Mode.P25_PHASE1) config.maxTrafficChannels else 0,
                listener = Listener(),
            )
            receiver.control.squelchThreshold = config.squelch
            if (config.mode == Mode.NBFM) audio = AudioOutput(receiver.control.channelRate).also { it.start() }
            if (vocoder != null) audio = AudioOutput(48_000).also { it.start() }
            audio?.setVolumeDb(config.volumeDb)

            var floats = FloatArray(0)
            val publishEvery = iq.sampleRate / 4
            var sincePublish = 0
            var sinceSpectrum = 0
            while (running) {
                while (true) commands.poll()?.invoke(receiver) ?: break
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (floats.size < chunk.length) floats = FloatArray(chunk.length)
                val samples = iq.format.toFloat(chunk.data, chunk.length, floats)
                receiver.process(floats, samples)
                sinceSpectrum += samples
                if (sinceSpectrum >= iq.sampleRate / SPECTRUM_RATE) {
                    sinceSpectrum = 0
                    analyzer.compute(floats, samples)?.let { _spectrum.value = it }
                }
                pool.offer(chunk)
                sincePublish += samples
                if (sincePublish >= publishEvery) {
                    sincePublish = 0
                    publish(receiver)
                }
            }
        }

        private inner class Listener : TrunkedReceiver.Listener {
            override fun onControlEvent(event: P25Event) {
                when (event) {
                    is P25Event.Sync -> {
                        syncs++
                        lastSyncAt = SystemClock.elapsedRealtime()
                    }
                    is P25Event.Message -> messages++
                    is P25Event.CrcFailed -> crcErrors++
                    else -> Unit
                }
            }

            override fun onDmrEvent(event: DmrEvent) {
                if (event is DmrEvent.Sync) {
                    syncs++
                    lastSyncAt = SystemClock.elapsedRealtime()
                }
            }

            override fun onLog(text: String) = addLog(withAliases(text))

            override fun onAudio(samples: ShortArray, count: Int) {
                audio?.write(samples, count)
            }

            override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                val slot = channel.timeslot?.let { " TDMA slot $it" } ?: ""
                val verb = if (config.mode == Mode.DMR) "Call" else "Following"
                addLog(withAliases("$verb ${label(channel)} on ${TrunkingTracker.mhz(channel.frequencyHz)}$slot"))
            }

            override fun onTrafficVoice(channel: TrunkedReceiver.TrafficChannel, ldu: Ldu) =
                play(channel, ldu.voiceFrames) { it.createImbeDecoder() }

            override fun onTrafficAmbe(channel: TrunkedReceiver.TrafficChannel, frames: List<ByteArray>) =
                play(channel, frames) { it.createAmbeDecoder() }

            /** One call has the speaker at a time: the first to deliver voice keeps it until it ends. */
            private fun play(
                channel: TrunkedReceiver.TrafficChannel,
                frames: List<ByteArray>,
                create: (VocoderProvider) -> VoiceDecoder?,
            ) {
                val provider = vocoder ?: return
                if (channel.encrypted) return
                if (playingChannel == null) playingChannel = channel.id
                if (playingChannel != channel.id) return
                val decoder = decoders.getOrPut(channel.id) { create(provider) ?: return }
                for (frame in frames) {
                    val pcm = runCatching { decoder.decode(frame) }.getOrNull() ?: continue
                    val samples = resampler.process(pcm)
                    audio?.write(samples, samples.size)
                }
            }

            override fun onTrafficEnd(channel: TrunkedReceiver.TrafficChannel, reason: String) {
                decoders.remove(channel.id)
                if (playingChannel == channel.id) playingChannel = null
                // Every voice frame is 20 ms, Phase 1 or 2.
                val seconds = channel.voiceFrames / 50
                addLog(withAliases("${label(channel)} ended after ${seconds}s ($reason)"))
            }
        }

        private fun label(channel: TrunkedReceiver.TrafficChannel): String {
            val who = if (channel.unitToUnit) "Private call to ${channel.talkgroup}" else "TG ${channel.talkgroup}"
            val from = channel.source?.let { " from $it" } ?: ""
            return who + from + if (channel.encrypted) " (encrypted)" else ""
        }

        private fun withAliases(text: String): String {
            if (config.aliases.isEmpty()) return text
            return TALKGROUP.replace(text) { match ->
                config.aliases[match.groupValues[1].toInt()]?.let { "${match.value} [$it]" } ?: match.value
            }
        }

        private fun addLog(text: String) {
            logLines.addFirst(LogLine(System.currentTimeMillis(), text))
            while (logLines.size > 400) logLines.removeLast()
            logChanged = true
            if (!running || _status.value.starting) _log.value = logLines.toList()
        }

        private fun publish(receiver: TrunkedReceiver) {
            _status.value = _status.value.copy(
                signalDbfs = receiver.control.signalDbfs,
                afcHz = receiver.control.afcHz,
                nac = receiver.control.nac,
                lastSyncAt = lastSyncAt,
                syncs = syncs,
                messages = messages,
                crcErrors = crcErrors,
                droppedBuffers = dropped,
            )
            val tracker = receiver.tracker
            _trunking.value = TrunkingView(
                site = tracker.site,
                following = receiver.trafficChannels.map {
                    FollowedCall(
                        it.id, it.frequencyHz, it.timeslot, it.talkgroup, it.source, it.unitToUnit, it.encrypted, it.voiceFrames,
                        playing = it.id == playingChannel,
                    )
                },
                active = tracker.activeCalls(),
                recent = tracker.recentCalls(),
                neighbors = tracker.neighbors(),
                bands = tracker.bandPlan(),
            )
            if (logChanged) {
                logChanged = false
                _log.value = logLines.toList()
            }
        }
    }

    companion object {
        private const val TAG = "RadioEngine"
        private val TALKGROUP = Regex("TG (\\d+)")

        /** Spectrum (and waterfall) frames per second. */
        private const val SPECTRUM_RATE = 12

        /** The tuner sits this far above the channel so the dongle's DC spike stays out of the channel filter. */
        const val TUNER_OFFSET_HZ = 150_000L
    }
}
