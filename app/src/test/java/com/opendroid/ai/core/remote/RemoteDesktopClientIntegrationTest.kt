package com.opendroid.ai.core.remote

import com.opendroid.ai.desktop.AgentConfig
import com.opendroid.ai.desktop.ClientStore
import com.opendroid.ai.desktop.DesktopControllerContract
import com.opendroid.ai.desktop.DesktopSession
import com.opendroid.ai.desktop.CapturedFrame
import com.opendroid.ai.desktop.AppEntryView
import com.opendroid.ai.desktop.LaunchOutcome
import com.opendroid.ai.desktop.PairingController
import com.opendroid.ai.remote.protocol.AppEntry
import com.opendroid.ai.remote.protocol.AppLaunchResult
import com.opendroid.ai.remote.protocol.AppList
import com.opendroid.ai.remote.protocol.InputEvent
import com.opendroid.ai.remote.protocol.RemoteProtocol
import com.opendroid.ai.remote.protocol.ScreenFrameCodec
import com.opendroid.ai.remote.protocol.RemoteMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Drives the real Android [RemoteDesktopClient] against the real desktop agent session handler over
 * a loopback socket. Nothing is simulated except the desktop's screen and input, which need a
 * display; pairing, authentication, command dispatch and file transfer are the production paths.
 */
class RemoteDesktopClientIntegrationTest {

    private val tempRoot: File = Files.createTempDirectory("remote-client-test").toFile()

    private class FakeController : DesktopControllerContract {
        val input = mutableListOf<String>()
        override fun captureScreen(maxWidth: Int, quality: Int, monitor: Int) =
            CapturedFrame(160, 120, "JPEG", 7L, byteArrayOf(1, 2, 3))
        override fun monitorCount() = 1
        override fun screenSize() = java.awt.Dimension(160, 120)
        override fun mouseMove(x: Int, y: Int) { input += "move($x,$y)" }
        override fun mouseDown(button: Int) { input += "down($button)" }
        override fun mouseUp(button: Int) { input += "up($button)" }
        override fun mouseWheel(amount: Int) { input += "wheel($amount)" }
        override fun keyDown(keyCode: Int) { input += "kd($keyCode)" }
        override fun keyUp(keyCode: Int) { input += "ku($keyCode)" }
        override fun typeText(text: String): Int { input += "text($text)"; return text.length }
        override fun listApplications() = listOf(AppEntryView("app:notes", "Notes", false))
        override fun launchApplication(id: String) = LaunchOutcome(id == "app:notes", null, 3L)
    }

    private class MemoryStore : PairedDesktopStore {
        private val items = mutableListOf<PairedDesktop>()
        override fun load() = items.toList()
        override fun save(desktop: PairedDesktop) {
            items.removeAll { it.deviceId == desktop.deviceId }
            items.add(desktop)
        }
        override fun remove(deviceId: String) { items.removeAll { it.deviceId == deviceId } }
    }

