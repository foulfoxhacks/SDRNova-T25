package com.sdrnova.t25.source

/** A stream of unsigned 8-bit interleaved I/Q, like an RTL-SDR produces. */
interface IqSource : AutoCloseable {
    val description: String
    val sampleRate: Int

    fun setCenterFrequency(hz: Long)

    /** Tuner gain in tenths of a dB, or [AUTO_GAIN]. */
    fun setGain(tenthsDb: Int)

    /** Crystal error in parts per million. */
    fun setPpm(ppm: Int)

    fun setBiasTee(enabled: Boolean) {}

    /** Streams on the calling thread until [stop] or an error; [sink] must copy the buffer before returning. */
    fun stream(sink: (ByteArray, Int) -> Unit)

    fun stop()

    companion object {
        const val AUTO_GAIN = -1

        /** R820T gain steps in tenths of a dB, the values sdrtrunk and rtl_tcp both use. */
        val GAIN_STEPS = intArrayOf(
            0, 9, 14, 26, 36, 76, 86, 124, 143, 156, 165, 196, 208, 228, 253, 279,
            296, 327, 337, 363, 371, 385, 401, 420, 433, 438, 444, 479, 482, 495,
        )
    }
}
