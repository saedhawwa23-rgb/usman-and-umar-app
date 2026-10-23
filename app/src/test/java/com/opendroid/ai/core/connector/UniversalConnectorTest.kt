package com.opendroid.ai.core.connector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalConnectorTest {
    private val tool = ConnectorTool(
        connectorId = "cloudflare",
        name = "deploy",
        description = "Deploy a project",
        parametersJsonSchema = "{\"type\":\"object\"}",
        risk = ConnectorRisk.SENSITIVE,
        credential = ConnectorCredentialRef("cloudflare")
    )

    @Test
    fun `registry discovers qualified tools without provider-specific agent code`() {
        val registry = UniversalToolRegistry()
        registry.register(object : UniversalConnector {
            override val id = "cloudflare"
            override val displayName = "Cloudflare"
            override val kind = ConnectorKind.CLOUD
            override fun tools() = listOf(tool)
            override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver) =
                ConnectorResult(true, "deployment-id")
        })
        assertEquals(tool, registry.findTool("connector_cloudflare__deploy"))
        assertEquals(listOf(tool), registry.catalogTools.value)
        registry.unregister("cloudflare")
        assertTrue(registry.catalogTools.value.isEmpty())
    }

    @Test
    fun `sensitive connector actions require approval and are logged`() = runBlocking {
        val registry = UniversalToolRegistry()
        val log = InMemoryConnectorExecutionLog()
        registry.register(object : UniversalConnector {
            override val id = "cloudflare"
            override val displayName = "Cloudflare"
            override val kind = ConnectorKind.CLOUD
            override fun tools() = listOf(tool)
            override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver) =
                ConnectorResult(true, "must-not-run")
        })
        val result = registry.execute(ConnectorInvocation(tool = tool, argumentsJson = "{}"), object : ConnectorCredentialResolver {
            override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block("secret")
        }, DefaultConnectorApprovalGate, log)
        assertFalse(result.success)
        assertEquals("User approval required", result.error)
        assertEquals("approval_required", log.recent().single().status)
    }

    @Test
    fun `read-only connector actions are allowed`() = runBlocking {
        val readTool = tool.copy(risk = ConnectorRisk.READ_ONLY, credential = null)
        val registry = UniversalToolRegistry()
        registry.register(object : UniversalConnector {
            override val id = "cloudflare"
            override val displayName = "Cloudflare"
            override val kind = ConnectorKind.CLOUD
            override fun tools() = listOf(readTool)
            override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver) =
                ConnectorResult(true, "logs")
        })
        val result = registry.execute(ConnectorInvocation(tool = readTool, argumentsJson = "{}"), object : ConnectorCredentialResolver {
            override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
        })
        assertTrue(result.success)
        assertEquals("logs", result.output)
    }
}
