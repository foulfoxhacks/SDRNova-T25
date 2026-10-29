package com.sdrnova.t25.source.airspy

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.sdrnova.t25.dsp.RealToComplex
import com.sdrnova.t25.dsp.SampleFormat
import com.sdrnova.t25.receiver.ChannelReceiver
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.UsbBulk
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Airspy R2 and Mini over Android's USB host API, with the vendor requests from sdrtrunk's AirspyTunerController
 * (libairspy's protocol). The radio streams unpacked real 12-bit samples at twice the complex rate; they're turned
 * into I/Q here and handed on as signed 16-bit pairs.
 */
class AirspySource private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    override val description: String,
    override val sampleRate: Int,
) : IqSource {
    override val format = SampleFormat.SIGNED_16

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
        out(SET_FREQUENCY, 0, 0, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hz.toInt()).array())
    }

    /** Automatic uses the LNA and mixer AGCs; manual spreads the gain over LNA (0-14), mixer and IF (0-15). */
    override fun setGain(tenthsDb: Int) {
        synchronized(lock) {
            if (tenthsDb == IqSource.AUTO_GAIN) {
                request(SET_LNA_AGC, 1)
                request(SET_MIXER_AGC, 1)
                request(SET_VGA_GAIN, 9)
            } else {
                val fraction = (tenthsDb / 496f).coerceIn(0f, 1f)
                request(SET_LNA_AGC, 0)
                request(SET_MIXER_AGC, 0)
                request(SET_LNA_GAIN, (fraction * 14).toInt())
                request(SET_MIXER_GAIN, (fraction * 15).toInt())
                request(SET_VGA_GAIN, 5 + (fraction * 8).toInt())
            }
        }
    }

    /** Bias tee is GPIO port 1 pin 13, as libairspy's airspy_set_rf_bias drives it. */
    override fun setBiasTee(enabled: Boolean) {
        synchronized(lock) { out(GPIO_WRITE, if (enabled) 1 else 0, (1 shl 5) or 13) }
    }

    override fun stream(sink: (ByteArray, Int) -> Unit) {
        running = true
        val converter = RealToComplex()
        synchronized(lock) { out(RECEIVER_MODE, 1, 0) }
        val raw = ShortArray(BUFFER_SIZE / 2)
        val iq = FloatArray(BUFFER_SIZE / 2)
        val bytes = ByteArray(BUFFER_SIZE)
        try {
            UsbBulk.stream(connection, endpoint, BUFFER_SIZE, REQUEST_COUNT, "Airspy", { running }) { buffer ->
                val samples = buffer.remaining() / 2
                buffer.asShortBuffer().get(raw, 0, samples)

                val pairs = samples / 2
                converter.convert(raw, pairs, iq)
                for (k in 0 until pairs * 2) {
                    val value = (iq[k] * 32767f).coerceIn(-32768f, 32767f).toInt()
                    bytes[2 * k] = value.toByte()
                    bytes[2 * k + 1] = (value shr 8).toByte()
                }
                if (pairs > 0) sink(bytes, pairs * 4)
            }
        } finally {
            running = false
            synchronized(lock) { runCatching { out(RECEIVER_MODE, 0, 0) } }
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

    private fun out(command: Int, value: Int, index: Int, data: ByteArray = ByteArray(0)) {
        val result = connection.controlTransfer(OUT, command, value, index, data, data.size, TIMEOUT_MS)
        if (result < 0) throw IOException("Airspy command $command failed")
    }

    /** Airspy settings are IN requests that return one status byte. */
    private fun request(command: Int, index: Int): Int {
        val data = ByteArray(1)
        val result = connection.controlTransfer(IN, command, 0, index, data, 1, TIMEOUT_MS)
        if (result < 1) throw IOException("Airspy command $command failed")
        return data[0].toInt() and 0xFF
    }

    private fun readInts(command: Int, index: Int, count: Int): IntArray {
        val data = ByteArray(count * 4)
        val result = connection.controlTransfer(IN, command, 0, index, data, data.size, TIMEOUT_MS)
        if (result != data.size) throw IOException("Airspy command $command failed")
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { buffer.getInt() }
    }

    companion object {
        private const val IN = 0xC0
        private const val OUT = 0x40
        private const val TIMEOUT_MS = 1000
        private const val BUFFER_SIZE = 16_384
        private const val REQUEST_COUNT = 16

        private const val RECEIVER_MODE = 1
        private const val SET_SAMPLE_RATE = 12
        private const val SET_FREQUENCY = 13
        private const val SET_LNA_GAIN = 14
        private const val SET_MIXER_GAIN = 15
        private const val SET_VGA_GAIN = 16
        private const val SET_LNA_AGC = 17
        private const val SET_MIXER_AGC = 18
        private const val GPIO_WRITE = 21
        private const val GET_SAMPLE_RATES = 25
        private const val SET_PACKING = 26

        fun isAirspy(device: UsbDevice) = device.vendorId == 0x1D50 && device.productId == 0x60A1

        fun open(context: Context, device: UsbDevice, requestedRate: Int): AirspySource {
            val manager = context.getSystemService(UsbManager::class.java)
            val connection = manager.openDevice(device) ?: throw IOException("Android refused to open the Airspy")
            try {
                val (usbInterface, endpoint) = UsbBulk.findBulkIn(device) ?: throw IOException("no bulk IN endpoint on the Airspy")
                if (!connection.claimInterface(usbInterface, true)) throw IOException("could not claim the Airspy interface")
                val name = runCatching { device.productName }.getOrNull() ?: "Airspy"

                // The firmware lists its rates; pick the lowest one at or above the request that decimates cleanly.
                val probe = AirspySource(connection, usbInterface, endpoint, name, 0)
                val count = probe.readInts(GET_SAMPLE_RATES, 0, 1)[0]
                val rates = probe.readInts(GET_SAMPLE_RATES, count, count)
                val usable = rates.withIndex().filter { ChannelReceiver.isSupportedRate(it.value) }.sortedBy { it.value }
                val choice = usable.firstOrNull { it.value >= requestedRate } ?: usable.lastOrNull()
                    ?: throw IOException("the Airspy offers no usable sample rate (${rates.joinToString()})")
                probe.request(SET_PACKING, 0)
                probe.request(SET_SAMPLE_RATE, choice.index)
                return AirspySource(connection, usbInterface, endpoint, name, choice.value)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }
    }
}
