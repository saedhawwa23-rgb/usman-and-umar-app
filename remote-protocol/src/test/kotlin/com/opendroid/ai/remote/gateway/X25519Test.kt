package com.opendroid.ai.remote.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * Pins [X25519] to the published RFC 7748 test vectors.
 *
 * A Curve25519 implementation that is subtly wrong still produces plausible-looking 32-byte keys,
 * so the only trustworthy check is the standard's own vectors.
 */
class X25519Test {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc 7748 section 5_2 vector 1`() {
        val scalar = hex(
            "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
        )
        val u = hex(
            "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
        )
        val expected = hex(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
        )
        assertArrayEquals(expected, X25519.scalarMultiply(scalar, u))
    }

    @Test
    fun `rfc 7748 section 5_2 vector 2`() {
        val scalar = hex(
            "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
        )
        val u = hex(
            "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
        )
        val expected = hex(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
        )
        assertArrayEquals(expected, X25519.scalarMultiply(scalar, u))
    }

    @Test
    fun `rfc 7748 section 6_1 diffie-hellman`() {
        val alicePrivate = hex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        )
        val alicePublic = hex(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
        )
        val bobPrivate = hex(
            "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
        )
        val bobPublic = hex(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
        )
        val shared = hex(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
        )

        assertArrayEquals("alice public", alicePublic, X25519.publicKeyFromPrivate(alicePrivate))
        assertArrayEquals("bob public", bobPublic, X25519.publicKeyFromPrivate(bobPrivate))
        assertArrayEquals(
            "shared secret from alice's side",
            shared,
            X25519.scalarMultiply(alicePrivate, bobPublic),
        )
        assertArrayEquals(
            "shared secret from bob's side",
            shared,
            X25519.scalarMultiply(bobPrivate, alicePublic),
        )
    }

    @Test
    fun `clamping clears the low three bits and pins the top two`() {
        val input = ByteArray(32) { 0xFF.toByte() }
        val clamped = X25519.clamp(input)
        assertEquals("low three bits", 0xF8, clamped[0].toInt() and 0xFF)
        assertEquals("top bit cleared, bit 6 set", 0x7F, clamped[31].toInt() and 0xFF)
    }

    @Test
    fun `public key derivation is stable for a base64 private key`() {
        val privateKey = Base64.getEncoder().encodeToString(
            hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
        )
        val expected = Base64.getEncoder().encodeToString(
            hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
        )
        assertEquals(expected, X25519.publicKeyFromPrivateBase64(privateKey))
    }

    @Test
    fun `a wrong sized private key is rejected`() {
        try {
            X25519.publicKeyFromPrivate(ByteArray(16))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
