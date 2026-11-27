package com.opendroid.ai.core.capability

import com.opendroid.ai.core.agent.AgentTool
import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool

/**
 * Projects the existing registries into [CapabilityRegistry]'s canonical vocabulary.
 *
 * The app has three registries that predate this catalog:
 *
 *  - [AgentTool] in `core/agent/AgentToolRegistry` — native actions the on-device agent can run.
 *  - [ConnectorTool] in `core/connector/UniversalToolRegistry` — MCP/OpenAPI/OpenSwarm tools.
 *  - the workflow `ToolRegistry` in `com.usmanandumar.workspace.core.tool` — workflow definitions.
 *
 * They stay authoritative for *execution*: this adapter never dispatches anything, and it never
 * bypasses a plugin grant, connector approval, Android permission or kill switch. It only answers
 * "which capability does this tool belong to, and is that capability usable right now", so the
 * catalog cannot drift from what the registries actually expose.
 */
object CapabilityAdapters {

    /**
     * Maps a connector tool to the capability that owns it.
     *
     * Ownership is derived from the tool's own risk and credential declarations rather than from a
     * name table, so a newly registered connector lands in the right place without editing this
     * file. A tool that needs a credential is gated on that credential; a tool that mutates is
     * never reported READ_ONLY.
     */
    fun capabilityFor(tool: ConnectorTool): Capability {
        val requirement = when {
            tool.credential != null -> CapabilityRequirement.CREDENTIAL
            tool.connectorId == "colab" -> CapabilityRequirement.CLOUD_SERVER
            else -> CapabilityRequirement.NONE
        }
        val mutating = tool.risk != ConnectorRisk.READ_ONLY
        return Capability(
            id = "plugin.${tool.qualifiedName}",
            title = tool.description.ifBlank { tool.qualifiedName },
            module = if (tool.connectorId == "colab") CapabilityModule.OPENSWARM else CapabilityModule.PLUGINS,
            requirement = requirement,
            navigationRoute = "plugin_platform",
            mutating = mutating,
        )
    }

    /** All connector tools, projected. Stable order so the catalog is diffable between builds. */
    fun fromConnectorTools(tools: Iterable<ConnectorTool>): List<Capability> =
        tools.map(::capabilityFor).sortedBy { it.id }

    /**
     * The catalog merged with every registered connector tool. A connector whose canonical name is
     * already a reviewed catalog id is skipped rather than allowed to shadow it; [collisions]
     * reports those, so a genuine clash is visible instead of silently overriding the catalog.
     */
    fun catalogWith(tools: Iterable<ConnectorTool>): List<Capability> {
        val shadowed = collisions(tools).toSet()
        val dynamic = fromConnectorTools(tools).filterNot { it.id.removePrefix("plugin.") in shadowed }
        val staticIds = CapabilityRegistry.all().map { it.id }.toSet()
        return CapabilityRegistry.all() + dynamic.filter { it.id !in staticIds }
    }

    /**
     * Connector tool names that shadow a reviewed catalog id. Empty in a healthy build.
     *
     * Compared against the *unprefixed* tool name: the `plugin.` prefix keeps the two namespaces
     * from colliding at the id level, so comparing prefixed ids would never report anything.
     */
    fun collisions(tools: Iterable<ConnectorTool>): List<String> {
        val staticIds = CapabilityRegistry.all().map { it.id }.toSet()
        return tools.map { it.qualifiedName }.filter { it in staticIds }.distinct()
    }

    /**
     * Canonical ids for the native agent tools, which are fixed at compile time. Kept as data so
     * the parity test can assert the catalog covers every native action the agent can take.
     */
    fun nativeAgentCapabilityIds(): List<String> = listOf(
        "agent.list-packages",
        "agent.device-property",
        "agent.battery-status",
        "agent.network-status",
        "agent.screen-brightness",
        "agent.display-size",
        "agent.run-linux",
        "agent.open-app",
        "agent.open-url",
        "agent.use-local-app",
    )
}
