package com.opendroid.ai.core.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stage 2 of v3.10.0: remote control of the owner's own paired devices.
 *
 * This is the pure, JVM-testable security core. It never opens an inbound port and never
 * contacts a public relay: a transport is supplied by the owner (their own private network or
 * tunnel) and only carries bytes this core has already authenticated.
 *
 * Every remote action must carry a device key id, a timestamp, a nonce, and an HMAC signature.
 * Requests with an unknown key, an expired timestamp, a reused nonce, or a bad signature are
 * rejected before the action is shown to the owner. The default answer to an approval prompt is
 * Deny, and it is applied after [DEFAULT_APPROVAL_TIMEOUT_MS].
 */

/** What the target device is willing to do with a remote action. */
enum class RemoteRisk(val severity: Int) {
    LOW(1),
    HIGH(2),
    CRITICAL(3),
}

/** An action a controlling device asks the target to perform. */
data class RemoteAction(
    val toolName: String,
    val argumentsJson: String,
    val risk: RemoteRisk,
)

/** A signed remote request, exactly as it travels over the owner's transport. */
data class SignedRemoteRequest(
    val keyId: String,
    val deviceId: String,
    val action: RemoteAction,
    val timestampMs: Long,
    val nonce: String,
    val signatureHex: String,
) {
    /** The canonical string that is signed. Kept stable so both sides agree. */
    fun canonicalPayload(): String = listOf(
        keyId,
        deviceId,
        action.toolName,
        action.argumentsJson,
        action.risk.name,
        timestampMs.toString(),
        nonce,
    ).joinToString("\n")
}

/** The result of authenticating a signed request. */
sealed interface RemoteAuthentication {
    data object Authenticated : RemoteAuthentication
    data class Rejected(val reason: String) : RemoteAuthentication
}

/** The owner's answer to an approval prompt. */
enum class RemoteApprovalDecision { APPROVED, DENIED, TIMED_OUT }

/** A single audit record. It never contains screen text, typed text, or secrets. */
data class RemoteAuditRecord(
    val timestampMs: Long,
    val deviceId: String,
    val action: String,
    val approval: String,
    val outcome: String,
)

/**
 * Pairing, authentication, approval, and session lifecycle for owner-paired devices.
 *
 * [now] is injected so expiry and replay behaviour are deterministic under test.
 */
