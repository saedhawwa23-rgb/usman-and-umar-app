package com.opendroid.ai.core.remote.gateway

import com.opendroid.ai.remote.gateway.GatewayPeer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Creates the phone's WireGuard identity.
 *
 * Uses the WireGuard library's own key generation so the output is a valid Curve25519 key rather
 * than random bytes that merely look like one. Only the public half is ever handed to the desktop.
 */
object GatewayKeys {

    data class Generated(val privateKeyBase64: String, val publicKeyBase64: String) {
        override fun toString(): String = "Generated(publicKey=${publicKeyBase64.take(8)}…, privateKey=<redacted>)"
    }

    /** Create a fresh key pair for this phone. */
    fun generate(): Generated {
        val pair = KeyPair()
        return Generated(
            privateKeyBase64 = pair.privateKey.toBase64(),
            publicKeyBase64 = pair.publicKey.toBase64(),
        )
    }

    /** Derive the public key for an existing private key, for when only the private half is stored. */
    fun publicKeyOf(privateKeyBase64: String): String =
        Key.fromBase64(privateKeyBase64).let { KeyPair(it).publicKey.toBase64() }

    /** Build the peer record once the desktop's public key and endpoint are known. */
    fun peerFor(
        privateKeyBase64: String,
        desktopPublicKeyBase64: String,
        endpoint: String,
    ): GatewayPeer = GatewayPeer(privateKeyBase64, desktopPublicKeyBase64, endpoint)
}
