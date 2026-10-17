package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.toOpenAIMessages
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Runtime-discovered OpenAI-compatible provider; built-ins remain unchanged. */
class DynamicOpenAIProvider(
    private val providerId: String,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {
    override val name: String = providerId
    override val availableModels: List<String> = listOf("dynamic-model")
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val definition = config.dynamicAIProviders.firstOrNull { it.id == name }
            ?: error("Discovered provider is no longer configured.")
        val apiKey = request.providerConfig?.apiKey.orEmpty().ifBlank { config.apiKeys[name].orEmpty() }
        val baseUrl = UrlUtils.formatBaseUrl(
            request.providerConfig?.endpoint.orEmpty().ifBlank { definition.endpoint },
            "https://api.openai.com/v1"
        )
        val model = request.model.orEmpty().ifBlank { definition.model }
        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to request.messages.toOpenAIMessages(request.systemPrompt),
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )
        if (request.responseFormat == com.opendroid.ai.core.llm.ResponseFormat.JSON) {
            payload["response_format"] = mapOf("type" to "json_object")
        }
        val httpRequest = Request.Builder().url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(gson.toJson(payload).toRequestBody(mediaType)).build()
        val start = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) throw response.toSafeProviderException(
                    ProviderErrorDetail.Provider.CUSTOM_OPENAI, request, listOf(apiKey)
                )
                val body = response.body.string()
                val root = gson.fromJson(body, JsonObject::class.java)
                val content = root.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").get("content").asString
                LLMResponse(content, root.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0,
                    model, name, System.currentTimeMillis() - start)
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        complete(request).content.split(" ").forEach { emit("$it ") }
    }

    override suspend fun isAvailable(): Boolean = true
}
