package com.opendroid.ai.core.agentplatform

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Exercises [AgentPlatformOrchestrator] against a real HTTP server, so the request shapes, the
 * polling and the failure messages are all produced by production code.
 */
class AgentPlatformOrchestratorTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    /** In-memory store; the real one needs Android Keystore, which is not available on the JVM. */
    private class FakeStore(
        private val connections: Map<String, PlatformConnection>,
        private val keys: Map<String, String>,
    ) : PlatformConnectionStore {
        override fun connections(): List<PlatformConnection> = connections.values.toList()
        override fun connection(platformId: String): PlatformConnection? = connections[platformId]
        override fun save(connection: PlatformConnection, sessionKey: String): Boolean = true
        override fun sessionKey(platformId: String): String? = keys[platformId]
        override fun remove(platformId: String): Boolean = true
    }

    private fun storeWith(platformId: String, key: String = "test-key") = FakeStore(
        connections = mapOf(
            platformId to PlatformConnection(
                platformId = platformId,
                displayName = platformId,
                baseUrl = server.url("/").toString().trimEnd('/'),
                connectedAtMillis = 0L,
            ),
        ),
        keys = mapOf(platformId to key),
    )

    private fun orchestrator(store: PlatformConnectionStore) =
        AgentPlatformOrchestrator(store, pollIntervalMillis = 1L, maxWaitMillis = 3_000L)

    @Test
    fun `preflight names the missing connection instead of failing later`() {
        val empty = FakeStore(emptyMap(), emptyMap())
        val result = orchestrator(empty).preflight(AgentPlatformCatalog.openHands.id)

        assertTrue(result is AgentPlatformOrchestrator.Preflight.Blocked)
        val reason = (result as AgentPlatformOrchestrator.Preflight.Blocked).reason
        assertTrue("Reason should name the platform: $reason", reason.contains("OpenHands"))
        assertTrue("Reason should say what to do: $reason", reason.contains("Connect"))
    }

    @Test
    fun `preflight reports an unknown platform rather than pretending it exists`() {
        val result = orchestrator(FakeStore(emptyMap(), emptyMap())).preflight("not_a_platform")

        assertTrue(result is AgentPlatformOrchestrator.Preflight.Blocked)
        assertTrue((result as AgentPlatformOrchestrator.Preflight.Blocked).reason.contains("not_a_platform"))
    }

    @Test
    fun `preflight refuses when the stored key is gone`() {
        val store = FakeStore(
            connections = mapOf(
                AgentPlatformCatalog.openHands.id to PlatformConnection(
                    AgentPlatformCatalog.openHands.id, "OpenHands",
                    server.url("/").toString().trimEnd('/'), 0L,
                ),
            ),
            keys = emptyMap(),
        )
        val result = orchestrator(store).preflight(AgentPlatformCatalog.openHands.id)

        assertTrue(result is AgentPlatformOrchestrator.Preflight.Blocked)
        val reason = (result as AgentPlatformOrchestrator.Preflight.Blocked).reason
        assertTrue("Reason should tell the user to reconnect: $reason", reason.contains("Reconnect"))
    }

    @Test
    fun `empty prompt is refused before any network call`() = runBlocking {
        val outcome = orchestrator(storeWith(AgentPlatformCatalog.openHands.id)).runTask("", "")

        assertEquals(AgentTaskState.FAILED, outcome.state)
        assertNotNull(outcome.failure)
        assertEquals("No HTTP request should be made for an empty prompt", 0, server.requestCount)
    }

    @Test
    fun `a platform failure is surfaced with the server's own message`() = runBlocking {
        // /api/settings is the validateConnection call; answer 401 with a detail.
        server.enqueue(
            MockResponse.Builder().code(401)
                .body("{\"detail\":\"Invalid session key\"}").build(),
        )

        val outcome = orchestrator(storeWith(AgentPlatformCatalog.openHands.id)).runTask("x", "do a thing")

        assertEquals(AgentTaskState.FAILED, outcome.state)
        assertNotNull("A failed run must carry a reason", outcome.failure)
        assertFalse("A failed run is not a success", outcome.succeeded)
    }

    @Test
    fun `openswarm run reports the backend's readiness refusal instead of a started task`() = runBlocking {
        // /agents validates the connection.
        server.enqueue(MockResponse.Builder().code(200).body("{\"agents\":[]}").build())
        // /execute refuses while the backend is not ready.
        server.enqueue(
            MockResponse.Builder().code(503).body(
                "{\"error_code\":\"BACKEND_NOT_READY\",\"recovery_action\":\"Configure Upstash Redis\"}",
            ).build(),
        )

        val outcome = orchestrator(storeWith(AgentPlatformCatalog.openSwarm.id)).runTask("s", "run it")

        assertEquals(AgentTaskState.FAILED, outcome.state)
        val failure = outcome.failure.orEmpty()
        assertTrue("Failure should carry the backend reason: $failure", failure.isNotBlank())
        assertFalse(outcome.succeeded)
    }

    @Test
    fun `runAcross keeps one outcome per platform and never drops a failing one`() = runBlocking {
        val store = FakeStore(
            connections = mapOf(
                AgentPlatformCatalog.openHands.id to PlatformConnection(
                    AgentPlatformCatalog.openHands.id, "OpenHands",
                    server.url("/").toString().trimEnd('/'), 0L,
                ),
            ),
            keys = mapOf(AgentPlatformCatalog.openHands.id to "k"),
        )

        val outcomes = orchestrator(store).runAcross(
            listOf(AgentPlatformCatalog.openHands.id, AgentPlatformCatalog.openSwarm.id),
            "hello",
        )

        assertEquals("One outcome per requested platform", 2, outcomes.size)
        val byId = outcomes.associateBy { it.platformId }
        // OpenSwarm has no stored connection, so it must be reported as blocked, not skipped.
        assertEquals(AgentTaskState.FAILED, byId.getValue(AgentPlatformCatalog.openSwarm.id).state)
        assertNotNull(byId.getValue(AgentPlatformCatalog.openSwarm.id).failure)
    }
}
