package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentAuthorizationStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun registryWith(pluginId: String, declared: Set<PluginCapability>): PluginRegistry {
        val registry = PluginRegistry()
        registry.register(
            PluginRecord(
                id = pluginId,
                displayName = pluginId,
                publisher = "test",
                homepageUrl = "https://example.test",
                declaredCapabilities = declared,
            )
        )
        registry.approve(pluginId, declared)
        registry.install(pluginId)
        return registry
    }

    @Test
    fun unattendedGrantAuthorizesWithinScope() {
        val registry = registryWith("sync", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 1_000L })
        store.grant("sync", setOf(PluginCapability.NETWORK_FETCH), scope = GrantScope.UNATTENDED)
        val decision = store.isAuthorized("sync", PluginCapability.NETWORK_FETCH)
        assertTrue(decision.authorized)
        assertEquals(GrantRefusal.NONE, decision.reason)
    }

    @Test
    fun grantCannotWidenBeyondDeclaredCapabilities() {
        val registry = registryWith("read-only", setOf(PluginCapability.FILESYSTEM_READ))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        // Ask for CODE_EXECUTION too; it is not declared, so it must be dropped.
        val grant = store.grant(
            "read-only",
            setOf(PluginCapability.FILESYSTEM_READ, PluginCapability.CODE_EXECUTION),
        )
        assertEquals(setOf(PluginCapability.FILESYSTEM_READ), grant.capabilities)
        assertFalse(store.isAuthorized("read-only", PluginCapability.CODE_EXECUTION).authorized)
    }

    @Test
    fun grantExpiresAtConfiguredTime() {
        var now = 1_000L
        val registry = registryWith("timer", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { now })
        store.grant("timer", setOf(PluginCapability.NETWORK_FETCH), ttlMs = 60_000L)
        assertTrue(store.isAuthorized("timer").authorized)
        now = 61_000L
        val expired = store.isAuthorized("timer")
        assertFalse(expired.authorized)
        assertEquals(GrantRefusal.EXPIRED, expired.reason)
    }

    @Test
    fun expiryCanBeChangedAfterGrant() {
        var now = 0L
        val registry = registryWith("timer", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { now })
        store.grant("timer", setOf(PluginCapability.NETWORK_FETCH), ttlMs = 10_000L)
        store.setExpiry("timer", null) // never expires now
        now = 10_000_000L
        assertTrue(store.isAuthorized("timer").authorized)
    }

    @Test
    fun revokeIsImmediateAndReasonIsSpecific() {
        val registry = registryWith("sync", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant("sync", setOf(PluginCapability.NETWORK_FETCH))
        assertTrue(store.isAuthorized("sync").authorized)
        assertTrue(store.revoke("sync"))
        val after = store.isAuthorized("sync")
        assertFalse(after.authorized)
        assertEquals(GrantRefusal.REVOKED, after.reason)
    }

    @Test
    fun budgetExhaustsAfterMaxUses() {
        val registry = registryWith("metered", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant("metered", setOf(PluginCapability.NETWORK_FETCH), maxUses = 2)
        assertTrue(store.isAuthorized("metered").authorized)
        assertTrue(store.isAuthorized("metered").authorized)
        val third = store.isAuthorized("metered")
        assertFalse(third.authorized)
        assertEquals(GrantRefusal.BUDGET_EXHAUSTED, third.reason)
    }

    @Test
    fun grantSurvivesRestartThroughFileStore() {
        val file = temporaryFolder.newFile("grants.json")
        val registry = registryWith("persistent", setOf(PluginCapability.NETWORK_FETCH))

        val first = PersistentAuthorizationStore(grants = FilePluginGrantStore(file), pluginRegistry = registry, clock = { 0L })
        first.grant("persistent", setOf(PluginCapability.NETWORK_FETCH), ttlMs = 1_000_000L)

        // A brand-new store over the same file is the "after restart" case.
        val second = PersistentAuthorizationStore(grants = FilePluginGrantStore(file), pluginRegistry = registry, clock = { 5_000L })
        val grant = second.grantFor("persistent")
        assertNotNull(grant)
        assertTrue(second.isAuthorized("persistent").authorized)
    }

    @Test
    fun revokeSurvivesRestart() {
        val file = temporaryFolder.newFile("grants.json")
        val registry = registryWith("persistent", setOf(PluginCapability.NETWORK_FETCH))
        val first = PersistentAuthorizationStore(grants = FilePluginGrantStore(file), pluginRegistry = registry, clock = { 0L })
        first.grant("persistent", setOf(PluginCapability.NETWORK_FETCH))
        first.revoke("persistent")

        val second = PersistentAuthorizationStore(grants = FilePluginGrantStore(file), pluginRegistry = registry, clock = { 0L })
        assertEquals(GrantRefusal.REVOKED, second.isAuthorized("persistent").reason)
    }

    @Test
    fun revokeAllClearsEveryActiveGrant() {
        val registry = PluginRegistry().apply {
            listOf("a", "b").forEach {
                register(PluginRecord(it, it, "test", "https://example.test", declaredCapabilities = setOf(PluginCapability.NETWORK_FETCH)))
                approve(it, setOf(PluginCapability.NETWORK_FETCH))
            }
        }
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant("a", setOf(PluginCapability.NETWORK_FETCH))
        store.grant("b", setOf(PluginCapability.NETWORK_FETCH))
        assertEquals(2, store.revokeAll())
        assertTrue(store.active().isEmpty())
        assertFalse(store.isAuthorized("a").authorized)
        assertFalse(store.isAuthorized("b").authorized)
    }

    @Test
    fun missingGrantIsRefusedWithSpecificReason() {
        val store = PersistentAuthorizationStore(clock = { 0L })
        val decision = store.isAuthorized("never-granted")
        assertFalse(decision.authorized)
        assertEquals(GrantRefusal.NOT_GRANTED, decision.reason)
    }

    @Test
    fun registryRefusalPropagates() {
        val registry = registryWith("sync", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        store.grant("sync", setOf(PluginCapability.NETWORK_FETCH))
        // The owner disables the plugin; authorization must now fail even though the grant is live.
        registry.disable("sync")
        assertFalse(store.isAuthorized("sync").authorized)
    }

    @Test
    fun secondsUntilExpiryIsReported() {
        val registry = registryWith("timer", setOf(PluginCapability.NETWORK_FETCH))
        val store = PersistentAuthorizationStore(pluginRegistry = registry, clock = { 0L })
        val grant = store.grant("timer", setOf(PluginCapability.NETWORK_FETCH), ttlMs = 90_000L)
        assertEquals(90L, grant.secondsUntilExpiry(0L))
        assertNull(store.grant("never", setOf(PluginCapability.NETWORK_FETCH)).secondsUntilExpiry(0L))
    }
}
