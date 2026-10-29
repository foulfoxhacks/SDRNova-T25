package com.sdrnova.t25.source.rtlsdr

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.UsbBulk
import java.io.IOException
import java.nio.ByteBuffer

/** An RTL2832U dongle with an R820T/R820T2/R828D tuner, driven directly through Android's USB host API. */
class RtlSdrUsbSource private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val rtl: Rtl2832,
    private val tuner: RtlTuner,
    override val description: String,
    override val sampleRate: Int,
) : IqSource {

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

    override fun setGain(tenthsDb: Int) {
        val step = if (tenthsDb == IqSource.AUTO_GAIN) null else
            IqSource.GAIN_STEPS.indices.minBy { kotlin.math.abs(IqSource.GAIN_STEPS[it] - tenthsDb) }
        synchronized(lock) { withRepeater { tuner.setGain(step) } }
    }

    override fun setBiasTee(enabled: Boolean) {
        synchronized(lock) { rtl.setBiasTee(enabled) }
    }

    /** A crystal that runs fast by [ppm] makes every synthesized frequency fast by the same ratio. */
    private fun retune() {
        val corrected = (requestedHz / (1 + ppm / 1e6)).toLong()
        withRepeater { tuner.setFrequency(corrected) }
    }

    private inline fun withRepeater(block: () -> Unit) {
        rtl.setI2cRepeater(true)
        try {
            block()
        } finally {
            rtl.setI2cRepeater(false)
        }
    }

    override fun stream(sink: (ByteArray, Int) -> Unit) {
        running = true
        synchronized(lock) { rtl.resetBuffer() }
        val chunk = ByteArray(BUFFER_SIZE)
        try {
            UsbBulk.stream(connection, endpoint, BUFFER_SIZE, REQUEST_COUNT, "dongle", { running }) { buffer ->
                val length = buffer.remaining()
                buffer.get(chunk, 0, length)
                if (length > 0) sink(chunk, length)
            }
        } finally {
            running = false
        }
    }

    override fun stop() {
        running = false
    }

    override fun close() {
        running = false
        synchronized(lock) {
            runCatching { rtl.powerDown() }
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 16_384
        private const val REQUEST_COUNT = 12

        private val SUPPORTED = setOf(
            0x0BDA to 0x2832, 0x0BDA to 0x2838, 0x0CCD to 0x00A9, 0x0CCD to 0x00B3, 0x0CCD to 0x00D3,
            0x0CCD to 0x00E0, 0x185B to 0x0620, 0x185B to 0x0650, 0x1F4D to 0xB803, 0x1F4D to 0xC803,
            0x1B80 to 0xD3A4, 0x1D19 to 0x1101, 0x1D19 to 0x1102, 0x1D19 to 0x1103, 0x0458 to 0x707F,
            0x1B80 to 0xD393, 0x1B80 to 0xD394, 0x1B80 to 0xD395, 0x1B80 to 0xD39D,
        )

        fun isRtlSdr(device: UsbDevice) = (device.vendorId to device.productId) in SUPPORTED

        fun find(context: Context): UsbDevice? =
            context.getSystemService(UsbManager::class.java).deviceList.values.firstOrNull(::isRtlSdr)

        /** Opens and initializes the dongle. The app must already hold USB permission for [device]. */
        fun open(context: Context, device: UsbDevice, sampleRate: Int): RtlSdrUsbSource {
            val manager = context.getSystemService(UsbManager::class.java)
            val connection = manager.openDevice(device) ?: throw IOException("Android refused to open the USB device")
            try {
                val (usbInterface, endpoint) = UsbBulk.findBulkIn(device) ?: throw IOException("no bulk IN endpoint on the dongle")
                if (!connection.claimInterface(usbInterface, true)) throw IOException("could not claim the USB interface")

                val rtl = Rtl2832(connection)
                rtl.initBaseband()
                val blogV4 = runCatching { device.manufacturerName == "RTLSDRBlog" && device.productName == "Blog V4" }
                    .getOrDefault(false)
                val tuner = detectTuner(rtl, blogV4) ?: throw IOException(
                    "Unsupported tuner chip. SDRNova T25 drives R820T, R820T2, R828D and FC0013 dongles.",
                )
                rtl.setI2cRepeater(true)
                try {
                    tuner.init()
                } finally {
                    rtl.setI2cRepeater(false)
                }
                rtl.setSampleRate(sampleRate)
                val name = runCatching { device.productName }.getOrNull() ?: "RTL-SDR"
                val label = if (blogV4) "RTL-SDR Blog V4" else name
                return RtlSdrUsbSource(connection, usbInterface, endpoint, rtl, tuner, "$label (${tuner.label})", sampleRate)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }

        /** Probes each supported chip's ID register, as sdrtrunk and librtlsdr do. */
        private fun detectTuner(rtl: Rtl2832, blogV4: Boolean): RtlTuner? {
            rtl.setI2cRepeater(true)
            try {
                for (type in R82xxTuner.Type.entries) {
                    val id = runCatching { rtl.i2cReadRegister(type.i2cAddress, 0x00) }.getOrNull()
                    if (id == 0x69) return R82xxTuner(rtl, type, blogV4)
                }
                val fc0013 = runCatching { rtl.i2cReadRegister(Fc0013Tuner.ADDRESS, 0x00) }.getOrNull()
                if (fc0013 == Fc0013Tuner.CHIP_ID) return Fc0013Tuner(rtl)
                return null
            } finally {
                rtl.setI2cRepeater(false)
            }
        }
    }
}
