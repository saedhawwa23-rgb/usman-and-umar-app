package com.opendroid.ai.core.agentplatform

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Contract tests for [OpenSwarmClient] and [OpenSwarmAdapter] against a real HTTP server.
 *
 * Every assertion is about a real request and a real response; there are no stubs of the client.
 */
class OpenSwarmClientTest {
    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    private fun client() = OpenSwarmClient(server.url("/").toString().trimEnd('/'), "test-app-token")

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `validateConnection sends bearer token to capabilities`() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"capabilities\":[]}").build())

        val result = client().validateConnection()

        assertTrue("Validation succeeds only on a real 2xx", result is PlatformResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/capabilities", request.url.encodedPath)
        assertEquals("Bearer test-app-token", request.headers["Authorization"])
    }

    @Test
    fun `health is read without a token and reports the backend verdict`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(
                    "{\"status\":\"not_ready\",\"ready\":false,\"auth_ready\":true," +
                        "\"recovery_reason\":\"UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN are required\"}"
                )
                .build()
        )

        val result = client().health()

        assertTrue(result is PlatformResult.Success)
        val health = (result as PlatformResult.Success).value
        assertEquals(false, health.ready)
        assertEquals(true, health.authReady)
        assertEquals("UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN are required", health.reason)
        assertNull("Health must not send a credential", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `health treats a string boolean as ready`() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"ready\":\"true\",\"auth_ready\":\"true\"}").build())

        val health = (client().health() as PlatformResult.Success).value

        assertTrue(health.ready)
    }

    @Test
    fun `a 401 surfaces the backend message and never the token`() {
        server.enqueue(MockResponse.Builder().code(401).body("{\"detail\":\"Invalid application token.\"}").build())

        val result = client().validateConnection()

        assertTrue(result is PlatformResult.Failure)
        val message = (result as PlatformResult.Failure).message
        assertTrue("Message should carry the server's reason", message.contains("Invalid application token"))
        assertTrue("Message must not leak the token", !message.contains("test-app-token"))
    }

    @Test
    fun `execute refusal carries the backend error code and recovery action`() {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body(
                    "{\"status\":\"error\",\"error\":\"Mandatory backend readiness checks failed; task execution is disabled.\"," +
                        "\"error_code\":\"BACKEND_NOT_READY\"," +
                        "\"recovery_action\":\"Restore Upstash Redis and backend authentication readiness before retrying.\"}"
                )
                .build()
        )

        val result = client().execute("probe-2", "Fetch https://example.com")

        assertTrue(result is PlatformResult.Failure)
        val message = (result as PlatformResult.Failure).message
        assertTrue(message.contains("BACKEND_NOT_READY"))
        assertTrue(message.contains("Restore Upstash Redis"))
        val request = server.takeRequest()
        assertEquals("/execute", request.url.encodedPath)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"task_id\":\"probe-2\""))
        assertTrue(body.contains("https://example.com"))
    }

    @Test
    fun `agents list is parsed from the backend shape`() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(
                    "{\"agents\":[{\"id\":\"manager\",\"name\":\"Manager / Orchestrator\"," +
                        "\"capabilities\":[\"planning\",\"delegation\"]}]}"
                )
                .build()
        )

        val result = client().agents()

        assertTrue(result is PlatformResult.Success)
        val agents = (result as PlatformResult.Success).value
        assertEquals(1, agents.size)
        assertEquals("manager", agents[0].id)
        assertEquals(listOf("planning", "delegation"), agents[0].capabilities)
    }

    @Test
    fun `task state is read from the task endpoint`() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"task_id\":\"t1\",\"state\":\"RUNNING\"}").build())

        val result = client().task("t1")

        assertTrue(result is PlatformResult.Success)
        assertEquals("RUNNING", (result as PlatformResult.Success).value.state)
        assertEquals("/tasks/t1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `task id is percent encoded so it cannot inject a route`() {
        server.enqueue(MockResponse.Builder().code(404).body("{\"detail\":\"Unknown task\"}").build())

        client().task("a/../b")

        assertEquals("/tasks/a%2F..%2Fb", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `adapter maps backend states to task states`() = runBlocking {
        val adapter = OpenSwarmAdapter(AgentPlatformCatalog.openSwarm, client())
        val cases = mapOf(
            "QUEUED" to AgentTaskState.QUEUED,
            "RUNNING" to AgentTaskState.RUNNING,
            "PAUSED" to AgentTaskState.WAITING_FOR_INPUT,
            "COMPLETE" to AgentTaskState.COMPLETE,
            "FAILED" to AgentTaskState.FAILED,
            "CANCELLED" to AgentTaskState.CANCELLED,
            "SOMETHING_ELSE" to AgentTaskState.UNKNOWN,
        )
        cases.forEach { (backendState, expected) ->
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("{\"task_id\":\"t\",\"state\":\"$backendState\"}").build()
            )
            val result = adapter.getTask("t")
            assertTrue("$backendState should parse", result is PlatformResult.Success)
            assertEquals("$backendState maps", expected, (result as PlatformResult.Success).value)
        }
    }

    @Test
    fun `adapter declares file transfer unsupported instead of faking it`() = runBlocking {
        val adapter = OpenSwarmAdapter(AgentPlatformCatalog.openSwarm, client())

        assertTrue(adapter.listArtifacts("t") is PlatformResult.Unsupported)
        assertTrue(adapter.downloadArtifact("t", "a") is PlatformResult.Unsupported)
    }

    @Test
    fun `adapter registry returns no adapter for an unknown platform`() {
        assertNull(AgentPlatformAdapters.create("does_not_exist", "https://example.com", "t"))
    }

    @Test
    fun `every advertised platform id really builds an adapter`() {
        val notBuildable = AgentPlatformAdapters.supportedIds()
            .filter { AgentPlatformAdapters.create(it, "https://example.com", "t") == null }
        assertEquals(
            "supportedIds() advertises platforms that create() refuses: $notBuildable",
            emptyList<String>(),
            notBuildable,
        )
    }

    @Test
    fun `catalog advertises openswarm with only verified capabilities`() {
        val swarm = AgentPlatformCatalog.byId("openswarm")
        assertTrue("openswarm must be in the catalog", swarm != null)
        assertEquals(
            setOf(
                PlatformCapability.CREATE_SESSION,
                PlatformCapability.SUBMIT_TASK,
                PlatformCapability.OBSERVE_EVENTS,
                PlatformCapability.CANCEL_TASK,
            ),
            swarm!!.supported,
        )
        assertTrue(
            "File transfer must not be advertised: the backend has no such endpoints",
            PlatformCapability.UPLOAD_FILE !in swarm.supported &&
                PlatformCapability.DOWNLOAD_ARTIFACT !in swarm.supported,
        )
    }

    /**
     * Opt-in live check against a real backend, using the shipping client.
     *
     * Skipped unless `SWARM_E2E_BASE_URL` and `SWARM_E2E_TOKEN` are set. Run with the backend
     * from `cloud-backend/`:
     *
     * ```
     * APP_CLIENT_TOKEN=test-app-token uvicorn main:app --port 8000
     * SWARM_E2E_BASE_URL=http://127.0.0.1:8000 SWARM_E2E_TOKEN=test-app-token ./gradlew :app:testDebugUnitTest
     * ```
     */
    @Test
    fun `live backend accepts the token and reports honest readiness`() {
        val baseUrl = System.getenv("SWARM_E2E_BASE_URL")
        val token = System.getenv("SWARM_E2E_TOKEN")
        assumeTrue("Set SWARM_E2E_BASE_URL and SWARM_E2E_TOKEN to run", !baseUrl.isNullOrBlank() && !token.isNullOrBlank())

        val live = OpenSwarmClient(baseUrl!!, token!!)

        val validation = live.validateConnection()
        assertTrue("Live token should be accepted: $validation", validation is PlatformResult.Success)

        val agents = live.agents()
        assertTrue("Live /agents should answer: $agents", agents is PlatformResult.Success)
        assertTrue("The backend registers agents", (agents as PlatformResult.Success).value.isNotEmpty())

        // /health needs no token; its verdict is the backend's, so only its presence is asserted.
        val health = live.health()
        assertTrue("Live /health should answer: $health", health is PlatformResult.Success)
        val h = (health as PlatformResult.Success).value
        assertTrue("The backend reports its auth state", h.authReady)
    }
}
