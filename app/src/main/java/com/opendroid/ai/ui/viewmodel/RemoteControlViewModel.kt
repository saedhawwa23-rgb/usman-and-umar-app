package com.opendroid.ai.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.remote.LanRemoteClient
import com.opendroid.ai.core.remote.RemoteApprovalGate
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import com.opendroid.ai.core.remote.RemoteRisk
import com.opendroid.ai.core.remote.RemoteSession
import com.opendroid.ai.core.remote.RemoteTransportController
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
    )

    private val mutableState = MutableStateFlow(
        UiState(
            pairedDevices = coordinator.pairedKeyIds(),
            sessions = coordinator.sessions(),
            killSwitchActive = coordinator.isKillSwitchActive(),
            listening = transport.state.value.running,
            listenPort = transport.state.value.port,
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

    private fun setMessage(text: String, isError: Boolean) {
        mutableState.value = mutableState.value.copy(message = text, messageIsError = isError)
    }
}
