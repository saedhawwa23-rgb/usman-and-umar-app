package com.opendroid.ai.remote.socks

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The desktop side of internet sharing: a SOCKS5 server that dials out on the paired phone's
 * behalf.
 *
 * A SOCKS5 CONNECT is the whole mechanism. The phone hands over a destination, the desktop opens
 * an ordinary outbound TCP connection from its own network stack, and then relays bytes. No
 * inbound port is opened on the desktop beyond this listener, and nothing is routed by the OS, so
 * the desktop's own traffic is unaffected.
 *
 * Authentication is mandatory. Every request must carry a credential minted from the paired
 * device's secret, so an unpaired host on the same network cannot use the desktop as an open
 * proxy.
 */
class Socks5Server(
    private val port: Int = Socks5.DEFAULT_PORT,
    private val bindAddress: String = "0.0.0.0",
    /** Resolves the paired secret for a device id, or null when the device is unknown or revoked. */
    private val secretFor: (deviceId: String) -> ByteArray?,
    /** Called for every accepted, refused or failed request, without credentials. */
    private val log: (String) -> Unit = {},
    private val connectTimeoutMs: Int = 10_000,
    /** Destinations a phone may reach. Defaults to allowing everything. */
    private val isAllowed: (Socks5.Target) -> Boolean = { true },
) {

    private val seenNonces = ConcurrentHashMap.newKeySet<String>()
    private val activeConnections = AtomicLong()
    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()
    private val openedTotal = AtomicLong()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var acceptThread: Thread? = null

    /** Live counters, so the agent can print what the phone has actually used. */
    data class Stats(
        val openConnections: Long,
        val totalOpened: Long,
        val bytesFromPhone: Long,
        val bytesToPhone: Long,
    )

    fun stats() = Stats(
        activeConnections.get(), openedTotal.get(), bytesUp.get(), bytesDown.get(),
    )

    /** Binds the listener. Throws if the port is taken, rather than failing later per request. */
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "socks5-proxy").apply { isDaemon = true }
        }
        pool = executor
        val thread = Thread({ acceptLoop(socket, executor) }, "socks5-accept").apply { isDaemon = true }
        acceptThread = thread
        thread.start()
        log("proxy listening on $bindAddress:$port (SOCKS5, paired devices only)")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        pool?.shutdownNow()
        acceptThread?.interrupt()
        serverSocket = null
        pool = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            runCatching { executor.submit { handle(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "unknown"
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            if (!authenticate(input, output, peer)) return
            val target = try {
                readRequest(input, output)
            } catch (e: IOException) {
                log("request from $peer rejected: ${e.message}")
                return
            } ?: return

            if (!isAllowed(target)) {
                log("refused ${target.host}:${target.port} for $peer by policy")
                writeReply(output, Socks5.REP_NOT_ALLOWED)
                return
            }

            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            } catch (e: IOException) {
                log("could not reach ${target.host}:${target.port}: ${e.message}")
                writeReply(output, Socks5.REP_HOST_UNREACHABLE)
                runCatching { upstream.close() }
                return
            }

            openedTotal.incrementAndGet()
            activeConnections.incrementAndGet()
            try {
                upstream.tcpNoDelay = true
                writeReply(output, Socks5.REP_SUCCESS)
                log("relaying ${target.host}:${target.port} for $peer")
                relay(client, upstream)
            } finally {
                activeConnections.decrementAndGet()
                runCatching { upstream.close() }
            }
        } catch (e: Exception) {
            log("session with $peer ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** Runs the RFC 1929 exchange. Returns false when the client must be dropped. */
    private fun authenticate(input: DataInputStream, output: DataOutputStream, peer: String): Boolean {
        val version = input.readByte()
        if (version != Socks5.VERSION) {
            log("refused $peer: not SOCKS5 (version byte $version)")
            return false
        }
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount).also(input::readFully)
        if (Socks5.AUTH_USERPASS !in methods) {
            // Never offer AUTH_NONE: an open proxy on a LAN is a real hazard.
            output.writeByte(Socks5.VERSION.toInt())
            output.writeByte(Socks5.AUTH_UNACCEPTABLE.toInt())
            output.flush()
            log("refused $peer: client did not offer username/password auth")
            return false
        }
        output.writeByte(Socks5.VERSION.toInt())
        output.writeByte(Socks5.AUTH_USERPASS.toInt())
        output.flush()

        val authVersion = input.readByte()
        if (authVersion != Socks5.USERPASS_VERSION) {
            log("refused $peer: unsupported auth sub-version $authVersion")
            return false
        }
        val userLength = input.readUnsignedByte()
        val username = ByteArray(userLength).also(input::readFully).toString(Charsets.UTF_8)
        val passLength = input.readUnsignedByte()
        val password = ByteArray(passLength).also(input::readFully).toString(Charsets.UTF_8)

        val secret = secretFor(username)
        val ok = secret != null &&
            Socks5.verifyCredential(secret, username, password, seenNonces = seenNonces)
        output.writeByte(Socks5.AUTH_SUCCESS.toInt())
        if (!ok) output.writeByte(Socks5.AUTH_FAILURE.toInt()) else output.writeByte(Socks5.AUTH_SUCCESS.toInt())
        output.flush()
        if (!ok) {
            log("refused $peer: credential for '$username' was not valid")
            return false
        }
        log("authenticated $username from $peer")
        return true
    }

    /** Reads the CONNECT request. Returns null when a reply has already been sent. */
    private fun readRequest(input: DataInputStream, output: DataOutputStream): Socks5.Target? {
        val version = input.readByte()
        if (version != Socks5.VERSION) {
            log("bad request version $version")
            return null
        }
        val command = input.readByte()
        input.readByte() // reserved
        val target = try {
            Socks5.Target.from(Socks5.readAddress(input))
        } catch (e: IOException) {
            writeReply(output, Socks5.REP_ATYP_UNSUPPORTED)
            throw e
        }
        if (command != Socks5.CMD_CONNECT) {
            writeReply(output, Socks5.REP_COMMAND_UNSUPPORTED)
            log("unsupported command $command (only CONNECT is served)")
            return null
        }
        return target
    }

    private fun writeReply(output: DataOutputStream, code: Byte) {
        output.writeByte(Socks5.VERSION.toInt())
        output.writeByte(code.toInt())
        output.writeByte(0)
        // A zeroed IPv4 bind address is what clients expect when the real one is not meaningful.
        output.writeByte(Socks5.ATYP_IPV4.toInt())
        output.write(byteArrayOf(0, 0, 0, 0))
        output.writeShort(0)
        output.flush()
    }

    /** Copies both directions until either side closes. */
    private fun relay(client: Socket, upstream: Socket) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val upstreamIn = upstream.getInputStream()
        val upstreamOut = upstream.getOutputStream()

        val up = Thread {
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = clientIn.read(buffer)
                    if (read < 0) break
                    upstreamOut.write(buffer, 0, read)
                    upstreamOut.flush()
                    bytesUp.addAndGet(read.toLong())
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
            while (true) {
                val read = upstreamIn.read(buffer)
                if (read < 0) break
                clientOut.write(buffer, 0, read)
                clientOut.flush()
                bytesDown.addAndGet(read.toLong())
            }
        } catch (_: IOException) {
        } finally {
            runCatching { client.shutdownOutput() }
            up.interrupt()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
    }
}
