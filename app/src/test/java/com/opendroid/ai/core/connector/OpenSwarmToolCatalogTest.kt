package com.opendroid.ai.core.connector

import com.opendroid.ai.core.browser.OpenSwarmRemoteTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSwarmToolCatalogTest {
    @Test
    fun `only adapter configured schema supported capabilities become model tools`() {
        val tools = OpenSwarmToolCatalog.modelTools(listOf(
            remote("browser.extract", configured = true),
            remote("files.read", configured = false, reason = "workspace not mounted"),
            remote("files.write", configured = true, risk = "high"),
            remote("terminal.execute", configured = true, risk = "critical"),
        ))
        assertEquals(listOf("colab_browser_extract", "colab_files_write", "colab_terminal_execute"), tools.map { it.name })
    }

    @Test
    fun `unavailable resources include adapter and schema reasons`() {
        val summary = OpenSwarmToolCatalog.unavailableSummary(listOf(
            remote("files.read", false, reason = "workspace not mounted"),
            remote("future.tool", false),
        ))
        assertTrue(summary.contains("workspace not mounted"))
        assertTrue(summary.contains("no app-owned parameter schema"))
    }

    @Test
    fun `write and execution schemas are exact and validated`() {
        assertEquals("files.write", OpenSwarmToolCatalog.capabilityFor("colab_files_write"))
        assertEquals("documents.create", OpenSwarmToolCatalog.capabilityFor("colab_documents_create"))
        assertEquals("terminal.execute", OpenSwarmToolCatalog.capabilityFor("colab_terminal_execute"))
        assertEquals("browser.interact", OpenSwarmToolCatalog.capabilityFor("colab_browser_interact"))
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_terminal_execute", """{"argv":["python3","script.py"],"timeout_seconds":15}""")
        }.isSuccess)
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_terminal_execute", """{"argv":[]}""")
        }.isFailure)
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_documents_create", """{"path":"report.md","content":"text"}""")
        }.isFailure)
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate(
                "colab_browser_interact",
                """{"url":"https://tool.example/generate","field_label":"Prompt","text":"a cat","submit_button":"Generate"}""",
            )
        }.isSuccess)
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_browser_extract", """{"url":"https://tool.example","include_links":"yes"}""")
        }.isFailure)
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
