package com.opendroid.ai.remote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Remote-control wire protocol, version 1.
 *
 * The same code runs on Android and on the desktop agent. A frame is
 *
 *     [4-byte big-endian payload length][1-byte frame type][payload]
 *
 * so a large screen frame never has to be base64-encoded into JSON. Control messages are JSON with
 * a `type` discriminator; screen and file frames carry raw bytes with a small binary header.
 *
 * Every field that crosses the wire is validated on read: the protocol version must match, lengths
 * are bounded, and an unknown frame type or message type is an error rather than being ignored.
 */
object RemoteProtocol {
    const val VERSION = 1

    /** Upper bound on a single frame, so a hostile or broken peer cannot exhaust memory. */
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    const val FRAME_CONTROL = 1
    const val FRAME_SCREEN = 2
    const val FRAME_FILE_CHUNK = 3

    internal val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        classDiscriminator = "type"
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A frame as read from or written to the wire. */
data class Frame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/** Length-prefixed framing over a stream. */
object FrameCodec {

    fun write(output: OutputStream, frame: Frame) {
        require(frame.payload.size <= RemoteProtocol.MAX_FRAME_BYTES) {
            "Frame of ${frame.payload.size} bytes exceeds the ${RemoteProtocol.MAX_FRAME_BYTES} byte limit"
        }
        val header = ByteBuffer.allocate(5)
        header.putInt(frame.payload.size)
        header.put(frame.type.toByte())
        output.write(header.array())
        output.write(frame.payload)
        output.flush()
    }

