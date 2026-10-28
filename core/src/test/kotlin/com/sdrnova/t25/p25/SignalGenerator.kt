package com.sdrnova.t25.p25

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Builds 96-bit TSBKs field by field, then fills in the CRC. */
class TsbkBuilder(opcode: Int, vendor: Int = 0, lastBlock: Boolean = true) {
    private val bytes = ByteArray(12)

    init {
        set(0, 1, if (lastBlock) 1 else 0)
        set(2, 6, opcode.toLong())
        set(8, 8, vendor.toLong())
    }

    fun set(start: Int, length: Int, value: Long): TsbkBuilder {
        for (i in 0 until length) {
            val bit = (value ushr (length - 1 - i)) and 1L
            val index = start + i
            val mask = 1 shl (7 - index % 8)
            bytes[index / 8] = (if (bit == 1L) bytes[index / 8].toInt() or mask else bytes[index / 8].toInt() and mask.inv()).toByte()
        }
        return this
    }

    fun set(start: Int, length: Int, value: Int) = set(start, length, value.toLong())

    fun build(): ByteArray {
        set(80, 16, Crc16.ccitt(bytes, 80))
        return bytes.copyOf()
    }
}

/** Produces P25 Phase 1 control channel signals as RTL-SDR style unsigned 8-bit I/Q. */
object SignalGenerator {
    private const val BASE_RATE = 48_000
    private const val SPS = 10

    /** One TSDU frame in transmit order: sync, NID, TSBKs, with a status dibit after every 35 dibits. */
    fun tsduFrame(nac: Int, blocks: List<ByteArray>): IntArray {
        val payload = ArrayList<Int>()
        P25.SYNC_DIBITS.forEach { payload += it }
        val nid = Bch63.encode(nac, P25.DUID_TSDU) shl 1
        for (i in 0 until 32) payload += ((nid ushr (62 - 2 * i)) and 3L).toInt()
        blocks.forEach { block -> Trellis12.encode(block).forEach { payload += it } }
        val out = ArrayList<Int>()
        var index = 0
        var source = 0
        while (source < payload.size || index % 36 != 0) {
            out += when {
                index % 36 == 35 -> 2
                source < payload.size -> payload[source++]
                else -> 0
            }
            index++
        }
        return out.toIntArray()
    }

    /**
     * C4FM: each dibit is an impulse of ±600 or ±1800 Hz through the TIA transmit filter (raised cosine, alpha 0.2,
     * with inverse-sinc shaping so the receiver's integrate-and-dump completes the Nyquist response).
     */
    fun modulate(
        dibits: IntArray,
        outputRate: Int,
        carrierHz: Double,
        snrDb: Double,
        random: Random,
        amplitude: Double = 0.25,
    ): ByteArray {
        val filter = transmitFilter()
        val deviation = DoubleArray(dibits.size * SPS + filter.size)
        for ((k, dibit) in dibits.withIndex()) {
            val hz = when (dibit) {
                1 -> 1800.0
                0 -> 600.0
                2 -> -600.0
                else -> -1800.0
            }
            for (t in filter.indices) deviation[k * SPS + t] += hz * filter[t]
        }
        return toIq(deviation, outputRate, carrierHz, snrDb, random, amplitude)
    }

    /** Frequency-modulates a baseband deviation signal (Hz at 48 kHz) onto a carrier, adds noise, quantizes to u8. */
    fun toIq(
        deviation: DoubleArray,
        outputRate: Int,
        carrierHz: Double,
        snrDb: Double,
        random: Random,
        amplitude: Double = 0.25,
    ): ByteArray {
        val ratio = outputRate / BASE_RATE
        val count = (deviation.size - 1) * ratio
        // SNR is measured in a 12.5 kHz channel; the noise itself spreads across the whole sample rate.
        val sigma = sqrt(amplitude * amplitude / (2 * 10.0.pow(snrDb / 10) * (12_500.0 / outputRate)))
        val out = ByteArray(count * 2)
        var phase = 0.0
        for (n in 0 until count) {
            val t = n.toDouble() / ratio
            val whole = floor(t).toInt()
            val hz = deviation[whole] + (deviation[whole + 1] - deviation[whole]) * (t - whole)
            phase += 2 * PI * (hz + carrierHz) / outputRate
            if (phase > PI) phase -= 2 * PI else if (phase < -PI) phase += 2 * PI
            out[2 * n] = quantize(amplitude * cos(phase) + sigma * random.nextGaussian())
            out[2 * n + 1] = quantize(amplitude * sin(phase) + sigma * random.nextGaussian())
        }
        return out
    }

    fun noise(samples: Int, random: Random, sigma: Double = 0.05): ByteArray =
        ByteArray(samples * 2) { quantize(sigma * random.nextGaussian()) }

    private fun quantize(value: Double): Byte = (value * 127.5 + 127.5).roundToInt().coerceIn(0, 255).toByte()

    private fun transmitFilter(alpha: Double = 0.2, spanSymbols: Int = 8): DoubleArray {
        val period = 1 / P25.SYMBOL_RATE
        val length = (spanSymbols * SPS) or 1
        val middle = (length - 1) / 2
        val maxHz = (1 + alpha) / (2 * period)
        val steps = 4000
        val step = maxHz / steps
        val taps = DoubleArray(length) { n ->
            val t = (n - middle).toDouble() / BASE_RATE
            var sum = 0.0
            for (s in 0 until steps) {
                val f = (s + 0.5) * step
                sum += response(f, period, alpha) * cos(2 * PI * f * t)
            }
            val window = 0.54 + 0.46 * cos(PI * (n - middle) / (middle + 1))
            2 * sum * step * window
        }
        // An impulse every symbol should settle at its own amplitude.
        val scale = SPS / taps.sum()
        return DoubleArray(length) { taps[it] * scale }
    }

    private fun response(f: Double, period: Double, alpha: Double): Double {
        val lower = (1 - alpha) / (2 * period)
        val upper = (1 + alpha) / (2 * period)
        val raisedCosine = when {
            f <= lower -> 1.0
            f <= upper -> 0.5 * (1 + cos(PI * period / alpha * (f - lower)))
            else -> 0.0
        }
        val x = PI * f * period
        return raisedCosine * (if (x == 0.0) 1.0 else x / sin(x))
    }
}
