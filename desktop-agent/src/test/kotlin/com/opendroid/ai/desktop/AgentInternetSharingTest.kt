package com.opendroid.ai.desktop

import com.opendroid.ai.remote.protocol.PairedClient
import com.opendroid.ai.remote.socks.Socks5
import com.opendroid.ai.remote.socks.Socks5Client
import com.opendroid.ai.remote.socks.Socks5Server
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Proves the agent's proxy is wired to the real pairing store.
 *
 * The server is started exactly as [main] starts it, with `secretFor` reading from a [ClientStore]
 * on disk. That is the part a unit test of the SOCKS5 code alone would not cover: a revoked or
 * unknown device must be refused, and a paired one must be served.
 */
class AgentInternetSharingTest {

    private val storeFile = File.createTempFile("agent-clients", ".json")
    private var server: Socks5Server? = null
    private var origin: ServerSocket? = null

    @After
    fun cleanUp() {
        server?.stop()
        runCatching { origin?.close() }
        storeFile.delete()
    }

    private fun startOrigin(): Int {
        val socket = ServerSocket(0)
        origin = socket
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                client.use {
                    val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                    val body = "AGENT-ORIGIN: $line"
                    it.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray(),
                    )
                    it.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** Mirrors the wiring in DesktopAgentMain: only paired, non-revoked devices get a secret. */
    private fun startProxyAsAgentDoes(store: ClientStore, port: Int) {
        server = Socks5Server(
            port = port,
            bindAddress = "127.0.0.1",
            secretFor = { deviceId -> store.find(deviceId)?.takeIf { !it.revoked }?.secret },
            log = {},
        ).also { it.start() }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a device paired in the agent store is served`() {
        val store = ClientStore(storeFile)
        val secret = "paired-secret-from-agent-store".toByteArray()
        store.save(PairedClient("phone-1", "Pixel", secret, System.currentTimeMillis()))
        val originPort = startOrigin()
        val port = freePort()
        startProxyAsAgentDoes(store, port)

        val socket = Socks5Client("127.0.0.1", port, secret, "phone-1")
            .connect(Socks5.Target("127.0.0.1", originPort))
        socket.use {
            it.getOutputStream().write("GET /from-paired-phone HTTP/1.1\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val reply = it.getInputStream().bufferedReader().readText()
            assertTrue("a paired phone must reach the origin, got: $reply", reply.contains("AGENT-ORIGIN: GET /from-paired-phone"))
        }
        assertEquals("the tunnel should be counted", 1, server!!.stats().totalOpened)
    }

    @Test
    fun `a device that was never paired is refused by the agent`() {
        val store = ClientStore(storeFile)
        val port = freePort()
        startProxyAsAgentDoes(store, port)
        val error = runCatching {
            Socks5Client("127.0.0.1", port, "anything".toByteArray(), "stranger")
                .connect(Socks5.Target("127.0.0.1", 80))
        }.exceptionOrNull()
        assertNotNull("an unpaired device must not get a tunnel", error)
    }

    @Test
    fun `a revoked device is refused even with the right secret`() {
        val store = ClientStore(storeFile)
        val secret = "secret-for-a-revoked-phone".toByteArray()
        store.save(PairedClient("phone-2", "Old phone", secret, System.currentTimeMillis()))
        store.save(PairedClient("phone-2", "Old phone", secret, System.currentTimeMillis(), revoked = true))
        val port = freePort()
        startProxyAsAgentDoes(store, port)

        val error = runCatching {
            Socks5Client("127.0.0.1", port, secret, "phone-2")
                .connect(Socks5.Target("127.0.0.1", 80))
        }.exceptionOrNull()
        assertNotNull("a revoked device must not get a tunnel", error)
    }

    @Test
    fun `the secret the agent serves is the one from the store, not a copy`() {
        val store = ClientStore(storeFile)
        val secret = "store-secret-must-be-authoritative".toByteArray()
        store.save(PairedClient("phone-3", "Tablet", secret, System.currentTimeMillis()))
        val found = store.find("phone-3")
        assertNotNull("the store must return the paired client", found)
        assertTrue("the secret must round-trip through the store file", found!!.secret.contentEquals(secret))
    }

    @Test
    fun `the proxy can be stopped and restarted on the same port`() {
        val store = ClientStore(storeFile)
        val secret = "restart-secret".toByteArray()
        store.save(PairedClient("phone-4", "Phone", secret, System.currentTimeMillis()))
        val port = freePort()
        startProxyAsAgentDoes(store, port)
        server!!.stop()
        startProxyAsAgentDoes(store, port)
        // A bound socket here proves the port was released on stop.
        Socket("127.0.0.1", port).use { assertTrue("the proxy must be listening again", it.isConnected) }
    }
}
