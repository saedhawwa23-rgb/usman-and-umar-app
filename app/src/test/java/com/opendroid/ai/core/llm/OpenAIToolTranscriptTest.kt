package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIToolTranscriptTest {
    @Test
    fun `assistant tool calls and tool results round trip in provider protocol`() {
        val assistant = ChatMessage(
            id = "assistant-1",
            text = "",
            sender = ChatMessage.Sender.AGENT,
            providerRole = "assistant",
            toolCallsJson = """[{"id":"call-1","type":"function","function":{"name":"connector_github__lookup","arguments":"{\"repo\":\"owner/name\"}"}}]"""
        )
        val result = ChatMessage(
            id = "tool-1",
            text = "Repository found",
            sender = ChatMessage.Sender.AGENT,
            providerRole = "tool",
            toolCallId = "call-1"
        )

        val transcript = listOf(assistant, result).toOpenAIMessages("System prompt")
        assertEquals("system", transcript[0]["role"])
        val assistantMessage = transcript[1]
        assertEquals("assistant", assistantMessage["role"])
        assertNull(assistantMessage["content"])
        @Suppress("UNCHECKED_CAST")
        val calls = assistantMessage["tool_calls"] as List<Map<String, Any>>
        assertEquals("call-1", calls.single()["id"])
        val toolMessage = transcript[2]
        assertEquals("tool", toolMessage["role"])
        assertEquals("call-1", toolMessage["tool_call_id"])
        assertEquals("Repository found", toolMessage["content"])
    }
}
