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

    @Volatile
    var state: DesktopSessionState = DesktopSessionState.Idle
        private set

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
            val sock = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
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
        store.save(PairedDesktop(host, port, deviceId, ack.serverName, secret))
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
                    onMessage(message)
                }
                RemoteProtocol.FRAME_FILE_CHUNK -> onMessage(
                    ProtocolError("file_chunk", "File transfer is handled by the transfer helper")
                )
            }
        }
        running.set(false)
        val reason = "The desktop closed the connection"
        onState(DesktopSessionState.Closed(reason).also { state = it })
    }

    // ---- Commands ----

    fun requestScreen(maxWidth: Int, quality: Int) = write(ScreenRequest(maxWidth, quality))

    fun stopScreen() = write(ScreenRequest(0, 0))

    fun sendInput(event: InputEvent) = write(event)

    fun listApplications() = write(AppListRequest())

    fun launchApplication(id: String) = write(AppLaunch(id))

    fun listFiles(path: String) = write(FileListRequest(path))

    fun downloadFile(path: String) = write(FileDownloadRequest(path))

    fun uploadFile(path: String, size: Long, transferId: String) = write(FileUploadBegin(path, size, transferId))

    fun sendHeartbeat() = write(Heartbeat(clock(), clock()))

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
    }
}
