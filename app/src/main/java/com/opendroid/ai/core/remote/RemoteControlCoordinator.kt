package com.opendroid.ai.core.remote

import java.util.UUID

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
    private val sessions: RemoteSessionManager = RemoteSessionManager(),
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
        sessions.beginPairing(deviceId, clock())
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
            RemoteAuthentication.Authenticated -> {
                // The device is authenticated now, so the session clock starts here - not when the
                // pairing code was shown. A five-minute code never bounds the session.
                val deviceId = service.deviceForKey(keyId)?.deviceId
                if (deviceId != null) sessions.markConnected(deviceId, clock())
                StepResult.Success("Device paired. It can now send actions for your approval.")
            }
            is RemoteAuthentication.Rejected -> StepResult.Rejected(result.reason)
        }
    }

    // ---- Session lifecycle ----

    fun sessionFor(deviceId: String): RemoteSession? = sessions.session(deviceId)

    fun sessions(): List<RemoteSession> = sessions.all()

    /** Evaluate session policy; returns any sessions that just ended, with the reason. */
    fun tickSessions(): List<RemoteSession> = sessions.tick(clock())

    /** Count a signed, authenticated action as activity and as a heartbeat. */
    fun noteSessionActivity(deviceId: String) = sessions.recordActivity(deviceId, clock())

    fun noteHeartbeat(deviceId: String) = sessions.recordHeartbeat(deviceId, clock())

    fun disconnectSession(deviceId: String): StepResult {
        val ended = sessions.disconnect(deviceId, clock())
            ?: return StepResult.Rejected("There is no session for that device.")
        return StepResult.Success(ended.endReason.message)
    }

    fun idleSecondsRemaining(deviceId: String): Long = sessions.idleSecondsRemaining(deviceId, clock())

    /** Remove a paired device and delete its key. Any live session for it ends as revoked. */
    fun unpair(keyId: String): StepResult {
        val device = service.deviceForKey(keyId)
        return if (service.unpair(keyId)) {
            device?.let { sessions.revoke(it.deviceId) }
            StepResult.Success("Device unpaired and its key was deleted.")
        } else {
            StepResult.Rejected("That device is not paired.")
        }
    }

    /** The kill switch disables remote control immediately and ends every open session. */
    fun engageKillSwitch(): StepResult {
        service.engageKillSwitch()
        sessions.onKillSwitch()
        return StepResult.Success("Kill switch engaged. Remote control is disabled and open sessions are closed.")
    }

    fun isKillSwitchActive(): Boolean = service.isKillSwitchActive()

    fun pairedKeyIds(): List<String> = service.pairedKeyIds().sorted()

    /**
     * Sign an action for a paired device. The timestamp and nonce are fresh for every call, so a
     * request is never reused.
     */
    fun sign(keyId: String, toolName: String, argumentsJson: String, risk: RemoteRisk): SignedRemoteRequest {
        val device = service.deviceForKey(keyId)
            ?: return throw IllegalStateException("That device is not paired on this phone.")
        val timestamp = clock()
        val nonce = UUID.randomUUID().toString()
        return service.sign(keyId, device.deviceId, RemoteAction(toolName, argumentsJson, risk), timestamp, nonce)
    }

    /** The owner-readable transfer code for a paired device, or null if it is not paired. */
    fun exportTransferCode(keyId: String): String? = service.exportKeyForTransfer(keyId)

    /** Import a transfer code from the other device so this phone can act as the controller. */
    fun importTransferCode(keyId: String, blob: String): StepResult =
        if (service.importKeyFromTransfer(keyId, blob)) StepResult.Success("Device key imported. You can now send actions to that device.")
        else StepResult.Rejected("That transfer code could not be read. Copy it again exactly.")

    /** Whole seconds remaining on a pairing code, floored at zero. */
    fun secondsRemaining(expiresAtMs: Long): Long = ((expiresAtMs - clock()).coerceAtLeast(0L)) / 1000L

    companion object {
        const val PAIRING_CODE_LENGTH = 6
    }
}
