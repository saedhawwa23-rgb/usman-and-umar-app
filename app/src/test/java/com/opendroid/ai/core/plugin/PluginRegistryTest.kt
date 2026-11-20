package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private fun record(
        id: String,
        declared: Set<PluginCapability> = setOf(PluginCapability.NETWORK_FETCH),
        risk: PluginRisk = PluginRisk.LOW,
    ) = PluginRecord(
        id = id,
        displayName = "Plugin $id",
        publisher = "publisher",
        homepageUrl = "https://example.com/$id",
        declaredCapabilities = declared,
        risk = risk,
    )

    @Test
    fun `unlisted plugin is refused before any request`() {
        val registry = PluginRegistry()
        registry.register(record("known"))

        val decision = registry.authorize("never-registered")

        assertTrue(decision is PluginAuthorization.Denied)
        assertEquals(PluginRegistry.UNKNOWN_PLUGIN, (decision as PluginAuthorization.Denied).reason)
    }

    @Test
    fun `unapproved plugin is refused`() {
        val registry = PluginRegistry()
        registry.register(record("pending"))

        val decision = registry.authorize("pending")

        assertEquals(
            PluginRegistry.UNAPPROVED_PLUGIN,
            (decision as PluginAuthorization.Denied).reason,
        )
    }

    @Test
    fun `revoked plugin is refused`() {
        val registry = PluginRegistry()
        registry.register(record("revocable"))
        registry.approve("revocable", setOf(PluginCapability.NETWORK_FETCH))
        assertTrue(registry.authorize("revocable") is PluginAuthorization.Allowed)

        registry.revoke("revocable")
        val decision = registry.authorize("revocable")

        assertEquals(PluginRegistry.REVOKED_PLUGIN, (decision as PluginAuthorization.Denied).reason)
        assertTrue(registry.get("revocable")!!.effectiveCapabilities.isEmpty())
    }

    @Test
    fun `three hundred plugin registry loads and lists correctly`() {
        val registry = PluginRegistry()
        val count = 300
        repeat(count) { index -> registry.register(record("plugin-$index")) }

        assertEquals(count, registry.all().size)
        assertEquals("plugin-0", registry.all().first().id)
        assertEquals("plugin-${count - 1}", registry.all().last().id)
        assertEquals(count, registry.all().map { it.id }.distinct().size)

        // Approving a subset keeps listing correct and does not cap the registry.
        repeat(50) { index -> registry.approve("plugin-$index", setOf(PluginCapability.NETWORK_FETCH)) }
        assertEquals(50, registry.approved().size)
        assertEquals(count, registry.all().size)
    }

    @Test
    fun `capability grant cannot be expanded by the plugin's own manifest`() {
        val registry = PluginRegistry()
        registry.register(record("sneaky", declared = setOf(PluginCapability.NETWORK_FETCH)))
        registry.approve("sneaky", setOf(PluginCapability.NETWORK_FETCH))

        // The plugin re-declares a larger manifest, hoping to gain filesystem write.
        registry.register(
            record(
                "sneaky",
                declared = setOf(
                    PluginCapability.NETWORK_FETCH,
                    PluginCapability.FILESYSTEM_WRITE,
                    PluginCapability.CODE_EXECUTION,
                ),
            )
        )

        val stored = registry.get("sneaky")!!
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), stored.effectiveCapabilities)
        assertFalse(PluginCapability.FILESYSTEM_WRITE in stored.effectiveCapabilities)

        val decision = registry.authorize("sneaky", PluginCapability.FILESYSTEM_WRITE)
        assertEquals(
            PluginRegistry.CAPABILITY_NOT_GRANTED,
            (decision as PluginAuthorization.Denied).reason,
        )
    }

    @Test
    fun `owner cannot grant a capability the plugin never declared`() {
        val registry = PluginRegistry()
        registry.register(record("limited", declared = setOf(PluginCapability.NETWORK_FETCH)))

        val approved = registry.approve(
            "limited",
            setOf(PluginCapability.NETWORK_FETCH, PluginCapability.DEVICE_CONTROL),
        )

        assertEquals(setOf(PluginCapability.NETWORK_FETCH), approved.effectiveCapabilities)
    }

    @Test
    fun `discovery only suggests and never approves`() {
        val registry = PluginRegistry()
        registry.suggest(
            PluginCandidate("candidate", "Candidate", "https://example.com", "fmhy")
        )

        assertEquals(1, registry.suggestions().size)
        assertTrue(registry.all().isEmpty())
        assertTrue(registry.authorize("candidate") is PluginAuthorization.Denied)

        registry.acceptSuggestion("candidate", record("candidate"))
        assertEquals(1, registry.all().size)
        // Accepting a suggestion registers the plugin but does not approve it.
        assertEquals(
            PluginRegistry.UNAPPROVED_PLUGIN,
            (registry.authorize("candidate") as PluginAuthorization.Denied).reason,
        )
    }

    @Test
    fun `invalid plugin id is rejected`() {
        val registry = PluginRegistry()
        val failure = runCatching { registry.register(record("Bad Id!")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
