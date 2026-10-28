package com.sdrnova.t25.source

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A remote dongle served by `rtl_tcp`: a 12-byte header, then raw u8 I/Q, with 5-byte commands going the other way. */
class RtlTcpSource private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    override val description: String,
) : IqSource {
    override val sampleRate = SAMPLE_RATE

    @Volatile
    private var running = false

    override fun setCenterFrequency(hz: Long) = command(0x01, hz.toInt())

    override fun setGain(tenthsDb: Int) {
        if (tenthsDb == IqSource.AUTO_GAIN) {
            command(0x03, 0)
        } else {
            command(0x03, 1)
            command(0x04, tenthsDb)
        }
    }

    override fun setPpm(ppm: Int) = command(0x05, ppm)

    override fun setBiasTee(enabled: Boolean) = command(0x0E, if (enabled) 1 else 0)

    override fun stream(sink: (ByteArray, Int) -> Unit) {
        running = true
        val buffer = ByteArray(16_384)
        try {
            while (running) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) throw IOException("rtl_tcp server closed the connection")
                // Keep I/Q pairs aligned.
                var length = read
                if (length % 2 == 1) {
                    buffer[length] = input.readByte()
                    length++
                }
                sink(buffer, length)
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
        runCatching { socket.close() }
    }

    @Synchronized
    private fun command(code: Int, value: Int) {
        val bytes = byteArrayOf(
            code.toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val SAMPLE_RATE = 960_000

        private val TUNERS = arrayOf("unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        fun connect(host: String, port: Int): RtlTcpSource {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 1_000
                socket.receiveBufferSize = 1 shl 20
                val input = DataInputStream(socket.getInputStream().buffered(1 shl 16))
                val magic = ByteArray(4).also(input::readFully)
                if (String(magic, Charsets.US_ASCII) != "RTL0") throw IOException("$host:$port is not an rtl_tcp server")
                val tuner = input.readInt()
                input.readInt() // gain count
                val source = RtlTcpSource(
                    socket, input, socket.getOutputStream(),
                    "rtl_tcp $host:$port (${TUNERS.getOrElse(tuner) { "tuner $tuner" }})",
                )
                source.command(0x02, SAMPLE_RATE)
                return source
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }
}
