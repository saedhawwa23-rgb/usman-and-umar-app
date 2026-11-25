package com.opendroid.ai.core.remote

import com.opendroid.ai.remote.protocol.AppEntry
import com.opendroid.ai.remote.protocol.AppLaunch
import com.opendroid.ai.remote.protocol.AppLaunchResult
import com.opendroid.ai.remote.protocol.AppList
import com.opendroid.ai.remote.protocol.AppListRequest
import com.opendroid.ai.remote.protocol.AuthChallenge
import com.opendroid.ai.remote.protocol.AuthResponse
import com.opendroid.ai.remote.protocol.AuthResult
import com.opendroid.ai.remote.protocol.Disconnect
import com.opendroid.ai.remote.protocol.FileChunkCodec
import com.opendroid.ai.remote.protocol.FileDownloadBegin
import com.opendroid.ai.remote.protocol.FileDownloadRequest
import com.opendroid.ai.remote.protocol.FileList
import com.opendroid.ai.remote.protocol.FileListRequest
import com.opendroid.ai.remote.protocol.FileUploadBegin
import com.opendroid.ai.remote.protocol.FrameCodec
import com.opendroid.ai.remote.protocol.ClipboardGet
import com.opendroid.ai.remote.protocol.ClipboardSet
import com.opendroid.ai.remote.protocol.ClipboardValue
import com.opendroid.ai.remote.protocol.Heartbeat
import com.opendroid.ai.remote.protocol.HeartbeatAck
import com.opendroid.ai.remote.protocol.Hello
import com.opendroid.ai.remote.protocol.HelloAck
import com.opendroid.ai.remote.protocol.InputEvent
import com.opendroid.ai.remote.protocol.PairRequest
import com.opendroid.ai.remote.protocol.PairResult
import com.opendroid.ai.remote.protocol.ProtocolError
import com.opendroid.ai.remote.protocol.RemoteCodec
import com.opendroid.ai.remote.protocol.RemoteCrypto
import com.opendroid.ai.remote.protocol.RemoteMessage
import com.opendroid.ai.remote.protocol.RemoteProtocol
import com.opendroid.ai.remote.protocol.ScreenFrameCodec
import com.opendroid.ai.remote.protocol.ScreenRequest
import com.opendroid.ai.remote.protocol.ScreenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The phone's view of a desktop session. */
sealed interface DesktopSessionState {
    data object Idle : DesktopSessionState
    data object Connecting : DesktopSessionState
    data class AwaitingPairing(val message: String) : DesktopSessionState
    data class Connected(val serverName: String, val screenWidth: Int, val screenHeight: Int) : DesktopSessionState
    data class Failed(val reason: String) : DesktopSessionState
    data class Closed(val reason: String) : DesktopSessionState
}

/** A paired desktop, remembered across app restarts. */
data class PairedDesktop(
    val host: String,
    val port: Int,
    val deviceId: String,
    val serverName: String,
    val secret: ByteArray,
    /** The certificate fingerprint recorded at pairing; null for a plain-TCP pairing. */
    val certificateFingerprint: String? = null,
    /**
     * The desktop's WireGuard public key, published when it offers internet sharing.
     * Null for older agents, which leaves the full-tunnel gateway unavailable but the SOCKS
     * proxy path working.
     */
    val gatewayPublicKey: String? = null,
    /** The desktop's WireGuard listen port; null falls back to the default. */
    val gatewayPort: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PairedDesktop && host == other.host && port == other.port && deviceId == other.deviceId

    override fun hashCode(): Int = listOf(host, port, deviceId).hashCode()
}

/** Where pairing secrets live; the Android binding uses the Keystore-backed store. */
interface PairedDesktopStore {
    fun load(): List<PairedDesktop>
    fun save(desktop: PairedDesktop)
    fun remove(deviceId: String)
}

