package com.sdrnova.t25.source

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.sdrnova.t25.source.airspy.AirspySource
import com.sdrnova.t25.source.hackrf.HackRfSource
import com.sdrnova.t25.source.rtlsdr.RtlSdrUsbSource

/** The USB radios SDRNova T25 can drive, and how to open each. */
object UsbRadios {
    enum class Kind(val label: String) {
        RTL_SDR("RTL-SDR"),
        HACKRF("HackRF"),
        AIRSPY("Airspy"),
    }

    fun kindOf(device: UsbDevice): Kind? = when {
        RtlSdrUsbSource.isRtlSdr(device) -> Kind.RTL_SDR
        HackRfSource.isHackRf(device) -> Kind.HACKRF
        AirspySource.isAirspy(device) -> Kind.AIRSPY
        else -> null
    }

    fun find(context: Context): UsbDevice? =
        context.getSystemService(UsbManager::class.java).deviceList.values.firstOrNull { kindOf(it) != null }

    /** Opens [device] at the rate closest to [requestedRate] that the hardware supports. */
    fun open(context: Context, device: UsbDevice, requestedRate: Int): IqSource = when (kindOf(device)) {
        Kind.RTL_SDR -> RtlSdrUsbSource.open(context, device, requestedRate)
        Kind.HACKRF -> HackRfSource.open(context, device, HackRfSource.sampleRateFor(requestedRate))
        Kind.AIRSPY -> AirspySource.open(context, device, requestedRate)
        null -> throw IllegalArgumentException("not a supported radio")
    }
}
