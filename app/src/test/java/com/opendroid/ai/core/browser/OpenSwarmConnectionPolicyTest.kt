package com.opendroid.ai.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenSwarmConnectionPolicyTest {
    @Test
    fun `url validation accepts named loca lt and trycloudflare tunnel hosts`() {
        assertEquals("https://usman-umar-1.loca.lt", validateOpenSwarmTunnelUrl(" https://usman-umar-1.loca.lt/ "))
        assertEquals("https://sample-123.trycloudflare.com", validateOpenSwarmTunnelUrl("https://sample-123.trycloudflare.com"))
    }

    @Test
    fun `url validation rejects http unsupported hosts lookalikes and path components`() {
        val rejected = listOf(
            "http://sample.loca.lt",
            "https://loca.lt",
            "https://a.b.loca.lt",
            "https://example.com",
            "https://sample.trycloudflare.com.evil.example",
            "https://trycloudflare.com",
            "https://a.b.trycloudflare.com",
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
        assertEquals(OpenSwarmConnectionState.WAKING, state(httpCode = null, timedOut = true, attempt = 1))
        assertEquals(OpenSwarmConnectionState.OFFLINE, state(httpCode = null, timedOut = true, attempt = 2))
        assertEquals(OpenSwarmConnectionState.OFFLINE, state(httpCode = 503, attempt = 1))
        incompleteReadiness().forEach { flags ->
            assertEquals(OpenSwarmConnectionState.WAKING, state(flags = flags, attempt = 1))
            assertEquals(OpenSwarmConnectionState.OFFLINE, state(flags = flags, attempt = 2))
        }
    }

    @Test
    fun `non OpenSwarm routes are behind one false shared flag`() {
        assertEquals(false, OpenSwarmRouteConfig.ENABLE_NON_OPENSWARM_ROUTES)
        assertEquals(1, OpenSwarmRouteConfig.MAX_CONNECTED_NODES)
        assertEquals(true, OpenSwarmRouteConfig.isHiddenAgent("terminal.execute", "Terminal"))
        assertEquals(true, OpenSwarmRouteConfig.isHiddenAgent("files.list", "File Manager"))
        assertEquals(true, OpenSwarmRouteConfig.isHiddenAgent("browser_agent", "Browser"))
        assertEquals(true, OpenSwarmRouteConfig.isHiddenAgent("delivery_agent", "Delivery"))
        assertEquals(false, OpenSwarmRouteConfig.isHiddenAgent("openswarm_agent", "OpenSwarm"))
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
        flags: Flags = Flags(),
        attempt: Int,
        timedOut: Boolean = false,
    ) = mapOpenSwarmHealth(
        httpCode = httpCode,
        ready = flags.ready,
        authReady = flags.authReady,
        redisReady = flags.redisReady,
        checkpointsReady = flags.checkpointsReady,
        checkpointStorageReady = flags.checkpointStorageReady,
        leaseReady = flags.leaseReady,
        leaseCoordinatorReady = flags.leaseCoordinatorReady,
        acceptingNewTasks = flags.acceptingNewTasks,
        draining = flags.draining,
        attempt = attempt,
        timedOut = timedOut,
    )

    private data class Flags(
        val ready: Boolean? = true,
        val authReady: Boolean? = true,
        val redisReady: Boolean? = true,
        val checkpointsReady: Boolean? = true,
        val checkpointStorageReady: Boolean? = true,
        val leaseReady: Boolean? = true,
        val leaseCoordinatorReady: Boolean? = true,
        val acceptingNewTasks: Boolean? = true,
        val draining: Boolean? = false,
    )

    private fun incompleteReadiness(): List<Flags> = listOf(
        Flags(ready = false), Flags(ready = null),
        Flags(authReady = false), Flags(authReady = null),
        Flags(redisReady = false), Flags(redisReady = null),
        Flags(checkpointsReady = false), Flags(checkpointsReady = null),
        Flags(checkpointStorageReady = false), Flags(checkpointStorageReady = null),
        Flags(leaseReady = false), Flags(leaseReady = null),
        Flags(leaseCoordinatorReady = false), Flags(leaseCoordinatorReady = null),
        Flags(acceptingNewTasks = false), Flags(acceptingNewTasks = null),
        Flags(draining = true), Flags(draining = null),
    )
}
