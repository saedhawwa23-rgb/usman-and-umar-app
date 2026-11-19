package com.opendroid.ai.core.capability

import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityAdaptersTest {

    private fun tool(
        connectorId: String = "sheets",
        name: String = "append_row",
        risk: ConnectorRisk = ConnectorRisk.READ_ONLY,
        credential: ConnectorCredentialRef? = null,
    ) = ConnectorTool(
        connectorId = connectorId,
        name = name,
        description = "Append a row",
        parametersJsonSchema = "{}",
        risk = risk,
        credential = credential,
    )

    @Test
    fun `connector tool maps to a plugin capability under its qualified name`() {
        val cap = CapabilityAdapters.capabilityFor(tool())
        assertEquals("plugin.connector_sheets__append_row", cap.id)
        assertEquals(CapabilityModule.PLUGINS, cap.module)
    }

    @Test
    fun `read only tool is not marked mutating`() {
        assertFalse(CapabilityAdapters.capabilityFor(tool(risk = ConnectorRisk.READ_ONLY)).mutating)
    }

    @Test
    fun `sensitive tool is marked mutating`() {
        assertTrue(CapabilityAdapters.capabilityFor(tool(risk = ConnectorRisk.SENSITIVE)).mutating)
    }

    @Test
    fun `credentialled tool requires a credential`() {
        val cap = CapabilityAdapters.capabilityFor(
            tool(credential = ConnectorCredentialRef("sheets_oauth")),
        )
        assertEquals(CapabilityRequirement.CREDENTIAL, cap.requirement)
    }

    @Test
    fun `colab tools are owned by openswarm and need a cloud server`() {
        val cap = CapabilityAdapters.capabilityFor(tool(connectorId = "colab", name = "run"))
        assertEquals(CapabilityModule.OPENSWARM, cap.module)
        assertEquals(CapabilityRequirement.CLOUD_SERVER, cap.requirement)
    }

    @Test
    fun `catalogWith appends connector capabilities to the reviewed catalog`() {
        val merged = CapabilityAdapters.catalogWith(listOf(tool()))
        assertEquals(CapabilityRegistry.all().size + 1, merged.size)
        assertTrue(merged.any { it.id == "plugin.connector_sheets__append_row" })
    }

    @Test
    fun `a connector cannot silently override a reviewed catalog entry`() {
        // Ordinary tools live in the plugin namespace and add cleanly.
        assertTrue(CapabilityAdapters.collisions(listOf(tool(connectorId = "ai", name = "providers"))).isEmpty())

        // A tool whose canonical name is a reviewed catalog id is reported, and is not merged,
        // so a connector can never shadow a reviewed definition.
        val staticId = CapabilityRegistry.all().first().id
        val forced = tool(connectorId = "x", name = "y").copy(toolNameOverride = staticId)
        assertEquals(listOf(staticId), CapabilityAdapters.collisions(listOf(forced)))
        val merged = CapabilityAdapters.catalogWith(listOf(forced))
        assertEquals(CapabilityRegistry.all().size, merged.size)
        assertEquals(CapabilityRegistry.all().map { it.id }, merged.map { it.id })
    }

    @Test
    fun `every native agent action has a capability id`() {
        val ids = CapabilityAdapters.nativeAgentCapabilityIds()
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.startsWith("agent.") })
    }

    @Test
    fun `adapter output is stable regardless of input order`() {
        val a = tool(name = "a")
        val b = tool(name = "b")
        assertEquals(
            CapabilityAdapters.fromConnectorTools(listOf(a, b)).map { it.id },
            CapabilityAdapters.fromConnectorTools(listOf(b, a)).map { it.id },
        )
    }
}
