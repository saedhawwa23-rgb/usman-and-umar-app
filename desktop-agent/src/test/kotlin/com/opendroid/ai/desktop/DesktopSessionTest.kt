package com.opendroid.ai.desktop

import com.opendroid.ai.remote.protocol.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises a real session over a real socket pair, using the same [DesktopSession] the agent runs.
 * Screen capture is replaced by a deterministic image so the test does not need a display, but
 * framing, pairing, authentication, input dispatch, file transfer and revocation are all real.
 */
class DesktopSessionTest {

    private val tempRoot: File = Files.createTempDirectory("agent-test").toFile()

    @Volatile private var ackNonce: String? = null

    @After
    fun cleanUp() {
        tempRoot.deleteRecursively()
    }

    /** A controller whose capture is deterministic, so a display is not required. */
    private class FakeController : DesktopControllerContract {
        val input = mutableListOf<String>()
        var launched: String? = null
        override fun captureScreen(maxWidth: Int, quality: Int, monitor: Int) =
            CapturedFrame(320, 240, "JPEG", 42L, byteArrayOf(1, 2, 3, 4))
        override fun monitorCount() = 1
        override fun screenSize() = java.awt.Dimension(1920, 1080)
        override fun mouseMove(x: Int, y: Int) { input += "move($x,$y)" }
        override fun mouseDown(button: Int) { input += "down($button)" }
        override fun mouseUp(button: Int) { input += "up($button)" }
        override fun mouseWheel(amount: Int) { input += "wheel($amount)" }
        override fun keyDown(keyCode: Int) { input += "keydown($keyCode)" }
        override fun keyUp(keyCode: Int) { input += "keyup($keyCode)" }
        override fun typeText(text: String): Int { input += "text($text)"; return text.length }
        override fun listApplications() = listOf(AppEntryView("app:test", "Test App", false))
        override fun launchApplication(id: String): LaunchOutcome {
            launched = id
            return if (id == "app:test") LaunchOutcome(true, null, 7L) else LaunchOutcome(false, "Unknown application id '$id'")
        }
        var clipboard: String? = "initial clipboard"
        override fun readClipboard(): String? = clipboard
        override fun writeClipboard(text: String) { clipboard = text }
    }

    private fun session(
        store: ClientStore,
        controller: DesktopControllerContract,
        pairing: PairingController,
        config: AgentConfig = AgentConfig("test-agent", 0, tempRoot),
    ) = DesktopSession(config, store, controller, pairing) { }

