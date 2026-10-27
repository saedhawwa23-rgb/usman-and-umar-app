package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenRouterProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "OpenRouter"
    override val availableModels: List<String> = listOf("openrouter/auto")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "google/gemini-2.0-flash-exp:free"

        val requestBodyMap = mutableMapOf<String, Any?>(
            "model" to selectedModel,
            "messages" to messagesList,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
            requestBodyMap["plugins"] = listOf(mapOf("id" to "response-healing"))
        }
        if (!request.tools.isNullOrEmpty()) {
            requestBodyMap["tools"] = request.tools.map { tool ->
                val parameters = runCatching {
                    gson.fromJson(tool.parameters, JsonObject::class.java)
                }.getOrNull() ?: JsonObject()
                mapOf("type" to "function", "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to parameters
                ))
            }
            requestBodyMap["tool_choice"] = "auto"
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://opendroid.ai")
            .header("X-Title", "USMAN AND UMAR")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OPENROUTER,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from OpenRouter")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val choices = jsonResponse.getAsJsonArray("choices")
                ?: throw IOException("OpenRouter response did not contain choices")
            val messageObj = choices.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
                ?: throw IOException("OpenRouter response contained no assistant message")
            val content = messageObj.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val toolCalls = messageObj.getAsJsonArray("tool_calls")?.mapNotNull { item ->
                runCatching {
                    val call = item.asJsonObject
                    val function = call.getAsJsonObject("function")
                    ProviderToolCall(
                        id = call.get("id")?.asString.orEmpty(),
                        name = function.get("name")?.asString.orEmpty(),
                        arguments = function.get("arguments")?.asString.orEmpty()
                    ).takeIf { it.name.isNotBlank() }
                }.getOrNull()
            }.orEmpty()

            val usage = jsonResponse.getAsJsonObject("usage")
            val tokensUsed = usage?.get("total_tokens")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = tokensUsed,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime,
                toolCalls = toolCalls
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val response = complete(request)
        val words = response.content.split(" ")
        for (word in words) {
            emit("$word ")
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
