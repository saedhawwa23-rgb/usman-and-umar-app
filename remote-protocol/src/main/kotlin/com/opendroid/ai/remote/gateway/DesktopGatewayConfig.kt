package com.opendroid.ai.remote.gateway

/**
 * Produces the desktop half of the gateway: a WireGuard configuration the standard desktop client
 * can load, plus the public key the phone needs.
 *
 * The desktop agent is a plain JVM process, so it does not carry WireGuard's kernel or userspace
 * datapath. It generates the keys and writes the config; the actual tunnel is run by the official
 * WireGuard application for the desktop platform. That split is deliberate: it keeps the agent
 * dependency-free and uses the audited implementation for packet handling.
 */
object DesktopGatewayConfig {

    /** Address the desktop takes inside the tunnel. Mirrors [GatewayConfig.DESKTOP_TUNNEL_ADDRESS]. */
    const val DESKTOP_TUNNEL_ADDRESS = GatewayConfig.DESKTOP_TUNNEL_ADDRESS + "/24"

    data class Provisioned(
        /** Config text for the desktop's WireGuard client. Contains the desktop private key. */
        val configText: String,
        /** Public key the phone must be given. Safe to display and transmit. */
        val publicKeyBase64: String,
    ) {
        override fun toString(): String =
            "Provisioned(publicKey=${publicKeyBase64.take(8)}…, configText=<redacted>)"
    }

    /**
     * Create a desktop identity and the matching config.
     *
     * @param phonePublicKey the phone's WireGuard public key, or null to write a placeholder the
     *   owner replaces after the phone has generated its key.
     * @param listenPort UDP port the desktop listens on.
     */
    fun provision(phonePublicKey: String?, listenPort: Int = GatewayConfig.DEFAULT_LISTEN_PORT): Provisioned {
        val privateKey = newPrivateKey()
        val publicKey = X25519.publicKeyFromPrivateBase64(privateKey)

        val peerKey = phonePublicKey?.takeIf { it.isNotBlank() } ?: "<phone public key>"
        val config = buildString {
            appendLine("[Interface]")
            appendLine("# Desktop side of the UMAR and Usman internet gateway.")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $DESKTOP_TUNNEL_ADDRESS")
            appendLine("ListenPort = $listenPort")
            appendLine()
            appendLine("[Peer]")
            appendLine("# The phone. Its public key appears in the app's Gateway card.")
            appendLine("PublicKey = $peerKey")
            appendLine("AllowedIPs = ${GatewayConfig.PHONE_TUNNEL_ADDRESS}")
            appendLine()
            appendLine("# After the tunnel is up, let the phone's traffic reach the internet:")
            appendLine("#   sysctl -w net.ipv4.ip_forward=1")
            appendLine("#   nft add rule ip nat POSTROUTING oifname \"<uplink>\" masquerade")
        }
        return Provisioned(config, publicKey)
    }

    /**
     * A 32-byte private key in the encoding WireGuard expects.
     *
     * `SecureRandom` supplies the entropy; clamping happens inside [X25519] when the public key is
     * derived, which is where WireGuard applies it too.
     */
    fun newPrivateKey(): String {
        val raw = ByteArray(32)
        java.security.SecureRandom().nextBytes(raw)
        return java.util.Base64.getEncoder().encodeToString(raw)
    }
}
