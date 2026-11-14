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
        assertEquals(listOf("colab__browser_extract", "colab__files_write", "colab__terminal_execute"), tools.map { it.name })
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

    @Test
    fun `files write accepts whole text and binary payloads within the transfer limit`() {
        // A text file larger than the old 1 MB cap is accepted (limit matches the Colab node).
        val largeText = "x".repeat(1_500_000)
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_files_write", """{"path":"big.txt","content":"$largeText"}""")
        }.isSuccess)

        // Any file may be transferred whole as base64, and the bytes are what is measured.
        val encoder = java.util.Base64.getEncoder()
        val binary = encoder.encodeToString(ByteArray(1_500_000) { it.toByte() })
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_files_write", """{"path":"blob.bin","content_base64":"$binary"}""")
        }.isSuccess)

        // Invalid base64 and an oversized payload are both rejected before dispatch.
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_files_write", """{"path":"bad.bin","content_base64":"not*base64"}""")
        }.isFailure)
        val oversize = encoder.encodeToString(ByteArray(OpenSwarmToolCatalog.MAX_FILE_CONTENT_BYTES + 1))
        assertTrue(runCatching {
            OpenSwarmToolCatalog.validate("colab_files_write", """{"path":"huge.bin","content_base64":"$oversize"}""")
        }.isFailure)
    }

    @Test
    fun `registry argument ceiling accommodates every accepted file payload`() {
        // The serialized base64 for a max-size payload must fit under the registry ceiling,
        // otherwise a file that validate() accepts is rejected later with a vague message.
        val maxBase64 = OpenSwarmToolCatalog.MAX_FILE_CONTENT_BYTES / 3 * 4 + 4
        assertTrue(maxBase64 <= OpenSwarmToolCatalog.MAX_FILE_ARGUMENT_CHARS)
    }

    private fun remote(id: String, configured: Boolean, risk: String = "low", reason: String? = null) =
        OpenSwarmRemoteTool(id, "test $id", emptyList(), risk, configured, reason)
}
