package com.opendroid.ai.remote.socks

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback HTTP proxy that forwards every request through the desktop's SOCKS5 proxy.
 *
 * This is the piece that makes internet sharing usable on the phone. Android gives an app no way
 * to make other apps transparently use a socket it holds, but almost every browser and many apps
 * accept an HTTP proxy address. Pointing them at `127.0.0.1:<port>` therefore routes their traffic
 * out through the desktop without a rooted device or a native tunnel.
 *
 * Two request forms are served:
 *  - `CONNECT host:port` for TLS and any raw TCP stream, which is what HTTPS needs;
 *  - absolute-form `GET http://host/path` for plain HTTP, forwarded as-is to the origin.
 *
 * The listener is bound to loopback only. Binding it to `0.0.0.0` would turn the phone into an open
 * proxy for its whole network, which is not what sharing with a paired desktop means.
 */
class HttpProxyBridge(
    private val tunnel: (Socks5.Target) -> Socket,
    private val port: Int = 0,
    private val bindAddress: String = "127.0.0.1",
    private val log: (String) -> Unit = {},
) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    private val requestsServed = AtomicLong()
    private val bytesRelayed = AtomicLong()

    /** The port actually bound. Useful when [port] was 0, letting the OS choose a free one. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "http-proxy").apply { isDaemon = true }
        }
        pool = executor
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                runCatching { executor.submit { serve(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }, "http-proxy-accept").apply { isDaemon = true }.start()
        log("HTTP proxy on $bindAddress:${socket.localPort} -> desktop SOCKS5")
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        pool?.shutdownNow()
        serverSocket = null
        pool = null
    }

    fun stats() = requestsServed.get() to bytesRelayed.get()

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val header = readHeader(input) ?: return
            val lines = header.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                writeStatus(output, 400, "Bad Request", "Malformed proxy request line")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                val destination = parseHostPort(target)
                if (destination == null) {
                    writeStatus(output, 400, "Bad Request", "CONNECT needs host:port, got '$target'")
                    return
                }
                tunnelTo(client, output, destination)
                return
            }

            val destination = parseAbsoluteUrl(target)
            if (destination == null) {
                // A relative request means the client is not using us as a proxy.
                writeStatus(
                    output, 400, "Bad Request",
                    "This is a proxy. Send CONNECT host:port, or an absolute URL such as http://example.com/",
                )
                return
            }
            forwardPlainHttp(client, input, output, header, destination)
        } catch (e: Exception) {
            log("proxy session ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** Handles CONNECT: reply 200, then copy bytes both ways through the desktop. */
    private fun tunnelTo(client: Socket, output: OutputStream, destination: Socks5.Target) {
        val upstream = try {
            tunnel(destination)
        } catch (e: Exception) {
            writeStatus(output, 502, "Bad Gateway", e.message ?: "The desktop could not reach $destination")
            return
        }
        try {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
            requestsServed.incrementAndGet()
            pump(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** Forwards a plain-HTTP request, keeping the absolute-form request line the origin expects. */
    private fun forwardPlainHttp(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        header: String,
        destination: Socks5.Target,
    ) {
        val upstream = try {
            tunnel(destination)
        } catch (e: Exception) {
            writeStatus(output, 502, "Bad Gateway", e.message ?: "The desktop could not reach $destination")
            return
        }
        try {
            upstream.getOutputStream().let {
                it.write(header.toByteArray())
                it.flush()
            }
            requestsServed.incrementAndGet()
            pump(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** Copies both directions until either side closes. */
    private fun pump(client: Socket, upstream: Socket) {
        val up = Thread {
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                val from = client.getInputStream()
                val to = upstream.getOutputStream()
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    to.write(buffer, 0, read)
                    to.flush()
                    bytesRelayed.addAndGet(read.toLong())
                }
            } catch (_: IOException) {
            } finally {
                runCatching { upstream.shutdownOutput() }
            }
        }
        up.isDaemon = true
        up.start()
        try {
            val buffer = ByteArray(BUFFER_BYTES)
            val from = upstream.getInputStream()
            val to = client.getOutputStream()
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
                bytesRelayed.addAndGet(read.toLong())
            }
        } catch (_: IOException) {
        } finally {
            runCatching { client.shutdownOutput() }
            up.interrupt()
        }
    }

    private fun readHeader(input: InputStream): String? {
        val builder = StringBuilder()
        var matched = 0
        val terminator = "\r\n\r\n"
        while (true) {
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            builder.append(b.toChar())
            matched = if (b.toChar() == terminator[matched]) matched + 1
            else if (b.toChar() == '\r') 1 else 0
            if (matched == terminator.length) return builder.toString()
            if (builder.length > MAX_HEADER_BYTES) {
                throw IOException("Proxy request header exceeded $MAX_HEADER_BYTES bytes")
            }
        }
    }

    private fun parseHostPort(value: String): Socks5.Target? {
        val trimmed = value.trim()
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0) return null
        val host = trimmed.substring(0, separator).removeSurrounding("[", "]")
        val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return Socks5.Target(host, port)
    }

    /** Turns `http://host:port/path` into the host, port and the origin-form path to request. */
    private fun parseAbsoluteUrl(url: String): Socks5.Target? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.lowercase() != "http") return null
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "80").toIntOrNull() ?: 80
        if (host.isBlank()) return null
        return Socks5.Target(host, port)
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String, message: String) {
        val body = "$message\n"
        val response = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        runCatching {
            output.write(response.toByteArray())
            output.flush()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}
