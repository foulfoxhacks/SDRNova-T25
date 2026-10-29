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
import com.sdrnova.t25.playlist.AliasBook
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistChannel
import com.sdrnova.t25.receiver.ChannelReceiver
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.receiver.TrunkedReceiver
import com.sdrnova.t25.scan.Scanner
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class EngineStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val source: String? = null,
    val error: String? = null,
    val frequencyHz: Long = 0,
    val centerHz: Long = 0,
    val sampleRate: Int = 0,
    val mode: Mode = Mode.P25_PHASE1,
    val channelName: String = "",
    val vocoder: String? = null,
    val signalDbfs: Float = -100f,
    val afcHz: Double = 0.0,
    val nac: Int = -1,
    val lastSyncAt: Long = 0,
    val syncs: Long = 0,
    val messages: Long = 0,
    val crcErrors: Long = 0,
    val droppedBuffers: Long = 0,
    /** A call is up or the squelch is open. */
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val scanHold: Boolean = false,
    /** 1-based position in the scan list. */
    val scanPosition: Int = 0,
    val scanSize: Int = 0,
)

/** Somewhere to listen: a playlist channel, a scan stop, or a frequency typed or tapped by hand. */
data class Tuning(val frequencyHz: Long, val mode: Mode, val name: String = "", val aliasList: String? = null) {
    val label: String get() = name.ifEmpty { TrunkingTracker.mhz(frequencyHz) }

    companion object {
        /** Null for channels SDRNova can't decode yet (AM, conventional Phase 2). */
        fun of(channel: PlaylistChannel): Tuning? {
            val mode = channel.protocol.mode ?: return null
            val frequency = channel.frequencies.firstOrNull() ?: return null
            return Tuning(frequency, mode, channel.name, channel.aliasList)
        }
    }
}

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

/** A call that was decoded and has ended, on whichever channel the radio was on. */
data class HeardCall(
    val endedAt: Long,
    val channel: String,
    val frequencyHz: Long,
    val timeslot: Int?,
    val talkgroup: Int,
    val source: Int?,
    val unitToUnit: Boolean,
    val encrypted: Boolean,
    val seconds: Int,
)

