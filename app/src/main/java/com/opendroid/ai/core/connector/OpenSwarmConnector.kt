package com.opendroid.ai.core.connector

import com.google.gson.JsonParser
import com.google.gson.Gson
import com.opendroid.ai.core.browser.CloudServerClient
import com.opendroid.ai.core.browser.OpenSwarmRemoteTool
import com.opendroid.ai.core.llm.ToolDefinition
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenSwarmConnector @Inject constructor(
    private val client: CloudServerClient,
) : UniversalConnector {
    override val id = "colab"
    override val displayName = "OpenSwarm Colab"
    override val kind = ConnectorKind.CLOUD
    @Volatile private var remoteTools: List<OpenSwarmRemoteTool> = emptyList()
    private val gson = Gson()

    fun updateTools(tools: List<OpenSwarmRemoteTool>) { remoteTools = tools.toList() }
    fun cachedTools(): List<OpenSwarmRemoteTool> = remoteTools

    override fun tools(): List<ConnectorTool> = listOf(discoveryTool) + OpenSwarmToolCatalog.specs.mapNotNull { spec ->
        val capability = remoteTools.firstOrNull { it.id == spec.capabilityId } ?: return@mapNotNull null
        if (!capability.adapterConfigured || capability.risk != "low") return@mapNotNull null
        ConnectorTool(
            connectorId = id,
            name = "${spec.functionName}",
            description = "${spec.description} Backend permissions=${capability.permissions.joinToString(",").ifBlank { "none declared" }}; backend risk=${capability.risk}.",
            parametersJsonSchema = spec.schema,
            risk = ConnectorRisk.READ_ONLY,
        )
    }

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult {
        if (invocation.tool.name == "discover_resources") {
            val listing = remoteTools.map { capability ->
                val supported = OpenSwarmToolCatalog.specs.firstOrNull { it.capabilityId == capability.id }
                val reason = when {
                    supported == null -> "no app parameter schema is available for this capability"
                    !capability.adapterConfigured -> capability.reason ?: "adapter is not configured"
                    capability.risk != "low" -> "risk=${capability.risk}; Phase 1 does not expose write or execution actions"
                    else -> "available as colab_${supported.functionName}"
                }
                mapOf("id" to capability.id, "description" to capability.description, "risk" to capability.risk,
                    "adapter_configured" to capability.adapterConfigured, "permissions" to capability.permissions, "availability" to reason)
            }
            return ConnectorResult(true, gson.toJson(mapOf("tools" to listing)))
        }
        val spec = OpenSwarmToolCatalog.specs.firstOrNull { "${it.functionName}" == invocation.tool.name }
            ?: return ConnectorResult(false, error = "Unrecognized OpenSwarm tool")
        val capability = remoteTools.firstOrNull { it.id == spec.capabilityId }
            ?: return ConnectorResult(false, error = "Colab capability is unavailable: ${spec.capabilityId}")
        if (!capability.adapterConfigured) return ConnectorResult(false, error = "Colab capability unavailable: ${capability.reason ?: "adapter not configured"}")
        if (capability.risk != "low") return ConnectorResult(false, error = "Risk ${capability.risk} capability is not exposed in Phase 1; user approval flow is not enabled.")
        val validated = runCatching { OpenSwarmToolCatalog.validate("colab_${spec.functionName}", invocation.argumentsJson) }
            .getOrElse { return ConnectorResult(false, error = it.message ?: "Invalid tool arguments") }
        val payload = JsonParser.parseString(validated).asJsonObject
        val response = client.executeOpenSwarmTool(spec.capabilityId, JSONObject(gson.toJson(payload)), approved = false)
        if (response.optString("status") != "executed") return ConnectorResult(false, error = response.toString())
        return ConnectorResult(true, response.opt("result")?.toString().orEmpty().take(24_000))
    }

    private val discoveryTool = ConnectorTool(
        connectorId = id,
        name = "discover_resources",
        description = "List OpenSwarm Colab tools, adapter readiness and unavailability reasons before planning.",
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
        risk = ConnectorRisk.READ_ONLY,
    )

    fun modelUnavailableSummary(): String = OpenSwarmToolCatalog.unavailableSummary(remoteTools)
    fun modelToolDefinitions(): List<ToolDefinition> = tools().map {
        ToolDefinition(it.qualifiedName, "${it.description} Risk=${it.risk}", it.parametersJsonSchema)
    }
}
