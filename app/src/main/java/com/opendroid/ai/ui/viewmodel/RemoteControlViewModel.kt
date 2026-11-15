package com.opendroid.ai.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import com.opendroid.ai.core.remote.RemoteControlCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs the Remote Control screen. It holds only presentation state and forwards every
 * decision to [RemoteControlCoordinator]; no cryptography or key handling happens here.
 */
@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val coordinator: RemoteControlCoordinator,
) : ViewModel() {

    /** What the screen shows. [pairingCode] is non-null only while a code is live. */
    data class UiState(
        val pairingCode: String? = null,
        val pairingDeviceId: String? = null,
        val pairingExpiresAtMs: Long = 0L,
        val pairedDevices: List<String> = emptyList(),
        val killSwitchActive: Boolean = false,
        val message: String? = null,
        val messageIsError: Boolean = false,
    )

    private val mutableState = MutableStateFlow(
        UiState(
            pairedDevices = coordinator.pairedKeyIds(),
            killSwitchActive = coordinator.isKillSwitchActive(),
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

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