    /** Starts the real agent session on a loopback port and returns its port and pairing code. */
    private fun startAgent(
        controller: FakeController,
        pairing: PairingController,
        store: ClientStore,
    ): Int {
        val server = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        val config = AgentConfig("test-desktop", server.localPort, tempRoot)
        Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread {
                    socket.use {
                        DesktopSession(config, store, controller, pairing) { }
                            .handle(it.getInputStream(), it.getOutputStream())
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    @Test
    fun `pairing then reconnecting and driving the desktop works end to end`() = runBlocking {
        val controller = FakeController()
        val pairing = PairingController()
        val code = pairing.open()
        val store = ClientStore(File(tempRoot, "clients.json"))
        val port = startAgent(controller, pairing, store)
        val phoneStore = MemoryStore()

        val frames = mutableListOf<ScreenFrameCodec.ScreenFrame>()
        val replies = mutableListOf<RemoteMessage>()
        val client = RemoteDesktopClient(phoneStore, "Test Phone")

        // First connection pairs with the code shown on the desktop. The client returns from
        // connect() when the peer closes, so the pairing is driven from a sibling coroutine.
        val pairJob = launch { client.connect("127.0.0.1", port, code, { }, { frames += it }, { replies += it }) }
        val deadline = System.currentTimeMillis() + 15_000
        while (phoneStore.load().isEmpty() && System.currentTimeMillis() < deadline) delay(50)
        assertTrue("the pairing secret must be stored", phoneStore.load().isNotEmpty())
        client.disconnect("paired")
        pairJob.join()

        // Second connection authenticates with the stored secret, then drives the desktop.
        val client2 = RemoteDesktopClient(phoneStore, "Test Phone")
        val receivedList = java.util.concurrent.atomic.AtomicReference<AppList?>(null)
        val launchResult = java.util.concurrent.atomic.AtomicReference<AppLaunchResult?>(null)
        val streamJob = launch {
            client2.connect("127.0.0.1", port, null, { }, { frames += it }) { message ->
                when (message) {
                    is AppList -> receivedList.set(message)
                    is AppLaunchResult -> launchResult.set(message)
                    else -> Unit
                }
            }
        }
        // Wait for the authenticated session before sending commands.
        val ready = System.currentTimeMillis() + 15_000
        while (client2.state !is DesktopSessionState.Connected && System.currentTimeMillis() < ready) delay(50)
        assertTrue("the client should reach the connected state", client2.state is DesktopSessionState.Connected)

        client2.listApplications()
        client2.sendInput(InputEvent(InputEvent.MOUSE_MOVE, x = 7, y = 9))
        client2.launchApplication("app:notes")
        client2.requestScreen(160, 50)
        val answered = System.currentTimeMillis() + 15_000
        while (receivedList.get() == null && System.currentTimeMillis() < answered) delay(50)
        while (frames.isEmpty() && System.currentTimeMillis() < answered) delay(50)

        assertEquals(listOf(AppEntry("app:notes", "Notes", false)), receivedList.get()!!.entries)
        assertTrue("the desktop should have received the mouse move", controller.input.contains("move(7,9)"))
        assertEquals(true, launchResult.get()?.accepted)
        assertTrue("a screen frame should arrive", frames.isNotEmpty())

        client2.disconnect("done")
        streamJob.join()
    }

    @Test
    fun `a connection without a stored secret reports a clear pairing message`() = runBlocking {
        val pairing = PairingController()
        pairing.open()
        val store = ClientStore(File(tempRoot, "clients.json"))
        val port = startAgent(FakeController(), pairing, store)
        val client = RemoteDesktopClient(MemoryStore(), "Test Phone")
        var failure: String? = null

        val result = client.connect(
            host = "127.0.0.1",
            port = port,
            pairingCode = null,
            onState = { if (it is DesktopSessionState.Failed) failure = it.reason },
            onScreenFrame = {},
            onMessage = {},
        )

        assertTrue(result.isFailure)
        assertTrue("the message must tell the user to pair", failure!!.contains("not paired"))
    }

    @Test
    fun `a wrong pairing code is reported and nothing is stored`() = runBlocking {
        val pairing = PairingController()
        pairing.open()
        val store = ClientStore(File(tempRoot, "clients.json"))
        val port = startAgent(FakeController(), pairing, store)
        val phoneStore = MemoryStore()
        val client = RemoteDesktopClient(phoneStore, "Test Phone")
        var failure: String? = null

        val result = client.connect(
            host = "127.0.0.1",
            port = port,
            pairingCode = "WRONGCODE",
            onState = { if (it is DesktopSessionState.Failed) failure = it.reason },
            onScreenFrame = {},
            onMessage = {},
        )

        assertTrue(result.isFailure)
        assertTrue("the message must explain the code was refused", failure!!.contains("not correct"))
        assertTrue(phoneStore.load().isEmpty())
    }

    @Test
    fun `a client against a different protocol version is told so`() = runBlocking {
        // A server that speaks the right framing but the wrong version, to prove the client checks.
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                com.opendroid.ai.remote.protocol.FrameCodec.read(input) // the client's hello
                com.opendroid.ai.remote.protocol.FrameCodec.write(
                    output,
                    com.opendroid.ai.remote.protocol.RemoteCodec.encode(
                        com.opendroid.ai.remote.protocol.HelloAck(
                            RemoteProtocol.VERSION + 1, "s", "future", "n", false,
                        )
                    ),
                )
            }
        }.apply { isDaemon = true }.start()

        val client = RemoteDesktopClient(MemoryStore(), "Test Phone")
        var failure: String? = null
        val result = client.connect(
            host = "127.0.0.1",
            port = server.localPort,
            pairingCode = "ABCD2345",
            onState = { if (it is DesktopSessionState.Failed) failure = it.reason },
            onScreenFrame = {},
            onMessage = {},
        )

        assertTrue(result.isFailure)
        assertTrue("the message must name both versions", failure!!.contains("speaks protocol"))
        server.close()
    }
}
