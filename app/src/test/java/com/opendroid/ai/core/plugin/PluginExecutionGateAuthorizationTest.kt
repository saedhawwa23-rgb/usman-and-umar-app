package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.ApprovalDecision
import com.opendroid.ai.core.connector.ConnectorApprovalGate
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.ConnectorTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The unattended grant has to be enforced at the boundary that actually runs a tool, not only in the
 * store. These tests drive the real [PluginExecutionGate] that sits in front of the connector
 * registry, so a revocation is proved to take effect on the next call.
 */
class PluginExecutionGateAuthorizationTest {

    private val alwaysAllow = object : ConnectorApprovalGate {
        override suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision = ApprovalDecision.ALLOW
    }

    private fun registryWithApprovedPlugin(pluginId: String): PluginRegistry {
        val registry = PluginRegistry()
        registry.register(
            PluginRecord(
                id = pluginId,
                displayName = pluginId,
                publisher = "test",
                homepageUrl = "https://example.test",
                declaredCapabilities = setOf(PluginCapability.NETWORK_FETCH),
            )
        )
        registry.approve(pluginId, setOf(PluginCapability.NETWORK_FETCH))
        registry.install(pluginId)
        return registry
    }

    private fun invocationFor(pluginId: String) = ConnectorInvocation(
        tool = ConnectorTool(
            connectorId = pluginId,
            name = "fetch",
            description = "fetch",
            parametersJsonSchema = """{"type":"object"}""",
        ),
        argumentsJson = "{}",
    )

    @Test
    fun approvedPluginWithoutGrantIsRefused() = runBlocking {
        val pluginId = "sync"
        val registry = registryWithApprovedPlugin(pluginId)
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        val gate = PluginExecutionGate(registry, store, alwaysAllow)

        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor(pluginId)))
        assertNotNull(gate.refusalReason(invocationFor(pluginId)))
    }

    @Test
    fun approvedPluginWithLiveGrantIsAllowed() = runBlocking {
        val pluginId = "sync"
        val registry = registryWithApprovedPlugin(pluginId)
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant(pluginId, setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED)
        val gate = PluginExecutionGate(registry, store, alwaysAllow)

        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocationFor(pluginId)))
        assertNull(gate.refusalReason(invocationFor(pluginId)))
    }

    @Test
    fun revokeStopsTheVeryNextCall() = runBlocking {
        val pluginId = "sync"
        val registry = registryWithApprovedPlugin(pluginId)
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant(pluginId, setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED)
        val gate = PluginExecutionGate(registry, store, alwaysAllow)
        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocationFor(pluginId)))

        store.revoke(pluginId)

        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor(pluginId)))
    }

    @Test
    fun expiredGrantIsRefusedAtTheGate() = runBlocking {
        val pluginId = "sync"
        var now = 0L
        val registry = registryWithApprovedPlugin(pluginId)
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { now })
        store.grant(pluginId, setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED, ttlMs = 1_000)
        val gate = PluginExecutionGate(registry, store, alwaysAllow)
        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocationFor(pluginId)))

        now = 5_000L

        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor(pluginId)))
    }

    @Test
    fun refusalReasonDoesNotConsumeTheBudget() = runBlocking {
        val pluginId = "sync"
        val registry = registryWithApprovedPlugin(pluginId)
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant(pluginId, setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED, maxUses = 2)
        val gate = PluginExecutionGate(registry, store, alwaysAllow)

        // Two status queries must not spend either of the two allowed uses.
        assertNull(gate.refusalReason(invocationFor(pluginId)))
        assertNull(gate.refusalReason(invocationFor(pluginId)))

        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocationFor(pluginId)))
        assertEquals(ApprovalDecision.ALLOW, gate.decide(invocationFor(pluginId)))
        // Budget is now spent.
        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor(pluginId)))
    }

    @Test
    fun nonPluginConnectorIsUnaffectedByTheGrantStore() = runBlocking {
        val registry = registryWithApprovedPlugin("sync")
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        val gate = PluginExecutionGate(registry, store, alwaysAllow)

        // A connector that is not a registered plugin keeps the delegate's decision.
        val direct = ConnectorInvocation(
            tool = ConnectorTool("builtin_time", "now", "now", """{"type":"object"}"""),
            argumentsJson = "{}",
        )
        assertEquals(ApprovalDecision.ALLOW, gate.decide(direct))
        assertNull(gate.refusalReason(direct))
    }

    @Test
    fun withDelegateRoutesNonPluginToolsToTheInteractiveGate() = runBlocking {
        val registry = registryWithApprovedPlugin("sync")
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        val denyEverything = object : ConnectorApprovalGate {
            override suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision = ApprovalDecision.DENY
        }
        val gate = PluginExecutionGate(registry, store, alwaysAllow).withDelegate(denyEverything)

        // A non-plugin tool is decided by the composed delegate, not by the plugin gate's default.
        val direct = ConnectorInvocation(
            tool = ConnectorTool("builtin_time", "now", "now", """{"type":"object"}"""),
            argumentsJson = "{}",
        )
        assertEquals(ApprovalDecision.DENY, gate.decide(direct))

        // A plugin tool still cannot bypass its grant, and then defers to the delegate.
        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor("sync")))
        store.grant("sync", setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED)
        assertEquals(ApprovalDecision.DENY, gate.decide(invocationFor("sync")))
    }
}
