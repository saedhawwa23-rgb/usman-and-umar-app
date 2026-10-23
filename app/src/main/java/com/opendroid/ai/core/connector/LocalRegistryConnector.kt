package com.opendroid.ai.core.connector

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Read-only tools for inspecting the app's locally registered connectors.
 * These tools execute on-device, but are selected by the configured provider
 * through the normal cloud tool-calling loop.
 */
class LocalRegistryConnector(
    private val registry: UniversalToolRegistry
) : UniversalConnector {
    override val id: String = "local_registry"
    override val displayName: String = "Registered Tool Catalog"
    override val kind: ConnectorKind = ConnectorKind.CLI

    override fun tools(): List<ConnectorTool> = listOf(
        ConnectorTool(
            connectorId = id,
            name = "list_available_tools",
            description = "List all tools currently registered on this device. Read-only and local.",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
            risk = ConnectorRisk.READ_ONLY,
            category = "Developer Tools"
        ),
        ConnectorTool(
            connectorId = id,
            name = "list_connectors",
            description = "List all locally registered universal connectors. Read-only and local.",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
            risk = ConnectorRisk.READ_ONLY,
            category = "Developer Tools"
        )
    )

    override suspend fun execute(
        invocation: ConnectorInvocation,
        credentials: ConnectorCredentialResolver
    ): ConnectorResult {
        val payload = when (invocation.tool.name) {
            "list_available_tools" -> buildJsonObject {
                put("source", "USMAN AND UMAR tool registry")
                putJsonArray("tools") {
                    registry.allTools()
                        .filterNot { it.connectorId == id }
                        .forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.qualifiedName)
                                put("connector", tool.connectorId)
                                put("description", tool.description)
                                put("risk", tool.risk.name)
                            })
                        }
                }
            }
            "list_connectors" -> buildJsonObject {
                put("source", "USMAN AND UMAR tool registry")
                putJsonArray("connectors") {
                    registry.all().forEach { connector ->
                        add(buildJsonObject {
                            put("id", connector.id)
                            put("name", connector.displayName)
                            put("kind", connector.kind.name)
                            put("toolCount", connector.tools().size)
                        })
                    }
                }
            }
            else -> return ConnectorResult(false, error = "Registry introspection tool is not allowlisted")
        }
        return ConnectorResult(success = true, output = payload.toString())
    }
}
