package com.sdrnova.t25.source.rtlsdr

import java.io.IOException

/**
 * Rafael Micro R820T/R820T2 and R828D tuner control, ported from sdrtrunk's R8xEmbeddedTuner, R820TEmbeddedTuner
 * and R828DEmbeddedTuner. Register writes go through a shadow copy so unchanged values are never resent.
 * Callers must hold the RTL2832 I2C repeater open around every method here.
 */
internal class R82xxTuner(private val rtl: Rtl2832, val type: Type, private val isBlogV4: Boolean) : RtlTuner {
    override val label: String get() = type.label


    enum class Type(val label: String, val i2cAddress: Int, val vcoPowerRef: Int) {
        R820T("R820T", 0x34, 2),
        R828D("R828D", 0x74, 1),
    }

    private val shadow = intArrayOf(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x83, 0x32, 0x75, 0xC0, 0x40, 0xD6, 0x6C, 0xF5, 0x63, 0x75, 0x68,
        0x6C, 0x83, 0x80, 0x00, 0x0F, 0x00, 0xC0, 0x30, 0x48, 0xCC, 0x60, 0x00, 0x54, 0xAE, 0x4A, 0xC0,
    )

    override fun init() {
        // Low-IF operation: disable zero-IF, use only the in-phase ADC, and shift the 3.57 MHz IF back to DC.
        rtl.writeDemod(1, 0xB1, 0x1A, 1)
        rtl.writeDemod(0, 0x08, 0x4D, 1)
        rtl.setIfFrequency(IF_FREQUENCY)
        rtl.writeDemod(1, 0x15, 0x01, 1)

        for (register in 5 until shadow.size) rtl.i2cWriteRegister(type.i2cAddress, register, shadow[register])
        setTvStandard()
        systemFrequencySelect()
    }

    override fun setFrequency(hz: Long) {
        val lo = hz + IF_FREQUENCY
        setMux(lo)
        setPll(lo)
        if (type == Type.R828D) {
            if (isBlogV4) {
                val notch = hz < 2_200_000 || hz in 85_000_001 until 112_000_000 || hz in 172_000_001 until 242_000_000
                write(Reg.DRAIN, if (notch) 0x00 else 0x08)
                when {
                    hz <= 28_800_000 -> {
                        write(Reg.INPUT_AIR, 0x20)
                        write(Reg.INPUT_CABLE_1, 0x00)
                        write(Reg.INPUT_CABLE_2, 0x08)
                    }
                    hz < 250_000_000 -> {
                        write(Reg.INPUT_AIR, 0x20)
                        write(Reg.INPUT_CABLE_1, 0x40)
                        write(Reg.INPUT_CABLE_2, 0x00)
                    }
                    else -> {
                        write(Reg.INPUT_AIR, 0x00)
                        write(Reg.INPUT_CABLE_1, 0x00)
                        write(Reg.INPUT_CABLE_2, 0x00)
                    }
                }
            } else {
                write(Reg.INPUT_AIR_AND_CABLE_1, if (hz > 345_000_000) 0x00 else 0x60)
            }
        }
    }

    override fun setGain(step: Int?) {
        if (step == null) {
            write(Reg.LNA_GAIN, 0x00)
            write(Reg.MIXER_GAIN, 0x10)
            write(Reg.VGA_GAIN, 0x0B)
        } else {
            // Each step raises the LNA and mixer gains alternately, with the IF (VGA) gain held at 21 dB.
            write(Reg.LNA_GAIN, 0x10 + (step + 1) / 2)
            write(Reg.MIXER_GAIN, step / 2)
            write(Reg.VGA_GAIN, 0x08)
        }
    }

    private fun setTvStandard() {
        write(Reg.XTAL_CHECK, 0x00)
        write(Reg.VERSION, VERSION)
        write(Reg.LNA_TOP, 0x00)
        var calibration = 0
        repeat(2) {
            write(Reg.FILTER_CAPACITOR, 0x6B)
            write(Reg.CALIBRATION_CLOCK, 0x04)
            write(Reg.PLL_XTAL_CAPACITOR, 0x00)
            setPll(56_000_000)
            write(Reg.CALIBRATION_TRIGGER, 0x10)
            write(Reg.CALIBRATION_TRIGGER, 0x00)
            write(Reg.CALIBRATION_CLOCK, 0x00)
            calibration = status(4) and 0x0F
        }
        if (calibration == 0x0F) calibration = 0
        write(Reg.FILTER_CALIBRATION_CODE, calibration or 0x10)
        write(Reg.BANDWIDTH_FILTER_GAIN_HIGHPASS, 0x6B)
        write(Reg.IMAGE_REVERSE, 0x00)
        write(Reg.FILTER_GAIN, 0x10)
        write(Reg.CHANNEL_FILTER_EXTENSION, 0x60)
        write(Reg.LOOP_THROUGH, 0x00)
        write(Reg.LOOP_THROUGH_ATTENUATION, 0x00)
        write(Reg.FILTER_EXTENSION_WIDEST, 0x00)
        write(Reg.RF_POLY_FILTER_CURRENT, 0x60)
    }