    /**
     * Reads exactly one frame. Returns null at a clean end of stream. A declared length above the
     * limit is refused before any payload is buffered, so a peer cannot force a huge allocation.
     */
    fun read(input: InputStream): Frame? {
        val header = readFully(input, 5) ?: return null
        val buffer = ByteBuffer.wrap(header)
        val length = buffer.int
        val type = buffer.get().toInt()
        if (length < 0 || length > RemoteProtocol.MAX_FRAME_BYTES) {
            throw ProtocolException("Declared frame length $length is out of range")
        }
        val payload = if (length == 0) ByteArray(0) else readFully(input, length)
            ?: throw EOFException("Stream ended inside a frame payload")
        return Frame(type, payload)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) return if (read == 0) null else throw EOFException("Stream ended after $read of $count bytes")
            read += n
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Control messages
// ---------------------------------------------------------------------------------------------

@Serializable
sealed class RemoteMessage

@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val nonce: String,
) : RemoteMessage()

@Serializable
@SerialName("hello_ack")
data class HelloAck(
    val protocolVersion: Int,
    val serverId: String,
    val serverName: String,
    val nonce: String,
    val paired: Boolean,
    /**
     * The desktop's WireGuard public key when it offers the full-tunnel gateway.
     *
     * Optional so a phone and an older agent still pair: null means the browser-proxy path is the
     * only way to share the desktop's internet.
     */
    val gatewayPublicKey: String? = null,
    /** The desktop's WireGuard listen port, when [gatewayPublicKey] is present. */
    val gatewayPort: Int? = null,
) : RemoteMessage()

/** First-time pairing. The code is the short-lived secret the owner reads off the desktop. */
@Serializable
@SerialName("pair_request")
data class PairRequest(val code: String, val clientPublicKey: String) : RemoteMessage()

@Serializable
@SerialName("pair_result")
data class PairResult(val accepted: Boolean, val reason: String? = null) : RemoteMessage()

/** Mutual authentication for an already-paired client. */
@Serializable
@SerialName("auth_challenge")
data class AuthChallenge(val challenge: String) : RemoteMessage()

@Serializable
@SerialName("auth_response")
data class AuthResponse(val deviceId: String, val proof: String) : RemoteMessage()

@Serializable
@SerialName("auth_result")
data class AuthResult(val accepted: Boolean, val reason: String? = null) : RemoteMessage()

@Serializable
@SerialName("heartbeat")
data class Heartbeat(val sequence: Long, val sentAtMs: Long) : RemoteMessage()

@Serializable
@SerialName("heartbeat_ack")
data class HeartbeatAck(val sequence: Long, val serverTimeMs: Long) : RemoteMessage()

/** The owner revoked this client; the agent must close the session and forget the pairing. */
@Serializable
@SerialName("revoke")
data class Revoke(val reason: String) : RemoteMessage()

@Serializable
@SerialName("disconnect")
data class Disconnect(val reason: String) : RemoteMessage()

@Serializable
@SerialName("error")
data class ProtocolError(val code: String, val message: String) : RemoteMessage()

// ---- Input ----

@Serializable
@SerialName("input")
data class InputEvent(
    val kind: String,
    val x: Int = 0,
    val y: Int = 0,
    val button: Int = 0,
    val keyCode: Int = 0,
    val text: String = "",
    val wheel: Int = 0,
    /** Zero-based display for coordinate-based mouse input; defaults preserve older clients. */
    val monitor: Int = 0,
    /** Pixel dimensions of the displayed frame used as the coordinate basis. */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
) : RemoteMessage() {
    companion object {
        const val MOUSE_MOVE = "mouse_move"
        const val MOUSE_DOWN = "mouse_down"
        const val MOUSE_UP = "mouse_up"
        const val MOUSE_WHEEL = "mouse_wheel"
        const val KEY_DOWN = "key_down"
        const val KEY_UP = "key_up"
        const val TEXT = "text"

        val KINDS = setOf(MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, MOUSE_WHEEL, KEY_DOWN, KEY_UP, TEXT)
    }
}

// ---- Screen ----

@Serializable
@SerialName("screen_request")
data class ScreenRequest(val maxWidth: Int, val quality: Int, val monitor: Int = 0) : RemoteMessage()

@Serializable
@SerialName("screen_info")
data class ScreenInfo(val width: Int, val height: Int, val monitorCount: Int) : RemoteMessage()

// ---- Applications ----

@Serializable
@SerialName("app_list_request")
data class AppListRequest(val unused: Int = 0) : RemoteMessage()

@Serializable
@SerialName("app_list")
data class AppList(val entries: List<AppEntry>) : RemoteMessage()

@Serializable
data class AppEntry(val id: String, val name: String, val running: Boolean)

@Serializable
@SerialName("app_launch")
data class AppLaunch(val id: String) : RemoteMessage()

@Serializable
@SerialName("app_launch_result")
data class AppLaunchResult(val id: String, val accepted: Boolean, val reason: String? = null) : RemoteMessage()

// ---- Files ----

@Serializable
@SerialName("file_list_request")
data class FileListRequest(val path: String) : RemoteMessage()

@Serializable
@SerialName("file_list")
data class FileList(val path: String, val entries: List<FileEntry>) : RemoteMessage()

@Serializable
data class FileEntry(val name: String, val directory: Boolean, val size: Long)

@Serializable
@SerialName("file_download_request")
data class FileDownloadRequest(val path: String) : RemoteMessage()

@Serializable
@SerialName("file_download_begin")
data class FileDownloadBegin(val path: String, val size: Long, val transferId: String) : RemoteMessage()

@Serializable
@SerialName("file_upload_begin")
data class FileUploadBegin(val path: String, val size: Long, val transferId: String) : RemoteMessage()

@Serializable
@SerialName("file_transfer_end")
data class FileTransferEnd(val transferId: String, val ok: Boolean, val reason: String? = null) : RemoteMessage()

// ---- Clipboard ----

/**
 * Clipboard text, in both directions.
 *
 * The two sides do not share a clipboard, so a change is pushed as an explicit message rather than
 * read on demand. [CLIPBOARD_TEXT_LIMIT] bounds the payload: a huge clipboard (a pasted log, an
 * image encoded as base64) would otherwise exceed the frame limit and break the session.
 */
@Serializable
@SerialName("clipboard_set")
data class ClipboardSet(val text: String) : RemoteMessage() {
    companion object {
        const val CLIPBOARD_TEXT_LIMIT = 256 * 1024
    }
}

/** Asks the other side for its current clipboard text. */
@Serializable
@SerialName("clipboard_get")
data class ClipboardGet(val unused: Int = 0) : RemoteMessage()

@Serializable
@SerialName("clipboard_value")
data class ClipboardValue(val text: String, val available: Boolean = true) : RemoteMessage()

/** Encodes and decodes control messages, refusing an unknown or mismatched message. */
object RemoteCodec {

    fun encode(message: RemoteMessage): Frame {
        val payload = RemoteProtocol.json.encodeToString(RemoteMessage.serializer(), message).toByteArray()
        return Frame(RemoteProtocol.FRAME_CONTROL, payload)
    }

