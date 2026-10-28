package com.sdrnova.t25.radio

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.sdrnova.t25.dsp.SpectrumAnalyzer
import com.sdrnova.t25.p25.P25
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.receiver.ChannelReceiver
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.RtlTcpSource
import com.sdrnova.t25.source.rtlsdr.RtlSdrUsbSource
import com.sdrnova.t25.trunking.Call
import com.sdrnova.t25.trunking.Neighbor
import com.sdrnova.t25.trunking.SiteInfo
import com.sdrnova.t25.trunking.TrunkingTracker
import com.sdrnova.t25.p25.IdentifierUpdate
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
    val mode: Mode = Mode.P25_PHASE1,
    val signalDbfs: Float = -100f,
    val afcHz: Double = 0.0,
    val nac: Int = -1,
    val lastSyncAt: Long = 0,
    val syncs: Long = 0,
    val messages: Long = 0,
    val crcErrors: Long = 0,
    val voiceFrames: Long = 0,
    val droppedBuffers: Long = 0,
)

data class TrunkingView(
    val site: SiteInfo = SiteInfo(),
    val active: List<Call> = emptyList(),
    val recent: List<Call> = emptyList(),
    val neighbors: List<Neighbor> = emptyList(),
    val bands: List<IdentifierUpdate> = emptyList(),
)

data class LogLine(val time: Long, val text: String)

/**
 * Owns the running radio: one thread reads I/Q from the source, another runs the DSP and decoder, and audio plays on
 * a third. The UI and the foreground service only see the state flows.
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

    @Volatile
    var config = RadioConfig()
        private set

    fun start(config: RadioConfig) {
        synchronized(lock) {
            if (session != null) return
            this.config = config
            _status.value = EngineStatus(starting = true, frequencyHz = config.frequencyHz, mode = config.mode)
            _trunking.value = TrunkingView()
            _log.value = emptyList()
            _spectrum.value = null
            session = Session(config).also { it.launch() }
        }
    }

    fun stop() {
        synchronized(lock) { session }?.shutdown()
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
        private val tracker = TrunkingTracker()
        private val analyzer = SpectrumAnalyzer(1024)
        private val audio = AudioOutput()
        private val logLines = ArrayDeque<LogLine>()

        @Volatile
        private var running = true

        @Volatile
        private var dropped = 0L
        private var source: IqSource? = null

        private var syncs = 0L
        private var messages = 0L
        private var crcErrors = 0L
        private var voiceFrames = 0L
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
                val iq = openSource()
                source = iq
                iq.setGain(config.gainTenthsDb)
                iq.setPpm(config.ppm)
                if (config.biasTee) iq.setBiasTee(true)
                iq.setCenterFrequency(config.frequencyHz + TUNER_OFFSET_HZ)
                if (!running) return

                _status.value = _status.value.copy(running = true, starting = false, source = iq.description)
                addLog("Listening on ${TrunkingTracker.mhz(config.frequencyHz)} (${config.mode.label}) via ${iq.description}")
                if (config.mode == Mode.NBFM) audio.start()

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

                process(iq.sampleRate)
                reader.join(2_000)
                readerError?.let { throw it }
            } catch (t: Throwable) {
                Log.e(TAG, "radio stopped", t)
                error = t.message ?: t.javaClass.simpleName
            } finally {
                running = false
                source?.stop()
                reader?.join(2_000)
                audio.stop()
                source?.let { runCatching { it.setBiasTee(false) }; runCatching { it.close() } }
                ended(this, error)
            }
        }

        private fun openSource(): IqSource = when (config.source) {
            SourceType.RTL_TCP -> RtlTcpSource.connect(config.host, config.port)
            SourceType.USB -> {
                val device = RtlSdrUsbSource.find(context) ?: throw IOException("No RTL-SDR dongle is plugged in")
                val manager = context.getSystemService(UsbManager::class.java)
                if (!manager.hasPermission(device)) throw IOException("USB permission for the dongle was not granted")
                RtlSdrUsbSource.open(context, device)
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

        private fun process(sampleRate: Int) {
            val receiver = ChannelReceiver(sampleRate, config.mode, object : ChannelReceiver.Listener {
                override fun onP25Event(event: P25Event) = handle(event)
                override fun onAudio(samples: ShortArray, count: Int) = audio.write(samples, count)
            })
            receiver.channelOffsetHz = -TUNER_OFFSET_HZ.toDouble()
            receiver.squelchThreshold = config.squelch
            val publishEvery = sampleRate / 4
            var sincePublish = 0
            var sinceSpectrum = 0
            while (running || queue.isNotEmpty()) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                receiver.process(chunk.data, chunk.length)
                val samples = chunk.length / 2
                sinceSpectrum += samples
                if (sinceSpectrum >= sampleRate / 8) {
                    sinceSpectrum = 0
                    analyzer.compute(chunk.data, chunk.length)?.let { _spectrum.value = it }
                }
                pool.offer(chunk)
                sincePublish += samples
                if (sincePublish >= publishEvery) {
                    sincePublish = 0
                    publish(receiver)
                }
                if (!running) break
            }
        }

        private fun handle(event: P25Event) {
            when (event) {
                is P25Event.Sync -> {
                    syncs++
                    lastSyncAt = SystemClock.elapsedRealtime()
                }
                is P25Event.Message -> {
                    messages++
                    val alias = (event.message as? com.sdrnova.t25.p25.GroupVoiceGrant)?.let { config.aliases[it.group] }
                    val text = tracker.onMessage(event.message)
                    addLog(if (alias != null) "$text ($alias)" else text)
                }
                is P25Event.CrcFailed -> crcErrors++
                is P25Event.VoiceFrame -> {
                    if (voiceFrames++ % 50 == 0L) addLog("Voice frames on NAC %03X (${P25.duidName(event.duid)})".format(event.nac))
                }
                is P25Event.Nid, P25Event.NidFailed -> Unit
            }
        }

        private fun addLog(text: String) {
            logLines.addFirst(LogLine(System.currentTimeMillis(), text))
            while (logLines.size > 400) logLines.removeLast()
            logChanged = true
            if (!running) _log.value = logLines.toList()
        }

        private fun publish(receiver: ChannelReceiver) {
            _status.value = _status.value.copy(
                signalDbfs = receiver.signalDbfs,
                afcHz = receiver.afcHz,
                nac = receiver.nac,
                lastSyncAt = lastSyncAt,
                syncs = syncs,
                messages = messages,
                crcErrors = crcErrors,
                voiceFrames = voiceFrames,
                droppedBuffers = dropped,
            )
            _trunking.value = TrunkingView(
                site = tracker.site,
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

        /** The tuner sits this far above the channel so the dongle's DC spike stays out of the channel filter. */
        const val TUNER_OFFSET_HZ = 150_000L
    }
}
