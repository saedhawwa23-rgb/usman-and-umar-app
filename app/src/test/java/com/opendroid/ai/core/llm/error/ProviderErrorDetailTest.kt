package com.opendroid.ai.core.llm.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorDetailTest {

    @Test
    fun `promotes only safe vendor tokens from an OpenAI error body`() {
        val knownKey = "sk-invalid-secret-value-6789"
        val rawBody = """
            {
              "error": {
                "message": "Prompt PROMPT_CANARY failed at https://secret.example/v1 for sk-inval**********6789",
                "type": "invalid_request_error",
                "code": "invalid_api_key"
              }
            }
        """.trimIndent()

        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            httpStatus = 401,
            rawBody = rawBody,
            knownSecrets = listOf(knownKey),
            forbiddenText = listOf("PROMPT_CANARY", "https://secret.example/v1")
        )

        assertEquals(ProviderErrorDetail.Provider.OPENAI, detail.provider)
        assertEquals(401, detail.httpStatus)
        assertEquals("invalid_request_error", detail.vendorType)
        assertEquals("invalid_api_key", detail.vendorCode)
        assertEquals(
            "OpenAI request failed with HTTP 401 (type=invalid_request_error, code=invalid_api_key).",
            detail.message
        )

        val observableText = detail.toString() + detail.message
        listOf(
            rawBody,
            "PROMPT_CANARY",
            "https://secret.example/v1",
            knownKey,
            "sk-inval",
            "6789"
        ).forEach { forbidden ->
            assertFalse("$forbidden survived in $observableText", observableText.contains(forbidden))
        }
    }

    @Test
    fun `rejects prompt and known key fragments disguised as vendor tokens`() {
        val promptCanary = "PROMPT_CANARY"
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            httpStatus = 401,
            rawBody = """
                {
                  "error": {
                    "type": "$promptCanary",
                    "code": "tail6789"
                  }
                }
            """.trimIndent(),
            knownSecrets = listOf("sk-invalid-secret-value-6789"),
            forbiddenText = listOf(promptCanary)
        )

        assertEquals(null, detail.vendorType)
        assertEquals(null, detail.vendorCode)
        assertEquals("OpenAI request failed with HTTP 401.", detail.message)
    }

    @Test
    fun `rejects four character fragments of an unprefixed known key`() {
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.COHERE,
            httpStatus = 401,
            rawBody = """{"type":"Ab1C","code":"6789"}""",
            knownSecrets = listOf("Ab1Cd2Ef3Gh4Ij5Kl6Mn6789"),
            forbiddenText = emptyList()
        )

        assertEquals(null, detail.vendorType)
        assertEquals(null, detail.vendorCode)
        assertEquals("Cohere request failed with HTTP 401.", detail.message)
    }

    @Test
    fun `surfaces a short safe provider message`() {
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENROUTER,
            httpStatus = 400,
            rawBody = """{"error":{"message":"Unknown model requested."}}""",
            knownSecrets = emptyList(),
            forbiddenText = listOf("A user prompt that is not repeated here")
        )

        assertEquals("Unknown model requested.", detail.vendorMessage)
        assertTrue(detail.toString().contains("Unknown model requested."))
    }

    @Test
    fun `rejects provider messages that echo prompts URLs or credential-like strings`() {
        val key = "sk-0123456789abcdefghijklmnopqrstuvwxyz"
        val rawBody = """{"error":{"message":"Prompt CANARY https://private.example $key"}}"""
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENROUTER,
            httpStatus = 400,
            rawBody = rawBody,
            knownSecrets = listOf(key),
            forbiddenText = listOf("CANARY", "https://private.example")
        )

        assertEquals(null, detail.vendorMessage)
        val observableText = detail.toString()
        assertFalse(observableText.contains("CANARY"))
        assertFalse(observableText.contains(key))
        assertFalse(observableText.contains("private.example"))
    }
}
