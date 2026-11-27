package com.opendroid.ai.desktop

import com.opendroid.ai.remote.protocol.*
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Configuration for one agent instance. */
data class AgentConfig(
    val serverName: String,
    val port: Int,
    /** The only directory the file-transfer commands may touch. */
    val fileRoot: File,
    val allowFileTransfer: Boolean = true,
    val allowInput: Boolean = true,
    val maxClients: Int = 4,
    /**
     * The desktop's WireGuard public key, advertised to paired phones so they can build the
     * full-tunnel gateway. Null when the owner has turned the gateway off.
     */
    val gatewayPublicKey: String? = null,
    val gatewayPort: Int? = null,
)

/** The result of handling one connection, for the log line. */
data class SessionOutcome(val deviceId: String?, val reason: String)

/**
 * Serves one connected client.
 *
 * The handshake is strict and ordered: version is checked, then the client is either paired (with a
 * live one-time code) or authenticated (with an HMAC proof over a fresh challenge). Nothing else is
 * accepted before that succeeds, so an unauthenticated peer cannot reach screen or input.
 */
class DesktopSession(
    private val config: AgentConfig,
    private val store: ClientStore,
    private val controller: DesktopControllerContract,
    private val pairing: PairingController,
    private val log: (String) -> Unit,
) {

    private val frameStreaming = AtomicBoolean(false)
    private val heartbeatSequence = AtomicLong(0)
    private var out: OutputStream? = null
    private var pairedClient: PairedClient? = null
    private var streamingThread: Thread? = null

    /**
     * The nonce sent in the hello acknowledgement. Pairing derives from this exact value, so the
     * server and the client must use the same one; generating a second nonce here would make the
     * two sides derive different secrets.
     */
    private var handshakeNonce: String = ""

    fun handle(input: java.io.InputStream, output: OutputStream): SessionOutcome {
        this.out = output
        try {
            val first = FrameCodec.read(input) ?: return SessionOutcome(null, "client disconnected before hello")
            val hello = RemoteCodec.decode(first)
            if (hello !is Hello) return SessionOutcome(null, "expected hello, got ${hello::class.simpleName}")
            if (hello.protocolVersion != RemoteProtocol.VERSION) {
                send(ProtocolError("version_mismatch", "Agent speaks protocol ${RemoteProtocol.VERSION}"))
                return SessionOutcome(null, "protocol version mismatch (client ${hello.protocolVersion})")
            }

            val known = store.find(hello.deviceId)
            handshakeNonce = RemoteCrypto.newNonce()
            send(
                HelloAck(
                    RemoteProtocol.VERSION,
                    store.serverId,
                    config.serverName,
                    handshakeNonce,
                    known != null,
                    gatewayPublicKey = config.gatewayPublicKey,
                    gatewayPort = config.gatewayPort,
                )
            )

            val authenticated = if (known != null) authenticate(input, known, hello)
            else pair(input, hello)
            val client = authenticated ?: return SessionOutcome(hello.deviceId, "authentication failed")
            pairedClient = client
            log("session authenticated for ${client.deviceName} (${client.deviceId})")
            val screenSize = controller.screenSize()
            send(ScreenInfo(screenSize.width, screenSize.height, controller.monitorCount()))

            return messageLoop(input)
        } catch (e: ProtocolException) {
            log("protocol error: ${e.message}")
            runCatching { send(ProtocolError("protocol_error", e.message ?: "protocol error")) }
            return SessionOutcome(null, e.message ?: "protocol error")
        } catch (e: Exception) {
            return SessionOutcome(null, e.message ?: e::class.simpleName ?: "error")
        } finally {
            stopStreaming()
        }
    }

    /** Pair with a live code. The derived secret is bound to both handshake nonces. */
    private fun pair(input: java.io.InputStream, hello: Hello): PairedClient? {
        val frame = FrameCodec.read(input) ?: return null
        val message = RemoteCodec.decode(frame)
        if (message !is PairRequest) {
            send(PairResult(false, "expected a pairing request"))
            return null
        }
        if (!pairing.isOpen()) {
            send(PairResult(false, "the pairing window is closed; start pairing on the desktop again"))
            return null
        }
        if (!RemoteCrypto.constantTimeEquals(message.code, pairing.code)) {
            send(PairResult(false, "the pairing code is not correct"))
            return null
        }
        val serverNonce = handshakeNonce
        // The client's nonce comes from its hello; the server nonce is the one it already sent in
        // the acknowledgement, so both sides derive the same secret.
        val secret = RemoteCrypto.deriveSecret(message.code, hello.nonce, serverNonce)
        val client = PairedClient(
            deviceId = hello.deviceId,
            deviceName = hello.deviceName,
            secret = secret,
            pairedAtMs = System.currentTimeMillis(),
        )
        store.save(client)
        send(PairResult(true))
        log("paired new client ${hello.deviceName} (${hello.deviceId})")
        return client
    }

    /** Prove possession of the shared secret over a fresh server challenge. */
    private fun authenticate(input: java.io.InputStream, known: PairedClient, hello: Hello): PairedClient? {
        if (known.revoked) {
            send(AuthResult(false, "this device was revoked; pair again"))
            return null
        }
        val challenge = RemoteCrypto.newNonce()
        send(AuthChallenge(challenge))
        val frame = FrameCodec.read(input) ?: return null
        val message = RemoteCodec.decode(frame)
        if (message !is AuthResponse) {
            send(AuthResult(false, "expected an authentication response"))
            return null
        }
        if (!RemoteCrypto.verifyProof(known.secret, known.deviceId, challenge, message.proof)) {
            send(AuthResult(false, "the authentication proof is not valid"))
            return null
        }
        send(AuthResult(true))
        return known
    }

    private fun messageLoop(input: java.io.InputStream): SessionOutcome {
        while (true) {
            val frame = FrameCodec.read(input) ?: return SessionOutcome(pairedClient?.deviceId, "client disconnected")
            when (frame.type) {
                RemoteProtocol.FRAME_FILE_CHUNK -> handleFileChunk(FileChunkCodec.decode(frame))
                RemoteProtocol.FRAME_CONTROL -> {
                    val result = handleControl(RemoteCodec.decode(frame))
                    if (result != null) return result
                }
                else -> send(ProtocolError("unexpected_frame", "Frame type ${frame.type} is not valid after authentication"))
            }
        }
    }

    /** Returns a non-null outcome when the loop should end. */
    private fun handleControl(message: RemoteMessage): SessionOutcome? = when (message) {
        is Heartbeat -> {
            send(HeartbeatAck(message.sequence, System.currentTimeMillis()))
            null
        }
        is Disconnect -> SessionOutcome(pairedClient?.deviceId, "client disconnected: ${message.reason}")
        is Revoke -> {
            store.revoke(message.reason)
            SessionOutcome(pairedClient?.deviceId, "client revoked")
        }
        is InputEvent -> {
            applyInput(message)
            null
        }
        is ScreenRequest -> {
            if (message.maxWidth <= 0) stopStreaming() else startStreaming(message)
            null
        }
        is AppListRequest -> {
            send(AppList(controller.listApplications().map { AppEntry(it.id, it.name, it.running) }))
            null
        }
        is AppLaunch -> {
            if (!config.allowInput) {
                send(AppLaunchResult(message.id, false, "application launching is disabled on this agent"))
            } else {
                val outcome = controller.launchApplication(message.id)
                send(AppLaunchResult(message.id, outcome.accepted, outcome.reason))
            }
            null
        }
        is FileListRequest -> {
            respondFileList(message)
            null
        }
        is FileDownloadRequest -> {
            respondFileDownload(message)
            null
        }
        is FileUploadBegin -> {
            beginUpload(message)
            null
        }
        is ClipboardGet -> {
            val text = controller.readClipboard()
            send(ClipboardValue(text.orEmpty(), text != null))
            null
        }
        is ClipboardSet -> {
            if (!config.allowInput) {
                send(ProtocolError("clipboard_disabled", "clipboard sync is disabled on this agent"))
            } else if (message.text.length > ClipboardSet.CLIPBOARD_TEXT_LIMIT) {
                send(ProtocolError("clipboard_too_large", "clipboard text exceeds ${ClipboardSet.CLIPBOARD_TEXT_LIMIT} characters"))
            } else {
                controller.writeClipboard(message.text)
            }
            null
        }
        is ProtocolError -> SessionOutcome(pairedClient?.deviceId, "client error ${message.code}")
        else -> {
            send(ProtocolError("unexpected_message", "Message ${message::class.simpleName} is not valid after authentication"))
            null
        }
    }

    private fun applyInput(event: InputEvent) {
        if (!config.allowInput) return
        if (event.kind !in InputEvent.KINDS) {
            send(ProtocolError("bad_input", "Unknown input kind '${event.kind}'"))
            return
        }
        runCatching {
            when (event.kind) {
                InputEvent.MOUSE_MOVE -> controller.mouseMove(event.x, event.y, event.monitor, event.screenWidth, event.screenHeight)
                InputEvent.MOUSE_DOWN -> controller.mouseDown(event.button)
                InputEvent.MOUSE_UP -> controller.mouseUp(event.button)
                InputEvent.MOUSE_WHEEL -> controller.mouseWheel(event.wheel)
                InputEvent.KEY_DOWN -> controller.keyDown(event.keyCode)
                InputEvent.KEY_UP -> controller.keyUp(event.keyCode)
                InputEvent.TEXT -> controller.typeText(event.text)
            }
        }.onFailure { send(ProtocolError("input_failed", it.message ?: "input failed")) }
    }

    private fun startStreaming(request: ScreenRequest) {
        stopStreaming()
        frameStreaming.set(true)
        val thread = Thread({
            log("screen streaming started (maxWidth=${request.maxWidth}, quality=${request.quality})")
            while (frameStreaming.get()) {
                val started = System.currentTimeMillis()
                try {
                    val captured = controller.captureScreen(request.maxWidth, request.quality, request.monitor)
                    send(
                        ScreenFrameCodec.encode(
                            ScreenFrameCodec.ScreenFrame(
                                captured.width, captured.height, captured.format, captured.capturedAtMs, captured.data,
                            )
                        )
                    )
                } catch (e: Exception) {
                    log("screen capture stopped: ${e.message}")
                    frameStreaming.set(false)
                    break
                }
                // Target about 10 frames a second; capture time is subtracted so a slow machine
                // degrades the rate instead of falling behind.
                val elapsed = System.currentTimeMillis() - started
                val sleep = (100 - elapsed).coerceAtLeast(0)
                if (sleep > 0) runCatching { Thread.sleep(sleep) }
            }
            log("screen streaming stopped")
        }, "screen-stream")
        thread.isDaemon = true
        streamingThread = thread
        thread.start()
    }

    private fun stopStreaming() {
        frameStreaming.set(false)
        streamingThread?.interrupt()
        streamingThread = null
    }

    // ---- Files ----

    /** Resolves a client path inside the configured root, refusing any traversal outside it. */
    private fun resolveWithinRoot(requested: String): Path? {
        if (!config.allowFileTransfer) return null
        val root = config.fileRoot.canonicalFile.toPath()
        val candidate = File(config.fileRoot, requested.trimStart('/', '\\')).canonicalFile.toPath()
        return if (candidate.startsWith(root)) candidate else null
    }

    private fun respondFileList(request: FileListRequest) {
        val path = resolveWithinRoot(request.path)
            ?: return send(FileList(request.path, emptyList()))
        val listing = path.toFile().listFiles()?.sortedBy { it.name }?.take(2_000)?.map {
            FileEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L)
        } ?: emptyList()
        send(FileList(request.path, listing))
    }

    private fun respondFileDownload(request: FileDownloadRequest) {
        val path = resolveWithinRoot(request.path) ?: return send(
            FileTransferEnd(request.path, false, "the path is outside the shared folder")
        )
        val file = path.toFile()
        if (!file.isFile) return send(FileTransferEnd(request.path, false, "not a file"))
        val transferId = RemoteCrypto.newNonce(8)
        send(FileDownloadBegin(request.path, file.length(), transferId))
        try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(256 * 1024)
                var sequence = 0
                var read = stream.read(buffer)
                var previous = ByteArray(0)
                while (read >= 0) {
                    val chunk = buffer.copyOf(read)
                    // Peek is not possible on a stream; send the previous chunk once the next read
                    // succeeds, so the final chunk is the one marked `last`.
                    if (previous.isNotEmpty()) {
                        send(FileChunkCodec.encode(FileChunkCodec.FileChunk(transferId, sequence++, false, previous)))
                    }
                    previous = chunk
                    read = stream.read(buffer)
                }
                send(FileChunkCodec.encode(FileChunkCodec.FileChunk(transferId, sequence, true, previous)))
            }
            log("sent ${file.length()} bytes for ${request.path}")
        } catch (e: Exception) {
            send(FileTransferEnd(transferId, false, e.message ?: "download failed"))
        }
    }

    private val uploads = HashMap<String, java.io.OutputStream>()

    private fun beginUpload(begin: FileUploadBegin) {
        val path = resolveWithinRoot(begin.path) ?: return send(
            FileTransferEnd(begin.transferId, false, "the path is outside the shared folder")
        )
        try {
            path.toFile().parentFile?.mkdirs()
            uploads[begin.transferId] = Files.newOutputStream(path)
        } catch (e: Exception) {
            send(FileTransferEnd(begin.transferId, false, e.message ?: "cannot open the destination"))
        }
    }

    private fun handleFileChunk(chunk: FileChunkCodec.FileChunk) {
        val stream = uploads[chunk.transferId] ?: return
        try {
            stream.write(chunk.data)
            if (chunk.last) {
                stream.close()
                uploads.remove(chunk.transferId)
                send(FileTransferEnd(chunk.transferId, true))
                log("received upload ${chunk.transferId}")
            }
        } catch (e: Exception) {
            runCatching { stream.close() }
            uploads.remove(chunk.transferId)
            send(FileTransferEnd(chunk.transferId, false, e.message ?: "write failed"))
        }
    }

    private fun send(message: RemoteMessage) {
        synchronized(this) { out?.let { FrameCodec.write(it, RemoteCodec.encode(message)) } }
    }

    private fun send(frame: Frame) {
        synchronized(this) { out?.let { FrameCodec.write(it, frame) } }
    }
}

