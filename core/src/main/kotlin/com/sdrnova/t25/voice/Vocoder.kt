package com.sdrnova.t25.voice

/**
 * Turns raw voice frames into audio. SDRNova T25 bundles no IMBE or AMBE+2 decoder; a provider is supplied by the
 * person building or running the app (see docs/VOCODER.md).
 */
interface VoiceDecoder {
    /**
     * Decodes one raw frame (MSB-first, still FEC coded) into 160 samples at 8 kHz, about ±1.0: a 144-bit IMBE frame
     * for Phase 1, or a 72-bit AMBE+2 frame for Phase 2.
     */
    fun decode(frame: ByteArray): FloatArray

    fun reset()
}

interface VocoderProvider {
    val name: String

    /** A fresh, independent IMBE decoder (they keep state between frames), or null if IMBE isn't supported. */
    fun createImbeDecoder(): VoiceDecoder?

    /** A fresh AMBE+2 half-rate decoder for Phase 2 voice, or null if unsupported. */
    fun createAmbeDecoder(): VoiceDecoder? = null
}

/** Upsamples 8 kHz vocoder output to the channel audio rate with a windowed-sinc interpolator. */
class VoiceResampler(private val outputRate: Int) {
    private val factor = outputRate / 8_000
    private val taps = com.sdrnova.t25.dsp.FirDesign.lowPass(outputRate.toDouble(), 3_400.0, 4_400.0, 50.0)
    private val history = FloatArray(taps.size)

    init {
        require(factor * 8_000 == outputRate) { "output rate must be a multiple of 8 kHz" }
    }

    fun process(input: FloatArray, gain: Float = 24_000f): ShortArray {
        val out = ShortArray(input.size * factor)
        var o = 0
        for (sample in input) {
            for (phase in 0 until factor) {
                history.copyInto(history, 1, 0, history.size - 1)
                history[0] = if (phase == 0) sample * factor else 0f
                var acc = 0f
                for (t in taps.indices) acc += taps[t] * history[t]
                out[o++] = (acc * gain).coerceIn(-32767f, 32767f).toInt().toShort()
            }
        }
        return out
    }
}
