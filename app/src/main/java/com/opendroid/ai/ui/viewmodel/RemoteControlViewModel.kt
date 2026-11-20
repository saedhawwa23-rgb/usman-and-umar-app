package com.opendroid.ai.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.remote.DesktopSessionState
import com.opendroid.ai.core.remote.LanRemoteClient
import com.opendroid.ai.core.remote.PairedDesktop
import com.opendroid.ai.core.remote.PairedDesktopStore
import com.opendroid.ai.core.remote.RemoteApprovalGate
import com.opendroid.ai.core.remote.RemoteDesktopClient
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import com.opendroid.ai.core.remote.RemoteRisk
import com.opendroid.ai.core.remote.RemoteSession
import com.opendroid.ai.core.remote.RemoteTransportController
import com.opendroid.ai.remote.protocol.AppEntry
import com.opendroid.ai.remote.protocol.AppLaunchResult
import com.opendroid.ai.remote.protocol.AppList
import com.opendroid.ai.remote.protocol.FileEntry
import com.opendroid.ai.remote.protocol.FileList
import com.opendroid.ai.remote.protocol.InputEvent
import com.opendroid.ai.remote.protocol.ProtocolError
import com.opendroid.ai.remote.protocol.RemoteMessage
import com.opendroid.ai.remote.protocol.ScreenFrameCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Remote Control screen. It holds only presentation state and forwards every
 * decision to [RemoteControlCoordinator]; no cryptography or key handling happens here.
 */
