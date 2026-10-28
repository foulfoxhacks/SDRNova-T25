package com.sdrnova.t25.source.rtlsdr

import java.io.IOException

/** A tuner chip behind the RTL2832U's I2C repeater. Callers hold the repeater open around every call. */
internal interface RtlTuner {
    val label: String

    fun init()

    fun setFrequency(hz: Long)

    /** [step] indexes [com.sdrnova.t25.source.IqSource.GAIN_STEPS]; null selects automatic gain. */
    fun setGain(step: Int?)
}

/**
 * Fitipower FC0013, ported from sdrtrunk's FC0013EmbeddedTuner. It runs zero-IF, so the RTL2832 keeps its default
 * zero-IF configuration.
 */
internal class Fc0013Tuner(private val rtl: Rtl2832) : RtlTuner {
    override val label = "FC0013"

    override fun init() {
        INITIAL.forEachIndexed { register, value -> if (register > 0) write(register, value) }
        writeMasked(0x0D, 0x08, 0xF7)
        write(0x13, 0x0A)
    }

    override fun setFrequency(hz: Long) {
        setVhfTracking(hz)
        setFilters(hz)
        val divider = DIVIDERS.firstOrNull { hz in it.min..it.max } ?: DIVIDERS[5]
        val integralStep = XTAL / 2 / divider.divider
        val vcoSelect = hz.toDouble() / integralStep >= 212.5
        var integral = (hz / integralStep).toInt()
        var pm = (integral / 8).coerceIn(11, 31)
        var am = integral - pm * 8
        if (am < 2) {
            am += 8
            pm--
        }
        am = am.coerceAtMost(15)
        integral = pm * 8 + am
        val fractionalStep = XTAL.toDouble() / divider.divider / 65_536
        val fractional = Math.round((hz - integral.toLong() * integralStep) / fractionalStep).toInt()
        if (pm !in 11..31 || am !in 2..15 || fractional !in 0..65_535) {
            throw IOException("FC0013 has no PLL setting for $hz Hz")
        }

        // Register 5 gets the low three bits set for the Realtek demodulator.
        val register5 = divider.register5 or 0x07
        var register6 = if (divider.threeX) 0xA0 else 0xA2
        if (vcoSelect) register6 = register6 or 0x08
        write(0x01, am)
        write(0x02, pm)
        write(0x03, (fractional shr 8) and 0xFF)
        write(0x04, fractional and 0xFF)
        write(0x05, register5)
        write(0x06, register6)
        val r11 = read(0x11)
        write(0x11, if (divider.divider == 64) r11 or 0x04 else r11 and 0xFB)

        var calibration = calibrate()
        val retry = (vcoSelect && calibration > 0x3C) || (!vcoSelect && calibration < 0x02)
        if (retry) {
            register6 = if (vcoSelect) register6 and 0x08.inv() else register6 or 0x08
            write(0x06, register6)
            calibration = calibrate()
            if ((!vcoSelect && calibration < 0x02) || (vcoSelect && calibration > 0x3C)) {
                throw IOException("FC0013 VCO calibration out of range at $hz Hz")
            }
        }
    }

    override fun setGain(step: Int?) {
        if (step == null) {
            writeMasked(0x0D, 0x00, 0xF7)
        } else {
            writeMasked(0x14, LNA_GAINS[step * (LNA_GAINS.size - 1) / 29], 0xE0)
            writeMasked(0x0D, 0x08, 0xF7)
        }
        write(0x13, 0x0A)
    }

    private fun calibrate(): Int {
        write(0x0E, 0x80)
        write(0x0E, 0x00)
        write(0x0E, 0x00)
        return read(0x0E) and 0x3F
    }

    private fun setFilters(hz: Long) {
        writeMasked(0x07, if (hz < 300_000_000) 0x10 else 0x00, 0xEF)
        writeMasked(0x14, if (hz < 300_000_000) 0x00 else 0x40, 0x1F)
    }

    private fun setVhfTracking(hz: Long) {
        val track = when {
            hz <= 177_500_000 -> 0x1C
            hz <= 184_500_000 -> 0x18
            hz <= 191_500_000 -> 0x14
            hz <= 198_500_000 -> 0x10
            hz <= 205_500_000 -> 0x0C
            hz <= 219_500_000 -> 0x08
            hz <= 300_000_000 -> 0x04
            else -> 0x1C
        }
        write(0x1D, (read(0x1D) and 0xE3) or track)
    }

    private fun write(register: Int, value: Int) = rtl.i2cWriteRegister(ADDRESS, register, value)

    private fun read(register: Int): Int = rtl.i2cReadRegister(ADDRESS, register)

    /** As sdrtrunk does it: keep the bits set in [keep], then OR in [value]. */
    private fun writeMasked(register: Int, value: Int, keep: Int) = write(register, (read(register) and keep) or value)

    private class Divider(val divider: Int, val threeX: Boolean, val register5: Int, val min: Long, val max: Long)

    companion object {
        const val ADDRESS = 0xC6
        const val CHIP_ID = 0xA3
        private const val XTAL = 28_800_000

        private val INITIAL = intArrayOf(
            0x00, 0x09, 0x16, 0x00, 0x00, 0x17, 0x02, 0x2A, 0xFF, 0x6E, 0xB8,
            0x82, 0xFE, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x50, 0x01,
        )

        /** LNA gain register values from lowest to highest gain. */
        private val LNA_GAINS = intArrayOf(
            0x02, 0x03, 0x05, 0x04, 0x00, 0x07, 0x01, 0x06, 0x0F, 0x0E, 0x0D, 0x0C,
            0x0B, 0x0A, 0x09, 0x08, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, 0x10,
        )

        private val DIVIDERS = listOf(
            Divider(96, true, 0x82, 13_500_000, 39_749_997),
            Divider(64, false, 0x02, 20_250_000, 59_624_996),
            Divider(48, true, 0x42, 27_000_000, 79_499_995),
            Divider(32, false, 0x82, 40_500_000, 119_249_993),
            Divider(24, true, 0x22, 54_000_000, 158_999_990),
            Divider(16, false, 0x42, 81_000_000, 238_499_986),
            Divider(12, true, 0x12, 108_000_000, 317_999_981),
            Divider(8, false, 0x22, 162_000_000, 476_999_972),
            Divider(6, true, 0x0A, 235_200_000, 635_999_963),
            Divider(4, false, 0x12, 514_900_000, 953_999_945),
            Divider(2, false, 0x0A, 648_000_000, 1_907_999_890),
        )
    }
}
