package com.sdrnova.t25.voice

import jmbe.JMBEAudioLibrary
import jmbe.codec.ambe.AMBEAudioCodec
import jmbe.codec.imbe.IMBEAudioCodec

/**
 * The built-in vocoder: JMBE's IMBE and AMBE+2 codecs, compiled in from the vocoder module. JMBE reads frames
 * most-significant bit first (BinaryFrame.fromBytes), the same layout the decoders here produce.
 */
object VocoderLoader {
    private val library by lazy { JMBEAudioLibrary() }

    val provider: VocoderProvider = object : VocoderProvider {
        override val name: String get() = "JMBE ${library.version} (built in)"

        override fun createImbeDecoder(): VoiceDecoder = decoder(IMBEAudioCodec.CODEC_NAME)

        override fun createAmbeDecoder(): VoiceDecoder = decoder(AMBEAudioCodec.CODEC_NAME)
    }

    private fun decoder(codecName: String): VoiceDecoder {
        val codec = library.getAudioConverter(codecName)
        return object : VoiceDecoder {
            override fun decode(frame: ByteArray): FloatArray = codec.getAudio(frame)

            override fun reset() = codec.reset()
        }
    }
}
