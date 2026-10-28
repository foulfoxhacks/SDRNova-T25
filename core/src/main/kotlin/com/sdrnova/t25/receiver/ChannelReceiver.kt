package com.sdrnova.t25.receiver

import com.sdrnova.t25.dsp.ComplexFirDecimator
import com.sdrnova.t25.dsp.FirDesign
import com.sdrnova.t25.dsp.Mixer
import com.sdrnova.t25.dsp.RealFir
import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.p25.C4fmDemodulator
import com.sdrnova.t25.p25.P25Event
import com.sdrnova.t25.p25.P25P1Framer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.sqrt

enum class Mode(val label: String) {
    P25_PHASE1("P25 Phase 1"),
    NBFM("Analog NBFM"),
}

/**
 * Everything between tuner samples and decoded output for one narrowband channel: mix the channel down to DC,
 * decimate to the channel rate (48 or 50 kHz) in stages, channel-filter, then demodulate. Several receivers can
 * share one tuner; [channelOffsetHz] says where this channel is relative to the tuner centre.
 */
class ChannelReceiver(
    val inputRate: Int,
    val mode: Mode,
    private val listener: Listener,
) {
    interface Listener {
        fun onP25Event(event: P25Event) {}
        fun onAudio(samples: ShortArray, count: Int) {}
    }

    val channelRate: Int = channelRateFor(inputRate)
    private val mixer = Mixer(inputRate.toDouble())
    private val stages: List<ComplexFirDecimator>
    private val channelFilter: ComplexFirDecimator
    private val framer = P25P1Framer(::onFramerEvent)
    private val demodulator = C4fmDemodulator(channelRate.toDouble(), framer)
    private val nbfm = NbfmDemodulator(channelRate)
    private var work = FloatArray(0)
    private var converted = FloatArray(0)
    private var audio = ShortArray(0)
    private var powerAverage = 1e-9

    /** Where the wanted channel sits relative to the tuner centre, in Hz. */
    var channelOffsetHz = 0.0
        set(value) {
            field = value
            afcHz = 0.0
            demodulator.reset()
            updateMixer()
        }

    /** Carrier offset the P25 decoder has measured and corrected, in Hz. */
    var afcHz = 0.0
        private set

    /** Channel power after filtering, in dB relative to a full-scale carrier. */
    val signalDbfs: Float get() = (10 * log10(powerAverage)).toFloat()

    val nac: Int get() = framer.nac

    var squelchThreshold: Float
        get() = nbfm.squelchThreshold
        set(value) {
            nbfm.squelchThreshold = value
        }

    init {
        val factors = decimationFactors(inputRate / channelRate)!!
        var rate = inputRate.toDouble()
        stages = factors.map { factor ->
            val outRate = rate / factor
            val taps = FirDesign.lowPass(rate, STAGE_PASS_HZ, outRate - STAGE_PASS_HZ, 60.0)
            rate = outRate
            ComplexFirDecimator(taps, factor)
        }
        val (pass, stop) = when (mode) {
            Mode.P25_PHASE1 -> 5_200.0 to 6_500.0
            Mode.NBFM -> 6_000.0 to 8_500.0
        }
        channelFilter = ComplexFirDecimator(FirDesign.lowPass(channelRate.toDouble(), pass, stop, 60.0), 1)
        updateMixer()
    }

    /** Processes [length] bytes of unsigned 8-bit interleaved I/Q (RTL-SDR format). */
    fun process(bytes: ByteArray, length: Int) {
        if (converted.size < length) converted = FloatArray(length)
        process(converted, SampleFormat.UNSIGNED_8.toFloat(bytes, length, converted))
    }

    /** Processes [samples] complex samples of interleaved float I/Q straight from the tuner. [iq] is not modified. */
    fun process(iq: FloatArray, samples: Int) {
        if (work.size < samples * 2) work = FloatArray(samples * 2)
        mixer.mix(iq, samples, work)
        var count = samples
        for (stage in stages) count = stage.process(work, count, work)
        count = channelFilter.process(work, count, work)
        if (count == 0) return

        var power = 0.0
        for (k in 0 until count) power += work[2 * k] * work[2 * k] + work[2 * k + 1] * work[2 * k + 1]
        powerAverage += 0.1 * (power / count - powerAverage)

        when (mode) {
            Mode.P25_PHASE1 -> demodulator.process(work, count)
            Mode.NBFM -> {
                if (audio.size < count) audio = ShortArray(count)
                nbfm.process(work, count, audio)
                listener.onAudio(audio, count)
            }
        }
    }

    private fun onFramerEvent(event: P25Event) {
        if (event is P25Event.Sync) {
            // Pull the carrier into the middle of the channel filter.
            afcHz = (afcHz + 0.5 * event.frequencyErrorHz).coerceIn(-AFC_LIMIT_HZ, AFC_LIMIT_HZ)
            updateMixer()
        }
        listener.onP25Event(event)
    }

    private fun updateMixer() {
        mixer.frequencyHz = -(channelOffsetHz + afcHz)
    }

    companion object {
        private const val STAGE_PASS_HZ = 8_000.0
        private const val AFC_LIMIT_HZ = 6_000.0

        /** 48 kHz suits RTL-SDR and HackRF rates; 50 kHz suits Airspy's 2.5/3/6/10 MS/s. */
        private val CHANNEL_RATES = intArrayOf(48_000, 50_000)

        fun isSupportedRate(inputRate: Int): Boolean = CHANNEL_RATES.any { decimationFactors(inputRate / it, inputRate % it) != null }

        fun channelRateFor(inputRate: Int): Int = CHANNEL_RATES.firstOrNull { decimationFactors(inputRate / it, inputRate % it) != null }
            ?: throw IllegalArgumentException("sample rate $inputRate doesn't decimate to 48 or 50 kHz")

        /** Splits a decimation into stages of 5, 4, 3 and 2; null if it can't be. */
        private fun decimationFactors(total: Int, remainder: Int = 0): List<Int>? {
            if (remainder != 0 || total < 1) return null
            val factors = ArrayList<Int>()
            var remaining = total
            for (factor in intArrayOf(5, 4, 3, 2)) {
                while (remaining % factor == 0 && remaining > 1) {
                    factors += factor
                    remaining /= factor
                }
            }
            return if (remaining == 1) factors else null
        }
    }
}

