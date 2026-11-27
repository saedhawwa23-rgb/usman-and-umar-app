package com.opendroid.ai.remote.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and session authentication.
 *
 * Pairing is a one-time exchange of a short code that the owner reads off the desktop. From that
 * code both sides derive a long-lived shared secret with HKDF-SHA256, and every later connection is
 * proved with an HMAC over a fresh challenge, so the code itself is never sent again and a replayed
 * handshake cannot authenticate.
 *
 * The code is short, so it must not be usable on its own: [deriveSecret] mixes in both nonces, and
 * the pairing window is time-limited by the caller. A wrong code produces a different secret and
 * therefore a failed proof, with no oracle beyond success or failure.
 */
object RemoteCrypto {

    private const val HKDF_INFO = "opendroid-remote-pairing-v1"
    private val random = SecureRandom()

    fun newNonce(bytes: Int = 32): String = base64(ByteArray(bytes).also(random::nextBytes))

    /** A pairing code is 8 characters from an unambiguous alphabet, read aloud easily. */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun newPairingCode(length: Int = 8): String =
        (1..length).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    /** Constant-time comparison for codes and proofs. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * HKDF-SHA256 (RFC 5869): extract then expand, with the pairing code as input key material and
     * both nonces in the salt so a replay on a different handshake derives a different secret.
     */
    fun deriveSecret(
        pairingCode: String,
        clientNonce: String,
        serverNonce: String,
        length: Int = 32,
    ): ByteArray {
        require(pairingCode.isNotBlank()) { "Pairing code is required" }
        val ikm = pairingCode.toByteArray()
        val salt = (clientNonce + "|" + serverNonce).toByteArray()
        val prk = hmac(salt, ikm)
        return expand(prk, HKDF_INFO.toByteArray(), length)
    }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "Invalid HKDF output length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** The proof a client sends for a challenge, keyed by the paired secret. */
    fun proof(secret: ByteArray, deviceId: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(deviceId.toByteArray())
        mac.update(0)
        mac.update(challenge.toByteArray())
        return base64(mac.doFinal())
    }

    fun verifyProof(secret: ByteArray, deviceId: String, challenge: String, candidate: String): Boolean =
        MessageDigest.isEqual(proof(secret, deviceId, challenge).toByteArray(), candidate.toByteArray())

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

}

/** A paired client: the shared secret and the identity it is bound to. */
data class PairedClient(
    val deviceId: String,
    val deviceName: String,
    val secret: ByteArray,
    val pairedAtMs: Long,
    val revoked: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is PairedClient && deviceId == other.deviceId && secret.contentEquals(other.secret)

    override fun hashCode(): Int = deviceId.hashCode()
}

/** A one-time pairing code with an expiry, so a code is only usable inside its window. */
data class PairingWindow(
    val code: String,
    val createdAtMs: Long,
    val ttlMs: Long,
) {
    fun isOpen(nowMs: Long): Boolean = nowMs - createdAtMs <= ttlMs
}
