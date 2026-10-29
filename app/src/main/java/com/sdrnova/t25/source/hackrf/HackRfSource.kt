package com.sdrnova.t25.source.hackrf

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.UsbBulk
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HackRF One over Android's USB host API, using the vendor requests from sdrtrunk's HackRFTunerController (the same
 * ones libhackrf sends). Samples arrive as signed 8-bit I/Q.
 */
class HackRfSource private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    override val description: String,
    override val sampleRate: Int,
) : IqSource {
    override val format = SampleFormat.SIGNED_8

    private val lock = Any()
    private var requestedHz = 0L
    private var ppm = 0

    @Volatile
    private var running = false

    override fun setCenterFrequency(hz: Long) {
        synchronized(lock) {
            requestedHz = hz
            retune()
        }
    }

    override fun setPpm(ppm: Int) {
        synchronized(lock) {
            this.ppm = ppm
            if (requestedHz > 0) retune()
        }
    }

    private fun retune() {
        val hz = (requestedHz / (1 + ppm / 1e6)).toLong()
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((hz / 1_000_000).toInt())
            .putInt((hz % 1_000_000).toInt())
            .array()
        out(SET_FREQUENCY, 0, 0, data)
    }

    /** HackRF has no AGC; "automatic" picks a middle setting. Manual gain splits between the LNA and baseband VGA. */
    override fun setGain(tenthsDb: Int) {
        val totalDb = if (tenthsDb == IqSource.AUTO_GAIN) 36 else tenthsDb / 10
        val lna = ((totalDb * 0.6).toInt() / 8 * 8).coerceIn(0, 40)
        val vga = ((totalDb - lna) / 2 * 2 + 10).coerceIn(0, 62)
        synchronized(lock) {
            gainRequest(SET_LNA_GAIN, lna)
            gainRequest(SET_VGA_GAIN, vga)
            out(AMP_ENABLE, 0, 0)
        }
    }

    override fun setBiasTee(enabled: Boolean) {
        synchronized(lock) { out(ANTENNA_ENABLE, if (enabled) 1 else 0, 0) }
    }

    override fun stream(sink: (ByteArray, Int) -> Unit) {
        running = true
        synchronized(lock) { out(SET_TRANSCEIVER_MODE, MODE_RECEIVE, 0) }
        val chunk = ByteArray(BUFFER_SIZE)
        try {
            UsbBulk.stream(connection, endpoint, BUFFER_SIZE, REQUEST_COUNT, "HackRF", { running }) { buffer ->
                val length = buffer.remaining()
                buffer.get(chunk, 0, length)
                if (length > 0) sink(chunk, length)
            }
        } finally {
            running = false
            synchronized(lock) { runCatching { out(SET_TRANSCEIVER_MODE, MODE_OFF, 0) } }
        }
    }

    override fun stop() {
        running = false
    }

    override fun close() {
        running = false
        synchronized(lock) {
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    private fun out(request: Int, value: Int, index: Int, data: ByteArray = ByteArray(0)) {
        val result = connection.controlTransfer(OUT, request, value, index, data, data.size, TIMEOUT_MS)
        if (result < 0) throw IOException("HackRF request $request failed")
    }

    private fun gainRequest(request: Int, gain: Int) {
        val data = ByteArray(1)
        val result = connection.controlTransfer(IN, request, 0, gain, data, 1, TIMEOUT_MS)
        if (result != 1 || data[0].toInt() == 0) throw IOException("HackRF rejected gain $gain for request $request")
    }

    companion object {
        /** Rates the HackRF handles that also decimate to a 48 or 50 kHz channel. */
        val SAMPLE_RATES = intArrayOf(2_400_000, 4_800_000, 8_000_000)

        private const val IN = 0xC0
        private const val OUT = 0x40
        private const val TIMEOUT_MS = 1000
        private const val BUFFER_SIZE = 16_384
        private const val REQUEST_COUNT = 16

        private const val SET_TRANSCEIVER_MODE = 1
        private const val SET_SAMPLE_RATE = 6
        private const val BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val SET_FREQUENCY = 16
        private const val AMP_ENABLE = 17
        private const val SET_LNA_GAIN = 19
        private const val SET_VGA_GAIN = 20
        private const val ANTENNA_ENABLE = 23
        private const val MODE_OFF = 0
        private const val MODE_RECEIVE = 1

        /** MAX2837 baseband filter settings, in Hz. */
        private val FILTERS = intArrayOf(
            1_750_000, 2_500_000, 3_500_000, 5_000_000, 5_500_000, 6_000_000, 7_000_000, 8_000_000,
            9_000_000, 10_000_000, 12_000_000, 14_000_000, 15_000_000, 20_000_000, 24_000_000, 28_000_000,
        )

        private val SUPPORTED = setOf(0x1D50 to 0x6089, 0x1D50 to 0x604B, 0x1D50 to 0xCC15)

        fun isHackRf(device: UsbDevice) = (device.vendorId to device.productId) in SUPPORTED

        /** The nearest HackRF-friendly rate to [requested]; the HackRF won't go below 2 MS/s. */
        fun sampleRateFor(requested: Int): Int = SAMPLE_RATES.firstOrNull { it >= requested } ?: SAMPLE_RATES.last()

        fun open(context: Context, device: UsbDevice, sampleRate: Int): HackRfSource {
            val manager = context.getSystemService(UsbManager::class.java)
            val connection = manager.openDevice(device) ?: throw IOException("Android refused to open the HackRF")
            try {
                val (usbInterface, endpoint) = UsbBulk.findBulkIn(device) ?: throw IOException("no bulk IN endpoint on the HackRF")
                if (!connection.claimInterface(usbInterface, true)) throw IOException("could not claim the HackRF interface")
                val name = runCatching { device.productName }.getOrNull() ?: "HackRF"
                val source = HackRfSource(connection, usbInterface, endpoint, name, sampleRate)
                val rate = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).putInt(1).array()
                source.out(SET_SAMPLE_RATE, 0, 0, rate)
                // The widest filter that still rejects aliases: about three quarters of the sample rate.
                val filter = FILTERS.lastOrNull { it <= sampleRate * 3 / 4 } ?: FILTERS.first()
                source.out(BASEBAND_FILTER_BANDWIDTH_SET, filter and 0xFFFF, filter ushr 16)
                return source
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }
    }
}
