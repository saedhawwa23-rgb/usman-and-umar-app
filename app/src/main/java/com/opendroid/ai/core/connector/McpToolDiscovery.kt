package com.opendroid.ai.core.connector

import com.google.gson.JsonParser

/**
 * Requirement 5, MCP half: talk to an external MCP server, discover the tools it offers, and
 * register them so they can be executed.
 *
 * Discovery is a JSON-RPC `tools/list` call. Execution reuses [McpConnector], which issues
 * `tools/call` over the same endpoint and returns the server's JSON result. Once registered, an MCP
 * tool is invoked through [UniversalToolRegistry.execute], so the kill switch, the approval gate and
 * the execution log apply exactly as they do to every other connector.
 */
class McpToolDiscovery(
    private val transport: HttpConnectorTransport = HttpConnectorTransport(),
) {

    sealed interface Result {
        data class Success(val tools: List<ConnectorTool>) : Result
        data class Failure(val reason: String) : Result
    }

    /** Calls `tools/list` on [endpoint] and maps each remote tool into a [ConnectorTool]. */
    suspend fun discover(
        connectorId: String,
        endpoint: String,
        bearer: String? = null,
    ): Result {
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            return Result.Failure("MCP endpoint must be an http(s) URL")
        }
        val request = """
            {"jsonrpc":"2.0","id":"discover","method":"tools/list","params":{}}
        """.trimIndent()
        val response = runCatching { transport.post(endpoint, request, bearer) }
            .getOrElse { return Result.Failure("MCP discovery request failed: ${it.message ?: it::class.simpleName}") }
        if (response.code !in 200..299) {
            return Result.Failure("MCP server returned HTTP ${response.code}")
        }
        val root = runCatching { JsonParser.parseString(response.body).asJsonObject }
            .getOrElse { return Result.Failure("MCP response was not a JSON object") }
        root.getAsJsonObject("error")?.let {
            return Result.Failure("MCP error: ${it.get("message")?.asString ?: it.toString()}")
        }
        val remoteTools = root.getAsJsonObject("result")?.getAsJsonArray("tools")
            ?: return Result.Failure("MCP response had no result.tools array")
        val tools = remoteTools.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ConnectorTool(
                connectorId = connectorId,
                name = name,
                description = obj.get("description")?.asString ?: "MCP tool $name",
                // Copy the server's declared input schema through unchanged; a server that omits it
                // gets an empty object schema rather than an invented one.
                parametersJsonSchema = obj.get("inputSchema")?.toString() ?: "{}",
                risk = ConnectorRisk.READ_ONLY,
            )
        }
        if (tools.isEmpty()) return Result.Failure("MCP server declared no tools")
        return Result.Success(tools)
    }

    /** Discovers and registers in one step. Returns the qualified names now available. */
    suspend fun discoverAndRegister(
        registry: UniversalToolRegistry,
        connectorId: String,
        displayName: String,
        endpoint: String,
        bearer: String? = null,
    ): kotlin.Result<List<String>> = when (val result = discover(connectorId, endpoint, bearer)) {
        is Result.Success -> {
            registry.registerMcp(connectorId, displayName, endpoint, result.tools)
            kotlin.Result.success(result.tools.map { it.qualifiedName })
        }
        is Result.Failure -> kotlin.Result.failure(IllegalStateException(result.reason))
    }
}