/** Narrowband FM voice: discriminator, 3 kHz audio filter, DC block and a noise squelch. */
class NbfmDemodulator(sampleRate: Int) {
    private val audioFilter = RealFir(FirDesign.lowPass(sampleRate.toDouble(), 3_000.0, 4_000.0, 50.0))
    private var previousI = 0f
    private var previousQ = 0f
    private var previousPhase = 0f
    private var dc = 0f
    private var noise = 3f
    private var discriminated = FloatArray(0)

    /** Squelch opens when discriminator noise (radians RMS) falls below this; 0 disables the squelch. */
    var squelchThreshold = 0.8f

    var squelchOpen = false
        private set

    fun process(iq: FloatArray, count: Int, out: ShortArray) {
        if (discriminated.size < count) discriminated = FloatArray(count)
        var noisePower = 0f
        for (k in 0 until count) {
            val i = iq[2 * k]
            val q = iq[2 * k + 1]
            val phase = atan2(q * previousI - i * previousQ, i * previousI + q * previousQ)
            // Voice barely changes between samples at 48 kHz, so the first difference is mostly noise. A carrier
            // quiets it; an empty channel doesn't.
            val step = phase - previousPhase
            noisePower += step * step
            previousPhase = phase
            discriminated[k] = phase
            previousI = i
            previousQ = q
        }
        noise += 0.2f * (sqrt(noisePower / count) - noise)
        squelchOpen = squelchThreshold <= 0f || noise < squelchThreshold * (if (squelchOpen) 1.2f else 1f)
        audioFilter.process(discriminated, count)

        for (k in 0 until count) {
            val sample = discriminated[k]
            dc += 0.0005f * (sample - dc)
            val value = if (squelchOpen) (sample - dc) * AUDIO_GAIN else 0f
            out[k] = value.coerceIn(-32767f, 32767f).toInt().toShort()
        }
    }

    private companion object {
        /** 5 kHz deviation at 48 kHz is 0.65 rad/sample; map about 0.5 rad to full scale. */
        val AUDIO_GAIN = (32767 / (2 * PI * 4_000 / 48_000)).toFloat()
    }
}
