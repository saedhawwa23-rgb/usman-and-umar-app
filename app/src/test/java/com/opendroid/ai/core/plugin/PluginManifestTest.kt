package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manifest registration, version compatibility, dependencies, lifecycle, health and history. */
class PluginManifestTest {

    private val appVersion = "3.10.1"

    private fun manifest(
        id: String = "acme.search",
        version: String = "1.0.0",
        interop: List<String> = listOf("OPENAPI_31", "OAUTH2_OIDC"),
        capabilities: List<String> = listOf("NETWORK_FETCH"),
        dependencies: List<String> = emptyList(),
        minApp: String = "3.0.0",
        range: String? = null,
    ) = PluginManifest(
        id = id,
        displayName = "Acme Search",
        version = version,
        publisher = "Acme",
        homepageUrl = "https://example.com",
        kind = "SERVICE",
        interop = interop,
        capabilities = capabilities,
        dependencies = dependencies,
        compatibility = PluginCompatibility(minAppVersion = minApp, requiredSemverRange = range),
    )

    @Test
    fun `a well-formed manifest parses and maps to a record`() {
        val json = """
            {"id":"acme.search","display_name":"Acme Search","version":"1.2.0",
             "kind":"SERVICE","interop":["OPENAPI_31","MCP"],"capabilities":["NETWORK_FETCH"],
             "compatibility":{"min_app_version":"3.0.0"}}
        """.trimIndent()

        val parsed = PluginManifest.parse(json)
        assertNotNull(parsed)
        val record = parsed!!.toRecord()
        assertEquals("acme.search", record.id)
        assertEquals(PluginKind.SERVICE, record.kind)
        assertTrue(record.interop.contains(PluginInterop.OPENAPI_31))
        assertTrue(record.interop.contains(PluginInterop.MCP))
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), record.declaredCapabilities)
    }

    @Test
    fun `malformed manifest json is rejected, not guessed`() {
        assertEquals(null, PluginManifest.parse("{not json"))
    }

    @Test
    fun `manifest with a recognized protocol and satisfied version registers as unapproved`() {
        val registry = PluginRegistry()

        val result = registry.registerManifest(manifest(), appVersion)

        assertTrue(result is PluginAuthorization.Allowed)
        val record = registry.get("acme.search")!!
        assertEquals(PluginApprovalState.UNAPPROVED, record.approvalState)
        assertEquals(PluginInstallState.DISCOVERED, record.installState)
        assertFalse(record.isCallable)
    }

    @Test
    fun `manifest requiring a newer app is refused with a specific reason`() {
        val registry = PluginRegistry()

        val result = registry.registerManifest(manifest(minApp = "99.0.0"), appVersion)

        assertTrue(result is PluginAuthorization.Denied)
        assertTrue((result as PluginAuthorization.Denied).reason.contains("99.0.0"))
        assertEquals(null, registry.get("acme.search"))
    }

    @Test
    fun `manifest with an unrecognized protocol is refused`() {
        val registry = PluginRegistry()

        val result = registry.registerManifest(manifest(interop = listOf("TELEPATHY")), appVersion)

        assertTrue(result is PluginAuthorization.Denied)
    }

    @Test
    fun `manifest with a missing dependency is refused and names it`() {
        val registry = PluginRegistry()

        val check = registry.checkManifest(manifest(dependencies = listOf("acme.core")), appVersion)

        assertFalse(check.compatible)
        assertEquals(setOf("acme.core"), check.missingDependencies)
    }

    @Test
    fun `a version that fails its semver range is refused`() {
        val registry = PluginRegistry()

        val result = registry.registerManifest(manifest(version = "1.0.0", range = ">=2.0.0"), appVersion)

        assertTrue(result is PluginAuthorization.Denied)
    }

    @Test
    fun `dependency must be installed before a dependent plugin installs`() {
        val registry = PluginRegistry()
        registry.registerManifest(manifest(id = "acme.core"), appVersion)
        registry.registerManifest(manifest(id = "acme.app", dependencies = listOf("acme.core")), appVersion)
        registry.install("acme.core")

        val installed = registry.install("acme.app")

        assertEquals(PluginInstallState.INSTALLED, installed.installState)
    }

    @Test
    fun `install refuses when a dependency is not installed`() {
        val registry = PluginRegistry()
        registry.registerManifest(manifest(id = "acme.core"), appVersion)
        registry.registerManifest(manifest(id = "acme.app", dependencies = listOf("acme.core")), appVersion)

        val error = runCatching { registry.install("acme.app") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("acme.core"))
    }

    @Test
    fun `an approved but disabled plugin is not callable`() {
        val registry = PluginRegistry()
        registry.registerManifest(manifest(), appVersion)
        registry.install("acme.search")
        registry.approve("acme.search", setOf(PluginCapability.NETWORK_FETCH))
        assertTrue(registry.authorize("acme.search") is PluginAuthorization.Allowed)

        registry.disable("acme.search")

        assertFalse(registry.get("acme.search")!!.isCallable)
        assertTrue(registry.authorize("acme.search") is PluginAuthorization.Denied)
    }

    @Test
    fun `an update cannot widen the owner's grant`() {
        val registry = PluginRegistry()
        registry.registerManifest(manifest(capabilities = listOf("NETWORK_FETCH")), appVersion)
        registry.install("acme.search")
        registry.approve("acme.search", setOf(PluginCapability.NETWORK_FETCH))

        val wider = manifest(version = "2.0.0", capabilities = listOf("NETWORK_FETCH", "FILESYSTEM_WRITE"))
        registry.applyUpdate("acme.search", wider)

        val record = registry.get("acme.search")!!
        assertEquals("2.0.0", record.version)
        assertEquals(setOf(PluginCapability.NETWORK_FETCH), record.effectiveCapabilities)
    }

    @Test
    fun `health reflects the recent execution history`() {
        val registry = PluginRegistry()
        assertEquals(PluginHealth.UNKNOWN, registry.health("p"))

        registry.recordExecution(PluginExecutionRecord("p", "run", 1, success = true, detail = "ok"))
        assertEquals(PluginHealth.HEALTHY, registry.health("p"))

        registry.recordExecution(PluginExecutionRecord("p", "run", 2, success = false, detail = "boom"))
        assertEquals(PluginHealth.DEGRADED, registry.health("p"))

        repeat(9) { registry.recordExecution(PluginExecutionRecord("p", "run", 3L + it, success = false, detail = "boom")) }
        assertEquals(PluginHealth.FAILING, registry.health("p"))
    }

    @Test
    fun `execution history is newest-first and bounded`() {
        val registry = PluginRegistry()
        repeat(150) { registry.recordExecution(PluginExecutionRecord("p", "run", it.toLong(), success = true, detail = "ok")) }

        val history = registry.executionHistory("p")
        assertEquals(100, history.size)
        assertEquals(149L, history.first().timestampMs)
    }

    @Test
    fun `removing a plugin clears its history and health`() {
        val registry = PluginRegistry()
        registry.registerManifest(manifest(), appVersion)
        registry.recordExecution(PluginExecutionRecord("acme.search", "run", 1, success = false, detail = "boom"))

        registry.remove("acme.search")

        assertTrue(registry.executionHistory("acme.search").isEmpty())
        assertEquals(PluginHealth.UNKNOWN, registry.health("acme.search"))
    }
}
