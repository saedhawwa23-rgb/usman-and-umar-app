package com.opendroid.ai.core.agentplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end test against a genuinely running OpenHands agent server.
 *
 * This test is skipped unless the operator supplies a live server, because a real run needs a
 * reachable server and a working LLM credential:
 *
 * ```
 * OH_E2E_BASE_URL=http://127.0.0.1:60000 \
 * OH_E2E_SESSION_KEY=<session key> \
 * ./gradlew :app:testDebugUnitTest --tests '*OpenHandsLiveE2ETest*'
 * ```
 *
 * It is not a mock test. When it runs, it creates a real conversation, sends real instructions,
 * waits for real execution, and asserts a real file was produced on the server's filesystem.
 */
class OpenHandsLiveE2ETest {

    private val baseUrl: String? = System.getenv("OH_E2E_BASE_URL")
    private val sessionKey: String? = System.getenv("OH_E2E_SESSION_KEY")

    /**
     * The server's own agent settings are authoritative for a self-hosted deployment, so the app
     * reuses them unchanged. When the operator needs a different LLM than the server's default
     * (for example a key with different model access), the E2E harness can override it.
     */
    private fun withLlmOverride(settings: JsonObject): JsonObject {
        val model = System.getenv("OH_E2E_LLM_MODEL")
        val apiKey = System.getenv("OH_E2E_LLM_API_KEY")
        val base = System.getenv("OH_E2E_LLM_BASE_URL")
        if (model.isNullOrBlank() && apiKey.isNullOrBlank() && base.isNullOrBlank()) return settings
        val llm = (settings["llm"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!model.isNullOrBlank()) llm["model"] = JsonPrimitive(model)
        if (!apiKey.isNullOrBlank()) llm["api_key"] = JsonPrimitive(apiKey)
        if (!base.isNullOrBlank()) llm["base_url"] = JsonPrimitive(base)
        return JsonObject(settings.toMutableMap().apply { put("llm", JsonObject(llm)) })
    }

    private fun requireServer(): Pair<String, String> {
        assumeTrue("OH_E2E_BASE_URL not set; skipping live E2E", !baseUrl.isNullOrBlank())
        assumeTrue("OH_E2E_SESSION_KEY not set; skipping live E2E", !sessionKey.isNullOrBlank())
        return baseUrl!! to sessionKey!!
    }

    @Test
    fun `discover connect submit monitor and retrieve real result`() {
        val (url, key) = requireServer()
        val client = OpenHandsClient(url, key)

        // 1. Connect: the key must be accepted by the real server.
        val validated = client.validateConnection()
        assertTrue("Connection failed: $validated", validated is PlatformResult.Success)

        // 2. Discover: the server reports its own agent settings, which the app reuses verbatim.
        val settings = client.fetchAgentSettings()
        assertTrue("Settings unavailable: $settings", settings is PlatformResult.Success)
        val settingsObject = withLlmOverride((settings as PlatformResult.Success).value)
        assertTrue("agent_settings must be a JSON object", settingsObject is JsonObject)

        // 3. Submit: create a real conversation in a scratch workspace with a unique marker.
        val marker = "UU_E2E_${System.currentTimeMillis()}"
        val workDir = File(System.getProperty("java.io.tmpdir"), "uu-e2e-$marker").apply { mkdirs() }
        val prompt =
            "Create a file named result.txt in the current directory containing exactly $marker " +
                "and nothing else. Then reply with the single word DONE."

        val created = client.startConversation(prompt, workDir.absolutePath, settingsObject, run = true)
        assertTrue("Conversation creation failed: $created", created is PlatformResult.Success)
        val conversation = (created as PlatformResult.Success).value
        assertTrue("Server returned no conversation id", conversation.id.isNotBlank())

        // 4. Monitor: poll real execution status until it reaches a terminal state.
        var status = ""
        var events: List<OhEvent> = emptyList()
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            val current = client.getConversation(conversation.id)
            assertTrue("Polling failed: $current", current is PlatformResult.Success)
            status = (current as PlatformResult.Success).value.executionStatus.orEmpty()
            val eventResult = client.searchEvents(conversation.id)
            if (eventResult is PlatformResult.Success) events = eventResult.value
            if (status in setOf("finished", "error", "stopped")) break
            Thread.sleep(3_000)
        }
        assertEquals("Execution did not finish cleanly. Events: ${events.map { it.summary() }}", "finished", status)
        assertTrue("No events were recorded", events.isNotEmpty())

        // 5. Retrieve: the final answer comes from the server, and the file exists on disk.
        val finalResponse = client.finalResponse(conversation.id)
        assertTrue("Final response failed: $finalResponse", finalResponse is PlatformResult.Success)
        assertEquals("DONE", (finalResponse as PlatformResult.Success).value.trim())

        val produced = File(workDir, "result.txt")
        assertTrue("The agent did not create result.txt", produced.exists())
        assertEquals(marker, produced.readText().trim())

        // A real tool execution must appear in the timeline, proving the agent acted.
        assertTrue(
            "Expected a terminal action event in the timeline",
            events.any { it.kind == "ActionEvent" },
        )
        println("E2E OK conversation=${conversation.id} marker=$marker events=${events.size}")
    }

    @Test
    fun `bad credentials surface the server error instead of a false success`() {
        val (url, _) = requireServer()
        val client = OpenHandsClient(url, "definitely-not-a-valid-session-key")
        val result = client.validateConnection()
        assertTrue("A bad key must fail, got $result", result is PlatformResult.Failure)
        val message = (result as PlatformResult.Failure).message
        assertTrue("Failure must carry a server status, got: $message", message.contains("HTTP"))
    }

    @Test
    fun `catalog only advertises capabilities the live server actually supports`() {
        val (url, key) = requireServer()
        val client = OpenHandsClient(url, key)
        val platform = AgentPlatformCatalog.openHands
        assertTrue(platform.supported.contains(PlatformCapability.SUBMIT_TASK))

        // Creating a conversation proves CREATE_SESSION and SUBMIT_TASK are real.
        val rawSettings = (client.fetchAgentSettings() as? PlatformResult.Success)?.value
        assertNotNull("Settings required to verify capabilities", rawSettings)
        val settings = withLlmOverride(rawSettings!!)
        val dir = File(System.getProperty("java.io.tmpdir"), "uu-cap-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        val created = client.startConversation("Reply with OK.", dir.absolutePath, settings, run = false)
        assertTrue("CREATE_SESSION advertised but failed: $created", created is PlatformResult.Success)
    }

    private fun JsonObject.selfOrNull(): JsonObject = Json.parseToJsonElement(toString()).jsonObject
}
