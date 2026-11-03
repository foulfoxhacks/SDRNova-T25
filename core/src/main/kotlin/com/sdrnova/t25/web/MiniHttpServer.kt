package com.sdrnova.t25.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Lower-cased names. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val text: String get() = String(body, Charsets.UTF_8)

    /** application/x-www-form-urlencoded fields. */
    fun form(): Map<String, String> = parseQuery(text)
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(json: String, status: Int = 200) = HttpResponse(status, "application/json; charset=utf-8", json.toByteArray())
        fun text(text: String, status: Int = 200) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray())
        fun html(html: String) = HttpResponse(200, "text/html; charset=utf-8", html.toByteArray())
    }
}

/**
 * A deliberately small HTTP/1.1 server for the local dashboard: one request per connection, bodies by
 * Content-Length only, a few worker threads. Bound to one address (the phone's Wi-Fi), never all interfaces.
 */
class MiniHttpServer(address: InetAddress, port: Int, private val handler: (HttpRequest) -> HttpResponse) : Closeable {
    private val socket = ServerSocket(port, 16, address)
    private val workers: ExecutorService = Executors.newFixedThreadPool(4) { Thread(it, "dashboard-http").apply { isDaemon = true } }

    val port: Int get() = socket.localPort

    fun start(): MiniHttpServer {
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }, "dashboard-accept").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 15_000
            val response = try {
                val request = read(BufferedInputStream(connection.getInputStream())) ?: return
                handler(request)
            } catch (e: RequestTooLarge) {
                HttpResponse.text("That's too large to upload.", 413)
            } catch (e: Exception) {
                HttpResponse.text("Something went wrong: ${e.message}", 500)
            }
            write(connection.getOutputStream(), response)
        }
    }

    private class RequestTooLarge : IOException()

    private fun read(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 100) return null
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw RequestTooLarge()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val question = target.indexOf('?')
        val path = URLDecoder.decode(if (question >= 0) target.substring(0, question) else target, "UTF-8")
        val query = if (question >= 0) parseQuery(target.substring(question + 1)) else emptyMap()
        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
            if (line.size() > 8_192) return null
        }
        return line.toString("ISO-8859-1")
    }

    private fun write(output: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            for ((name, value) in response.headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> if (status >= 500) "Internal Server Error" else "OK"
    }

    companion object {
        /** Big enough for a statewide sdrtrunk playlist. */
        const val MAX_BODY = 16 * 1024 * 1024
    }
}

internal fun parseQuery(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.associate { pair ->
    val equals = pair.indexOf('=')
    val name = if (equals >= 0) pair.substring(0, equals) else pair
    val value = if (equals >= 0) pair.substring(equals + 1) else ""
    URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
}
