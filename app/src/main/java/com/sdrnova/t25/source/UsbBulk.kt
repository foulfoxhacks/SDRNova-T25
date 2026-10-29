package com.sdrnova.t25.source

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException

/** Sample streaming shared by the USB radios, which all deliver I/Q (or real samples) on one bulk IN endpoint. */
object UsbBulk {
    /** The first bulk IN endpoint on any of [device]'s interfaces, with the interface to claim for it. */
    fun findBulkIn(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e) ?: continue
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_IN) {
                    return usbInterface to endpoint
                }
            }
        }
        return null
    }

    /**
     * Keeps [requestCount] transfers of [bufferSize] bytes queued on [endpoint] and hands each filled buffer to
     * [consume] (positioned at 0, limit at the end of the data, little-endian) until [isRunning] turns false.
     * [radio] names the device in errors.
     */
    fun stream(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        bufferSize: Int,
        requestCount: Int,
        radio: String,
        isRunning: () -> Boolean,
        consume: (ByteBuffer) -> Unit,
    ) {
        val requests = ArrayList<UsbRequest>(requestCount)
        // Requests the kernel still holds; each must come back through requestWait before it's freed.
        val pending: MutableSet<UsbRequest> = Collections.newSetFromMap(IdentityHashMap())
        try {
            repeat(requestCount) {
                val request = UsbRequest()
                if (!request.initialize(connection, endpoint)) throw IOException("could not set up USB transfers for the $radio")
                requests += request
                val buffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
                request.clientData = buffer
                if (!request.queue(buffer)) throw IOException("could not queue USB transfer")
                pending += request
            }
            while (isRunning()) {
                val done = try {
                    connection.requestWait(WAIT_MS)
                } catch (_: TimeoutException) {
                    continue
                } ?: throw IOException("USB transfer failed; was the $radio unplugged?")
                if (!pending.remove(done)) continue
                val buffer = done.clientData as ByteBuffer
                buffer.flip()
                consume(buffer)
                buffer.clear()
                if (isRunning()) {
                    if (!done.queue(buffer)) throw IOException("could not re-queue USB transfer")
                    pending += done
                }
            }
        } finally {
            reap(connection, pending)
            for (request in requests) request.close()
        }
    }

    /** Cancelled transfers still complete; collect them so none is freed while the kernel can still fill it. */
    private fun reap(connection: UsbDeviceConnection, pending: MutableSet<UsbRequest>) {
        for (request in pending) request.cancel()
        val deadline = System.nanoTime() + REAP_TIMEOUT_NS
        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            val done = try {
                connection.requestWait(REAP_WAIT_MS)
            } catch (_: TimeoutException) {
                continue
            } catch (_: RuntimeException) {
                break
            } ?: break
            pending.remove(done)
        }
    }

    private const val WAIT_MS = 500L
    private const val REAP_WAIT_MS = 100L
    private const val REAP_TIMEOUT_NS = 1_000_000_000L
}
