package com.opendroid.ai.core.remote

/**
 * Drives a remote-control pairing session on the target device.
 *
 * The security core ([RemoteControlService]) decides pairing, authentication, replay and
 * approval; this coordinator only sequences those decisions for the UI and turns each
 * rejection into a plain message. It deliberately holds no key material and performs no
 * cryptography itself.
 */
class RemoteControlCoordinator(
    private val service: RemoteControlService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A pairing session the owner can read out to the controlling device. */
    data class PairingStart(
        val code: String,
        val deviceId: String,
        val deviceName: String,
        val expiresAtMs: Long,
    )

    sealed interface StepResult {
        data class Success(val message: String) : StepResult
        data class Rejected(val reason: String) : StepResult
    }

    /** Start pairing on this device. The code is short-lived and single-use. */
    fun startPairing(deviceId: String, deviceName: String): PairingStart {
        val session = service.startPairing(deviceId = deviceId, deviceName = deviceName)
        return PairingStart(
            code = session.code,
            deviceId = session.deviceId,
            deviceName = session.deviceName,
            expiresAtMs = session.expiresAtMs,
        )
    }

    /** Complete pairing with the code the owner entered on the controlling device. */
    fun completePairing(code: String, keyId: String): StepResult {
        val trimmed = code.trim()
        if (trimmed.length != PAIRING_CODE_LENGTH || trimmed.any { !it.isDigit() }) {
            return StepResult.Rejected("Enter the $PAIRING_CODE_LENGTH-digit pairing code.")
        }
        return when (val result = service.completePairing(trimmed, keyId)) {
            RemoteAuthentication.Authenticated ->
                StepResult.Success("Device paired. It can now send actions for your approval.")
            is RemoteAuthentication.Rejected -> StepResult.Rejected(result.reason)
        }
    }

    /** Remove a paired device and delete its key. */
    fun unpair(keyId: String): StepResult =
        if (service.unpair(keyId)) StepResult.Success("Device unpaired and its key was deleted.")
        else StepResult.Rejected("That device is not paired.")

    /** The kill switch disables remote control immediately and clears open sessions. */
    fun engageKillSwitch(): StepResult {
        service.engageKillSwitch()
        return StepResult.Success("Kill switch engaged. Remote control is disabled and open sessions are closed.")
    }

    fun isKillSwitchActive(): Boolean = service.isKillSwitchActive()

    fun pairedKeyIds(): List<String> = service.pairedKeyIds().sorted()

    /** Whole seconds remaining on a pairing code, floored at zero. */
    fun secondsRemaining(expiresAtMs: Long): Long = ((expiresAtMs - clock()).coerceAtLeast(0L)) / 1000L

    companion object {
        const val PAIRING_CODE_LENGTH = 6
    }
}