    private fun systemFrequencySelect() {
        write(Reg.LNA_TOP2, 0xE5)
        val mixerTop = 0x24
        write(Reg.MIXER_TOP, mixerTop)
        write(Reg.LNA_VTH_L, 0x53)
        write(Reg.MIXER_VTH_L, 0x75)
        write(Reg.INPUT_AIR_AND_CABLE_1, 0x00)
        write(Reg.INPUT_CABLE_2, 0x00)
        write(Reg.CP_CUR, 0x38)
        write(Reg.DIVIDER_BUFFER_CURRENT, 0x30)
        write(Reg.FILTER_CURRENT, 0x40)
        write(Reg.LNA_TOP, 0x00)
        write(Reg.MIXER_TOP2, 0x00)
        write(Reg.PRE_DETECT, 0x00)
        write(Reg.AGC_CLOCK, 0x30)
        write(Reg.LNA_TOP, 0x18)
        write(Reg.MIXER_TOP2, mixerTop)
        write(Reg.LNA_DISCHARGE_CURRENT, 0x14)
        write(Reg.AGC_CLOCK, 0x20)
    }

    private fun setMux(lo: Long) {
        val range = RANGES.firstOrNull { lo in it.min..it.max }
        write(Reg.DRAIN, range?.openDrain ?: 0)
        write(Reg.RF_POLY_MUX, range?.rfMux ?: 0)
        write(Reg.TF_BAND, range?.trackingFilter ?: 0)
        write(Reg.PLL_XTAL_CAPACITOR_AND_DRIVE, 0x00)
        write(Reg.UNKNOWN_08, 0x00)
        write(Reg.UNKNOWN_09, 0x00)
    }

    private fun setPll(lo: Long) {
        write(Reg.REFERENCE_DIVIDER_2, 0x00)
        write(Reg.PLL_AUTOTUNE, 0x00)
        write(Reg.VCO_CURRENT, 0x80)

        val divider = DIVIDERS.firstOrNull { lo in it.min..it.max } ?: DIVIDERS[5]
        val vcoFineTune = (status(4) and 0x30) shr 4
        val dividerNumber = when {
            vcoFineTune < type.vcoPowerRef -> divider.number - 1
            vcoFineTune > type.vcoPowerRef -> divider.number + 1
            else -> divider.number
        }.coerceIn(0, 7)
        write(Reg.DIVIDER, dividerNumber shl 5)

        // The VCO runs at lo * mixDivider = 2 * xtal * (nint + sdm / 65536); nint 30 sits at the divider's minimum.
        val integral = ((lo - divider.min) / divider.step).toInt().coerceIn(0, 31)
        val nint = 30 + integral
        write(Reg.PLL, ((nint - 13) / 4) or (((nint - 13) % 4) shl 6))
        val fraction = (lo - divider.min - integral * divider.step).toDouble() / divider.step
        val sdm = (fraction * 0x10000).toInt() and 0xFFFF
        if (sdm != 0) {
            write(Reg.SDM_POWER, 0x00)
            write(Reg.SDM_MSB, (sdm shr 8) and 0xFF)
            write(Reg.SDM_LSB, sdm and 0xFF)
        } else {
            write(Reg.SDM_POWER, 0x08)
        }

        if (!pllLocked()) {
            write(Reg.VCO_CURRENT, 0x60)
            if (!pllLocked()) throw IOException("${type.label} PLL did not lock at ${lo - IF_FREQUENCY} Hz")
        }
        write(Reg.PLL_AUTOTUNE_VARIANT, 0x08)
    }

    private fun pllLocked() = status(2) and 0x40 != 0

    /** The R82xx returns its status registers bit-reversed. */
    private fun status(index: Int): Int {
        val raw = rtl.i2cRead(type.i2cAddress, 5)[index].toInt() and 0xFF
        return Integer.reverse(raw) ushr 24
    }

    private fun write(register: Reg, value: Int) {
        val current = shadow[register.address]
        val next = if (register.mask != 0) (current and register.mask.inv()) or (value and register.mask) else value and 0xFF
        if (next != current) {
            rtl.i2cWriteRegister(type.i2cAddress, register.address, next)
            shadow[register.address] = next
        }
    }