@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val coordinator: RemoteControlCoordinator,
    private val transport: RemoteTransportController,
    private val approvalGate: RemoteApprovalGate,
    private val client: LanRemoteClient,
    private val desktopClient: RemoteDesktopClient,
    private val desktopStore: PairedDesktopStore,
) : ViewModel() {

    /** What the screen shows. [pairingCode] is non-null only while a code is live. */
    data class UiState(
        val pairingCode: String? = null,
        val pairingDeviceId: String? = null,
        val pairingExpiresAtMs: Long = 0L,
        val pairedDevices: List<String> = emptyList(),
        val sessions: List<RemoteSession> = emptyList(),
        val killSwitchActive: Boolean = false,
        val listening: Boolean = false,
        val listenPort: Int? = null,
        val pendingApproval: RemoteApprovalGate.Pending? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
        // Desktop companion agent
        val pairedDesktops: List<PairedDesktop> = emptyList(),
        val desktopConnected: Boolean = false,
        val desktopName: String? = null,
        val desktopStreaming: Boolean = false,
        val lastScreenFrame: ScreenFrameCodec.ScreenFrame? = null,
        val desktopApplications: List<AppEntry> = emptyList(),
        // Secure file transfer (desktop agent)
        val remotePath: String = ".",
        val remoteEntries: List<FileEntry> = emptyList(),
        // Remote terminal (executed as an authorized remote action on this phone)
        val terminalOutput: String? = null,
    )

    private val mutableState = MutableStateFlow(
        UiState(
            pairedDevices = coordinator.pairedKeyIds(),
            sessions = coordinator.sessions(),
            killSwitchActive = coordinator.isKillSwitchActive(),
            listening = transport.state.value.running,
            listenPort = transport.state.value.port,
            pairedDesktops = desktopStore.load(),
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            transport.state.collect { s ->
                mutableState.value = mutableState.value.copy(listening = s.running, listenPort = s.port)
            }
        }
        viewModelScope.launch {
            approvalGate.current.collect { pending ->
                mutableState.value = mutableState.value.copy(pendingApproval = pending)
            }
        }
        // Evaluate session policy once a second so an inactivity or max-duration end is shown with
        // its reason, and so a reconnecting session is visible while it recovers.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                coordinator.tickSessions()
                mutableState.value = mutableState.value.copy(sessions = coordinator.sessions())
            }
        }
    }

    private val desktopConnected: Boolean get() = mutableState.value.desktopConnected

    private val pairedDesktops: List<PairedDesktop> get() = mutableState.value.pairedDesktops

    /** Turn the LAN listener on so a paired device can reach this phone. */
    fun startListening() {
        if (mutableState.value.killSwitchActive) {
            setMessage("Remote control is disabled by the kill switch.", isError = true)
            return
        }
        val result = transport.start()
        setMessage(result, isError = !transport.state.value.running)
    }

    fun stopListening() {
        transport.stop()
        setMessage("Stopped listening. Paired devices can no longer reach this phone.", isError = false)
    }

    /** The owner's answer to an inbound remote action. */
    fun approvePending() = approvalGate.decide(true)

    fun denyPending() = approvalGate.decide(false)

    /** Show the transfer code for a paired device so the owner can move it to the controller. */
    fun exportTransferCode(keyId: String) {
        val code = coordinator.exportTransferCode(keyId)
        setMessage(code ?: "That device is not paired.", isError = code == null)
    }

    /** Import a transfer code from the other device so this phone can control it. */
    fun importTransferCode(blob: String) {
        when (val result = coordinator.importTransferCode("key-" + UUID.randomUUID().toString().take(8), blob)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    pairedDevices = coordinator.pairedKeyIds(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    /**
     * Send one signed action to a paired device. Requires a paired key and the device's address.
     * The action is signed here; nothing leaves unsigned.
     */
    fun sendAction(host: String, toolName: String, argumentsJson: String) {
        val keyId = mutableState.value.pairedDevices.firstOrNull()
        if (keyId == null) {
            setMessage("Pair a device first; there is no key to sign with.", isError = true)
            return
        }
        viewModelScope.launch {
            val signed = runCatching {
                coordinator.sign(keyId, toolName, argumentsJson, RemoteRisk.HIGH)
            }.getOrElse {
                setMessage("Could not sign the request: ${it.message ?: "unknown error"}", isError = true)
                return@launch
            }
            val reply = client.send(host, request = signed)
            reply.fold(
                onSuccess = { setMessage("${it.toolName}: ${it.detail}", isError = !it.executed) },
                onFailure = { setMessage("Send failed: ${it.message ?: "unknown error"}", isError = true) },
            )
        }
    }

    /** Start a pairing session for this device. The code is shown to the owner to read out. */
    fun startPairing() {
        if (mutableState.value.killSwitchActive) {
            setMessage("Remote control is disabled by the kill switch.", isError = true)
            return
        }
        val start = coordinator.startPairing(
            deviceId = "this-device-" + UUID.randomUUID().toString().take(8),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "This device" },
        )
        mutableState.value = mutableState.value.copy(
            pairingCode = start.code,
            pairingDeviceId = start.deviceId,
            pairingExpiresAtMs = start.expiresAtMs,
            message = "Enter this code on the controlling device.",
            messageIsError = false,
        )
    }

    /** Complete pairing with the code shown on the controlling device. */
    fun completePairing(code: String) {
        when (val result = coordinator.completePairing(code, keyId = "key-" + UUID.randomUUID().toString().take(8))) {
            is RemoteControlCoordinator.StepResult.Success -> {
                mutableState.value = mutableState.value.copy(
                    pairingCode = null,
                    pairingDeviceId = null,
                    pairingExpiresAtMs = 0L,
                    pairedDevices = coordinator.pairedKeyIds(),
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            }
            is RemoteControlCoordinator.StepResult.Rejected ->
                setMessage(result.reason, isError = true)
        }
    }

    fun unpair(keyId: String) {
        when (val result = coordinator.unpair(keyId)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    pairedDevices = coordinator.pairedKeyIds(),
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    /** Disconnect a live session immediately; the reason is shown to the owner. */
    fun disconnectSession(deviceId: String) {
        when (val result = coordinator.disconnectSession(deviceId)) {
            is RemoteControlCoordinator.StepResult.Success ->
                mutableState.value = mutableState.value.copy(
                    sessions = coordinator.sessions(),
                    message = result.message,
                    messageIsError = false,
                )
            is RemoteControlCoordinator.StepResult.Rejected -> setMessage(result.reason, isError = true)
        }
    }

    fun engageKillSwitch() {
        val result = coordinator.engageKillSwitch()
        mutableState.value = mutableState.value.copy(
            pairingCode = null,
            pairingDeviceId = null,
            pairingExpiresAtMs = 0L,
            killSwitchActive = true,
            sessions = coordinator.sessions(),
            message = (result as? RemoteControlCoordinator.StepResult.Success)?.message,
            messageIsError = false,
        )
    }

    fun secondsRemaining(): Long =
        mutableState.value.pairingExpiresAtMs.takeIf { it > 0 }?.let(coordinator::secondsRemaining) ?: 0L

    // ---- Desktop companion agent ----

    /**
     * Pair with a desktop agent running on a computer. The code is the one the agent printed when it
     * started; nothing is stored unless the agent accepts it.
     */
    fun pairWithDesktop(host: String, port: Int, code: String) {
        if (desktopConnected) {
            setMessage("Disconnect the current desktop before pairing with another.", isError = true)
            return
        }
        viewModelScope.launch {
            desktopClient.connect(
                host = host.trim(),
                port = port,
                pairingCode = code.trim().uppercase(),
                onState = ::onDesktopState,
                onScreenFrame = { frame -> mutableState.value = mutableState.value.copy(lastScreenFrame = frame) },
                onMessage = ::onDesktopMessage,
            )
        }
    }

    /** Connect to a desktop that has already been paired. */
    fun connectToDesktop(host: String, port: Int) {
        if (desktopConnected) return
        viewModelScope.launch {
            desktopClient.connect(
                host = host.trim(),
                port = port,
                pairingCode = null,
                onState = ::onDesktopState,
                onScreenFrame = { frame -> mutableState.value = mutableState.value.copy(lastScreenFrame = frame) },
                onMessage = ::onDesktopMessage,
            )
        }
    }

    fun disconnectDesktop() {
        desktopClient.disconnect("disconnected from the phone")
        mutableState.value = mutableState.value.copy(
            desktopStreaming = false,
            message = "Disconnected from the desktop.",
            messageIsError = false,
        )
    }

    fun startDesktopStream(width: Int, quality: Int) {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before viewing its screen.", isError = true)
            return
        }
        desktopClient.requestScreen(width, quality)
        mutableState.value = mutableState.value.copy(desktopStreaming = true)
    }

    fun stopDesktopStream() {
        desktopClient.stopScreen()
        mutableState.value = mutableState.value.copy(desktopStreaming = false, lastScreenFrame = null)
    }

    fun sendDesktopInput(event: InputEvent) {
        if (!desktopConnected) return
        desktopClient.sendInput(event)
    }

    fun listDesktopApplications() {
        if (!desktopConnected) {
            setMessage("Connect to a desktop before listing its applications.", isError = true)
            return
        }
        desktopClient.listApplications()
    }

    fun launchDesktopApplication(id: String) {
        if (!desktopConnected) return
        desktopClient.launchApplication(id)
    }

    /** Forget every paired desktop on this phone. The desktops are told as well. */
    fun forgetDesktops() {
        val count = pairedDesktops.size
        desktopClient.revoke()
        mutableState.value = mutableState.value.copy(
            pairedDesktops = emptyList(),
            desktopConnected = false,
            desktopStreaming = false,
            lastScreenFrame = null,
            desktopApplications = emptyList(),
            message = if (count == 0) "No desktops were paired." else "Forgot $count paired desktop(s).",
            messageIsError = false,
        )
    }

    /** Browse a folder on the paired computer. Empty path lists the agent's shared folder root. */
    fun listRemoteFiles(path: String) {
        if (!desktopConnected) {
            setMessage("Connect to the computer first.", isError = true)
            return
        }
        val target = path.trim().ifBlank { "." }
        desktopClient.listFiles(target)
        mutableState.value = mutableState.value.copy(remotePath = target)
    }

    /**
     * Download a file from the paired computer into this phone's app storage. The bytes travel over
     * the already-authenticated desktop session; nothing is uploaded to a third party.
     */
    fun downloadRemoteFile(path: String) {
        if (!desktopConnected) {
            setMessage("Connect to the computer first.", isError = true)
            return
        }
        desktopClient.downloadFile(path)
        setMessage("Requested '$path' from the computer.", isError = false)
    }

    /**
     * Run an authorized remote terminal command on THIS phone, sent from a paired controller.
     * It uses the same signed-action path as every other remote request: the owner is asked to
     * approve, the kill switch and approval gate apply, and nothing runs before approval.
     */
    fun runRemoteTerminal(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            setMessage("Enter a command to send.", isError = true)
            return
        }
        val keyId = mutableState.value.pairedDevices.firstOrNull()
        if (keyId == null) {
            setMessage("Pair a device first; there is no key to sign with.", isError = true)
            return
        }
        viewModelScope.launch {
            val signed = runCatching {
                coordinator.sign(keyId, "shell", """{"command":${jsonString(trimmed)}}""", RemoteRisk.HIGH)
            }.getOrElse {
                setMessage("Could not sign the request: ${it.message ?: "unknown error"}", isError = true)
                return@launch
            }
            val reply = client.send(host = "127.0.0.1", request = signed)
            reply.fold(
                onSuccess = { mutableState.value = mutableState.value.copy(terminalOutput = it.detail) },
                onFailure = { setMessage("Remote terminal failed: ${it.message ?: "unknown error"}", isError = true) },
            )
        }
    }

    private fun jsonString(value: String): String =
        com.google.gson.JsonPrimitive(value).toString()

    private fun onDesktopState(newState: DesktopSessionState) {
        val current = mutableState.value
        mutableState.value = when (newState) {
            is DesktopSessionState.Connected -> current.copy(
                desktopConnected = true,
                desktopName = newState.serverName,
                message = "Connected to ${newState.serverName}.",
                messageIsError = false,
            )
            is DesktopSessionState.Connecting -> current.copy(
                message = "Connecting to the desktop…",
                messageIsError = false,
            )
            is DesktopSessionState.AwaitingPairing -> current.copy(
                message = newState.message,
                messageIsError = false,
            )
            is DesktopSessionState.Failed -> current.copy(
                desktopConnected = false,
                desktopStreaming = false,
                lastScreenFrame = null,
                pairedDesktops = desktopStore.load(),
                message = newState.reason,
                messageIsError = true,
            )
            is DesktopSessionState.Closed -> current.copy(
                desktopConnected = false,
                desktopStreaming = false,
                lastScreenFrame = null,
                message = newState.reason,
                messageIsError = false,
            )
            DesktopSessionState.Idle -> current
        }
    }

    private fun onDesktopMessage(message: RemoteMessage) {
        val current = mutableState.value
        when (message) {
            is AppList -> mutableState.value = current.copy(
                desktopApplications = message.entries,
                message = "${message.entries.size} application(s) on the desktop.",
                messageIsError = false,
            )
            is AppLaunchResult -> mutableState.value = current.copy(
                message = if (message.accepted) "The desktop started the application."
                else "The desktop refused to start it: ${message.reason ?: "no reason given"}",
                messageIsError = !message.accepted,
            )
            is FileList -> mutableState.value = current.copy(
                remotePath = message.path,
                remoteEntries = message.entries,
                message = if (message.entries.isEmpty()) "No files in ${message.path}."
                else "${message.entries.size} item(s) in ${message.path}.",
                messageIsError = false,
            )
            is ProtocolError -> mutableState.value = current.copy(
                message = "The desktop reported ${message.code}: ${message.message}",
                messageIsError = true,
            )
            else -> Unit
        }
    }

    private fun setMessage(text: String, isError: Boolean) {
        mutableState.value = mutableState.value.copy(message = text, messageIsError = isError)
    }
}
