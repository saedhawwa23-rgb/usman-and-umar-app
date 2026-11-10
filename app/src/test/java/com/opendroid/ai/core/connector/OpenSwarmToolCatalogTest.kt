package com.opendroid.ai.core.connector

import com.opendroid.ai.core.browser.OpenSwarmRemoteTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSwarmToolCatalogTest {
    @Test
    fun `only adapter configured low risk capabilities become model tools`() {
        val tools = OpenSwarmToolCatalog.modelTools(listOf(
            remote("browser.extract", configured = true),
            remote("files.read", configured = false, reason = "workspace not mounted"),
            remote("files.write", configured = true, risk = "high"),
            remote("terminal.execute", configured = true, risk = "critical"),
        ))
        assertEquals(listOf("colab_browser_extract"), tools.map { it.name })
    }

    @Test
    fun `unavailable resources include adapter and risk reasons`() {
        val summary = OpenSwarmToolCatalog.unavailableSummary(listOf(
            remote("files.read", false, reason = "workspace not mounted"),
            remote("files.write", true, risk = "high"),
            remote("future.tool", false),
        ))
        assertTrue(summary.contains("workspace not mounted"))
        assertTrue(summary.contains("risk is high"))
        assertTrue(summary.contains("no app-owned parameter schema"))
    }

    @Test
    fun `invalid tool arguments are rejected before dispatch`() {
        val valid = OpenSwarmToolCatalog.validate("colab_browser_extract", """{"url":"https://example.com","allowed_domains":["example.com"]}""")
        assertTrue(valid.contains("example.com"))
        val missing = runCatching { OpenSwarmToolCatalog.validate("colab_browser_extract", "{}") }.exceptionOrNull()
        val extra = runCatching { OpenSwarmToolCatalog.validate("colab_files_read", """{"path":"a.txt","shell":"rm -rf /"}""") }.exceptionOrNull()
        val type = runCatching { OpenSwarmToolCatalog.validate("colab_research_search", """{"query":12}""") }.exceptionOrNull()
        assertFalse(missing == null)
        assertFalse(extra == null)
        assertFalse(type == null)
    }

    private fun remote(id: String, configured: Boolean, risk: String = "low", reason: String? = null) =
        OpenSwarmRemoteTool(id, "test $id", emptyList(), risk, configured, reason)
}