    private enum class Reg(val address: Int, val mask: Int) {
        LNA_GAIN(0x05, 0x1F),
        INPUT_AIR(0x05, 0x20),
        INPUT_CABLE_1(0x05, 0x40),
        INPUT_AIR_AND_CABLE_1(0x05, 0x60),
        LOOP_THROUGH(0x05, 0x80),
        INPUT_CABLE_2(0x06, 0x08),
        FILTER_GAIN(0x06, 0x30),
        PRE_DETECT(0x06, 0x40),
        MIXER_GAIN(0x07, 0x1F),
        IMAGE_REVERSE(0x07, 0x80),
        UNKNOWN_08(0x08, 0x3F),
        UNKNOWN_09(0x09, 0x3F),
        FILTER_CALIBRATION_CODE(0x0A, 0x1F),
        FILTER_CURRENT(0x0A, 0x60),
        CALIBRATION_TRIGGER(0x0B, 0x10),
        FILTER_CAPACITOR(0x0B, 0x60),
        BANDWIDTH_FILTER_GAIN_HIGHPASS(0x0B, 0xEF),
        XTAL_CHECK(0x0C, 0x0F),
        VGA_GAIN(0x0C, 0x9F),
        LNA_VTH_L(0x0D, 0x00),
        MIXER_VTH_L(0x0E, 0x00),
        CALIBRATION_CLOCK(0x0F, 0x04),
        FILTER_EXTENSION_WIDEST(0x0F, 0x80),
        PLL_XTAL_CAPACITOR(0x10, 0x03),
        PLL_XTAL_CAPACITOR_AND_DRIVE(0x10, 0x0B),
        REFERENCE_DIVIDER_2(0x10, 0x10),
        DIVIDER(0x10, 0xE0),
        CP_CUR(0x11, 0x38),
        SDM_POWER(0x12, 0x08),
        VCO_CURRENT(0x12, 0xE0),
        VERSION(0x13, 0x3F),
        PLL(0x14, 0x00),
        SDM_LSB(0x15, 0x00),
        SDM_MSB(0x16, 0x00),
        DRAIN(0x17, 0x08),
        DIVIDER_BUFFER_CURRENT(0x17, 0x30),
        RF_POLY_FILTER_CURRENT(0x19, 0x60),
        PLL_AUTOTUNE(0x1A, 0x0C),
        PLL_AUTOTUNE_VARIANT(0x1A, 0x08),
        AGC_CLOCK(0x1A, 0x30),
        RF_POLY_MUX(0x1A, 0xC3),
        TF_BAND(0x1B, 0x00),
        MIXER_TOP(0x1C, 0xF8),
        MIXER_TOP2(0x1C, 0x04),
        LNA_TOP(0x1D, 0x38),
        LNA_TOP2(0x1D, 0xC7),
        CHANNEL_FILTER_EXTENSION(0x1E, 0x60),
        LNA_DISCHARGE_CURRENT(0x1E, 0x1F),
        LOOP_THROUGH_ATTENUATION(0x1F, 0x80),
    }

    private class Range(val min: Long, val max: Long, val openDrain: Int, val rfMux: Int, val trackingFilter: Int)

    private class Divider(val number: Int, val min: Long, val max: Long, val step: Long)

    private companion object {
        const val IF_FREQUENCY = 3_570_000
        const val VERSION = 49

        val RANGES = listOf(
            Range(24_000_000, 49_999_999, 0x08, 0x02, 0xDF),
            Range(50_000_000, 54_999_999, 0x08, 0x02, 0xBE),
            Range(55_000_000, 59_999_999, 0x08, 0x02, 0x8B),
            Range(60_000_000, 64_999_999, 0x08, 0x02, 0x7B),
            Range(65_000_000, 69_999_999, 0x08, 0x02, 0x69),
            Range(70_000_000, 74_999_999, 0x08, 0x02, 0x58),
            Range(75_000_000, 89_999_999, 0x00, 0x02, 0x44),
            Range(90_000_000, 109_999_999, 0x00, 0x02, 0x34),
            Range(110_000_000, 139_999_999, 0x00, 0x02, 0x24),
            Range(140_000_000, 179_999_999, 0x00, 0x02, 0x14),
            Range(180_000_000, 249_999_999, 0x00, 0x02, 0x13),
            Range(250_000_000, 279_999_999, 0x00, 0x02, 0x11),
            Range(280_000_000, 309_999_999, 0x00, 0x02, 0x00),
            Range(310_000_000, 587_999_999, 0x00, 0x41, 0x00),
            Range(588_000_000, 1_766_000_000, 0x00, 0x40, 0x00),
        )

        /** Mixer dividers 2..256 with the VCO kept in 1728-3571 MHz; step is the LO change per integer of nint. */
        val DIVIDERS = listOf(
            Divider(0, 864_000_000, 1_785_600_000, 28_800_000),
            Divider(1, 432_000_000, 892_800_000, 14_400_000),
            Divider(2, 216_000_000, 460_800_000, 7_200_000),
            Divider(3, 108_000_000, 223_200_000, 3_600_000),
            Divider(4, 54_000_000, 111_600_000, 1_800_000),
            Divider(5, 27_000_000, 55_800_000, 900_000),
            Divider(6, 13_500_000, 27_900_000, 450_000),
            Divider(7, 6_750_000, 13_950_000, 225_000),
        )
    }
}
