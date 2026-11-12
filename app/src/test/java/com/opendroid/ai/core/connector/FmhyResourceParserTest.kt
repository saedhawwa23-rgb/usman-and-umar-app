package com.opendroid.ai.core.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmhyResourceParserTest {
    @Test
    fun `parser keeps only public HTTPS hosts and deduplicates normalized URLs`() {
        val text = """
            https://good.example/page https://good.example:443/page.
            http://plain.example http://plain-port.example:8080/path https://user:pass@auth.example/path
            https://port.example:8443/path https://localhost/path
            https://127.0.0.1/private https://192.168.1.2/private
            https://[::1]/private https://good.example/other
        """.trimIndent()

        val resources = FmhyResourceParser.parse(text)

        assertEquals(
            listOf(
                "https://good.example/page",
                "https://port.example:8443/path",
                "https://good.example/other",
            ),
            resources.map { it.url },
        )
        assertEquals(
            listOf("good.example", "port.example", "good.example"),
            resources.map { it.host },
        )
    }

    @Test
    fun `parser caps output at two hundred urls and twenty hosts`() {
        val tooManyPaths = (1..250).joinToString(" ") { "https://one.example/path$it" }
        val paths = FmhyResourceParser.parse(tooManyPaths)
        assertEquals(200, paths.size)
        assertEquals("https://one.example/path1", paths.first().url)
        assertEquals("https://one.example/path200", paths.last().url)

        val tooManyHosts = (1..25).joinToString(" ") { "https://host$it.example/path" }
        val hosts = FmhyResourceParser.parse(tooManyHosts)
        assertEquals(20, hosts.map { it.host }.distinct().size)
        assertEquals("host20.example", hosts.last().host)
    }

    @Test
    fun `FMHY outbound links map dynamically to arbitrary public HTTPS destinations`() {
        val resources = FmhyResourceParser.parse(
            "AI generator: https://tool-one.example/generate?model=fast " +
                "another tool: http://tools.example.org:8080/create " +
                "repeat: https://tool-one.example/generate?model=fast"
        )

        assertEquals(
            listOf("https://tool-one.example/generate?model=fast"),
            resources.map { it.url },
        )
        assertEquals(listOf("tool-one.example"), resources.map { it.host })
        assertTrue(resources.all { it.url.startsWith("https://") })
    }

    @Test
    fun `site tool payload binds trusted host and permits only rooted safe paths`() {
        val payload = siteToolPayload("tool-one.example", "/catalog/tools")
        assertEquals("https://tool-one.example/catalog/tools", payload.get("url").asString)
        assertEquals("tool-one.example", payload.getAsJsonArray("allowed_domains").get(0).asString)
    }

    @Test
    fun `site tool payload rejects parent traversal`() {
        val failure = runCatching { siteToolPayload("tool-one.example", "/catalog/../private") }.exceptionOrNull()
        assertTrue("Expected traversal path to be rejected", failure is IllegalArgumentException)
    }

    @Test
    fun `site tool payload rejects unrooted paths and invalid hosts`() {
        assertTrue(runCatching { siteToolPayload("tool-one.example", "catalog") }.isFailure)
        assertTrue(runCatching { siteToolPayload("attacker.example/path", "/") }.isFailure)
    }
}
