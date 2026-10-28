package com.sdrnova.t25.source.rtlsdr

import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * Register access to the RTL2832U demodulator over USB vendor control transfers, ported from sdrtrunk's
 * RTL2832TunerController (which follows librtlsdr). Not thread-safe; callers serialize access.
 */
internal class Rtl2832(private val connection: UsbDeviceConnection) {

    fun writeRegister(block: Int, address: Int, value: Int, length: Int) {
        controlOut(address, (block shl 8) or 0x10, bigEndian(value, length))
    }

    fun readRegister(block: Int, address: Int, length: Int): Int =
        littleEndian(controlIn(address, block shl 8, length))

    fun writeDemod(page: Int, address: Int, value: Int, length: Int) {
        controlOut((address shl 8) or 0x20, 0x10 or page, bigEndian(value, length))
        // librtlsdr and sdrtrunk both follow every demod write with this dummy read.
        readDemod(0x0A, 0x01, 1)
    }

    fun readDemod(page: Int, address: Int, length: Int): Int =
        littleEndian(controlIn((address shl 8) or 0x20, page, length))

    fun i2cWrite(i2cAddress: Int, data: ByteArray) = controlOut(i2cAddress, (BLOCK_IIC shl 8) or 0x10, data)

    fun i2cRead(i2cAddress: Int, length: Int): ByteArray = controlIn(i2cAddress, BLOCK_IIC shl 8, length)

    fun i2cReadRegister(i2cAddress: Int, register: Int): Int {
        i2cWrite(i2cAddress, byteArrayOf(register.toByte()))
        return i2cRead(i2cAddress, 1)[0].toInt() and 0xFF
    }

    fun i2cWriteRegister(i2cAddress: Int, register: Int, value: Int) =
        i2cWrite(i2cAddress, byteArrayOf(register.toByte(), value.toByte()))

    fun setI2cRepeater(enabled: Boolean) = writeDemod(1, 0x01, if (enabled) 0x18 else 0x10, 1)

    fun initBaseband() {
        // USB
        writeRegister(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeRegister(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeRegister(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        // Power on and reset the demodulator
        writeRegister(BLOCK_SYS, DEMOD_CTL_1, 0x22, 1)
        writeRegister(BLOCK_SYS, DEMOD_CTL, 0xE8, 1)
        writeDemod(1, 0x01, 0x14, 1)
        writeDemod(1, 0x01, 0x10, 1)
        // No spectrum inversion or adjacent channel rejection yet; clear DDC shift and IF frequency
        writeDemod(1, 0x15, 0x00, 1)
        writeDemod(1, 0x16, 0x0000, 2)
        for (register in 0x16..0x1B) writeDemod(1, register, 0x00, 1)
        FIR_COEFFICIENTS.forEachIndexed { index, value -> writeDemod(1, 0x1C + index, value, 1) }
        // SDR mode, AGCs off, PID filter off, default ADC datapath, zero-IF on, no 4.096 MHz clock output
        writeDemod(0, 0x19, 0x05, 1)
        writeDemod(1, 0x93, 0xF0, 1)
        writeDemod(1, 0x94, 0x0F, 1)
        writeDemod(1, 0x11, 0x00, 1)
        writeDemod(1, 0x04, 0x00, 1)
        writeDemod(0, 0x61, 0x60, 1)
        writeDemod(0, 0x06, 0x80, 1)
        writeDemod(1, 0xB1, 0x1B, 1)
        writeDemod(0, 0x0D, 0x83, 1)
    }

    fun powerDown() = writeRegister(BLOCK_SYS, DEMOD_CTL, 0x20, 1)

    fun setIfFrequency(hz: Int) {
        val value = -((1L shl 22) * hz / XTAL_HZ)
        writeDemod(1, 0x19, ((value shr 16) and 0x3F).toInt(), 1)
        writeDemod(1, 0x1A, ((value shr 8) and 0xFF).toInt(), 1)
        writeDemod(1, 0x1B, (value and 0xFF).toInt(), 1)
    }

    /** Valid rates are 225001-300000 and 900001-3200000 samples per second. */
    fun setSampleRate(rate: Int) {
        val ratio = ((XTAL_HZ.toLong() shl 22) / rate).toInt() and 0x0FFFFFFC
        writeDemod(1, 0x9F, (ratio ushr 16) and 0xFFFF, 2)
        writeDemod(1, 0xA1, ratio and 0xFFFF, 2)
        writeDemod(1, 0x3F, 0x00, 1)
        writeDemod(1, 0x3E, 0x00, 1)
        writeDemod(1, 0x01, 0x14, 1)
        writeDemod(1, 0x01, 0x10, 1)
    }

    fun resetBuffer() {
        writeRegister(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeRegister(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
    }

    /** GPIO 0 powers the bias tee on RTL-SDR Blog dongles. */
    fun setBiasTee(enabled: Boolean) {
        val bit = 0x01
        // Make the pin an output (librtlsdr's rtlsdr_set_gpio_output), then drive it.
        writeRegister(BLOCK_SYS, GPD, readRegister(BLOCK_SYS, GPD, 1) and bit.inv(), 1)
        writeRegister(BLOCK_SYS, GPOE, readRegister(BLOCK_SYS, GPOE, 1) or bit, 1)
        val output = readRegister(BLOCK_SYS, GPO, 1)
        writeRegister(BLOCK_SYS, GPO, if (enabled) output or bit else output and bit.inv(), 1)
    }

    private fun controlOut(value: Int, index: Int, data: ByteArray) {
        val result = connection.controlTransfer(OUT, 0, value, index, data, data.size, TIMEOUT_MS)
        if (result != data.size) {
            throw IOException("USB write failed (value 0x%04X index 0x%04X): %d".format(value, index, result))
        }
    }

    private fun controlIn(value: Int, index: Int, length: Int): ByteArray {
        val data = ByteArray(length)
        val result = connection.controlTransfer(IN, 0, value, index, data, length, TIMEOUT_MS)
        if (result != length) {
            throw IOException("USB read failed (value 0x%04X index 0x%04X): %d".format(value, index, result))
        }
        return data
    }

    private fun bigEndian(value: Int, length: Int): ByteArray = when (length) {
        1 -> byteArrayOf(value.toByte())
        2 -> byteArrayOf((value shr 8).toByte(), value.toByte())
        else -> throw IllegalArgumentException("register writes are 1 or 2 bytes")
    }

    private fun littleEndian(data: ByteArray): Int =
        if (data.size == 1) data[0].toInt() and 0xFF else (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

    companion object {
        const val XTAL_HZ = 28_800_000

        private const val IN = 0xC0
        private const val OUT = 0x40
        private const val TIMEOUT_MS = 1000

        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2
        private const val BLOCK_IIC = 6

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000
        private const val GPO = 0x3001
        private const val GPOE = 0x3003
        private const val GPD = 0x3004
        private const val DEMOD_CTL_1 = 0x300B

        private val FIR_COEFFICIENTS = intArrayOf(
            0xCA, 0xDC, 0xD7, 0xD8, 0xE0, 0xF2, 0x0E, 0x35, 0x06, 0x50,
            0x9C, 0x0D, 0x71, 0x11, 0x14, 0x71, 0x74, 0x19, 0x41, 0xA5,
        )
    }
}