/** Holds the current one-time pairing code and its window. */
class PairingController(private val ttlMs: Long = 5 * 60 * 1000L) {
    @Volatile private var window: PairingWindow? = null

    fun open(nowMs: Long = System.currentTimeMillis()): String {
        val code = RemoteCrypto.newPairingCode()
        window = PairingWindow(code, nowMs, ttlMs)
        return code
    }

    val code: String get() = window?.code ?: ""

    fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean = window?.isOpen(nowMs) == true

    fun close() { window = null }
}

/** A client store persisted as JSON on the agent machine. */
class ClientStore(private val file: File) {
    val serverId: String = System.getProperty("opendroid.agent.id") ?: java.util.UUID.randomUUID().toString()

    private data class Record(
        val deviceId: String,
        val deviceName: String,
        val secret: String,
        val pairedAtMs: Long,
        val revoked: Boolean,
    )

    private val clients = LinkedHashMap<String, Record>()

    init { load() }

    @Synchronized
    fun find(deviceId: String): PairedClient? = clients[deviceId]?.let {
        PairedClient(it.deviceId, it.deviceName, Base64.getDecoder().decode(it.secret), it.pairedAtMs, it.revoked)
    }

    @Synchronized
    fun save(client: PairedClient) {
        clients[client.deviceId] = Record(
            client.deviceId, client.deviceName, Base64.getEncoder().encodeToString(client.secret),
            client.pairedAtMs, client.revoked,
        )
        persist()
    }

