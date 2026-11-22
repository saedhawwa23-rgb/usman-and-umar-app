package com.opendroid.ai.remote.gateway

/**
 * Builds WireGuard peer configuration for the phone side of the internet gateway.
 *
 * Kept free of Android types so the routing rules can be tested on a plain JVM. The caller supplies
 * the keys and addresses; nothing here generates or stores secrets.
 */
object GatewayConfig {

    /** Interface address the phone takes inside the tunnel. */
    const val PHONE_TUNNEL_ADDRESS = "10.66.0.2/32"

    /** Address the desktop holds inside the tunnel; also the DNS resolver when one is offered. */
    const val DESKTOP_TUNNEL_ADDRESS = "10.66.0.1"

    /** Default WireGuard port the desktop listens on. */
    const val DEFAULT_LISTEN_PORT = 51820

    /** DNS server inside the tunnel, used only when the desktop offers one. */
    const val DESKTOP_DNS = "10.66.0.1"

    /**
     * Every route, so all traffic leaves through the desktop.
     *
     * Both address families are listed because a tunnel that claims only IPv4 would silently leak
     * IPv6 traffic onto the phone's own network.
     */
    val FULL_TUNNEL_ALLOWED_IPS: List<String> = listOf("0.0.0.0/0", "::/0")

    /**
     * Loopback and the private ranges stay on the phone.
     *
     * Without this, `adb`-style local services and the phone's own hotspot clients would be routed
     * into the tunnel and stop working. The tunnel's own subnet is excluded too so WireGuard's
     * outer packets do not try to go through the tunnel they carry.
     */
    val SPLIT_TUNNEL_EXCLUDED: List<String> = listOf(
        "127.0.0.0/8",
        "10.66.0.0/24",
        "169.254.0.0/16",
        "224.0.0.0/4",
        "::1/128",
    )

    /**
     * Build the allowed-IP list for the requested mode.
     *
     * @param fullTunnel route everything through the desktop.
     * @param extraRoutes caller-supplied destinations to add in split mode, already in CIDR form.
     */
    fun allowedIps(fullTunnel: Boolean, extraRoutes: List<String> = emptyList()): List<String> {
        if (fullTunnel) return FULL_TUNNEL_ALLOWED_IPS
        val routes = extraRoutes.map { it.trim() }.filter { it.isNotEmpty() }
        return (routes + SPLIT_TUNNEL_EXCLUDED).distinct()
    }

    /**
     * Render a WireGuard configuration for the phone.
     *
     * @param phonePrivateKey base64 private key held on this phone.
     * @param desktopPublicKey base64 public key of the desktop peer.
     * @param endpoint host:port where the desktop is reachable.
     * @param allowedIps routes to send through the tunnel.
     * @param dns resolver to advertise, or null to keep the phone's own.
     * @param keepalive persistent keepalive seconds; non-zero keeps NAT mappings open.
     */
    fun render(
        phonePrivateKey: String,
        desktopPublicKey: String,
        endpoint: String,
        allowedIps: List<String>,
        dns: String? = null,
        keepalive: Int = 25,
        mtu: Int = 1420,
    ): String {
        require(phonePrivateKey.isNotBlank()) { "phone private key is required" }
        require(desktopPublicKey.isNotBlank()) { "desktop public key is required" }
        require(endpoint.contains(':')) { "endpoint must be host:port, got '$endpoint'" }
        require(allowedIps.isNotEmpty()) { "at least one allowed IP is required" }

        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $phonePrivateKey")
            appendLine("Address = $PHONE_TUNNEL_ADDRESS")
            if (dns != null) appendLine("DNS = $dns")
            appendLine("MTU = $mtu")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $desktopPublicKey")
            appendLine("Endpoint = $endpoint")
            appendLine("AllowedIPs = ${allowedIps.joinToString(", ")}")
            if (keepalive > 0) appendLine("PersistentKeepalive = $keepalive")
        }
    }

    /**
     * Redact a rendered configuration for logging.
     *
     * Private keys and any preshared key are replaced; everything else stays readable so a
     * misconfiguration can still be diagnosed from a log.
     */
    fun redact(config: String): String = config.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("PrivateKey") || trimmed.startsWith("PresharedKey") ->
                "${line.substringBefore('=').trimEnd()} = <redacted>"
            else -> line
        }
    }
}
