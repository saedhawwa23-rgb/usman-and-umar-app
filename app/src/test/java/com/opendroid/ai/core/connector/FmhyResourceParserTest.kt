package com.opendroid.ai.core.connector

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmhyResourceParserTest {
    @Test
    fun `parser keeps only safe https hosts and deduplicates normalized URLs`() {
        val text = """
            https://good.example/page https://good.example:443/page.
            http://plain.example https://user:pass@auth.example/path
            https://port.example:8443/path https://localhost/path
            https://127.0.0.1/private https://192.168.1.2/private
            https://[::1]/private https://good.example/other
        """.trimIndent()

        val resources = FmhyResourceParser.parse(text)

        assertEquals(listOf("https://good.example/page", "https://good.example/other"), resources.map { it.url })
        assertEquals(listOf("good.example", "good.example"), resources.map { it.host })
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
    fun `site path payload cannot change url or allowed host`() {
        val payload = JsonParser.parseString(
            FmhyResourceParser.lockedExtractionPayload("media.example", "{\"path\":\"/guides/start\"}")
        ).asJsonObject

        assertEquals("https://media.example/guides/start", payload.get("url").asString)
        assertEquals(listOf("media.example"), payload.getAsJsonArray("allowed_domains").map { it.asString })
        assertFalse(runCatching {
            FmhyResourceParser.lockedExtractionPayload("media.example", "{\"path\":\"https://evil.example/\"}")
        }.isSuccess)
        assertFalse(runCatching {
            FmhyResourceParser.lockedExtractionPayload("media.example", "{\"path\":\"/ok\",\"allowed_domains\":[\"evil.example\"]}")
        }.isSuccess)
        assertFalse(runCatching {
            FmhyResourceParser.lockedExtractionPayload("media.example", "{\"path\":\"//evil.example/path\"}")
        }.isSuccess)
    }

    @Test
    fun `resource hosts map to unique stable site tool names`() {
        val resources = FmhyResourceParser.parse(
            "https://foo-bar.example/a https://foo.bar.example/b https://foo-bar.example/c"
        )
        val mapping = FmhyResourceParser.toolMapping(resources)

        assertEquals(setOf("foo-bar.example", "foo.bar.example"), mapping.keys)
        assertEquals(2, mapping.values.distinct().size)
        assertTrue(mapping.values.all { it.matches(Regex("site_[a-z0-9_]+_[a-f0-9]{12,64}")) })
        assertEquals(mapping, FmhyResourceParser.toolMapping(resources))
        assertFalse(mapping.values.contains("discover_fmhy_resources"))
    }
}
