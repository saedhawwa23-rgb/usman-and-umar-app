package com.opendroid.ai.core.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

@Serializable
sealed class StreamChunk {
    @Serializable
    data class Content(val text: String) : StreamChunk()
    @Serializable
    data class ToolCall(val name: String, val arguments: String, val id: String = "") : StreamChunk()
}

interface AIProvider {
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamChunk>
}

interface LLMProvider : AIProvider {
    val name: String
    val availableModels: List<String>
    suspend fun complete(request: LLMRequest): LLMResponse
    fun streamComplete(request: LLMRequest): Flow<String>
    suspend fun isAvailable(): Boolean

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> {
        val systemPrompt = "You are an autonomous AI agent for Android."
        val request = LLMRequest(
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools.map { Tool(it.name, it.description, it.parameters) }
        )
        return kotlinx.coroutines.flow.flow {
            streamComplete(request).collect { text ->
                emit(StreamChunk.Content(text))
            }
        }
    }
}

/**
 * Ephemeral provider configuration captured with a resolved request. It is
 * deliberately excluded from serialization and renders only as redacted text.
 */
class ProviderRequestConfig(
    val apiKey: String,
    val endpoint: String
) {
    override fun toString(): String = "<redacted provider configuration>"
}

@Serializable
data class LLMRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    /**
     * Resolved by [WrappedLLMProvider] from the provider/model pairing. Direct
     * provider callers may leave it null and receive the catalog default.
     */
    val model: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2000,
    val responseFormat: ResponseFormat = ResponseFormat.JSON,
    val tools: List<Tool>? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    @Transient
    val providerConfig: ProviderRequestConfig? = null
)

enum class ResponseFormat {
    JSON, TEXT
}

enum class RetryPolicy {
    DEFAULT,
    NONE
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: String // JSON Schema string representing parameters
)

@Serializable
data class LLMResponse(
    val content: String,
    val tokensUsed: Int,
    val model: String,
    val provider: String,
    val latencyMs: Long,
    val toolCalls: List<ProviderToolCall> = emptyList()
)

@Serializable
data class ProviderToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

fun List<ChatMessage>.toOpenAIMessages(systemPrompt: String): List<Map<String, Any?>> {
    val messagesList = mutableListOf<Map<String, Any?>>()
    messagesList.add(mapOf("role" to "system", "content" to systemPrompt))
    this.forEach { msg ->
        if (msg.providerRole == "tool") {
            messagesList.add(mapOf("role" to "tool", "tool_call_id" to msg.toolCallId, "content" to msg.text))
            return@forEach
        }
        if (msg.providerRole == "assistant" && msg.toolCallsJson != null) {
            val toolCalls = runCatching {
                Gson().fromJson<Array<Map<String, Any?>>>(
                    msg.toolCallsJson,
                    object : TypeToken<Array<Map<String, Any?>>>() {}.type
                ).toList()
            }.getOrDefault(emptyList())
            messagesList.add(mapOf("role" to "assistant", "content" to msg.text.ifBlank { null }, "tool_calls" to toolCalls))
            return@forEach
        }
        val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
        if (msg.imageBase64 != null && role == "user") {
            messagesList.add(
                mapOf(
                    "role" to role,
                    "content" to listOf(
                        mapOf("type" to "text", "text" to msg.text),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/jpeg;base64,${msg.imageBase64}"
                            )
                        )
                    )
                )
            )
        } else {
            messagesList.add(mapOf("role" to role, "content" to msg.text))
        }
    }
    return messagesList
}
