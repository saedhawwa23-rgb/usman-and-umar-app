package com.opendroid.ai.core.remote.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitIpVerifierTest {

    @Test
    fun `a different address proves the exit changed`() {
        assertTrue(ExitIpVerifier.changed("203.0.113.10", "198.51.100.7"))
    }

    @Test
    fun `an unchanged address does not prove anything`() {
        assertFalse(ExitIpVerifier.changed("203.0.113.10", "203.0.113.10"))
    }

    @Test
    fun `a missing reading is never treated as a change`() {
        assertFalse("no baseline", ExitIpVerifier.changed(null, "198.51.100.7"))
        assertFalse("no current reading", ExitIpVerifier.changed("203.0.113.10", null))
        assertFalse("neither reading", ExitIpVerifier.changed(null, null))
    }

    @Test
    fun `an unreachable service reports a failure instead of throwing`() {
        // Port 1 on loopback refuses immediately, so this exercises the failure path without
        // depending on any external service.
        val verifier = ExitIpVerifier(endpoints = listOf("http://127.0.0.1:1/ip"), timeoutMs = 1000)
        assertTrue(verifier.fetch() is ExitIpVerifier.Result.Failure)
    }
}
