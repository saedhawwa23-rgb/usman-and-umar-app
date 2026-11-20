package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.llm.toOpenAIMessages
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NvidiaNimProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : LLMProvider {
    override val name: String = "NVIDIA NIM"
    override val availableModels: List<String> = listOf(NVIDIA_MODEL)

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey
            ?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name].orEmpty()
        check(apiKey.isNotBlank()) { "NVIDIA NIM API Key is not configured." }

        // Keep Android agent requests aligned with the Colab browser executor and
        // the model specifically provisioned for this NVIDIA key.
        val model = NVIDIA_MODEL
        val requestBody = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to request.messages.toOpenAIMessages(request.systemPrompt),
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens,
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            requestBody["response_format"] = mapOf("type" to "json_object")
        }
        val body = gson.toJson(requestBody)
        val httpRequest = Request.Builder()
            .url(NVIDIA_CHAT_COMPLETIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(mediaType))
            .build()
        val startedAt = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw response.toSafeProviderException(
                        provider = ProviderErrorDetail.Provider.NVIDIA_NIM,
                        request = request.copy(model = model),
                        knownSecrets = listOf(apiKey),
                    )
                }
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) throw IOException("Empty response body from NVIDIA NIM")
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val content = json.getAsJsonArray("choices")
                    ?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString
                    ?: throw IOException("Malformed response body from NVIDIA NIM")
                val tokens = json.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
                LLMResponse(
                    content = content,
                    tokensUsed = tokens,
                    model = model,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        complete(request).content.split(" ").forEach { emit("$it ") }
    }

    override suspend fun isAvailable(): Boolean = true

    companion object {
        const val NVIDIA_MODEL = "nvidia/nemotron-3.5-bolt-e4b"
        const val NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
        const val NVIDIA_CHAT_COMPLETIONS_URL = "$NVIDIA_BASE_URL/chat/completions"
        const val NVIDIA_MODELS_URL = "$NVIDIA_BASE_URL/models"
    }
}
