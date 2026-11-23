package com.opendroid.ai.core.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleDetectorTest {

    @Test
    fun `the tailnet block is recognised`() {
        assertTrue(TailscaleDetector.isTailnetRange("100.64.0.1"))
        assertTrue(TailscaleDetector.isTailnetRange("100.101.102.103"))
        assertTrue(TailscaleDetector.isTailnetRange("100.127.255.254"))
    }

    @Test
    fun `ordinary and adjacent addresses are not mistaken for the tailnet`() {
        assertFalse("just below the block", TailscaleDetector.isTailnetRange("100.63.255.255"))
        assertFalse("just above the block", TailscaleDetector.isTailnetRange("100.128.0.1"))
        assertFalse("normal LAN", TailscaleDetector.isTailnetRange("192.168.1.10"))
        assertFalse("not an address", TailscaleDetector.isTailnetRange("not-an-ip"))
        assertFalse("too few parts", TailscaleDetector.isTailnetRange("100.64.1"))
    }

    @Test
    fun `a connected vpn blocks the app gateway`() {
        // Two VpnService instances cannot be active together, so this must be treated as blocking.
        assertTrue(TailscaleDetector.State(installed = true, vpnActive = true).blocksGateway)
        assertFalse(TailscaleDetector.State(installed = true, vpnActive = false).blocksGateway)
    }
}
