package com.opendroid.ai.core.remote

import android.util.Log
import com.opendroid.ai.core.agent.AgentToolEnvironment
import com.opendroid.ai.core.agent.AgentToolRegistry
import com.opendroid.ai.core.agent.ChatToolCall
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * The owner-provided LAN transport for remote control.
 *
 * It is a single-threaded, line-oriented TCP listener that carries only bytes the security core
 * has already authenticated. It is off by default and starts only when the owner explicitly
 * turns it on, because it opens a listening socket; nothing here is reachable until then.
 *
 * Security order for every connection is fixed and fail-closed:
 * 1. [RemoteControlService.authenticate] - kill switch, pairing, clock window, nonce, signature.
 * 2. [RemoteApprovalGate.requestApproval] - the owner's decision, deny on timeout.
 * 3. [AgentToolRegistry.execute] - only after both pass.
 *
 * The socket never sends a request to anyone and never talks to a public relay; it only answers
 * a paired device on the same network. It is not encrypted at the transport layer, so it must be
 * used only on a trusted private network - the payload is signed, but its contents are readable
 * by anyone who can see the traffic.
 */
class LanRemoteControlServer(
    private val service: RemoteControlService,
    private val approvalGate: RemoteApprovalGate,
    private val environment: AgentToolEnvironment,
    private val registry: AgentToolRegistry = AgentToolRegistry.standard(),
    private val port: Int = DEFAULT_PORT,
    private val auditSink: (String) -> Unit = {},
) {

    companion object {
        const val DEFAULT_PORT = 45871
        private const val TAG = "LanRemoteControl"
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** True while the listener is bound and accepting. */
    val isRunning: Boolean get() = running.get()

    /** Bind and accept. Returns the bound port. Throws if the socket cannot be opened. */
    @Synchronized
    fun start(): Int {
        if (running.get()) return serverSocket?.localPort ?: port
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running.set(true)
        acceptThread = thread(name = "lan-remote-accept", isDaemon = true) { acceptLoop(socket) }
        audit("listening on port ${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        audit("stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (closed: Exception) {
                break
            }
            // One connection at a time, handled inline: a remote action must be approved by the
            // owner before the next one is read, so concurrency would only add ways to race.
            handleClient(client)
        }
    }

    private fun handleClient(client: Socket) {
        client.use { connection ->
            connection.soTimeout = SOCKET_READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(connection.getOutputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            val reply = handleLine(line)
            writer.write(reply.encode())
            writer.write("\n")
            writer.flush()
        }
    }

    /** The whole decision path for one received line. Visible for testing. */
    fun handleLine(line: String): RemoteTransportReply {
        val request = RemoteRequestCodec.decode(line)
            ?: return RemoteTransportReply(false, "unknown", "Rejected: request was not a complete signed action.")

        val action = request.action
        when (val auth = service.authenticate(request)) {
            is RemoteAuthentication.Rejected -> {
                audit("rejected ${action.toolName}: ${auth.reason}")
                return RemoteTransportReply(false, action.toolName, "Rejected: ${auth.reason}")
            }
            RemoteAuthentication.Authenticated -> Unit
        }

        if (registry.find(action.toolName) == null) {
            return RemoteTransportReply(false, action.toolName, "Unknown tool '${action.toolName}'. Nothing was executed.")
        }

        val approved = approvalGate.requestApproval(
            RemoteApprovalGate.Pending(
                deviceId = request.deviceId,
                toolName = action.toolName,
                risk = action.risk,
                summary = "Remote device ${request.deviceId} wants to run ${action.toolName}.",
            )
        )
        if (!approved) {
            audit("denied ${action.toolName}")
            return RemoteTransportReply(false, action.toolName, "Not approved. Nothing was executed.")
        }

        val outcome = runCatching {
            val call = ChatToolCall(action = action.toolName, rawJson = action.argumentsJson)
            runBlocking { registry.execute(call, environment) }
        }.getOrElse {
            return RemoteTransportReply(false, action.toolName, "Execution failed: ${it.message ?: "unknown error"}")
        }
        audit("executed ${action.toolName}")
        return RemoteTransportReply(true, action.toolName, outcome)
    }

    private fun audit(message: String) {
        // Never log arguments, keys, or screen text - only the tool name and the outcome.
        Log.i(TAG, message)
        auditSink(message)
    }
}