/**
 * The Android client for the desktop agent.
 *
 * It speaks the same [RemoteProtocol] as the agent, so a mismatch is impossible to ship. Pairing and
 * authentication happen on a background dispatcher; a frame listener delivers screen frames and
 * control replies to the UI. Nothing here blocks the main thread.
 *
 * Transport is chosen by [RemoteTlsMode]:
 *  - [RemoteTlsMode.Pinned] is the default and the only mode that should cross an untrusted
 *    network. The agent's self-signed certificate is pinned by SHA-256 fingerprint, so no CA is
 *    needed and an impostor on the path cannot complete the handshake.
 *  - [RemoteTlsMode.Plain] exists for a loopback or trusted-LAN first test, and the UI warns.
 */
class RemoteDesktopClient(
    private val store: PairedDesktopStore,
    private val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val running = AtomicBoolean(false)
    private val pendingReplies = ConcurrentHashMap<String, (RemoteMessage) -> Unit>()

    /** A download in progress, so chunks can be collected until the final one arrives. */
    private class DownloadTarget(var transferId: String?, val path: String) {
        val bytes = java.io.ByteArrayOutputStream()
        var done = false
    }

    @Volatile
    private var downloadTarget: DownloadTarget? = null

    /** Called with the full bytes of a completed download, and the path it came from. */
    @Volatile
    var onDownloadComplete: (path: String, bytes: ByteArray) -> Unit = { _, _ -> }

    /** Called after each chunk so the UI can show progress on a large transfer. */
    @Volatile
    var onDownloadProgress: (path: String, bytesReceived: Long) -> Unit = { _, _ -> }

    @Volatile
    var state: DesktopSessionState = DesktopSessionState.Idle
        private set

    /**
     * How this client reaches the desktop. Set before [connect].
     *
     * A paired desktop carries the certificate fingerprint recorded at pairing, so the screen
     * switches this to [RemoteTlsMode.Pinned] automatically; a first pairing uses whatever the user
     * was shown on the desktop.
     */
    @Volatile
    var tlsMode: RemoteTlsMode = RemoteTlsMode.Plain

    /** The device id this phone presents; stable across connections so the desktop recognises it. */
    val deviceId: String = store.load().firstOrNull()?.deviceId ?: UUID.randomUUID().toString()

    /**
     * Connect to a desktop. When [pairingCode] is null the client authenticates with a stored
     * secret; when it is present the client pairs first and stores the derived secret.
     */
    suspend fun connect(
        host: String,
        port: Int,
        pairingCode: String?,
        onState: (DesktopSessionState) -> Unit,
        onScreenFrame: (ScreenFrameCodec.ScreenFrame) -> Unit,
        onMessage: (RemoteMessage) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onState(DesktopSessionState.Connecting.also { state = it })
            val sock = RemoteTransport.open(host, port, tlsMode)
            socket = sock
            val inStream = sock.getInputStream()
            val outStream = sock.getOutputStream()
            input = inStream
            output = outStream

            val clientNonce = RemoteCrypto.newNonce()
            write(Hello(RemoteProtocol.VERSION, deviceId, deviceName, clientNonce))
            val ack = expect<HelloAck>(inStream)
            if (ack.protocolVersion != RemoteProtocol.VERSION) {
                return@withContext fail(onState, "The desktop speaks protocol ${ack.protocolVersion}; this app speaks ${RemoteProtocol.VERSION}")
            }

            if (pairingCode != null) {
                val accepted = pair(inStream, outStream, pairingCode, clientNonce, ack, host, port, onState)
                if (!accepted) return@withContext Result.failure(IllegalStateException(state.toString()))
            } else {
                val known = store.load().firstOrNull { it.host == host && it.port == port }
                    ?: return@withContext fail(onState, "This desktop is not paired yet. Enter the pairing code shown on the desktop.")
                val authenticated = authenticate(inStream, outStream, known, onState)
                if (!authenticated) return@withContext Result.failure(IllegalStateException(state.toString()))
                // The desktop may have published its gateway key after this phone paired, so pick
                // up whatever it now advertises rather than requiring a re-pair.
                if (ack.gatewayPublicKey != known.gatewayPublicKey || ack.gatewayPort != known.gatewayPort) {
                    store.save(known.copy(gatewayPublicKey = ack.gatewayPublicKey, gatewayPort = ack.gatewayPort))
                }
            }

            running.set(true)
            onState(DesktopSessionState.Connected(ack.serverName, 0, 0).also { state = it })
            listen(inStream, onScreenFrame, onMessage, onState)
            Result.success(Unit)
        } catch (e: Exception) {
            fail(onState, e.message ?: "Could not reach the desktop")
        }
    }

    private suspend fun pair(
        inStream: InputStream,
        outStream: OutputStream,
        code: String,
        clientNonce: String,
        ack: HelloAck,
        host: String,
        port: Int,
        onState: (DesktopSessionState) -> Unit,
    ): Boolean {
        onState(DesktopSessionState.AwaitingPairing("Pairing…").also { state = it })
        write(PairRequest(code, ""))
        val result = expect<PairResult>(inStream)
        if (!result.accepted) {
            fail(onState, result.reason ?: "The desktop refused the pairing code")
            return false
        }
        // Both sides derive the same secret from the code and both nonces; the server nonce
        // travelled in its hello acknowledgement.
        val secret = RemoteCrypto.deriveSecret(code, clientNonce, ack.nonce)
        val fingerprint = (socket as? javax.net.ssl.SSLSocket)?.let { tls ->
            runCatching {
                tls.session.peerCertificates.firstOrNull()
                    ?.let { fingerprintOf(it as java.security.cert.X509Certificate) }
            }.getOrNull()
        }
        store.save(
            PairedDesktop(
                host, port, deviceId, ack.serverName, secret, fingerprint,
                gatewayPublicKey = ack.gatewayPublicKey,
                gatewayPort = ack.gatewayPort,
            )
        )
        return true
    }

    private fun authenticate(
        inStream: InputStream,
        outStream: OutputStream,
        known: PairedDesktop,
        onState: (DesktopSessionState) -> Unit,
    ): Boolean {
        val challenge = expect<AuthChallenge>(inStream)
        val proof = RemoteCrypto.proof(known.secret, known.deviceId, challenge.challenge)
        write(AuthResponse(known.deviceId, proof))
        val result = expect<AuthResult>(inStream)
        if (!result.accepted) {
            fail(onState, result.reason ?: "The desktop refused the connection")
            return false
        }
        return true
    }

    private fun listen(
        inStream: InputStream,
        onScreenFrame: (ScreenFrameCodec.ScreenFrame) -> Unit,
        onMessage: (RemoteMessage) -> Unit,
        onState: (DesktopSessionState) -> Unit,
    ) {
        while (running.get()) {
            val frame = FrameCodec.read(inStream) ?: break
            when (frame.type) {
                RemoteProtocol.FRAME_SCREEN -> onScreenFrame(ScreenFrameCodec.decode(frame))
                RemoteProtocol.FRAME_CONTROL -> {
                    val message = RemoteCodec.decode(frame)
                    if (message is HeartbeatAck) {
                        pendingReplies.remove("heartbeat")?.invoke(message)
                    }
                    if (message is FileDownloadBegin) {
                        // The agent chooses the transfer id, so the pending download adopts it here.
                        downloadTarget?.transferId = message.transferId
                    }
                    onMessage(message)
                }
                RemoteProtocol.FRAME_FILE_CHUNK -> {
                    val chunk = FileChunkCodec.decode(frame)
                    onIncomingChunk(chunk)
                }
            }
        }
        running.set(false)
        val reason = "The desktop closed the connection"
        onState(DesktopSessionState.Closed(reason).also { state = it })
    }

    /**
     * Accumulates an incoming download.
     *
     * The agent sends a [FileDownloadBegin] with the size, then chunks, with the final chunk marked
     * `last`. The bytes are held until complete so a truncated transfer is never handed on as if it
     * were the whole file.
     */
    private fun onIncomingChunk(chunk: FileChunkCodec.FileChunk) {
        val target = downloadTarget ?: return
        if (target.transferId != null && target.transferId != chunk.transferId) return
        synchronized(target) {
            if (target.done) return
            target.bytes.write(chunk.data)
            onDownloadProgress(target.path, target.bytes.size().toLong())
            if (chunk.last) {
                target.done = true
                downloadTarget = null
                onDownloadComplete(target.path, target.bytes.toByteArray())
            }
        }
    }

    // ---- Commands ----

    fun requestScreen(maxWidth: Int, quality: Int, monitor: Int = 0) = write(ScreenRequest(maxWidth, quality, monitor.coerceAtLeast(0)))

    fun stopScreen() = write(ScreenRequest(0, 0))

    fun sendInput(event: InputEvent) = write(event)

    fun listApplications() = write(AppListRequest())

    fun launchApplication(id: String) = write(AppLaunch(id))

    fun listFiles(path: String) = write(FileListRequest(path))

    /**
     * Ask for a file. The bytes arrive as chunks and are delivered to [onDownloadComplete] once the
     * final chunk lands; a failed transfer reports through [FileTransferEnd] on [onMessage].
     */
    fun downloadFile(path: String) {
        downloadTarget = DownloadTarget(transferId = null, path = path)
        write(FileDownloadRequest(path))
    }

    /**
     * Upload [bytes] to the desktop.
     *
     * The agent opens the destination on [FileUploadBegin], then accepts chunks until the final one
     * is marked. Chunks are sent from the caller's dispatcher so a large file does not stall the
     * frame listener.
     */
    suspend fun uploadFile(
        path: String,
        bytes: ByteArray,
        transferId: String = UUID.randomUUID().toString().take(16),
        chunkSize: Int = 128 * 1024,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("The file is larger than the ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB limit"),
            )
        }
        write(FileUploadBegin(path, bytes.size.toLong(), transferId))
        var offset = 0
        var sequence = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val last = end == bytes.size
            val slice = bytes.copyOfRange(offset, end)
            val stream = output ?: return@withContext Result.failure(
                IllegalStateException("The desktop connection closed during the upload"),
            )
            synchronized(this@RemoteDesktopClient) {
                FrameCodec.write(
                    stream,
                    FileChunkCodec.encode(FileChunkCodec.FileChunk(transferId, sequence++, last, slice)),
                )
            }
            offset = end
        }
        if (bytes.isEmpty()) {
            val stream = output ?: return@withContext Result.failure(
                IllegalStateException("The desktop connection closed during the upload"),
            )
            synchronized(this@RemoteDesktopClient) {
                FrameCodec.write(
                    stream,
                    FileChunkCodec.encode(FileChunkCodec.FileChunk(transferId, 0, true, ByteArray(0))),
                )
            }
        }
        Result.success(Unit)
    }

    fun sendHeartbeat() = write(Heartbeat(clock(), clock()))

    /** Push this phone's clipboard to the desktop. Oversized text is refused, not truncated. */
    fun setClipboard(text: String): Boolean {
        if (text.length > ClipboardSet.CLIPBOARD_TEXT_LIMIT) return false
        write(ClipboardSet(text))
        return true
    }

    fun requestClipboard() = write(ClipboardGet())

    /** Revoke this desktop on the phone side; the desktop is told so it can forget the pairing. */
    fun revoke() {
        runCatching { write(com.opendroid.ai.remote.protocol.Revoke("revoked from the phone")) }
        store.load().filter { it.deviceId == deviceId }.forEach { store.remove(it.deviceId) }
        disconnect("revoked")
    }

    fun disconnect(reason: String = "closed by the user") {
        running.set(false)
        runCatching { write(Disconnect(reason)) }
        runCatching { socket?.close() }
        socket = null
    }

    private fun write(message: RemoteMessage) {
        val stream = output ?: return
        synchronized(this) { runCatching { FrameCodec.write(stream, RemoteCodec.encode(message)) } }
    }

    private inline fun <reified T : RemoteMessage> expect(inStream: InputStream): T {
        val frame = FrameCodec.read(inStream) ?: throw IllegalStateException("The desktop closed the connection")
        val message = RemoteCodec.decode(frame)
        if (message !is T) throw IllegalStateException("Expected ${T::class.simpleName}, got ${message::class.simpleName}")
        return message
    }

    private fun fail(onState: (DesktopSessionState) -> Unit, reason: String): Result<Nothing> {
        running.set(false)
        runCatching { socket?.close() }
        onState(DesktopSessionState.Failed(reason).also { state = it })
        return Result.failure(IllegalStateException(reason))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** A phone-side ceiling, so a mistaken selection cannot exhaust memory or the desktop disk. */
        const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024
    }
}
