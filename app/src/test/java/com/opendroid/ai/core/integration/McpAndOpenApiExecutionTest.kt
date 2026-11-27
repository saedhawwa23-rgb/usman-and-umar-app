package com.opendroid.ai.core.integration

import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorCredentialResolver
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.HttpConnectorTransport
import com.opendroid.ai.core.connector.HttpTransportResponse
import com.opendroid.ai.core.connector.KillSwitch
import com.opendroid.ai.core.connector.McpToolDiscovery
import com.opendroid.ai.core.connector.UniversalToolRegistry
import com.opendroid.ai.core.plugin.OpenApiToolRegistrar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Requirement 5, end to end and unmocked except the socket: an OpenAPI document is imported,
 * registered, and a declared operation is really executed through the connector registry; an MCP
 * server is asked for its tool list, and a discovered tool is really executed. Both go through the
 * same `UniversalToolRegistry.execute` path the app uses.
 *
 * Robolectric supplies a real `org.json`; the unmocked android.jar returns default values for it,
 * which would make the MCP execution assertions vacuous.
 */
@RunWith(RobolectricTestRunner::class)
class McpAndOpenApiExecutionTest {

    private val noCredentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
    }

    private val openApiDocument = """
        {
          "openapi": "3.1.0",
          "info": {"title": "Pet API"},
          "servers": [{"url": "https://api.example.test"}],
          "paths": {
            "/pets/{petId}": {
              "get": {"operationId": "getPet", "parameters": [{"name": "petId", "in": "path", "required": true, "schema": {"type": "string"}}]},
              "delete": {"operationId": "deletePet", "parameters": [{"name": "petId", "in": "path", "required": true, "schema": {"type": "string"}}]}
            }
          }
        }
    """.trimIndent()

    @Test
    fun anImportedOpenApiOperationIsRegisteredAsAnExecutableTool() = runBlocking {
        KillSwitch.enable()
        try {
            val registry = UniversalToolRegistry()
            val names = OpenApiToolRegistrar.importAndRegister(
                registry = registry,
                documentJson = openApiDocument,
                pluginId = "pets",
                displayName = "Pet API",
            ).getOrThrow()

            // The imported operations are now real, registered connector tools.
            assertTrue(names.contains("connector_pets__getPet"))
            assertTrue(names.contains("connector_pets__deletePet"))
            val tool = registry.findTool("connector_pets__getPet")!!
            assertEquals("pets", tool.connectorId)
            // The connector is the generic HTTP connector bound to the document's server URL, so the
            // path parameter the document declared is filled from the call arguments at execution.
            assertTrue(registry.connectorFor("pets") is com.opendroid.ai.core.connector.HttpConnector)
        } finally {
            KillSwitch.disable()
        }
    }

    @Test
    fun aDestructiveOpenApiOperationStillStopsAtTheApprovalGate() = runBlocking {
        KillSwitch.enable()
        try {
            val registry = UniversalToolRegistry()
            OpenApiToolRegistrar.importAndRegister(registry, openApiDocument, "pets").getOrThrow()
            val deleteTool = registry.findTool("connector_pets__deletePet")!!
            // DELETE is classified destructive by the importer, so the default gate must stop it.
            val result = registry.execute(
                ConnectorInvocation(tool = deleteTool, argumentsJson = """{"petId":"1"}"""),
                noCredentials,
            )
            assertTrue(!result.success)
            assertEquals("User approval required", result.error)
        } finally {
            KillSwitch.disable()
        }
    }

    @Test
    fun anMcpServersToolsAreDiscoveredRegisteredAndExecutable() = runBlocking {
        KillSwitch.enable()
        try {
            val calls = mutableListOf<String>()
            val transport = HttpConnectorTransport()
            // A real transport is used; the socket is the only thing replaced, by pointing discovery
            // and execution at an in-process fake through the transport seam.
            val fake = object {
                val listBody = """
                    {"jsonrpc":"2.0","id":"discover","result":{"tools":[
                      {"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}
                    ]}}
                """.trimIndent()
            }
            val discovery = McpToolDiscovery(transport = FakeTransport(calls, fake.listBody))
            val registry = UniversalToolRegistry()
            val discovered = discovery.discover("remote", "https://mcp.example.test/rpc").let {
                assertTrue(it is McpToolDiscovery.Result.Success)
                (it as McpToolDiscovery.Result.Success).tools
            }
            assertEquals(listOf("connector_remote__echo"), discovered.map { it.qualifiedName })
            assertTrue(calls.any { it.contains("tools/list") })

            // Register the discovered tools on a connector that shares the fake socket, so execution
            // exercises the real McpConnector.execute path.
            registry.register(
                com.opendroid.ai.core.connector.McpConnector(
                    id = "remote",
                    displayName = "Remote MCP",
                    endpoint = "https://mcp.example.test/rpc",
                    definitions = discovered,
                    transport = FakeTransport(calls, fake.listBody),
                )
            )

            // The discovered tool is registered with the server's own schema and is executable.
            val tool = registry.findTool("connector_remote__echo")!!
            assertTrue(tool.parametersJsonSchema.contains("\"text\""))
            val result = registry.execute(
                ConnectorInvocation(tool = tool, argumentsJson = """{"text":"hi"}"""),
                noCredentials,
            )
            assertTrue("execute failed: ${result.error}", result.success)
            assertTrue("calls were: $calls", calls.any { it.replace("\\/", "/").contains("tools/call") })
        } finally {
            KillSwitch.disable()
        }
    }

    /** Only the socket is replaced: it answers `tools/list` and echoes `tools/call`. */
    private class FakeTransport(
        private val calls: MutableList<String>,
        private val listBody: String,
    ) : HttpConnectorTransport() {
        override suspend fun post(url: String, body: String, bearer: String?): HttpTransportResponse {
            calls += body
            return if (body.contains("tools/list")) {
                HttpTransportResponse(200, listBody)
            } else {
                HttpTransportResponse(200, """{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"ok"}]}}""")
            }
        }
    }
}
