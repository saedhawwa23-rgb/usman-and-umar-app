package com.opendroid.ai.core.agentplatform

import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorCredentialResolver
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress

/**
 * Exercises [McpPlatformAdapter] against a real MCP server, so the discovery and call shapes are
 * produced by production code.
 */
@RunWith(RobolectricTestRunner::class)
class McpPlatformAdapterTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    private val noCredentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(
            ref: ConnectorCredentialRef?,
            block: suspend (String?) -> T,
        ): T = block(null)
    }

    private fun adapter() = McpPlatformAdapter(
        platform = AgentPlatformCatalog.mcpServer,
        endpoint = server.url("/").toString().trimEnd('/'),
        credentials = noCredentials,
    )

    @Test
    fun `validateConnection performs a real tools-list call`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("{\"jsonrpc\":\"2.0\",\"id\":\"discover\",\"result\":{\"tools\":[{\"name\":\"search\"}]}}")
                .build(),
        )

        val result = adapter().validateConnection()

        assertTrue("Discovery should succeed on a real tools/list response", result is PlatformResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(
            "Body must be a tools/list call: ${request.body?.utf8()}",
            request.body?.utf8().orEmpty().contains("\"tools/list\""),
        )
    }

    @Test
    fun `a tool call is a single submission and reports COMPLETE`() = runBlocking {
        // tools/list is not needed for submit; the connector posts tools/call directly.
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"content\":[]}}")
                .build(),
        )

        // One adapter instance: an MCP call has no server-side task, so the result is remembered
        // by the adapter that made the call.
        val adapter = adapter()
        val submitted = adapter.submitTask("", "search {\"q\":\"kotlin\"}")

        assertTrue(
            "A 2xx tools/call should be accepted, got: $submitted",
            submitted is PlatformResult.Success,
        )
        val taskId = (submitted as PlatformResult.Success).value
        val state = adapter.getTask(taskId)
        assertEquals(AgentTaskState.COMPLETE, (state as PlatformResult.Success).value)
    }

    @Test
    fun `an empty prompt is refused before any request`() = runBlocking {
        val result = adapter().submitTask("", "   ")

        assertTrue(result is PlatformResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `session and cancel are unsupported rather than simulated`() = runBlocking {
        val a = adapter()
        assertTrue(a.createSession() is PlatformResult.Unsupported)
        assertTrue(a.cancelTask("x") is PlatformResult.Unsupported)
        assertTrue(a.observeTaskEvents("x", 0) is PlatformResult.Unsupported)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unknown invocation is reported, not guessed`() = runBlocking {
        val result = adapter().getTask("mcp-999")

        assertTrue(result is PlatformResult.Failure)
    }

    @Test
    fun `the catalog declares only the capability MCP really has`() {
        assertEquals(
            setOf(PlatformCapability.SUBMIT_TASK),
            AgentPlatformCatalog.mcpServer.supported,
        )
    }
}
