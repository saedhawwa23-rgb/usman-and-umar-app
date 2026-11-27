package com.opendroid.ai.core.integration

import com.google.gson.JsonParser
import com.opendroid.ai.core.connector.BuiltInHttpIntegration
import com.opendroid.ai.core.connector.BuiltInIntegrationIds
import com.opendroid.ai.core.connector.BuiltInTextIntegration
import com.opendroid.ai.core.connector.BuiltInTimeIntegration
import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorCredentialResolver
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.HttpFetchResponse
import com.opendroid.ai.core.connector.HttpFetchTransport
import com.opendroid.ai.core.connector.KillSwitch
import com.opendroid.ai.core.connector.UniversalToolRegistry
import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRunState
import com.usmanandumar.workspace.core.workflow.WorkflowStep
import com.usmanandumar.workspace.core.workflow.WorkflowStepRunState
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * End-to-end demonstration: a single workflow executes three real integrations in sequence, each
 * one a genuinely registered connector invoked through the same `UniversalToolRegistry.execute`
 * path the app uses at runtime. Nothing here is mocked except the HTTP socket, which is injected so
 * the test does not depend on the internet.
 *
 * The three steps are chained by output mapping, so the run only succeeds if each integration
 * really produced the value the next step consumed.
 */
class MultiPluginWorkflowDemoTest {

    private val noCredentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
    }

    @Test
    fun workflowRunsThreeRealIntegrationsInSequence() = runBlocking {
        // The connector path is fail-closed behind the process-wide kill switch; enable it for the
        // duration of the demo, as the app does when the owner turns automation on.
        KillSwitch.enable()
        try {
        // 1. Register three real built-in integrations into the app's connector registry.
        val connectors = UniversalToolRegistry()
        val requestedUrls = mutableListOf<String>()
        connectors.register(BuiltInTimeIntegration(clock = { 1_700_000_000_000L }, zone = { ZoneId.of("UTC") }))
        connectors.register(BuiltInTextIntegration())
        connectors.register(
            BuiltInHttpIntegration(transport = HttpFetchTransport { url, _ ->
                requestedUrls += url
                HttpFetchResponse(200, """{"url":"$url","ok":true}""")
            })
        )

        // The workflow engine resolves tools by id; mirror the connector tools into a ToolRegistry
        // using each tool's real qualified name, so the engine's tool id is the connector's name.
        val toolRegistry = ToolRegistry(
            connectors.allTools().map {
                ToolDefinition(it.qualifiedName, it.name, it.description, it.connectorId)
            }
        )
        fun qualified(connectorId: String, tool: String) =
            connectors.allTools().first { it.connectorId == connectorId && it.name == tool }.qualifiedName

        // The executor is the real connector path: registry lookup + execute, no shortcuts.
        val executedOrder = mutableListOf<String>()
        val executor = WorkflowToolExecutor { toolId, input ->
            val tool = connectors.findTool(toolId)
                ?: return@WorkflowToolExecutor WorkflowToolResult(false, errorCode = "UNKNOWN_TOOL", errorMessage = toolId)
            executedOrder += toolId
            val result = connectors.execute(ConnectorInvocation(tool = tool, argumentsJson = input.toJson()), noCredentials)
            if (result.success) {
                val obj = JsonParser.parseString(result.output).asJsonObject
                WorkflowToolResult(true, obj.entrySet().associate { (k, v) -> k to v.asString })
            } else {
                WorkflowToolResult(false, errorCode = "CONNECTOR_FAILED", errorMessage = result.error)
            }
        }

        val engine = WorkflowEngine(toolRegistry, executor)
        val workflow = WorkflowDefinition(
            id = "demo-three-integrations",
            name = "Three integrations in sequence",
            steps = listOf(
                WorkflowStep(id = "clock", toolId = qualified(BuiltInIntegrationIds.TIME, "now")),
                WorkflowStep(
                    id = "label",
                    toolId = qualified(BuiltInIntegrationIds.TEXT, "transform"),
                    dependsOn = listOf("clock"),
                    inputMappings = mapOf(
                        "text" to "clock.iso",
                        "operation" to "${WorkflowEngine.LITERAL_PREFIX}slug",
                    ),
                ),
                WorkflowStep(
                    id = "check",
                    toolId = qualified(BuiltInIntegrationIds.HTTP, "status"),
                    dependsOn = listOf("label"),
                    // The URL is built from the text step's output, so the http step only runs with
                    // a value the text step really produced.
                    inputMappings = mapOf("url" to "${WorkflowEngine.LITERAL_PREFIX}https://example.test/{label.result}"),
                ),
            ),
        )

        val run = engine.run(workflow)

        assertEquals(WorkflowRunState.SUCCEEDED, run.state)
        assertEquals(3, run.steps.size)
        assertTrue(run.steps.all { it.state == WorkflowStepRunState.SUCCEEDED })
        // Sequence is deterministic: clock, then text, then http.
        assertEquals(
            listOf(
                qualified(BuiltInIntegrationIds.TIME, "now"),
                qualified(BuiltInIntegrationIds.TEXT, "transform"),
                qualified(BuiltInIntegrationIds.HTTP, "status"),
            ),
            executedOrder,
        )
        // Each integration really produced a value, and the http step really ran.
        assertTrue(run.step("clock")?.output?.get("epochMs").orEmpty().isNotBlank())
        assertTrue(run.step("label")?.output?.get("result").orEmpty().isNotBlank())
        assertEquals("200", run.step("check")?.output?.get("status"))
        // The URL the HTTP integration actually fetched was built from the text step's output.
        val labelResult = run.step("label")?.output?.get("result").orEmpty()
        assertEquals(listOf("https://example.test/$labelResult"), requestedUrls)
        } finally {
            KillSwitch.disable()
        }
    }

    private fun Map<String, String>.toJson(): String {
        val obj = com.google.gson.JsonObject()
        forEach { (k, v) -> obj.addProperty(k, v) }
        return obj.toString()
    }
}
