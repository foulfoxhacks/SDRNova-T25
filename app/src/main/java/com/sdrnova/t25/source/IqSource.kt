package com.sdrnova.t25.source

import com.sdrnova.t25.dsp.SampleFormat

/** A stream of raw interleaved I/Q from a tuner. */
interface IqSource : AutoCloseable {
    val description: String
    val sampleRate: Int
    val format: SampleFormat get() = SampleFormat.UNSIGNED_8

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

        /** RTL-SDR rates that decimate cleanly to the 48 kHz channel rate. */
        val RTL_SAMPLE_RATES = intArrayOf(960_000, 1_920_000, 2_400_000)

        /** R820T gain steps in tenths of a dB, the values sdrtrunk and rtl_tcp both use. */
        val GAIN_STEPS = intArrayOf(
            0, 9, 14, 26, 36, 76, 86, 124, 143, 156, 165, 196, 208, 228, 253, 279,
            296, 327, 337, 363, 371, 385, 401, 420, 433, 438, 444, 479, 482, 495,
        )
    }
}
