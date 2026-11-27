package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryPersistenceTest {

    private fun record(
        id: String,
        declared: Set<PluginCapability> = setOf(PluginCapability.NETWORK_FETCH),
    ) = PluginRecord(
        id = id,
        displayName = "Plugin $id",
        publisher = "pub",
        homepageUrl = "https://example.com/$id",
        supportedLanguages = setOf("en", "ar"),
        supportedRegions = setOf("GLOBAL"),
        declaredCapabilities = declared,
        risk = PluginRisk.HIGH,
    )

    @Test
    fun `round trip preserves approval state and grants`() {
        val registry = PluginRegistry()
        registry.register(record("a"))
        registry.approve("a", setOf(PluginCapability.NETWORK_FETCH))
        registry.register(record("b"))

        val payload = PluginRegistryPersistence().serialize(registry)

        val restored = PluginRegistry()
        PluginRegistryPersistence().restore(restored, payload)

        assertEquals(2, restored.all().size)
        val a = restored.get("a")!!
        assertEquals(PluginApprovalState.APPROVED, a.approvalState)
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), a.effectiveCapabilities)
        assertEquals(PluginApprovalState.UNAPPROVED, restored.get("b")!!.approvalState)
        assertTrue(restored.authorize("b") is PluginAuthorization.Denied)
    }

    @Test
    fun `revoked plugin stays revoked after restore`() {
        val registry = PluginRegistry()
        registry.register(record("r"))
        registry.approve("r", setOf(PluginCapability.NETWORK_FETCH))
        registry.revoke("r")

        val restored = PluginRegistry()
        PluginRegistryPersistence().restore(restored, PluginRegistryPersistence().serialize(registry))

        assertEquals(PluginApprovalState.REVOKED, restored.get("r")!!.approvalState)
        assertEquals(
            PluginRegistry.REVOKED_PLUGIN,
            (restored.authorize("r") as PluginAuthorization.Denied).reason,
        )
    }

    @Test
    fun `restore never widens a grant beyond what the manifest declares`() {
        val registry = PluginRegistry()
        registry.register(
            record("c", declared = setOf(PluginCapability.NETWORK_FETCH, PluginCapability.CODE_EXECUTION))
        )
        registry.approve("c", setOf(PluginCapability.NETWORK_FETCH, PluginCapability.CODE_EXECUTION))
        val payload = PluginRegistryPersistence().serialize(registry)

        // Simulate a hand-edited payload whose manifest no longer declares CODE_EXECUTION while
        // the grant still lists it: the effective set must shrink, never widen.
        val shrunkManifest = payload.replace(
            "\"declaredCapabilities\":[\"CODE_EXECUTION\",\"NETWORK_FETCH\"]",
            "\"declaredCapabilities\":[\"NETWORK_FETCH\"]",
        )
        assertTrue("fixture must actually shrink the manifest", shrunkManifest != payload)

        val restored = PluginRegistry()
        PluginRegistryPersistence().restore(restored, shrunkManifest)

        val c = restored.get("c")!!
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), c.effectiveCapabilities)
        assertTrue(PluginCapability.CODE_EXECUTION !in c.effectiveCapabilities)
    }

    @Test
    fun `blank and malformed payloads restore nothing and do not throw`() {
        val registry = PluginRegistry()
        val persistence = PluginRegistryPersistence()

        persistence.restore(registry, null)
        persistence.restore(registry, "")
        persistence.restore(registry, "not json at all")

        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `a malformed entry is skipped while valid entries load`() {
        val registry = PluginRegistry()
        registry.register(record("good"))
        val payload = PluginRegistryPersistence().serialize(registry)
        // Inject one entry with an invalid id, which register() rejects.
        val withBad = payload.dropLast(1) + """,""" +
            """{"id":"BAD ID","displayName":"x","publisher":"p","homepageUrl":"https://e.com","declaredCapabilities":[],"risk":"LOW","approvalState":"UNAPPROVED","grantedCapabilities":[]}]"""

        val restored = PluginRegistry()
        PluginRegistryPersistence().restore(restored, withBad)

        assertEquals(1, restored.all().size)
        assertEquals("good", restored.all().first().id)
    }
}
