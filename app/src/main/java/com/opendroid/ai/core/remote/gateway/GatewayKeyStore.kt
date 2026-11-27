package com.opendroid.ai.core.remote.gateway

import com.opendroid.ai.core.security.SecretBytesStore
import com.opendroid.ai.remote.gateway.GatewayPeer

/**
 * Keeps the phone's gateway credentials in encrypted storage.
 *
 * The record is a framed blob (private key length, private key, public key, endpoint) written through
 * [SecretBytesStore], so the private key is never stored as readable text. A record that cannot be
 * decrypted is treated as absent rather than falling back to something weaker.
 */
class GatewayKeyStore(private val secrets: SecretBytesStore) {

    fun save(peer: GatewayPeer) {
        val priv = peer.privateKey.toByteArray(Charsets.UTF_8)
        val pub = peer.desktopPublicKey.toByteArray(Charsets.UTF_8)
        val endpoint = peer.endpoint.toByteArray(Charsets.UTF_8)
        val out = java.nio.ByteBuffer
            .allocate(12 + priv.size + pub.size + endpoint.size)
            .putInt(priv.size)
            .put(priv)
            .putInt(pub.size)
            .put(pub)
            .putInt(endpoint.size)
            .put(endpoint)
            .array()
        try {
            secrets.write(KEY, out)
        } finally {
            out.fill(0)
        }
    }

    /** Returns the stored peer, or null when nothing usable is stored. */
    fun load(): GatewayPeer? {
        val raw = secrets.read(KEY) ?: return null
        return try {
            val buf = java.nio.ByteBuffer.wrap(raw)
            val priv = ByteArray(buf.int).also { buf.get(it) }
            val pub = ByteArray(buf.int).also { buf.get(it) }
            val endpoint = ByteArray(buf.int).also { buf.get(it) }
            GatewayPeer(
                privateKey = String(priv, Charsets.UTF_8),
                desktopPublicKey = String(pub, Charsets.UTF_8),
                endpoint = String(endpoint, Charsets.UTF_8),
            ).also {
                priv.fill(0)
            }
        } catch (e: Exception) {
            // A truncated or foreign record must read as absent, never as a half-valid peer.
            null
        } finally {
            raw.fill(0)
        }
    }

    fun clear() {
        secrets.remove(KEY)
    }

    private companion object {
        const val KEY = "gateway_peer"
    }
}
