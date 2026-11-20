package com.opendroid.ai.core.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) code verifier and challenge.
 *
 * The verifier is 43-128 characters from the unreserved set `[A-Za-z0-9-._~]`. It is generated
 * from a CSPRNG and never derived from anything guessable. Only the S256 method is produced:
 * `plain` is permitted by the RFC but offers no protection against interception, so it is not
 * implemented.
 */
object Pkce {

    const val MIN_VERIFIER_LENGTH = 43
    const val MAX_VERIFIER_LENGTH = 128

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val VERIFIER_PATTERN = Regex("[A-Za-z0-9\\-._~]{$MIN_VERIFIER_LENGTH,$MAX_VERIFIER_LENGTH}")

    /** Generate a fresh 32-byte verifier, which base64url-encodes to 43 characters. */
    fun generateVerifier(random: SecureRandom = SecureRandom(), byteLength: Int = 32): String {
        require(byteLength in 32..96) { "Verifier entropy must be 32-96 bytes" }
        val bytes = ByteArray(byteLength).also(random::nextBytes)
        return base64UrlNoPadding(bytes)
    }

    /** S256 challenge: BASE64URL(SHA256(ASCII(verifier))), no padding. */
    fun challengeS256(verifier: String): String {
        require(isValidVerifier(verifier)) { "Code verifier must be 43-128 unreserved characters" }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    fun isValidVerifier(verifier: String): Boolean = VERIFIER_PATTERN.matches(verifier)

    fun isValidChallenge(challenge: String): Boolean =
        challenge.length == 43 && challenge.all { it in UNRESERVED && it != '~' }

    /** Constant-time comparison, so a challenge check cannot be timed character by character. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    internal fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** The `S256` method is the only one implemented; `plain` is deliberately absent. */
enum class PkceMethod { S256 }

/**
 * A single authorization attempt. The verifier and state are secret to this attempt and are
 * consumed exactly once, on the redirect that matches [state].
 */
data class PkceTransaction(
    val state: String,
    val verifier: String,
    val challenge: String,
    val method: PkceMethod = PkceMethod.S256,
    val redirectUri: String,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun isExpired(nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean = nowMs - createdAtMs > ttlMs

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        fun create(
            redirectUri: String,
            random: SecureRandom = SecureRandom(),
            nowMs: Long = System.currentTimeMillis(),
        ): PkceTransaction {
            val verifier = Pkce.generateVerifier(random)
            return PkceTransaction(
                state = Pkce.base64UrlNoPadding(ByteArray(32).also(random::nextBytes)),
                verifier = verifier,
                challenge = Pkce.challengeS256(verifier),
                redirectUri = redirectUri,
                createdAtMs = nowMs,
            )
        }
    }
}
