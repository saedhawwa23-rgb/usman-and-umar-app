package com.opendroid.ai.core.agentplatform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlatformTest {

    private class StubAdapter(
        override val platform: AgentPlatform,
        private val onSubmit: (String, String) -> PlatformResult<String> = { _, _ ->
            PlatformResult.Unsupported("submitTask", "stub")
        },
    ) : AgentPlatformAdapter {
        override suspend fun submitTask(sessionId: String, prompt: String) = onSubmit(sessionId, prompt)
    }

    @Test
    fun `unimplemented operations report Unsupported rather than succeeding`() = runBlocking {
        val adapter = StubAdapter(AgentPlatformCatalog.openHands)
        assertTrue(adapter.cancelTask("t") is PlatformResult.Unsupported)
        assertTrue(adapter.listArtifacts("t") is PlatformResult.Unsupported)
        assertTrue(adapter.downloadArtifact("t", "a") is PlatformResult.Unsupported)
        assertTrue(adapter.getTask("t") is PlatformResult.Unsupported)
        assertTrue(adapter.createSession() is PlatformResult.Unsupported)
        assertTrue(adapter.observeTaskEvents("t", 0) is PlatformResult.Unsupported)
    }

    @Test
    fun `Unsupported names the operation and the platform`() = runBlocking {
        val adapter = StubAdapter(AgentPlatformCatalog.openHands)
        val result = adapter.cancelTask("t") as PlatformResult.Unsupported
        assertEquals("cancelTask", result.operation)
        assertEquals("openhands", result.platformId)
    }

    @Test
    fun `listCapabilities reports exactly what the platform declared`() = runBlocking {
        val adapter = StubAdapter(AgentPlatformCatalog.openHands)
        val result = adapter.listCapabilities() as PlatformResult.Success
        assertEquals(AgentPlatformCatalog.openHands.supported, result.value)
        // Cancellation is advertised because it was verified against a live server:
        // POST /interrupt returned 200 and the conversation moved running -> paused.
        assertTrue(PlatformCapability.CANCEL_TASK in result.value)
    }

    @Test
    fun `a browser-only platform is never treated as an API integration`() {
        val browser = AgentPlatformCatalog.browserOnlyExample
        assertEquals(IntegrationMode.APPROVED_BROWSER_AUTOMATION, browser.integrationMode)
        assertEquals(TrustLevel.BROWSER_ONLY, browser.trustLevel)
        assertTrue(browser.supported.isEmpty())
        assertTrue(AgentPlatformCatalog.apiIntegrated().none { it.id == browser.id })
    }

    @Test
    fun `guard blocks an undeclared capability and allows a declared one`() {
        val platform = AgentPlatformCatalog.openHands
        assertNull(AgentPlatformCatalog.guard(platform, PlatformCapability.SUBMIT_TASK))
        assertNull(AgentPlatformCatalog.guard(platform, PlatformCapability.CANCEL_TASK))
        // Artifact retrieval is still unimplemented, so it stays blocked.
        val blocked = AgentPlatformCatalog.guard(platform, PlatformCapability.LIST_ARTIFACTS)
        assertTrue(blocked is PlatformResult.Unsupported)
    }

    @Test
    fun `every catalog entry declares how it is driven and names no duplicate id`() {
        val ids = AgentPlatformCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(AgentPlatformCatalog.all.all { it.integrationMode.displayName.isNotBlank() })
    }

    @Test
    fun `catalog entries that claim artifacts also claim task submission`() {
        // An artifact cannot exist without a task; a platform advertising one but not the other
        // would produce a UI flow that can never complete.
        AgentPlatformCatalog.all.forEach { platform ->
            if (PlatformCapability.LIST_ARTIFACTS in platform.supported) {
                assertTrue(
                    "${platform.id} claims artifacts without tasks",
                    PlatformCapability.SUBMIT_TASK in platform.supported,
                )
            }
        }
    }

    @Test
    fun `openhands limitations are stated so the UI cannot overstate support`() {
        assertTrue(AgentPlatformCatalog.openHands.limitations.isNotEmpty())
        assertTrue(AgentPlatformCatalog.openHands.documentationUrl.startsWith("https://"))
    }
}