data class TrunkingView(
    val site: SiteInfo = SiteInfo(),
    val following: List<FollowedCall> = emptyList(),
    val active: List<Call> = emptyList(),
    val recent: List<Call> = emptyList(),
    val heard: List<HeardCall> = emptyList(),
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
    private val _aliases = MutableStateFlow(AliasBook.EMPTY)

    val status: StateFlow<EngineStatus> = _status
    val trunking: StateFlow<TrunkingView> = _trunking
    val log: StateFlow<List<LogLine>> = _log
    val spectrum: StateFlow<FloatArray?> = _spectrum

    /** Names, priorities and lockouts for the channel being listened to. */
    val aliases: StateFlow<AliasBook> = _aliases

    /** Called on an engine thread once everything has stopped, whether asked to or because of an error. */
    var onStopped: (() -> Unit)? = null

    private val playlists = PlaylistStore(context)
    private val lock = Any()
    private var session: Session? = null

    fun start(config: RadioConfig) {
        synchronized(lock) {
            if (session != null) return
            _status.value = EngineStatus(
                starting = true, frequencyHz = config.frequencyHz, centerHz = config.effectiveCenterHz,
                sampleRate = config.sampleRate, mode = config.mode, channelName = config.channelName,
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

    /** Moves the running radio to [tuning] (retuning the dongle if it's outside the band in view) and stops scanning. */
    fun select(tuning: Tuning) = post {
        stopScan()
        retarget(tuning)
    }

    fun setScan(enabled: Boolean) = post { if (enabled) startScan() else stopScan() }

    fun setScanHold(hold: Boolean) = post {
        scanner?.hold = hold
        publishScan()
    }

    fun skip() = post { scanner?.let { hop(it.next()) } }

    /** The saved playlist changed: reload aliases and the scan list. */
    fun playlistChanged() = post { reloadPlaylist() }

    private fun post(command: Session.() -> Unit) {
        synchronized(lock) { session }?.post(command)
    }

    private fun ended(ended: Session, error: String?) {
        synchronized(lock) { if (session === ended) session = null }
        _status.value = _status.value.copy(running = false, starting = false, scanning = false, busy = false, error = error)
        onStopped?.invoke()
    }

    private class Chunk(var data: ByteArray, var length: Int = 0)

    private inner class Session(private val config: RadioConfig) {
        private val queue = ArrayBlockingQueue<Chunk>(64)
        private val pool = ArrayBlockingQueue<Chunk>(80)
        private val analyzer = SpectrumAnalyzer(1024)
        private val logLines = ArrayDeque<LogLine>()
        private val heard = ArrayDeque<HeardCall>()
        @Volatile
        private var audio: AudioOutput? = null
        @Volatile
        private var volumeDb = config.volumeDb
        private var vocoder: VocoderProvider? = null
        private val decoders = HashMap<Int, VoiceDecoder>()
        private val resampler = VoiceResampler(48_000)
        private var playingChannel: Int? = null
        private val listener = Listener()

        private lateinit var iq: IqSource
        private lateinit var receiver: TrunkedReceiver
        private var tuning = config.tuning
        private var centerHz = config.effectiveCenterHz
        private var playlist = Playlist.EMPTY
        private val books = HashMap<String, AliasBook>()
        private var aliases = AliasBook.EMPTY

        var scanner: Scanner<Tuning>? = null
            private set
        private var arrivalPending = false
        private var syncSinceUpdate = false

        /** Samples processed, the scanner's clock. */
        private var streamSamples = 0L

        /** Samples still to discard after retuning the dongle: they were captured before the move. */
        private var skipSamples = 0

        @Volatile
        private var running = true

        @Volatile
        private var dropped = 0L

        @Volatile
        var source: IqSource? = null
            private set
        private val commands = ConcurrentLinkedQueue<Session.() -> Unit>()

        /** Runs [command] on the DSP thread before the next block of samples. */
        fun post(command: Session.() -> Unit) {
            commands.add(command)
        }

        fun setVolumeDb(db: Float) {
            volumeDb = db
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
                iq = openSource()
                source = iq
                iq.setGain(config.gainTenthsDb)
                iq.setPpm(config.ppm)
                if (config.biasTee) iq.setBiasTee(true)
                iq.setCenterFrequency(centerHz)
                if (!running) return

                vocoder = VocoderLoader.provider
                _status.value = _status.value.copy(
                    running = true, starting = false, source = iq.description, vocoder = vocoder?.name,
                )
                addLog(
                    "Listening on ${tuning.label} (${config.mode.label}) via ${iq.description}, " +
                        "%.2f MS/s".format(iq.sampleRate / 1e6),
                )
                if (config.mode != Mode.NBFM || config.scan) addLog("Voice audio through ${vocoder?.name}")

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

                process()
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

        private fun process() {
            receiver = newReceiver(tuning)
            ensureAudio()
            reloadPlaylist()
            if (config.scan) startScan()

            var floats = FloatArray(0)
            val publishEvery = iq.sampleRate / 4
            var sincePublish = 0
            var sinceSpectrum = 0
            while (running) {
                while (true) commands.poll()?.invoke(this) ?: break
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (floats.size < chunk.length) floats = FloatArray(chunk.length)
                val samples = iq.format.toFloat(chunk.data, chunk.length, floats)
                pool.offer(chunk)
                if (skipSamples > 0) {
                    skipSamples -= samples
                    continue
                }
                receiver.process(floats, samples)
                streamSamples += samples
                sinceSpectrum += samples
                if (sinceSpectrum >= iq.sampleRate / SPECTRUM_RATE) {
                    sinceSpectrum = 0
                    analyzer.compute(floats, samples)?.let { _spectrum.value = it }
                }
                scan()
                sincePublish += samples
                if (sincePublish >= publishEvery) {
                    sincePublish = 0
                    publish()
                }
            }
        }

        private fun newReceiver(target: Tuning) = TrunkedReceiver(
            inputRate = iq.sampleRate,
            centerHz = centerHz,
            controlHz = target.frequencyHz,
            mode = target.mode,
            maxTrafficChannels = if (target.mode == Mode.P25_PHASE1) config.maxTrafficChannels else 0,
            listener = listener,
        ).also { it.control.squelchThreshold = config.squelch }

        /** NBFM plays at the channel rate, digital voice at 48 kHz after the vocoder. */
        private fun ensureAudio() {
            val rate = if (receiver.mode == Mode.NBFM) receiver.control.channelRate else 48_000
            if (audio?.sampleRate == rate) return
            audio?.stop()
            audio = AudioOutput(rate).also {
                it.setVolumeDb(volumeDb)
                it.start()
            }
        }

        /**
         * Listens to [target]: inside the band in view and in the same mode, the decoder just moves; otherwise the
         * dongle retunes (placing the channel off its DC spike) and a fresh receiver starts.
         */
        fun retarget(target: Tuning) {
            if (target == tuning) return
            val old = receiver
            val inView = abs(target.frequencyHz - centerHz) <= old.usableHalfBandwidthHz
            if (target.mode == old.mode && inView) {
                old.retune(target.frequencyHz)
            } else {
                old.releaseAll("retuned")
                if (!inView) {
                    centerHz = target.frequencyHz + TUNER_OFFSET_HZ
                    iq.setCenterFrequency(centerHz)
                    while (true) pool.offer(queue.poll() ?: break)
                    skipSamples = iq.sampleRate / 10
                }
                receiver = newReceiver(target)
                ensureAudio()
            }
            tuning = target
            aliases = bookFor(target.aliasList)
            _aliases.value = aliases
            _status.value = _status.value.copy(
                frequencyHz = target.frequencyHz, centerHz = centerHz, mode = target.mode, channelName = target.name,
                nac = -1, error = null,
            )
        }

        fun startScan() {
            val stops = playlist.channels.filter { it.enabled }.mapNotNull(Tuning::of)
            if (stops.isEmpty()) {
                addLog("Nothing to scan: import a playlist or add channels under Channels, and tick the ones to scan")
                scanner = null
                publishScan()
                return
            }
            val here = stops.indexOfFirst { it.frequencyHz == tuning.frequencyHz && it.mode == tuning.mode }
            val wasScanning = scanner != null
            scanner = Scanner(stops, config.scanDwellMs.toLong(), config.scanHangMs.toLong(), start = here.coerceAtLeast(0))
            if (!wasScanning) addLog("Scanning ${stops.size} channels")
            hop(stops[here.coerceAtLeast(0)])
        }

        fun stopScan() {
            if (scanner == null) return
            scanner = null
            addLog("Scan stopped on ${tuning.label}")
            publishScan()
        }

        fun hop(next: Tuning) {
            if (next != tuning) retarget(next)
            arrivalPending = true
            publishScan()
        }

        private fun scan() {
            val scanner = scanner ?: return
            val now = streamSamples * 1000 / iq.sampleRate
            if (arrivalPending) {
                arrivalPending = false
                syncSinceUpdate = false
                scanner.arrived(now)
                return
            }
            val next = scanner.update(now, receiver.busy, syncSinceUpdate)
            syncSinceUpdate = false
            if (next != null) hop(next)
        }

        fun publishScan() {
            val scanner = scanner
            _status.value = _status.value.copy(
                scanning = scanner != null,
                scanHold = scanner?.hold == true,
                scanPosition = (scanner?.index ?: -1) + 1,
                scanSize = scanner?.size ?: 0,
            )
        }

        fun reloadPlaylist() {
            playlist = playlists.load()
            books.clear()
            aliases = bookFor(tuning.aliasList)
            _aliases.value = aliases
            val previous = scanner ?: return
            startScan()
            scanner?.hold = previous.hold
            publishScan()
        }

        private fun bookFor(list: String?): AliasBook =
            books.getOrPut(list.orEmpty()) { AliasBook(playlist.aliasesIn(list), config.aliases) }

        private inner class Listener : TrunkedReceiver.Listener {
            override fun onControlEvent(event: P25Event) {
                when (event) {
                    is P25Event.Sync -> {
                        syncs++
                        lastSyncAt = SystemClock.elapsedRealtime()
                        syncSinceUpdate = true
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
                    syncSinceUpdate = true
                }
            }

            override fun onLog(text: String) = addLog(withAliases(text))

            override fun onAudio(samples: ShortArray, count: Int) {
                audio?.write(samples, count)
            }

            override fun onTrafficStart(channel: TrunkedReceiver.TrafficChannel) {
                val slot = channel.timeslot?.let { " TDMA slot $it" } ?: ""
                val verb = if (channel.frequencyHz == receiver.controlHz) "Call" else "Following"
                addLog(withAliases("$verb ${label(channel)} on ${TrunkingTracker.mhz(channel.frequencyHz)}$slot"))
            }

            override fun onTrafficVoice(channel: TrunkedReceiver.TrafficChannel, ldu: Ldu) =
                play(channel, ldu.voiceFrames) { it.createImbeDecoder() }

            override fun onTrafficAmbe(channel: TrunkedReceiver.TrafficChannel, frames: List<ByteArray>) =
                play(channel, frames) { it.createAmbeDecoder() }

            /**
             * One call has the speaker at a time: the first to deliver voice keeps it until it ends, unless a call
             * with a higher alias priority starts talking. Locked-out talkgroups are never played.
             */
            private fun play(
                channel: TrunkedReceiver.TrafficChannel,
                frames: List<ByteArray>,
                create: (VocoderProvider) -> VoiceDecoder?,
            ) {
                val provider = vocoder ?: return
                if (channel.encrypted || lockedOut(channel)) return
                val current = playingChannel
                if (current != null && current != channel.id) {
                    val playing = receiver.trafficChannels.firstOrNull { it.id == current }
                    if (playing != null && priority(channel) >= priority(playing)) return
                }
                playingChannel = channel.id
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
                if (channel.voiceFrames > 0) {
                    heard.addFirst(
                        HeardCall(
                            System.currentTimeMillis(), tuning.label, channel.frequencyHz, channel.timeslot,
                            channel.talkgroup, channel.source, channel.unitToUnit, channel.encrypted, seconds,
                        ),
                    )
                    while (heard.size > 100) heard.removeLast()
                }
            }
        }

        private fun lockedOut(channel: TrunkedReceiver.TrafficChannel) = !channel.unitToUnit && aliases.lockedOut(channel.talkgroup)

        private fun priority(channel: TrunkedReceiver.TrafficChannel) =
            if (channel.unitToUnit) AliasBook.UNLISTED_PRIORITY else aliases.priority(channel.talkgroup)

        private fun label(channel: TrunkedReceiver.TrafficChannel): String {
            val who = if (channel.unitToUnit) "Private call to ${channel.talkgroup}" else "TG ${channel.talkgroup}"
            val from = channel.source?.let { id -> " from $id" + (aliases.radioName(id)?.let { " [$it]" } ?: "") } ?: ""
            return who + from + if (channel.encrypted) " (encrypted)" else ""
        }

        private fun withAliases(text: String): String {
            if (aliases.isEmpty) return text
            return TALKGROUP.replace(text) { match ->
                aliases.name(match.groupValues[1].toInt())?.let { "${match.value} [$it]" } ?: match.value
            }
        }

        private fun addLog(text: String) {
            logLines.addFirst(LogLine(System.currentTimeMillis(), text))
            while (logLines.size > 400) logLines.removeLast()
            logChanged = true
            if (!running || _status.value.starting) _log.value = logLines.toList()
        }

        private fun publish() {
            val control = receiver.control
            _status.value = _status.value.copy(
                signalDbfs = control.signalDbfs,
                afcHz = control.afcHz,
                nac = control.nac,
                lastSyncAt = lastSyncAt,
                syncs = syncs,
                messages = messages,
                crcErrors = crcErrors,
                droppedBuffers = dropped,
                busy = receiver.busy,
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
                heard = heard.toList(),
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