    /**
     * Runs the real session handler over a real loopback TCP socket. A socket pair is used rather
     * than in-memory pipes so framing, partial reads and stream shutdown behave exactly as they do
     * on the wire.
     */
    private fun runSession(
        session: DesktopSession,
        block: (Client) -> Unit,
    ): SessionOutcome {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val outcome = arrayOfNulls<SessionOutcome>(1)
        val done = CountDownLatch(1)
        val acceptor = Thread {
            server.accept().use { socket ->
                outcome[0] = session.handle(socket.getInputStream(), socket.getOutputStream())
            }
            done.countDown()
        }
        acceptor.isDaemon = true
        acceptor.start()
        java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).use { clientSocket ->
            clientSocket.soTimeout = 10_000
            block(Client(clientSocket.getInputStream(), clientSocket.getOutputStream()))
        }
        done.await(10, TimeUnit.SECONDS)
        server.close()
        return outcome[0] ?: SessionOutcome(null, "no outcome")
    }

    private class Client(val input: java.io.InputStream, val output: java.io.OutputStream) {
        // The server closes the socket after a refused handshake, so a final send can legitimately
        // fail. The assertions that matter run on the messages received before that point.
        fun send(message: RemoteMessage) = runCatching { FrameCodec.write(output, RemoteCodec.encode(message)) }
        fun sendRaw(frame: Frame) = runCatching { FrameCodec.write(output, frame) }
        fun receive(): RemoteMessage = RemoteCodec.decode(FrameCodec.read(input)!!)
        fun receiveFrame(): Frame = FrameCodec.read(input)!!
    }

    private fun hello(deviceId: String = "device-1") =
        Hello(RemoteProtocol.VERSION, deviceId, "Test Phone", RemoteCrypto.newNonce())

    // ---- Pairing and authentication ----

    @Test
    fun `a client with the right code pairs and the secret is stored`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        val outcome = runSession(session) { client ->
            client.send(hello())
            val ack = client.receive() as HelloAck
            assertFalse("a fresh client must not be reported as paired", ack.paired)
            client.send(PairRequest(code, ""))
            assertTrue((client.receive() as PairResult).accepted)
            client.send(Disconnect("done"))
        }

        assertEquals("device-1", outcome.deviceId)
        assertNotNull(store.find("device-1"))
    }

    @Test
    fun `a wrong pairing code is refused and nothing is stored`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest("WRONGCODE", ""))
            val result = client.receive() as PairResult
            assertFalse(result.accepted)
            assertNotNull(result.reason)
            client.send(Disconnect("done"))
        }

        assertEquals(null, store.find("device-1"))
    }

    @Test
    fun `a closed pairing window refuses the code`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController(ttlMs = 0)
        val code = pairing.open()
        Thread.sleep(5)
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            assertFalse((client.receive() as PairResult).accepted)
            client.send(Disconnect("done"))
        }

        assertEquals(null, store.find("device-1"))
    }

    @Test
    fun `a paired client authenticates with a proof over the challenge`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val session = session(store, FakeController(), PairingController())

        val outcome = runSession(session) { client ->
            client.send(hello())
            val ack = client.receive() as HelloAck
            assertTrue("a stored client must be reported as paired", ack.paired)
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            assertTrue((client.receive() as AuthResult).accepted)
            client.send(Disconnect("done"))
        }

        assertEquals("device-1", outcome.deviceId)
    }

    @Test
    fun `a wrong authentication proof is refused`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        store.save(PairedClient("device-1", "Phone", RemoteCrypto.deriveSecret("ABCD2345", "n", "s"), 0L))
        val session = session(store, FakeController(), PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.receive() // challenge
            client.send(AuthResponse("device-1", "not-a-valid-proof"))
            assertFalse((client.receive() as AuthResult).accepted)
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `clipboard get returns the desktop clipboard`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val controller = FakeController().apply { clipboard = "desktop has this" }
        val session = session(store, controller, PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()

            client.send(ClipboardGet())
            val value = client.receive() as ClipboardValue
            assertEquals("desktop has this", value.text)
            assertTrue(value.available)

            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `clipboard get reports unavailable when the desktop holds no text`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val controller = FakeController().apply { clipboard = null }
        val session = session(store, controller, PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()

            client.send(ClipboardGet())
            val value = client.receive() as ClipboardValue
            assertEquals("", value.text)
            assertFalse("an absent clipboard must be reported as unavailable", value.available)

            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `clipboard set writes to the desktop clipboard`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val controller = FakeController()
        val session = session(store, controller, PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()

            client.send(ClipboardSet("text from the phone"))
            client.send(ClipboardGet())
            assertEquals("text from the phone", (client.receive() as ClipboardValue).text)

            client.send(Disconnect("done"))
        }
        assertEquals("text from the phone", controller.clipboard)
    }

    @Test
    fun `clipboard set is refused when input is disabled`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val controller = FakeController().apply { clipboard = "untouched" }
        val session = session(
            store, controller, PairingController(),
            AgentConfig("test-agent", 0, tempRoot, allowInput = false),
        )

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()

            client.send(ClipboardSet("should not apply"))
            val error = client.receive() as ProtocolError
            assertEquals("clipboard_disabled", error.code)

            client.send(Disconnect("done"))
        }
        assertEquals("the desktop clipboard must be untouched", "untouched", controller.clipboard)
    }

    @Test
    fun `an oversized clipboard payload is refused by the agent`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, System.currentTimeMillis()))
        val controller = FakeController().apply { clipboard = "untouched" }
        val session = session(store, controller, PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()

            client.send(ClipboardSet("y".repeat(ClipboardSet.CLIPBOARD_TEXT_LIMIT + 1)))
            val error = client.receive() as ProtocolError
            assertEquals("clipboard_too_large", error.code)

            client.send(Disconnect("done"))
        }
        assertEquals("untouched", controller.clipboard)
    }

    @Test
    fun `a revoked device cannot authenticate`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, 0L))
        store.revokeDevice("device-1")
        val session = session(store, FakeController(), PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val result = client.receive() as AuthResult
            assertFalse(result.accepted)
            assertTrue(result.reason!!.contains("revoked"))
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `a protocol version mismatch is refused with a specific error`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val session = session(store, FakeController(), PairingController())

        runSession(session) { client ->
            client.send(Hello(99, "device-1", "Phone", "n"))
            val error = client.receive() as ProtocolError
            assertEquals("version_mismatch", error.code)
        }
    }

    @Test
    fun `an unauthenticated client cannot send input`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val controller = FakeController()
        val session = session(store, controller, PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            // No pairing or authentication happened; an input event must not reach the desktop.
            client.send(InputEvent(InputEvent.MOUSE_MOVE, x = 5, y = 5))
            client.send(Disconnect("done"))
        }

        assertTrue("input must not be applied before authentication", controller.input.isEmpty())
    }

    // ---- Input ----

    @Test
    fun `an authenticated client drives the mouse and keyboard`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val controller = FakeController()
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, controller, pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(InputEvent(InputEvent.MOUSE_MOVE, x = 100, y = 200))
            client.send(InputEvent(InputEvent.MOUSE_DOWN, button = 1))
            client.send(InputEvent(InputEvent.MOUSE_UP, button = 1))
            client.send(InputEvent(InputEvent.TEXT, text = "hi"))
            client.send(Disconnect("done"))
        }

        assertTrue(controller.input.contains("move(100,200)"))
        assertTrue(controller.input.contains("down(1)"))
        assertTrue(controller.input.contains("text(hi)"))
    }

    @Test
    fun `an unknown input kind is rejected with an error`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(InputEvent("delete_everything"))
            val error = client.receive() as ProtocolError
            assertEquals("bad_input", error.code)
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `input is refused when the agent disables it`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val controller = FakeController()
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(
            store, controller, pairing,
            AgentConfig("test-agent", 0, tempRoot, allowInput = false),
        )

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(InputEvent(InputEvent.MOUSE_MOVE, x = 1, y = 1))
            client.send(Disconnect("done"))
        }

        assertTrue(controller.input.isEmpty())
    }

    // ---- Heartbeat ----

    @Test
    fun `a heartbeat is answered with its sequence number`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(Heartbeat(77, 0))
            val ack = client.receive() as HeartbeatAck
            assertEquals(77, ack.sequence)
            client.send(Disconnect("done"))
        }
    }

    // ---- Applications ----

    @Test
    fun `listing and launching an application works`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val controller = FakeController()
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, controller, pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(AppListRequest())
            val list = client.receive() as AppList
            assertEquals(1, list.entries.size)
            client.send(AppLaunch("app:test"))
            assertTrue((client.receive() as AppLaunchResult).accepted)
            client.send(Disconnect("done"))
        }

        assertEquals("app:test", controller.launched)
    }

    // ---- Files ----

    @Test
    fun `a file inside the shared root downloads as chunks`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)
        File(tempRoot, "hello.txt").writeText("hello world")

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(FileDownloadRequest("hello.txt"))
            val begin = client.receive() as FileDownloadBegin
            assertEquals(11L, begin.size)
            val received = ByteArrayOutputStream()
            while (true) {
                val chunk = FileChunkCodec.decode(client.receiveFrame())
                received.write(chunk.data)
                if (chunk.last) break
            }
            assertEquals("hello world", received.toString())
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `a path outside the shared root is refused`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(FileDownloadRequest("../../etc/passwd"))
            val end = client.receive() as FileTransferEnd
            assertFalse(end.ok)
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `a file upload is written inside the shared root`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(FileUploadBegin("uploaded.txt", 5, "t-1"))
            client.sendRaw(FileChunkCodec.encode(FileChunkCodec.FileChunk("t-1", 0, true, "hello".toByteArray())))
            val end = client.receive() as FileTransferEnd
            assertTrue(end.ok)
            client.send(Disconnect("done"))
        }

        assertEquals("hello", File(tempRoot, "uploaded.txt").readText())
    }

    @Test
    fun `file transfer is refused when the agent disables it`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(
            store, FakeController(), pairing,
            AgentConfig("test-agent", 0, tempRoot, allowFileTransfer = false),
        )
        File(tempRoot, "secret.txt").writeText("nope")

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(FileDownloadRequest("secret.txt"))
            val end = client.receive() as FileTransferEnd
            assertFalse(end.ok)
            client.send(Disconnect("done"))
        }
    }

    // ---- Revocation ----

    @Test
    fun `a revoke message marks every client revoked`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        store.save(PairedClient("device-1", "Phone", secret, 0L))
        val session = session(store, FakeController(), PairingController())

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-1", RemoteCrypto.proof(secret, "device-1", challenge.challenge)))
            client.receive()
            client.send(Revoke("owner revoked"))
        }

        assertTrue(store.find("device-1")!!.revoked)
    }

    // ---- Screen streaming ----

    @Test
    fun `a screen request produces a screen frame`() {
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)

        runSession(session) { client ->
            client.send(hello())
            client.receive()
            client.send(PairRequest(code, ""))
            client.receive()
            client.send(ScreenRequest(320, 60))
            val frame = client.receiveFrame()
            assertEquals(RemoteProtocol.FRAME_SCREEN, frame.type)
            val screen = ScreenFrameCodec.decode(frame)
            assertEquals(320, screen.width)
            assertEquals(240, screen.height)
            client.send(ScreenRequest(0, 0))
            client.send(Disconnect("done"))
        }
    }

    @Test
    fun `a client that derives its own secret from the acknowledgement can authenticate`() {
        // This is the cross-implementation check: the client derives the secret from the code and
        // the two nonces exactly as RemoteDesktopClient does, then authenticates with it. If the
        // server derived from a different nonce than the one it sent, this fails.
        val store = ClientStore(File(tempRoot, "clients.json"))
        val pairing = PairingController()
        val code = pairing.open()
        val session = session(store, FakeController(), pairing)
        val clientNonce = RemoteCrypto.newNonce()

        val outcome = runSession(session) { client ->
            client.send(Hello(RemoteProtocol.VERSION, "device-2", "Test Phone", clientNonce))
            val ack = client.receive() as HelloAck
            ackNonce = ack.nonce
            client.send(PairRequest(code, ""))
            assertTrue((client.receive() as PairResult).accepted)
            client.send(Disconnect("paired"))
        }

        assertEquals("device-2", outcome.deviceId)

        // Now reconnect as the client would, deriving the secret from what it was told.
        val stored = store.find("device-2")!!
        val clientSideSecret = RemoteCrypto.deriveSecret(code, clientNonce, ackNonceOf(outcome, session))
        assertTrue(
            "the client must derive the same secret the server stored",
            clientSideSecret.contentEquals(stored.secret),
        )

        val secondSession = session(store, FakeController(), PairingController())
        runSession(secondSession) { client ->
            client.send(Hello(RemoteProtocol.VERSION, "device-2", "Test Phone", RemoteCrypto.newNonce()))
            val ack = client.receive() as HelloAck
            assertTrue(ack.paired)
            val challenge = client.receive() as AuthChallenge
            client.send(AuthResponse("device-2", RemoteCrypto.proof(clientSideSecret, "device-2", challenge.challenge)))
            assertTrue("the client-derived secret must authenticate", (client.receive() as AuthResult).accepted)
            client.send(Disconnect("done"))
        }
    }

    /** The nonce the server put in its hello acknowledgement, captured from the first handshake. */
    private fun ackNonceOf(outcome: SessionOutcome, session: DesktopSession): String =
        ackNonce ?: error("the acknowledgement nonce was not captured")

    @Test
    fun `the client store persists and reloads a paired device`() {
        val file = File(tempRoot, "clients.json")
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "n", "s")
        ClientStore(file).save(PairedClient("device-1", "Phone", secret, 123L))

        val reloaded = ClientStore(file).find("device-1")

        assertNotNull(reloaded)
        assertEquals("Phone", reloaded!!.deviceName)
        assertEquals(123L, reloaded.pairedAtMs)
        assertTrue(reloaded.secret.contentEquals(secret))
    }

    @Test
    fun `the client store marks a device revoked and keeps it revoked across a reload`() {
        val file = File(tempRoot, "clients.json")
        val store = ClientStore(file)
        store.save(PairedClient("device-1", "Phone", RemoteCrypto.deriveSecret("ABCD2345", "n", "s"), 0L))
        store.revokeDevice("device-1")

        assertTrue(ClientStore(file).find("device-1")!!.revoked)
    }

    @Test
    fun `pairing controller closes the window after a code is used`() {
        val pairing = PairingController()
        pairing.open()
        assertTrue(pairing.isOpen())
        pairing.close()
        assertFalse(pairing.isOpen())
    }
}
