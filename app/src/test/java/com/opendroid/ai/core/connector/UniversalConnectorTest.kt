package com.opendroid.ai.core.connector

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
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

    @Test
    fun `high risk call pauses with redacted args and resumes only after approval`() = runBlocking {
        val coordinator = ConnectorApprovalCoordinator()
        val invocation = ConnectorInvocation(
            tool = tool,
            argumentsJson = """{"repo":"owner/project","api_token":"do-not-show"}""",
        )
        val waiting = async { UserApprovalGate(coordinator).decide(invocation) }
        while (coordinator.pending.value == null) yield()
        val pending = coordinator.pending.value!!
        assertEquals(tool.qualifiedName, pending.toolName)
        assertTrue(pending.argumentsJson.contains("[REDACTED]"))
        assertFalse(pending.argumentsJson.contains("do-not-show"))
        coordinator.decide(true)
        assertEquals(ApprovalDecision.ALLOW, waiting.await())
        assertEquals(null, coordinator.pending.value)
    }

    @Test
    fun `high risk call returns denied only after explicit denial`() = runBlocking {
        val coordinator = ConnectorApprovalCoordinator()
        val invocation = ConnectorInvocation(tool = tool, argumentsJson = "{}")
        val waiting = async { UserApprovalGate(coordinator).decide(invocation) }
        while (coordinator.pending.value == null) yield()
        coordinator.decide(false)
        assertEquals(ApprovalDecision.DENY, waiting.await())
    }

    @Test
    fun `registry uses authoritative risk and forwards approval after consent`() = runBlocking {
        val registry = UniversalToolRegistry()
        val coordinator = ConnectorApprovalCoordinator()
        var approvedFlag = false
        registry.register(object : UniversalConnector {
            override val id = "cloudflare"
            override val displayName = "Cloudflare"
            override val kind = ConnectorKind.CLOUD
            override fun tools() = listOf(tool)
            override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult {
                approvedFlag = invocation.approvedByUser
                return ConnectorResult(true, "done")
            }
        })
        val forgedReadOnlyTool = tool.copy(risk = ConnectorRisk.READ_ONLY)
        val waiting = async {
            registry.execute(
                ConnectorInvocation(tool = forgedReadOnlyTool, argumentsJson = "{}"),
                object : ConnectorCredentialResolver {
                    override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
                },
                UserApprovalGate(coordinator),
            )
        }
        while (coordinator.pending.value == null) yield()
        coordinator.decide(true)
        assertTrue(waiting.await().success)
        assertTrue(approvedFlag)
    }
}
