package com.opendroid.ai.core.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolNameMappingTest {
    @Test
    fun `dot names map to double underscores and round trip uniquely`() {
        val mapping = ProviderToolNameMapping.fromCanonicalNames(
            listOf("colab.browser_extract", "device.open_app", "site.example.net")
        )
        assertEquals("colab__browser_extract", mapping.providerName("colab.browser_extract"))
        assertEquals("colab.browser_extract", mapping.canonicalName("colab__browser_extract"))
        assertEquals("device__open_app", mapping.providerName("device.open_app"))
        assertEquals("site__example__net", mapping.providerName("site.example.net"))
        assertNull(mapping.canonicalName("not_registered"))
    }

    @Test
    fun `model names use the documented exact regex`() {
        val regex = Regex(ProviderToolNameMapping.MODEL_NAME_PATTERN)
        assertTrue(regex.matches("colab__browser_extract"))
        assertTrue(regex.matches("a"))
        assertTrue(regex.matches("x".repeat(64)))
        assertTrue(!regex.matches("x".repeat(65)))
        assertTrue(!regex.matches("colab.browser_extract"))
        assertTrue(!regex.matches("name with spaces"))
    }

    @Test
    fun `ambiguous dot and double underscore names are rejected`() {
        val error = runCatching {
            ProviderToolNameMapping.fromCanonicalNames(listOf("colab.browser_extract", "colab__browser_extract"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
