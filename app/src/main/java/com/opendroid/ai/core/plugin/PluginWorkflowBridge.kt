package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.DefaultConnectorApprovalGate
import com.opendroid.ai.core.connector.UniversalToolRegistry
import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult

/**
 * v3.10.4: the bridge that makes multi-plugin workflows genuinely executable.
 *
 * It projects every registered connector tool (a plugin's OpenAPI/MCP tools and the built-in
 * integrations) into the workspace [ToolRegistry] as `plugin_<toolName>` definitions, and it
 * executes a workflow step by routing the tool back through the existing
 * [UniversalToolRegistry.execute] path. That means the kill switch, the argument-size limit, the
 * plugin approval gate and the execution log all apply to a workflow step exactly as they do to a
 * tool the agent calls directly - a workflow gets no shortcut around the security core.
 *
 * `plugin_`-prefixed ids cannot collide with the built-in workflow tool ids, which are plain names.
 */
class PluginWorkflowBridge(
    private val tools: UniversalToolRegistry,
    private val credentials: com.opendroid.ai.core.connector.ConnectorCredentialResolver,
    private val approvalGate: com.opendroid.ai.core.connector.ConnectorApprovalGate = DefaultConnectorApprovalGate,
) {

    /** The workspace tool id a connector tool is exposed under in a workflow. */
    fun workflowToolId(connectorToolName: String): String = "plugin_$connectorToolName"

    /** A [ToolDefinition] per registered connector tool, so the workflow engine can validate steps. */
    fun toolDefinitions(): List<ToolDefinition> = tools.allTools().map { tool ->
        ToolDefinition(
            id = workflowToolId(tool.name),
            name = "${tool.connectorId}: ${tool.name}",
            description = tool.description,
            category = "plugin",
            inputSchema = tool.parametersJsonSchema,
            outputSchema = "{}",
            availability = com.usmanandumar.workspace.core.tool.ToolAvailability.AVAILABLE,
            requiresApproval = true,
        )
    }

    /** Register every connector tool into a workspace [ToolRegistry]. Idempotent per id. */
    fun registerInto(registry: ToolRegistry) {
        toolDefinitions().forEach { definition ->
            if (registry.get(definition.id) == null) registry.register(definition)
        }
    }

    /**
     * The executor the workflow engine calls. It resolves the `plugin_`-prefixed id back to the
     * connector tool and runs it through the shared registry, so every guard applies.
     */
    fun executor(): WorkflowToolExecutor = WorkflowToolExecutor { toolId, input ->
        val connectorToolName = toolId.removePrefix("plugin_")
        val tool = tools.allTools().firstOrNull { it.name == connectorToolName }
            ?: return@WorkflowToolExecutor WorkflowToolResult(
                success = false,
                errorCode = "UNKNOWN_TOOL",
                errorMessage = "No plugin tool is registered for '$toolId'.",
            )
        val argumentsJson = toJsonObject(input)
        val invocation = ConnectorInvocation(
            tool = tool,
            argumentsJson = argumentsJson,
            credential = tool.credential ?: ConnectorCredentialRef(id = tool.connectorId, kind = "api_key"),
        )
        val result = runCatching {
            tools.execute(invocation, credentials, approvalGate)
        }.getOrElse { return@WorkflowToolExecutor WorkflowToolResult(success = false, errorCode = "EXECUTION_ERROR", errorMessage = it.message ?: "plugin tool failed") }
        if (result.success) {
            WorkflowToolResult(success = true, output = mapOf("result" to result.output))
        } else {
            WorkflowToolResult(
                success = false,
                errorCode = "PLUGIN_TOOL_FAILED",
                errorMessage = result.error ?: "plugin tool returned a failure",
            )
        }
    }

    private fun toJsonObject(input: Map<String, String>): String {
        val json = com.google.gson.JsonObject()
        input.forEach { (key, value) -> json.addProperty(key, value) }
        return json.toString()
    }
}
