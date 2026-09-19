package com.lian.plus.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A response body that is written incrementally, for server-sent events. */
class SseWriter(private val out: BufferedOutputStream) {
    fun send(data: String) {
        out.write("data: $data\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun done() {
        out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }
}

sealed interface HttpResponse {
    data class Text(
        val status: Int,
        val contentType: String,
        val body: String,
        val extraHeaders: Map<String, String> = emptyMap(),
    ) : HttpResponse

    /** Switches the connection to `text/event-stream` and hands over the writer. */
    data class Stream(val block: suspend (SseWriter) -> Unit) : HttpResponse

    companion object {
        fun json(body: String, status: Int = 200) = Text(status, "application/json", body)
        fun error(status: Int, message: String, type: String = "invalid_request_error") = Text(
            status,
            "application/json",
            """{"error":{"message":${quote(message)},"type":"$type","code":null}}""",
        )

        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}

/**
 * A small HTTP/1.1 server.
 *
 * Hand-rolled rather than pulled from a library because the only thing it has
 * to do well is stream tokens out as they are produced — the rest of an
 * embedded server framework would be dead weight in the APK. It handles one
 * request per connection thread, which is right for a server whose bottleneck
 * is a single-threaded inference engine.
 */
class HttpServer(
    private val port: Int,
    private val bindToAllInterfaces: Boolean,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    val isRunning: Boolean get() = running.get()

    fun start(): Result<Int> = runCatching {
        if (running.get()) return@runCatching port

        val address = if (bindToAllInterfaces) {
            InetSocketAddress(port)
        } else {
            // Loopback only: nothing outside this phone can reach it.
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(address, 32)
        serverSocket = socket
        running.set(true)

        val job = SupervisorJob()
        val s = CoroutineScope(Dispatchers.IO + job)
        scope = s

        s.launch {
            Log.i(TAG, "listening on ${if (bindToAllInterfaces) "0.0.0.0" else "127.0.0.1"}:$port")
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // stop() closed the socket
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                s.launch { serve(client) }
            }
        }
        socket.localPort
    }.onFailure {
        running.set(false)
        Log.e(TAG, "could not start on port $port: ${it.message}")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 120_000
            socket.tcpNoDelay = true
            val out = BufferedOutputStream(socket.getOutputStream())
            try {
                val request = parseRequest(socket.getInputStream()) ?: return
                if (request.method == "OPTIONS") {
                    writeHead(out, 204, "text/plain", 0, corsHeaders())
                    out.flush()
                    return
                }

                when (val response = handler(request)) {
                    is HttpResponse.Text -> {
                        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
                        writeHead(
                            out, response.status, response.contentType, bytes.size,
                            corsHeaders() + response.extraHeaders,
                        )
                        out.write(bytes)
                        out.flush()
                    }
                    is HttpResponse.Stream -> {
                        writeStreamHead(out)
                        response.block(SseWriter(out))
                    }
                }
            } catch (e: IOException) {
                // A client that hung up mid-stream is normal, not an error.
                Log.d(TAG, "connection ended: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                runCatching {
                    val body = """{"error":{"message":"internal error"}}"""
                        .toByteArray(StandardCharsets.UTF_8)
                    writeHead(out, 500, "application/json", body.size, corsHeaders())
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    private fun parseRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                decode(k) to decode(v)
            }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            if (length > MAX_BODY_BYTES) return null
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else ""

        return HttpRequest(method, path, query, headers, body)
    }

    /** Reads a CRLF-terminated line without buffering past it. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun corsHeaders() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
    )

    private fun writeHead(
        out: BufferedOutputStream,
        status: Int,
        contentType: String,
        length: Int,
        headers: Map<String, String>,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeStreamHead(out: BufferedOutputStream) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Connection: close\r\n")
        // No Content-Length and no chunking: the body ends when we close.
        corsHeaders().forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private companion object {
        const val TAG = "LianHttp"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_LINE = 16 * 1024
    }
}
