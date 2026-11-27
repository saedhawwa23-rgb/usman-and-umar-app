package com.opendroid.ai.remote.gateway

/**
 * The credentials and endpoint the phone needs to reach the desktop gateway.
 *
 * [privateKey] is this phone's WireGuard private key. It is deliberately a plain `String` so this
 * type stays free of Android types, but callers must keep it in encrypted storage and must never
 * log it. Use [toString] output (which is redacted) rather than printing the fields.
 */
data class GatewayPeer(
    /** Base64 WireGuard private key for this phone. Secret. */
    val privateKey: String,
    /** Base64 WireGuard public key of the desktop peer. Not secret. */
    val desktopPublicKey: String,
    /** host:port where the desktop WireGuard listener can be reached. */
    val endpoint: String,
) {
    init {
        require(endpoint.contains(':')) { "endpoint must be host:port, got '$endpoint'" }
    }

    /** Redacted rendering, safe for logs and crash reports. */
    override fun toString(): String =
        "GatewayPeer(endpoint=$endpoint, desktopPublicKey=${desktopPublicKey.take(8)}…, privateKey=<redacted>)"
}
