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
}
