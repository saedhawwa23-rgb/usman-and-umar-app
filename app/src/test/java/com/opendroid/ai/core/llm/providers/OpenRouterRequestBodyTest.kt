package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.Tool
import com.opendroid.ai.data.models.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRequestBodyTest {
    @Test
    fun `chat-only body contains cf and omits tools and tool choice`() {
        val request = LLMRequest(
            systemPrompt = "",
            messages = listOf(ChatMessage("test", "cf", ChatMessage.Sender.USER)),
            model = " openrouter/auto ",
            responseFormat = ResponseFormat.TEXT,
            tools = null,
        )
        val json = Gson().toJsonTree(buildOpenRouterRequestBody(request, request.model.orEmpty())).asJsonObject
        assertEquals("openrouter/auto", json.get("model").asString)
        assertEquals("cf", json.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
        assertFalse(json.has("tools"))
        assertFalse(json.has("tool_choice"))
    }

    @Test
    fun `tool mode serializes one valid function`() {
        val request = LLMRequest(
            systemPrompt = "system",
            messages = emptyList(),
            model = "openai/gpt-4o-mini",
            responseFormat = ResponseFormat.TEXT,
            tools = listOf(Tool("colab__browser_extract", "Read a public page", "{\"type\":\"object\"}")),
        )
        val json = Gson().toJsonTree(buildOpenRouterRequestBody(request, "openai/gpt-4o-mini")).asJsonObject
        assertTrue(json.has("tools"))
        assertEquals("auto", json.get("tool_choice").asString)
    }

    @Test
    fun `model id is trimmed and checked against exact regex`() {
        assertEquals("google/gemini-2.0-flash-exp:free", validateOpenRouterModelId("  google/gemini-2.0-flash-exp:free  "))
        listOf("no-provider", "provider/", "provider/model extra", "provider/model/variant", "provider/model?x=1")
            .forEach { invalid -> assertTrue(runCatching { validateOpenRouterModelId(invalid) }.isFailure) }
    }
}
