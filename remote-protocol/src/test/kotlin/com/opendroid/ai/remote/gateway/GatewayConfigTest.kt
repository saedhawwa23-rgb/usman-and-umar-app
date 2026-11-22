package com.opendroid.ai.remote.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayConfigTest {

    private val phoneKey = "cGhvbmUtcHJpdmF0ZS1rZXktYmFzZTY0LXBsYWNlaG9sZGVyMDA="
    private val desktopKey = "ZGVza3RvcC1wdWJsaWMta2V5LWJhc2U2NC1wbGFjZWhvbGRlcjA="

    @Test
    fun `full tunnel routes every address family`() {
        val ips = GatewayConfig.allowedIps(fullTunnel = true)
        assertTrue("IPv4 default route missing", ips.contains("0.0.0.0/0"))
        assertTrue("IPv6 default route missing: IPv6 would leak", ips.contains("::/0"))
    }

    @Test
    fun `split tunnel keeps loopback and the tunnel subnet local`() {
        val ips = GatewayConfig.allowedIps(fullTunnel = false, extraRoutes = listOf("1.1.1.1/32"))
        assertTrue("requested route missing", ips.contains("1.1.1.1/32"))
        assertTrue("loopback must stay local", ips.contains("127.0.0.0/8"))
        assertTrue("tunnel subnet must stay local", ips.contains("10.66.0.0/24"))
        assertFalse("split tunnel must not claim the default route", ips.contains("0.0.0.0/0"))
    }

    @Test
    fun `split tunnel with no routes still protects the local ranges`() {
        val ips = GatewayConfig.allowedIps(fullTunnel = false)
        assertEquals(GatewayConfig.SPLIT_TUNNEL_EXCLUDED.toSet(), ips.toSet())
    }

    @Test
    fun `duplicate routes are collapsed`() {
        val ips = GatewayConfig.allowedIps(fullTunnel = false, extraRoutes = listOf("8.8.8.8/32", "8.8.8.8/32"))
        assertEquals(1, ips.count { it == "8.8.8.8/32" })
    }

    @Test
    fun `render produces the sections WireGuard expects`() {
        val config = GatewayConfig.render(
            phonePrivateKey = phoneKey,
            desktopPublicKey = desktopKey,
            endpoint = "school.example.org:51820",
            allowedIps = GatewayConfig.allowedIps(fullTunnel = true),
            dns = GatewayConfig.DESKTOP_DNS,
        )
        assertTrue(config.contains("[Interface]"))
        assertTrue(config.contains("[Peer]"))
        assertTrue(config.contains("PrivateKey = $phoneKey"))
        assertTrue(config.contains("PublicKey = $desktopKey"))
        assertTrue(config.contains("Endpoint = school.example.org:51820"))
        assertTrue(config.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(config.contains("DNS = 10.66.0.1"))
        assertTrue(config.contains("Address = 10.66.0.2/32"))
        assertTrue(config.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `keepalive can be disabled`() {
        val config = GatewayConfig.render(phoneKey, desktopKey, "h:1", listOf("0.0.0.0/0"), keepalive = 0)
        assertFalse("keepalive should be omitted when zero", config.contains("PersistentKeepalive"))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `endpoint without a port is rejected`() {
        assertRejected { GatewayConfig.render(phoneKey, desktopKey, "school.example.org", listOf("0.0.0.0/0")) }
    }

    @Test
    fun `blank keys are rejected`() {
        assertRejected { GatewayConfig.render("", desktopKey, "h:1", listOf("0.0.0.0/0")) }
        assertRejected { GatewayConfig.render(phoneKey, "  ", "h:1", listOf("0.0.0.0/0")) }
    }

    @Test
    fun `an empty route set is rejected`() {
        assertRejected { GatewayConfig.render(phoneKey, desktopKey, "h:1", emptyList()) }
    }

    @Test
    fun `redaction removes private and preshared keys but keeps the rest`() {
        val config = GatewayConfig.render(
            phonePrivateKey = phoneKey,
            desktopPublicKey = desktopKey,
            endpoint = "school.example.org:51820",
            allowedIps = listOf("0.0.0.0/0"),
        ) + "PresharedKey = $phoneKey\n"

        val redacted = GatewayConfig.redact(config)
        assertFalse("private key leaked into log output", redacted.contains(phoneKey))
        assertTrue(redacted.contains("PrivateKey = <redacted>"))
        assertTrue(redacted.contains("PresharedKey = <redacted>"))
        assertTrue("public key should stay readable", redacted.contains(desktopKey))
        assertTrue(redacted.contains("Endpoint = school.example.org:51820"))
    }
}