    @Synchronized
    fun revoke(reason: String) {
        val changed = clients.values.map { it.copy(revoked = true) }
        clients.clear()
        changed.forEach { clients[it.deviceId] = it }
        persist()
    }

    @Synchronized
    fun revokeDevice(deviceId: String): Boolean {
        val record = clients[deviceId] ?: return false
        clients[deviceId] = record.copy(revoked = true)
        persist()
        return true
    }

    @Synchronized
    fun all(): List<PairedClient> = clients.values.map {
        PairedClient(it.deviceId, it.deviceName, ByteArray(0), it.pairedAtMs, it.revoked)
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val text = file.readText()
            val array = com.google.gson.JsonParser.parseString(text).asJsonArray
            for (element in array) {
                val obj = element.asJsonObject
                val record = Record(
                    obj.get("deviceId").asString,
                    obj.get("deviceName").asString,
                    obj.get("secret").asString,
                    obj.get("pairedAtMs").asLong,
                    obj.get("revoked")?.asBoolean ?: false,
                )
                clients[record.deviceId] = record
            }
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val array = com.google.gson.JsonArray()
        for (record in clients.values) {
            val obj = com.google.gson.JsonObject()
            obj.addProperty("deviceId", record.deviceId)
            obj.addProperty("deviceName", record.deviceName)
            obj.addProperty("secret", record.secret)
            obj.addProperty("pairedAtMs", record.pairedAtMs)
            obj.addProperty("revoked", record.revoked)
            array.add(obj)
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString())
        restrictToOwner(temp)
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(file.toPath(), perms)
        }
    }
}
