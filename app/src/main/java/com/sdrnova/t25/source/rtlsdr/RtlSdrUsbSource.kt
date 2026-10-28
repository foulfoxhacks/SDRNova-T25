package com.sdrnova.t25.source.rtlsdr

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.sdrnova.t25.source.IqSource
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** An RTL2832U dongle with an R820T/R820T2/R828D tuner, driven directly through Android's USB host API. */
class RtlSdrUsbSource private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val rtl: Rtl2832,
    private val tuner: R82xxTuner,
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
        val requests = List(REQUEST_COUNT) { UsbRequest().apply { initialize(connection, endpoint) } }
        val chunk = ByteArray(BUFFER_SIZE)
        try {
            for (request in requests) {
                val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                request.clientData = buffer
                if (!request.queue(buffer)) throw IOException("could not queue USB transfer")
            }
            while (running) {
                val done = try {
                    connection.requestWait(500)
                } catch (_: TimeoutException) {
                    continue
                } ?: throw IOException("USB transfer failed; was the dongle unplugged?")
                val buffer = done.clientData as ByteBuffer
                val length = buffer.position()
                buffer.flip()
                buffer.get(chunk, 0, length)
                buffer.clear()
                if (length > 0) sink(chunk, length)
                if (running && !done.queue(buffer)) throw IOException("could not re-queue USB transfer")
            }
        } finally {
            running = false
            for (request in requests) {
                request.cancel()
                request.close()
            }
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
                val usbInterface = device.getInterface(0)
                if (!connection.claimInterface(usbInterface, true)) throw IOException("could not claim the USB interface")
                val endpoint = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
                } ?: throw IOException("no bulk IN endpoint on the dongle")

                val rtl = Rtl2832(connection)
                rtl.initBaseband()
                val type = detectTuner(rtl) ?: throw IOException(
                    "Unsupported tuner chip. SDRNova T25 currently drives R820T, R820T2 and R828D dongles.",
                )
                val blogV4 = runCatching { device.manufacturerName == "RTLSDRBlog" && device.productName == "Blog V4" }
                    .getOrDefault(false)
                val tuner = R82xxTuner(rtl, type, blogV4)
                rtl.setI2cRepeater(true)
                try {
                    tuner.init()
                } finally {
                    rtl.setI2cRepeater(false)
                }
                rtl.setSampleRate(sampleRate)
                val name = runCatching { device.productName }.getOrNull() ?: "RTL-SDR"
                val label = if (blogV4) "RTL-SDR Blog V4" else name
                return RtlSdrUsbSource(connection, usbInterface, endpoint, rtl, tuner, "$label (${type.label})", sampleRate)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }

        private fun detectTuner(rtl: Rtl2832): R82xxTuner.Type? {
            rtl.setI2cRepeater(true)
            try {
                for (type in R82xxTuner.Type.entries) {
                    val id = runCatching { rtl.i2cReadRegister(type.i2cAddress, 0x00) }.getOrNull()
                    if (id == 0x69) return type
                }
                return null
            } finally {
                rtl.setI2cRepeater(false)
            }
        }
    }
}