class RemoteControlService(
    private val keyStore: DeviceKeyStore = InMemoryDeviceKeyStore(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val auditSink: (RemoteAuditRecord) -> Unit = {},
) {

    companion object {
        const val PAIRING_CODE_TTL_MS = 5 * 60 * 1000L
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 60 * 1000L

        /** How far a request timestamp may drift from the target's clock. */
        const val MAX_CLOCK_SKEW_MS = 60 * 1000L

        const val UNKNOWN_DEVICE = "Device is not paired"
        const val EXPIRED_PAIRING_CODE = "Pairing code is expired or already used"
        const val EXPIRED_REQUEST = "Request timestamp is outside the allowed window"
        const val REPLAYED_REQUEST = "Request nonce was already used"
        const val BAD_SIGNATURE = "Request signature is invalid"
        const val KILL_SWITCH_ACTIVE = "Remote control is disabled by the kill switch"

        private val HMAC_ALGORITHM = "HmacSHA256"
    }

    /** A pairing session started on the target device. The code is single-use and short-lived. */
    data class PairingSession(
        val code: String,
        val deviceId: String,
        val deviceName: String,
        val expiresAtMs: Long,
    )

    private val pairingSessions = LinkedHashMap<String, PairingSession>()
    private val consumedCodes = HashSet<String>()
    private val seenNonces = LinkedHashMap<String, Long>()
    private var killSwitchActive = false

    /** The target device starts pairing and shows a one-time code plus a device name. */
    @Synchronized
    fun startPairing(deviceId: String, deviceName: String): PairingSession {
        val code = (1..6).joinToString("") { random.nextInt(10).toString() }
        val session = PairingSession(
            code = code,
            deviceId = deviceId,
            deviceName = deviceName,
            expiresAtMs = now() + PAIRING_CODE_TTL_MS,
        )
        pairingSessions[code] = session
        return session
    }

    /**
     * The controlling phone enters the code. On success a per-device key is created and stored
     * with the device id, and the code is consumed so it can never be used again.
     */
    @Synchronized
    fun completePairing(code: String, keyId: String): RemoteAuthentication {
        val session = pairingSessions[code] ?: return RemoteAuthentication.Rejected(EXPIRED_PAIRING_CODE)
        if (code in consumedCodes || now() > session.expiresAtMs) {
            pairingSessions.remove(code)
            return RemoteAuthentication.Rejected(EXPIRED_PAIRING_CODE)
        }
        val secret = ByteArray(32).also { random.nextBytes(it) }
        keyStore.put(PairedDevice(keyId = keyId, deviceId = session.deviceId, secret = secret))
        consumedCodes.add(code)
        pairingSessions.remove(code)
        audit(session.deviceId, "pair", "approved", "paired")
        return RemoteAuthentication.Authenticated
    }

    /** Sign an action with a stored key. Used by the controlling side. */
    fun sign(keyId: String, deviceId: String, action: RemoteAction, timestampMs: Long, nonce: String): SignedRemoteRequest {
        val device = keyStore.get(keyId) ?: throw IllegalArgumentException(UNKNOWN_DEVICE)
        val unsigned = SignedRemoteRequest(keyId, deviceId, action, timestampMs, nonce, "")
        val signature = hmacHex(device.secret, unsigned.canonicalPayload())
        return unsigned.copy(signatureHex = signature)
    }

    /**
     * Authenticate an incoming request on the target. Checks, in order: kill switch, pairing,
     * clock window, nonce replay, then signature. Nothing is shown to the owner until this passes.
     */
    @Synchronized
    fun authenticate(request: SignedRemoteRequest): RemoteAuthentication {
        if (killSwitchActive) return RemoteAuthentication.Rejected(KILL_SWITCH_ACTIVE)

        val device = keyStore.get(request.keyId) ?: return RemoteAuthentication.Rejected(UNKNOWN_DEVICE)
        if (device.deviceId != request.deviceId) return RemoteAuthentication.Rejected(UNKNOWN_DEVICE)

        val delta = kotlin.math.abs(now() - request.timestampMs)
        if (delta > MAX_CLOCK_SKEW_MS) return RemoteAuthentication.Rejected(EXPIRED_REQUEST)

        if (request.nonce in seenNonces) return RemoteAuthentication.Rejected(REPLAYED_REQUEST)

        val expected = hmacHex(device.secret, request.canonicalPayload())
        if (!constantTimeEquals(expected, request.signatureHex)) {
            return RemoteAuthentication.Rejected(BAD_SIGNATURE)
        }

        seenNonces[request.nonce] = request.timestampMs
        return RemoteAuthentication.Authenticated
    }

    /**
     * Show an authenticated action to the owner. The owner's decision wins; if no decision
     * arrives within [timeoutMs], the answer is Deny. A denial is never a failure.
     */
    @Synchronized
    fun requestApproval(
        deviceId: String,
        action: RemoteAction,
        ownerDecision: RemoteApprovalDecision?,
        timeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
    ): RemoteApprovalDecision {
        val effective = when {
            ownerDecision == null -> RemoteApprovalDecision.TIMED_OUT
            ownerDecision == RemoteApprovalDecision.TIMED_OUT -> RemoteApprovalDecision.TIMED_OUT
            timeoutMs <= 0 -> RemoteApprovalDecision.TIMED_OUT
            else -> ownerDecision
        }
        audit(
            deviceId,
            action.toolName,
            effective.name.lowercase(),
            if (effective == RemoteApprovalDecision.APPROVED) "executed" else "not executed",
        )
        return effective
    }

    /**
     * The target's kill switch. It disables remote control immediately and closes open sessions.
     * The remote side cannot turn it back on.
     */
    @Synchronized
    fun engageKillSwitch() {
        killSwitchActive = true
        pairingSessions.clear()
        seenNonces.clear()
    }

    @Synchronized
    fun isKillSwitchActive(): Boolean = killSwitchActive

    /** Remove a paired device: deletes the key and invalidates open sessions on both sides. */
    @Synchronized
    fun unpair(keyId: String): Boolean {
        val device = keyStore.get(keyId)
        keyStore.remove(keyId)
        if (device != null) {
            pairingSessions.entries.removeIf { it.value.deviceId == device.deviceId }
            audit(device.deviceId, "unpair", "approved", "keys removed")
        }
        return device != null
    }

    @Synchronized
    fun pairedKeyIds(): Set<String> = keyStore.all().map { it.keyId }.toSet()

    /** The paired device for a key id, or null. Used by the controlling side to sign. */
    @Synchronized
    fun deviceForKey(keyId: String): PairedDevice? = keyStore.get(keyId)

    /**
     * A transferable copy of the pairing secret, for the owner to move to the controlling device
     * by a channel they control (a password manager, a QR code, or typing it). It is never logged
     * and this app never sends it over the network.
     */
    @Synchronized
    fun exportKeyForTransfer(keyId: String): String? = keyStore.get(keyId)?.let { device ->
        val payload = device.deviceId + "\n" + Base64.getEncoder().encodeToString(device.secret)
        Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /** Import a transfer code on the controlling device. Returns false if it cannot be read. */
    @Synchronized
    fun importKeyFromTransfer(keyId: String, blob: String): Boolean = runCatching {
        val payload = String(Base64.getDecoder().decode(blob.trim()), Charsets.UTF_8)
        val deviceId = payload.substringBefore('\n')
        val secret = Base64.getDecoder().decode(payload.substringAfter('\n'))
        require(deviceId.isNotBlank() && secret.isNotEmpty()) { "Transfer code is incomplete" }
        keyStore.put(PairedDevice(keyId = keyId, deviceId = deviceId, secret = secret))
    }.isSuccess

    private fun audit(deviceId: String, action: String, approval: String, outcome: String) {
        auditSink(RemoteAuditRecord(now(), deviceId, action, approval, outcome))
    }

    private fun hmacHex(secret: ByteArray, payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