    fun decode(frame: Frame): RemoteMessage {
        if (frame.type != RemoteProtocol.FRAME_CONTROL) {
            throw ProtocolException("Frame type ${frame.type} is not a control message")
        }
        return runCatching {
            RemoteProtocol.json.decodeFromString(RemoteMessage.serializer(), String(frame.payload))
        }.getOrElse { throw ProtocolException("Malformed control message: ${it.message}", it) }
    }
}

/** Screen frames: a compact binary header followed by the encoded image. */
object ScreenFrameCodec {

    data class ScreenFrame(
        val width: Int,
        val height: Int,
        val format: String,
        val capturedAtMs: Long,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ScreenFrame && width == other.width && height == other.height &&
                format == other.format && capturedAtMs == other.capturedAtMs && data.contentEquals(other.data)

        override fun hashCode(): Int = listOf(width, height, format, capturedAtMs, data.contentHashCode()).hashCode()
    }

    private const val HEADER_BYTES = 4 + 4 + 8 + 1

    fun encode(frame: ScreenFrame): Frame {
        val formatCode = when (frame.format.uppercase()) {
            "JPEG" -> 1
            "PNG" -> 2
            else -> throw ProtocolException("Unsupported screen format ${frame.format}")
        }
        require(frame.width in 1..16_384 && frame.height in 1..16_384) { "Screen dimensions are out of range" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + frame.data.size)
        buffer.putInt(frame.width)
        buffer.putInt(frame.height)
        buffer.putLong(frame.capturedAtMs)
        buffer.put(formatCode.toByte())
        buffer.put(frame.data)
        return Frame(RemoteProtocol.FRAME_SCREEN, buffer.array())
    }

    fun decode(frame: Frame): ScreenFrame {
        if (frame.type != RemoteProtocol.FRAME_SCREEN) {
            throw ProtocolException("Frame type ${frame.type} is not a screen frame")
        }
        if (frame.payload.size < HEADER_BYTES) throw ProtocolException("Screen frame is shorter than its header")
        val buffer = ByteBuffer.wrap(frame.payload)
        val width = buffer.int
        val height = buffer.int
        val capturedAt = buffer.long
        val format = when (val code = buffer.get().toInt()) {
            1 -> "JPEG"
            2 -> "PNG"
            else -> throw ProtocolException("Unknown screen format code $code")
        }
        if (width !in 1..16_384 || height !in 1..16_384) throw ProtocolException("Screen dimensions are out of range")
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return ScreenFrame(width, height, format, capturedAt, data)
    }
}

/** File chunks: a transfer id, a sequence number, and a final-chunk flag. */
object FileChunkCodec {

    data class FileChunk(
        val transferId: String,
        val sequence: Int,
        val last: Boolean,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is FileChunk && transferId == other.transferId && sequence == other.sequence &&
                last == other.last && data.contentEquals(other.data)

        override fun hashCode(): Int = listOf(transferId, sequence, last, data.contentHashCode()).hashCode()
    }

    fun encode(chunk: FileChunk): Frame {
        val id = chunk.transferId.toByteArray()
        require(id.size in 1..255) { "Transfer id must be 1-255 bytes" }
        val buffer = ByteBuffer.allocate(1 + id.size + 4 + 1 + chunk.data.size)
        buffer.put(id.size.toByte())
        buffer.put(id)
        buffer.putInt(chunk.sequence)
        buffer.put(if (chunk.last) 1 else 0)
        buffer.put(chunk.data)
        return Frame(RemoteProtocol.FRAME_FILE_CHUNK, buffer.array())
    }

    fun decode(frame: Frame): FileChunk {
        if (frame.type != RemoteProtocol.FRAME_FILE_CHUNK) {
            throw ProtocolException("Frame type ${frame.type} is not a file chunk")
        }
        val buffer = ByteBuffer.wrap(frame.payload)
        if (buffer.remaining() < 1) throw ProtocolException("File chunk has no id length")
        val idLength = buffer.get().toInt() and 0xFF
        if (idLength == 0 || buffer.remaining() < idLength + 5) throw ProtocolException("File chunk header is truncated")
        val idBytes = ByteArray(idLength).also(buffer::get)
        val sequence = buffer.int
        val last = buffer.get().toInt() == 1
        val data = ByteArray(buffer.remaining()).also(buffer::get)
        return FileChunk(String(idBytes), sequence, last, data)
    }
}

/** Reads a whole stream into memory, bounded. Used for tests and small transfers. */
internal fun InputStream.readAllBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > limit) throw ProtocolException("Stream exceeded the $limit byte limit")
    }
    return out.toByteArray()
}
