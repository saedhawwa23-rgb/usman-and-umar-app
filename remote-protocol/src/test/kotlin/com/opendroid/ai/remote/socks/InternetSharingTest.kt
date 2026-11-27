package com.opendroid.ai.remote.socks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Internet sharing, tested against real sockets.
 *
 * Every test here uses an actual ServerSocket and an actual relay, so a protocol mistake fails the
 * test rather than passing against a stub. Nothing is mocked: the point of these tests is to prove
 * that bytes really move from one end to the other.
 */
class InternetSharingTest {

    private val secret = "a-paired-secret-value-0123456789".toByteArray()
    private val deviceId = "phone-device-1"

    private lateinit var origin: ServerSocket
    private lateinit var originThread: Thread
    private lateinit var proxy: Socks5Server
    private var proxyPort = 0
    private var originPort = 0

    /** A tiny origin server that answers one request and closes. */
    @Before
    fun startOrigin() {
        origin = ServerSocket(0)
        originPort = origin.localPort
        originThread = Thread {
            while (!origin.isClosed) {
                val client = try {
                    origin.accept()
                } catch (e: Exception) {
                    break
                }
                Executors.newSingleThreadExecutor().submit {
                    client.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        val body = "ORIGIN-SAW: $requestLine"
                        it.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                ).toByteArray()
                        )
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        originThread.isDaemon = true
        originThread.start()
    }

    private fun startProxy(allowed: (Socks5.Target) -> Boolean = { true }): Int {
        val socket = ServerSocket(0)
        proxyPort = socket.localPort
        socket.close()
        proxy = Socks5Server(
            port = proxyPort,
            bindAddress = "127.0.0.1",
            secretFor = { id -> if (id == deviceId) secret else null },
            isAllowed = allowed,
        )
        proxy.start()
        return proxyPort
    }

    @After
    fun stopEverything() {
        runCatching { proxy.stop() }
        runCatching { origin.close() }
        runCatching { originThread.interrupt() }
    }

    // ---- The end-to-end claim: a phone request leaves through the proxy ----

    @Test
    fun `a request sent through the tunnel reaches the origin and the reply comes back`() {
        startProxy()
        val client = Socks5Client("127.0.0.1", proxyPort, secret, deviceId)
        val socket = client.connect(Socks5.Target("127.0.0.1", originPort))
        socket.use {
            it.getOutputStream().write("GET /through-proxy HTTP/1.1\r\nHost: origin\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val reply = it.getInputStream().bufferedReader().readText()
            assertTrue("the origin must have received the request", reply.contains("ORIGIN-SAW: GET /through-proxy"))
            assertTrue("the reply must arrive intact", reply.contains("200 OK"))
        }
        // The upstream counter is bumped by the pump thread after the client already has the
        // reply, so a single sample can race it. Poll briefly instead of asserting instantly.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var stats = proxy.stats()
        while (stats.bytesFromPhone <= 0 && System.nanoTime() < deadline) {
            Thread.sleep(10)
            stats = proxy.stats()
        }
        assertEquals("exactly one tunnel should have been opened", 1, stats.totalOpened)
        assertTrue("bytes from the phone must be counted", stats.bytesFromPhone > 0)
        assertTrue("bytes back to the phone must be counted", stats.bytesToPhone > 0)
    }

    @Test
    fun `a domain name is resolved by the proxy side, not by the client`() {
        startProxy()
        val client = Socks5Client("127.0.0.1", proxyPort, secret, deviceId)
        // localhost resolves to loopback on the proxy side, which is where the origin listens.
        val socket = client.connect(Socks5.Target("localhost", originPort))
        socket.use {
            it.getOutputStream().write("GET /by-name HTTP/1.1\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val reply = it.getInputStream().bufferedReader().readText()
            assertTrue("a hostname target must work", reply.contains("ORIGIN-SAW: GET /by-name"))
        }
    }

    // ---- Authentication ----

    @Test
    fun `an unpaired device is refused`() {
        startProxy()
        val stranger = Socks5Client("127.0.0.1", proxyPort, secret, "not-paired")
        val error = runCatching { stranger.connect(Socks5.Target("127.0.0.1", originPort)) }.exceptionOrNull()
        assertNotNull("an unknown device must not get a tunnel", error)
        assertTrue(
            "the failure must name the credential problem, was: ${error?.message}",
            error!!.message!!.contains("credential", ignoreCase = true),
        )
    }

    @Test
    fun `a wrong secret is refused`() {
        startProxy()
        val impostor = Socks5Client("127.0.0.1", proxyPort, "wrong-secret".toByteArray(), deviceId)
        val error = runCatching { impostor.connect(Socks5.Target("127.0.0.1", originPort)) }.exceptionOrNull()
        assertNotNull("a bad secret must not get a tunnel", error)
    }

    @Test
    fun `a client offering no authentication is rejected rather than served`() {
        startProxy()
        Socket("127.0.0.1", proxyPort).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.writeByte(5)
            out.writeByte(1)
            out.writeByte(Socks5.AUTH_NONE.toInt())
            out.flush()
            assertEquals("the proxy must answer with SOCKS5", 5, input.readUnsignedByte())
            assertEquals(
                "the proxy must refuse when only no-auth is offered",
                Socks5.AUTH_UNACCEPTABLE.toInt() and 0xFF,
                input.readUnsignedByte(),
            )
        }
    }

    @Test
    fun `a replayed credential is refused`() {
        startProxy()
        val credential = Socks5.issueCredential(secret, deviceId, System.currentTimeMillis())
        val seen = mutableSetOf<String>()
        assertTrue(
            "the first use must be accepted",
            Socks5.verifyCredential(secret, deviceId, credential.password, seenNonces = seen),
        )
        assertFalse(
            "the same credential must not be usable twice",
            Socks5.verifyCredential(secret, deviceId, credential.password, seenNonces = seen),
        )
    }

    @Test
    fun `an expired credential is refused`() {
        val issuedAt = System.currentTimeMillis() - Socks5.CREDENTIAL_TTL_MS - 1_000
        val credential = Socks5.issueCredential(secret, deviceId, issuedAt)
        assertFalse(
            "a credential older than the TTL must not verify",
            Socks5.verifyCredential(secret, deviceId, credential.password),
        )
    }

    @Test
    fun `a credential minted for one device does not verify for another`() {
        val credential = Socks5.issueCredential(secret, deviceId, System.currentTimeMillis())
        assertFalse(
            "the device id is part of the MAC, so it cannot be swapped",
            Socks5.verifyCredential(secret, "other-device", credential.password),
        )
    }

    // ---- Refusals ----

    @Test
    fun `a destination the policy forbids is refused`() {
        startProxy { target -> target.port != originPort }
        val client = Socks5Client("127.0.0.1", proxyPort, secret, deviceId)
        val error = runCatching { client.connect(Socks5.Target("127.0.0.1", originPort)) }.exceptionOrNull()
        assertNotNull("a forbidden destination must not be tunnelled", error)
        assertTrue(
            "the message must say policy refused it, was: ${error?.message}",
            error!!.message!!.contains("refused", ignoreCase = true),
        )
    }

    @Test
    fun `an unreachable destination reports a clear failure instead of hanging`() {
        startProxy()
        // Port 1 on loopback has nothing listening.
        val client = Socks5Client("127.0.0.1", proxyPort, secret, deviceId, connectTimeoutMs = 3_000)
        val error = runCatching { client.connect(Socks5.Target("127.0.0.1", 1)) }.exceptionOrNull()
        assertNotNull("an unreachable host must fail", error)
        assertTrue(
            "the message must name the destination, was: ${error?.message}",
            error!!.message!!.contains("could not reach", ignoreCase = true),
        )
    }

    // ---- Address encoding ----

    @Test
    fun `addresses round-trip as ipv4, ipv6 and domain`() {
        for (target in listOf(
            Socks5.Target("93.184.216.34", 443),
            Socks5.Target("::1", 8080),
            Socks5.Target("example.com", 80),
        )) {
            val buffer = java.io.ByteArrayOutputStream()
            val out = DataOutputStream(buffer)
            Socks5.writeAddress(out, target.toAddress())
            out.flush()
            val decoded = Socks5.readAddress(DataInputStream(buffer.toByteArray().inputStream()))
            assertEquals("host must survive the round trip", target.host, decoded.host)
            assertEquals("port must survive the round trip", target.port, decoded.port)
        }
    }

    @Test
    fun `an over-long domain is rejected before it is written`() {
        val tooLong = Socks5.Target("a".repeat(Socks5.MAX_DOMAIN_BYTES + 1) + ".com", 80)
        val error = runCatching {
            Socks5.writeAddress(DataOutputStream(java.io.ByteArrayOutputStream()), tooLong.toAddress())
        }.exceptionOrNull()
        assertNotNull("a domain over 255 bytes must be rejected", error)
    }

    // ---- HTTP proxy bridge, the path apps on the phone use ----

    @Test
    fun `the http bridge tunnels CONNECT to the origin`() {
        startProxy()
        val tunnel = { target: Socks5.Target ->
            Socks5Client("127.0.0.1", proxyPort, secret, deviceId).connect(target)
        }
        val bridge = HttpProxyBridge(tunnel, port = 0)
        val port = bridge.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                val out = socket.getOutputStream()
                out.write("CONNECT 127.0.0.1:$originPort HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val status = reader.readLine()
                assertTrue("CONNECT must be accepted, was: $status", status.contains("200"))
                // Consume the blank line, then speak the tunnelled protocol.
                reader.readLine()
                out.write("GET /via-connect HTTP/1.1\r\n\r\n".toByteArray())
                out.flush()
                val reply = socket.getInputStream().bufferedReader().readText()
                assertTrue("bytes must reach the origin through CONNECT", reply.contains("GET /via-connect"))
            }
            assertEquals("the bridge must count the request", 1, bridge.stats().first)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `the http bridge forwards absolute-form requests`() {
        startProxy()
        val tunnel = { target: Socks5.Target ->
            Socks5Client("127.0.0.1", proxyPort, secret, deviceId).connect(target)
        }
        val bridge = HttpProxyBridge(tunnel, port = 0)
        val port = bridge.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                val out = socket.getOutputStream()
                out.write("GET http://127.0.0.1:$originPort/absolute HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                out.flush()
                val reply = socket.getInputStream().bufferedReader().readText()
                assertTrue("the origin must see the request", reply.contains("ORIGIN-SAW: GET"))
                assertTrue("the reply must come back", reply.contains("200 OK"))
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `the http bridge reports a dead desktop clearly`() {
        val tunnel = { _: Socks5.Target -> throw java.io.IOException("desktop is offline") }
        val bridge = HttpProxyBridge(tunnel, port = 0)
        val port = bridge.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                assertTrue("a failed tunnel must be a 502, was: $status", status.contains("502"))
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a relative request is refused with an explanation`() {
        val bridge = HttpProxyBridge({ throw java.io.IOException("unused") }, port = 0)
        val port = bridge.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET /just-a-path HTTP/1.1\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val reply = socket.getInputStream().bufferedReader().readText()
                assertTrue("must be a 400, was: $reply", reply.contains("400"))
                assertTrue("must explain how to use the proxy", reply.contains("CONNECT host:port"))
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `the bridge binds loopback only`() {
        val bridge = HttpProxyBridge({ throw java.io.IOException("unused") }, port = 0)
        val port = bridge.start()
        try {
            // A loopback bind is not reachable on a non-loopback local address.
            val local = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            if (local != null) {
                val reachable = runCatching {
                    Socket().use { it.connect(InetSocketAddress(local, port), 1_000) }
                }.isSuccess
                assertFalse("the proxy must not be reachable on $local", reachable)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `stats start at zero and count a completed transfer`() {
        startProxy()
        val client = Socks5Client("127.0.0.1", proxyPort, secret, deviceId)
        client.connect(Socks5.Target("127.0.0.1", originPort)).use {
            it.getOutputStream().write("GET /stats HTTP/1.1\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            it.getInputStream().bufferedReader().readText()
        }
        // The relay threads finish just after the client sees EOF, so allow a moment.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && proxy.stats().openConnections > 0) {
            Thread.sleep(20)
        }
        assertEquals("no connections should remain open", 0, proxy.stats().openConnections)
        assertEquals("one connection was opened", 1, proxy.stats().totalOpened)
    }

    @Test
    fun `verifyCredential rejects a malformed password`() {
        assertFalse(Socks5.verifyCredential(secret, deviceId, ""))
        assertFalse(Socks5.verifyCredential(secret, deviceId, "not-a-credential"))
        assertFalse(Socks5.verifyCredential(secret, deviceId, "123.abc"))
    }

    @Test
    fun `secretFor returning null refuses the device without throwing`() {
        startProxy()
        val socket = Socket("127.0.0.1", proxyPort)
        socket.use {
            val out = DataOutputStream(it.getOutputStream())
            val input = DataInputStream(it.getInputStream())
            out.writeByte(5); out.writeByte(1); out.writeByte(2); out.flush()
            input.readUnsignedByte(); input.readUnsignedByte()
            val user = "ghost".toByteArray()
            val pass = Socks5.issueCredential(secret, "ghost", System.currentTimeMillis()).password.toByteArray()
            out.writeByte(1); out.writeByte(user.size); out.write(user); out.writeByte(pass.size); out.write(pass)
            out.flush()
            input.readByte()
            assertEquals(
                "an unknown device must get an auth failure, not a crash",
                Socks5.AUTH_FAILURE.toInt(),
                input.readUnsignedByte(),
            )
        }
    }
}
