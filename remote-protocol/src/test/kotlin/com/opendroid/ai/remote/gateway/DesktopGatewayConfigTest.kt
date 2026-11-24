package com.opendroid.ai.remote.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DesktopGatewayConfigTest {

    @Test
    fun `provisioned config carries the desktop interface and the phone peer`() {
        val phonePublic = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val p = DesktopGatewayConfig.provision(phonePublic, listenPort = 51820)

        assertTrue(p.configText.contains("[Interface]"))
        assertTrue(p.configText.contains("[Peer]"))
        assertTrue(p.configText.contains("ListenPort = 51820"))
        assertTrue(p.configText.contains("Address = 10.66.0.1/24"))
        assertTrue(p.configText.contains("PublicKey = $phonePublic"))
        assertTrue(p.configText.contains("AllowedIPs = 10.66.0.2/32"))
    }

    @Test
    fun `the advertised public key matches the private key in the config`() {
        val p = DesktopGatewayConfig.provision(null)
        val privateLine = p.configText.lineSequence().first { it.startsWith("PrivateKey = ") }
        val privateKey = privateLine.removePrefix("PrivateKey = ").trim()

        assertEquals(
            "advertised public key must be derived from the config's private key",
            X25519.publicKeyFromPrivateBase64(privateKey),
            p.publicKeyBase64,
        )
    }

    @Test
    fun `config reuses an existing desktop identity rather than generating a different key`() {
        val persistedPrivateKey = DesktopGatewayConfig.newPrivateKey()
        val persistedPublicKey = X25519.publicKeyFromPrivateBase64(persistedPrivateKey)

        val p = DesktopGatewayConfig.provision(
            phonePublicKey = null,
            desktopPrivateKeyBase64 = persistedPrivateKey,
        )

        val configPrivateKey = p.configText.lineSequence()
            .first { it.startsWith("PrivateKey = ") }
            .removePrefix("PrivateKey = ")
            .trim()
        assertEquals(persistedPrivateKey, configPrivateKey)
        assertEquals(persistedPublicKey, p.publicKeyBase64)
    }

    @Test
    fun `keys are the right size and differ between runs`() {
        val a = DesktopGatewayConfig.provision(null)
        val b = DesktopGatewayConfig.provision(null)
        assertEquals(32, Base64.getDecoder().decode(a.publicKeyBase64).size)
        assertFalse("two provisions must not reuse a key", a.publicKeyBase64 == b.publicKeyBase64)
    }

    @Test
    fun `a missing phone key becomes a visible placeholder`() {
        val p = DesktopGatewayConfig.provision(null)
        assertTrue(p.configText.contains("<phone public key>"))
    }

    @Test
    fun `provisioned rendering hides the private key`() {
        val p = DesktopGatewayConfig.provision(null)
        val privateKey = p.configText.lineSequence()
            .first { it.startsWith("PrivateKey = ") }
            .removePrefix("PrivateKey = ").trim()
        assertFalse("private key leaked in toString", p.toString().contains(privateKey))
    }

    @Test
    fun `the desktop config starts a resolver on the address the phone uses for DNS`() {
        // The phone renders "DNS = 10.66.0.1". If nothing serves DNS there, every lookup fails
        // while the tunnel still reports itself up.
        val config = DesktopGatewayConfig.provision(phonePublicKey = null).configText
        assertTrue(
            "no resolver is started on the tunnel DNS address",
            config.contains("PostUp = dnsmasq") &&
                config.contains(GatewayConfig.DESKTOP_DNS),
        )
        assertTrue("the resolver is never stopped", config.contains("PostDown = "))
    }

    @Test
    fun `the desktop config tells the owner to enable forwarding and NAT`() {
        val config = DesktopGatewayConfig.provision(phonePublicKey = null).configText
        assertTrue("forwarding hint missing", config.contains("ip_forward=1"))
        assertTrue("NAT hint missing", config.contains("masquerade"))
    }
}
