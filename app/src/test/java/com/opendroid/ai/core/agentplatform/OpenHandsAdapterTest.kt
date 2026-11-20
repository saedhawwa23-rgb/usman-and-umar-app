package com.opendroid.ai.core.agentplatform

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Proves the OpenHands adapter maps the Agent Server's own vocabulary onto [AgentTaskState] and
 * forwards only the operations the catalog declares.
 */
class OpenHandsAdapterTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    private fun adapter() = OpenHandsAdapter(
        platform = AgentPlatformCatalog.openHands,
        client = OpenHandsClient(server.url("/").toString().trimEnd('/'), "k"),
        workingDir = "/workspace",
    )

    @Test
    fun `execution status is mapped from the server's own strings`() = runBlocking {
        val cases = mapOf(
            "running" to AgentTaskState.RUNNING,
            "finished" to AgentTaskState.COMPLETE,
            "error" to AgentTaskState.FAILED,
            "awaiting_user_input" to AgentTaskState.WAITING_FOR_INPUT,
        )
        for ((serverStatus, expected) in cases) {
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("{\"id\":\"c1\",\"execution_status\":\"$serverStatus\"}").build(),
            )
            val state = adapter().getTask("c1")
            assertTrue("Expected success for $serverStatus", state is PlatformResult.Success)
            assertEquals("Wrong mapping for $serverStatus", expected, (state as PlatformResult.Success).value)
        }
    }

    @Test
    fun `an unrecognised execution status is UNKNOWN rather than coerced`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("{\"id\":\"c1\",\"execution_status\":\"something_new\"}").build(),
        )
        val state = adapter().getTask("c1")

        assertEquals(
            AgentTaskState.UNKNOWN,
            (state as PlatformResult.Success).value,
        )
    }

    @Test
    fun `cancellation posts to the interrupt endpoint`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val result = adapter().cancelTask("c1")

        assertTrue(result is PlatformResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/conversations/c1/interrupt", request.url.encodedPath)
    }

    @Test
    fun `artifact enumeration stays unsupported because the server cannot list files`() = runBlocking {
        val result = adapter().listArtifacts("c1")

        assertTrue(result is PlatformResult.Unsupported)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `submitting without a session is refused with a specific message`() = runBlocking {
        val result = adapter().submitTask("", "do work")

        assertTrue(result is PlatformResult.Failure)
        assertTrue(
            (result as PlatformResult.Failure).message.contains("session"),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the catalog and the adapter agree on which capabilities exist`() {
        val declared = AgentPlatformCatalog.openHands.supported
        assertTrue(PlatformCapability.CREATE_SESSION in declared)
        assertTrue(PlatformCapability.SUBMIT_TASK in declared)
        assertTrue(PlatformCapability.CANCEL_TASK in declared)
        // Declaring LIST_ARTIFACTS would promise an enumeration the server does not expose.
        assertTrue(
            "OpenHands must not declare LIST_ARTIFACTS",
            PlatformCapability.LIST_ARTIFACTS !in declared,
        )
    }
}
