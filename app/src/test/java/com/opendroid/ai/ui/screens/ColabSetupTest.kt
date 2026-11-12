package com.opendroid.ai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColabSetupTest {
    @Test
    fun `finds a loca lt origin in copied Colab output`() {
        assertEquals(
            "https://usman-umar-1.loca.lt",
            findColabTunnelUrl("Public URL: https://usman-umar-1.loca.lt"),
        )
    }

    @Test
    fun `finds a trycloudflare origin and drops path and punctuation`() {
        assertEquals(
            "https://bright-tunnel.trycloudflare.com",
            findColabTunnelUrl("Copy https://bright-tunnel.trycloudflare.com/status, then return"),
        )
    }

    @Test
    fun `rejects unrelated hosts and non-https URLs`() {
        assertNull(findColabTunnelUrl("https://example.com"))
        assertNull(findColabTunnelUrl("http://demo.loca.lt"))
        assertNull(findColabTunnelUrl("https://notloca.lt"))
        assertNull(findColabTunnelUrl(null))
    }
}
