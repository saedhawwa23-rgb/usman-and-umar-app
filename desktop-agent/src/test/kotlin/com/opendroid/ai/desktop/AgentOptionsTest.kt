package com.opendroid.ai.desktop

import com.opendroid.ai.remote.gateway.GatewayConfig
import com.opendroid.ai.remote.socks.Socks5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOptionsTest {
    @Test
    fun `SOCKS and WireGuard use separate ports`() {
        val options = AgentOptions.parse(arrayOf("--share-port", "1081", "--gateway-port", "51830"))

        assertEquals(1081, options.sharePort)
        assertEquals(51830, options.gatewayPort)
    }

    @Test
    fun `defaults retain independent SOCKS and WireGuard ports`() {
        val options = AgentOptions.parse(emptyArray())

        assertEquals(Socks5.DEFAULT_PORT, options.sharePort)
        assertEquals(GatewayConfig.DEFAULT_LISTEN_PORT, options.gatewayPort)
        assertTrue(options.shareInternet)
        assertTrue(options.gatewayEnabled)
    }

    @Test
    fun `disabling SOCKS does not disable the WireGuard gateway`() {
        val options = AgentOptions.parse(arrayOf("--no-share-internet"))

        assertFalse(options.shareInternet)
        assertTrue(options.gatewayEnabled)
    }

    @Test
    fun `gateway can be disabled independently`() {
        val options = AgentOptions.parse(arrayOf("--no-gateway"))

        assertTrue(options.shareInternet)
        assertFalse(options.gatewayEnabled)
    }
}
