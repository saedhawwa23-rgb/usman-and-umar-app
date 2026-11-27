package com.opendroid.ai.remote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class RemoteProtocolTest {

    private fun roundTrip(message: RemoteMessage): RemoteMessage {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, RemoteCodec.encode(message))
        return RemoteCodec.decode(FrameCodec.read(ByteArrayInputStream(out.toByteArray()))!!)
    }

    @Test
    fun `a control message round-trips through the framing`() {
        val original = InputEvent(InputEvent.MOUSE_MOVE, x = 12, y = 34)

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `every control message type round-trips`() {
        val messages = listOf(
            Hello(1, "d", "phone", "n"),
            HelloAck(1, "s", "desktop", "n", true),
            PairRequest("ABCD2345", "pk"),
            PairResult(true),
            AuthChallenge("c"),
            AuthResponse("d", "p"),
            AuthResult(true),
            Heartbeat(5, 1),
            HeartbeatAck(5, 2),
            Revoke("r"),
            Disconnect("bye"),
            ProtocolError("code", "msg"),
            InputEvent(InputEvent.KEY_DOWN, keyCode = 65, monitor = 2, screenWidth = 640, screenHeight = 360),
            ScreenRequest(1280, 70, 1),
            ScreenInfo(1920, 1080, 2),
            AppListRequest(),
            AppList(listOf(AppEntry("id", "Name", true))),
            AppLaunch("id"),
            AppLaunchResult("id", true),
            FileListRequest("/tmp"),
            FileList("/tmp", listOf(FileEntry("a.txt", false, 12))),
            FileDownloadRequest("/tmp/a.txt"),
            FileDownloadBegin("/tmp/a.txt", 12, "t1"),
            FileUploadBegin("/tmp/a.txt", 12, "t1"),
            FileTransferEnd("t1", true),
            ClipboardSet("copied text"),
            ClipboardGet(),
            ClipboardValue("desktop text", true),
        )

        for (message in messages) {
            assertEquals(message, roundTrip(message))
        }
    }

    @Test
    fun `a frame is a four byte length, a type and the payload`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, Frame(RemoteProtocol.FRAME_CONTROL, byteArrayOf(1, 2, 3)))
        val bytes = out.toByteArray()

        assertEquals(5 + 3, bytes.size)
        assertEquals(3, bytes[3].toInt())
        assertEquals(RemoteProtocol.FRAME_CONTROL, bytes[4].toInt())
    }

    @Test
    fun `reading an empty stream returns null`() {
        assertNull(FrameCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a declared length above the limit is refused before allocating`() {
        val header = ByteArray(5)
        header[0] = 0x7F; header[1] = 0xFF.toByte(); header[2] = 0xFF.toByte(); header[3] = 0xFF.toByte()
        val error = runCatching { FrameCodec.read(ByteArrayInputStream(header)) }.exceptionOrNull()
        assertTrue(error is ProtocolException)
    }

    @Test
    fun `a negative declared length is refused`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0)
        assertTrue(runCatching { FrameCodec.read(ByteArrayInputStream(header)) }.exceptionOrNull() is ProtocolException)
    }

    @Test
    fun `a stream ending inside a payload raises an EOF`() {
        val header = byteArrayOf(0, 0, 0, 10, 1)
        val error = runCatching { FrameCodec.read(ByteArrayInputStream(header)) }.exceptionOrNull()
        assertTrue(error is EOFException)
    }

    @Test
    fun `a screen frame round-trips with its bytes`() {
        val frame = ScreenFrameCodec.ScreenFrame(800, 600, "JPEG", 1234L, byteArrayOf(9, 8, 7))

        val decoded = ScreenFrameCodec.decode(ScreenFrameCodec.encode(frame))

        assertEquals(800, decoded.width)
        assertEquals(600, decoded.height)
        assertEquals("JPEG", decoded.format)
        assertEquals(1234L, decoded.capturedAtMs)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.data)
    }

    @Test
    fun `a png screen frame round-trips`() {
        val frame = ScreenFrameCodec.ScreenFrame(10, 10, "PNG", 1L, byteArrayOf(1))
        assertEquals("PNG", ScreenFrameCodec.decode(ScreenFrameCodec.encode(frame)).format)
    }

    @Test
    fun `an unsupported screen format is refused`() {
        val frame = ScreenFrameCodec.ScreenFrame(10, 10, "BMP", 1L, byteArrayOf(1))
        assertTrue(runCatching { ScreenFrameCodec.encode(frame) }.exceptionOrNull() is ProtocolException)
    }

    @Test
    fun `a screen frame shorter than its header is refused`() {
        val error = runCatching {
            ScreenFrameCodec.decode(Frame(RemoteProtocol.FRAME_SCREEN, ByteArray(4)))
        }.exceptionOrNull()
        assertTrue(error is ProtocolException)
    }

    @Test
    fun `a file chunk round-trips and marks the last chunk`() {
        val chunk = FileChunkCodec.FileChunk("transfer-1", 3, true, byteArrayOf(4, 5, 6))

        val decoded = FileChunkCodec.decode(FileChunkCodec.encode(chunk))

        assertEquals("transfer-1", decoded.transferId)
        assertEquals(3, decoded.sequence)
        assertTrue(decoded.last)
        assertArrayEquals(byteArrayOf(4, 5, 6), decoded.data)
    }

    @Test
    fun `a file chunk with a truncated header is refused`() {
        val error = runCatching { FileChunkCodec.decode(Frame(RemoteProtocol.FRAME_FILE_CHUNK, byteArrayOf(5, 1))) }
            .exceptionOrNull()
        assertTrue(error is ProtocolException)
    }

    @Test
    fun `a malformed control payload is refused`() {
        val error = runCatching { RemoteCodec.decode(Frame(RemoteProtocol.FRAME_CONTROL, "{oops".toByteArray())) }
            .exceptionOrNull()
        assertTrue(error is ProtocolException)
    }

    @Test
    fun `decoding a non-control frame as a message is refused`() {
        val error = runCatching { RemoteCodec.decode(Frame(RemoteProtocol.FRAME_SCREEN, ByteArray(0))) }
            .exceptionOrNull()
        assertTrue(error is ProtocolException)
    }

    @Test
    fun `an unknown message type is refused rather than ignored`() {
        val payload = """{"type":"something_else","x":1}""".toByteArray()
        assertTrue(runCatching { RemoteCodec.decode(Frame(RemoteProtocol.FRAME_CONTROL, payload)) }.exceptionOrNull() != null)
    }

    // ---- Crypto ----

    @Test
    fun `both sides derive the same secret from the code and nonces`() {
        val a = RemoteCrypto.deriveSecret("ABCD2345", "nonce-client", "nonce-server")
        val b = RemoteCrypto.deriveSecret("ABCD2345", "nonce-client", "nonce-server")

        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun `a wrong pairing code derives a different secret`() {
        val right = RemoteCrypto.deriveSecret("ABCD2345", "c", "s")
        val wrong = RemoteCrypto.deriveSecret("ABCD2346", "c", "s")

        assertFalse(right.contentEquals(wrong))
    }

    @Test
    fun `a different nonce derives a different secret`() {
        val a = RemoteCrypto.deriveSecret("ABCD2345", "c1", "s")
        val b = RemoteCrypto.deriveSecret("ABCD2345", "c2", "s")

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a proof verifies only with the right secret and challenge`() {
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "c", "s")
        val proof = RemoteCrypto.proof(secret, "device-1", "challenge-1")

        assertTrue(RemoteCrypto.verifyProof(secret, "device-1", "challenge-1", proof))
        assertFalse(RemoteCrypto.verifyProof(secret, "device-2", "challenge-1", proof))
        assertFalse(RemoteCrypto.verifyProof(secret, "device-1", "challenge-2", proof))
        assertFalse(RemoteCrypto.verifyProof(RemoteCrypto.deriveSecret("OTHER234", "c", "s"), "device-1", "challenge-1", proof))
    }

    @Test
    fun `a fresh challenge produces a different proof`() {
        val secret = RemoteCrypto.deriveSecret("ABCD2345", "c", "s")

        assertNotEquals(RemoteCrypto.proof(secret, "d", "challenge-1"), RemoteCrypto.proof(secret, "d", "challenge-2"))
    }

    @Test
    fun `a pairing code uses an unambiguous alphabet`() {
        repeat(200) {
            val code = RemoteCrypto.newPairingCode()
            assertEquals(8, code.length)
            assertTrue(code.all { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" })
            // Characters that are easily confused when read aloud or typed are excluded.
            assertFalse(code.contains('0'))
            assertFalse(code.contains('O'))
            assertFalse(code.contains('1'))
            assertFalse(code.contains('I'))
        }
    }

    @Test
    fun `a nonce is 32 bytes of base64url`() {
        val nonce = RemoteCrypto.newNonce()
        assertEquals(43, nonce.length)
        assertFalse(nonce.contains('='))
    }

    @Test
    fun `constant time comparison is exact`() {
        assertTrue(RemoteCrypto.constantTimeEquals("abc", "abc"))
        assertFalse(RemoteCrypto.constantTimeEquals("abc", "abd"))
        assertFalse(RemoteCrypto.constantTimeEquals("abc", "ab"))
    }

    @Test
    fun `a pairing window closes after its ttl`() {
        val window = PairingWindow("ABCD2345", createdAtMs = 0L, ttlMs = 1_000L)

        assertTrue(window.isOpen(500L))
        assertTrue(window.isOpen(1_000L))
        assertFalse(window.isOpen(1_001L))
    }

    @Test
    fun `deriveSecret refuses a blank code`() {
        assertTrue(runCatching { RemoteCrypto.deriveSecret("", "c", "s") }.exceptionOrNull() is IllegalArgumentException)
    }
}
