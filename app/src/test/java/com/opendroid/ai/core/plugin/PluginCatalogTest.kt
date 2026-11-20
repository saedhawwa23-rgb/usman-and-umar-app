package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real catalog and platform service, so a catalog entry is proven to register as
 * UNAPPROVED and a connect is proven to go through install → approve → grant rather than bypassing
 * the registry.
 */
class PluginCatalogTest {

    @Test
    fun `every catalog entry registers as discovered and unapproved`() {
        val registry = PluginRegistry()
        PluginCatalog.seed(registry)

        assertTrue(PluginCatalog.all().isNotEmpty())
        PluginCatalog.all().forEach { entry ->
            val stored = registry.get(entry.id)
            assertTrue("missing ${entry.id}", stored != null)
            assertEquals(PluginApprovalState.UNAPPROVED, stored!!.approvalState)
            assertEquals(PluginInstallState.DISCOVERED, stored.installState)
            assertTrue(stored.grantedCapabilities.isEmpty())
            assertFalse(stored.isCallable)
        }
    }

    @Test
    fun `catalog ids are unique and lower-case`() {
        val ids = PluginCatalog.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id -> assertTrue(id, id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) }
    }

    @Test
    fun `seeding twice does not duplicate an entry`() {
        val registry = PluginRegistry()
        PluginCatalog.seed(registry)
        val first = registry.all().size
        PluginCatalog.seed(registry)
        assertEquals(first, registry.all().size)
    }

    @Test
    fun `every catalog entry declares at least one capability`() {
        PluginCatalog.all().forEach { entry ->
            assertTrue("${entry.id} declares nothing", entry.declaredCapabilities.isNotEmpty())
        }
    }
}

class PluginPlatformServiceTest {

    private fun service(registry: PluginRegistry = PluginRegistry()): Pair<PluginPlatformService, PluginRegistry> {
        val tools = com.opendroid.ai.core.connector.UniversalToolRegistry()
        val installer = PluginInstaller(registry, tools, "3.10.4")
        val store = PersistentAuthorizationStore()
        val platform = PluginPlatformService(
            registry = registry,
            installer = installer,
            toolRegistry = tools,
            authorizationStore = store,
            mcpDiscovery = com.opendroid.ai.core.connector.McpToolDiscovery(),
            appVersion = "3.10.4",
        )
        return platform to registry
    }

    @Test
    fun `connect installs, approves and grants the declared capabilities`() {
        val (platform, registry) = service()
        platform.seedCatalog()

        val outcome = platform.connectCatalogEntry("ai_openai", setOf(PluginCapability.NETWORK_FETCH))

        assertTrue(outcome.message, outcome.ok)
        val record = registry.get("ai_openai")!!
        assertEquals(PluginApprovalState.APPROVED, record.approvalState)
        assertTrue(record.isCallable)
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), record.effectiveCapabilities)
    }

    @Test
    fun `a connect cannot widen beyond what the plugin declares`() {
        val (platform, registry) = service()
        platform.seedCatalog()

        // ai_openai declares only NETWORK_FETCH; asking for CODE_EXECUTION must not grant it.
        platform.connectCatalogEntry("ai_openai", setOf(PluginCapability.NETWORK_FETCH, PluginCapability.CODE_EXECUTION))

        val record = registry.get("ai_openai")!!
        assertFalse(PluginCapability.CODE_EXECUTION in record.effectiveCapabilities)
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), record.effectiveCapabilities)
    }

    @Test
    fun `revoke clears the grant so the plugin is no longer callable`() {
        val (platform, registry) = service()
        platform.seedCatalog()
        platform.connectCatalogEntry("ai_openai", setOf(PluginCapability.NETWORK_FETCH))
        assertTrue(registry.get("ai_openai")!!.isCallable)

        val outcome = platform.revoke("ai_openai")

        assertTrue(outcome.ok)
        assertFalse(registry.get("ai_openai")!!.isCallable)
        assertTrue(platform.activeGrantCapabilities("ai_openai").isEmpty())
    }

    @Test
    fun `disabling keeps the approval but stops the plugin being callable`() {
        val (platform, registry) = service()
        platform.seedCatalog()
        platform.connectCatalogEntry("ai_openai", setOf(PluginCapability.NETWORK_FETCH))

        platform.setEnabled("ai_openai", false)

        val record = registry.get("ai_openai")!!
        assertEquals(PluginApprovalState.APPROVED, record.approvalState)
        assertEquals(PluginInstallState.DISABLED, record.installState)
        assertFalse(record.isCallable)
    }

    @Test
    fun `an unknown plugin id is refused with a specific message`() {
        val (platform, _) = service()
        val outcome = platform.connectCatalogEntry("does_not_exist", emptySet())
        assertFalse(outcome.ok)
        assertTrue(outcome.message.contains("does_not_exist"))
    }
}
