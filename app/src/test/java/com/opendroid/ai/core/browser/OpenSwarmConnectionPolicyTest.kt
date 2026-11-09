package com.opendroid.ai.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSwarmConnectionPolicyTest {
    @Test
    fun `url validation accepts named loca lt and trycloudflare tunnel hosts`() {
        assertEquals("https://usman-umar-1.loca.lt", validateOpenSwarmTunnelUrl(" https://usman-umar-1.loca.lt/ "))
        assertEquals("https://a.b.loca.lt", validateOpenSwarmTunnelUrl("https://a.b.loca.lt"))
        assertEquals("https://sample-123.trycloudflare.com", validateOpenSwarmTunnelUrl("https://sample-123.trycloudflare.com"))
    }

    @Test
    fun `url validation rejects http unsupported hosts lookalikes and path components`() {
        val rejected = listOf(
            "http://sample.loca.lt",
            "https://loca.lt",
            "https://example.com",
            "https://sample.trycloudflare.com.evil.example",
            "https://trycloudflare.com",
            "https://sample.loca.lt/path",
            "https://user@sample.loca.lt",
            "https://sample.loca.lt:8443",
            "https://sample.loca.lt?token=x",
        )
        rejected.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { validateOpenSwarmTunnelUrl(value) }
        }
    }

    @Test
    fun `readiness fields map to Online only when every required flag agrees`() {
        assertEquals(OpenSwarmConnectionState.ONLINE, state(attempt = 1))
        assertEquals(OpenSwarmConnectionState.WAKING, state(ready = false, attempt = 1))
        assertEquals(OpenSwarmConnectionState.OFFLINE, state(ready = false, attempt = 2))
        assertEquals(OpenSwarmConnectionState.WAKING, state(httpCode = null, timedOut = true, attempt = 1))
        assertEquals(OpenSwarmConnectionState.OFFLINE, state(httpCode = null, timedOut = true, attempt = 2))
        assertEquals(OpenSwarmConnectionState.OFFLINE, state(httpCode = 503, attempt = 1))
    }

    @Test
    fun `offline OpenSwarm task route is blocked with actionable message`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            requireOpenSwarmOnline(online = false)
        }
        assertEquals("OpenSwarm server offline. Start the Colab notebook and try again.", failure.message)
        requireOpenSwarmOnline(online = true)
    }

    private fun state(
        httpCode: Int? = 200,
        ready: Boolean? = true,
        attempt: Int,
        timedOut: Boolean = false,
    ) = mapOpenSwarmHealth(
        httpCode = httpCode,
        ready = ready,
        authReady = true,
        redisReady = true,
        checkpointsReady = true,
        checkpointStorageReady = true,
        leaseReady = true,
        leaseCoordinatorReady = true,
        acceptingNewTasks = true,
        draining = false,
        attempt = attempt,
        timedOut = timedOut,
    )
}
