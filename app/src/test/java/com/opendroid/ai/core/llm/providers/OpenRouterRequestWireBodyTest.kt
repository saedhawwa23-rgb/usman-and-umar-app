package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captures the exact bytes [OpenRouterProvider] puts on the wire for a chat-only turn.
 * The HTTP client is mocked with an interceptor, so no request reaches openrouter.ai.
 */
class OpenRouterRequestWireBodyTest {

    private class CapturingClient {
        var body: String? = null
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            body = buffer.readUtf8()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"choices":[{"message":{"content":"ok"}}],"usage":{"total_tokens":3}}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private class FakeCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)
        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)
        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())
        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }

    private fun newRepository(): SettingsRepository = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("openrouter-wire-test")
                    .resolve("settings.preferences_pb").toFile()
            },
        ),
        providerCredentialStore = FakeCredentialStore(),
        runStartupMigration = false,
    )

    @Test
    fun `chat-only cf turn sends model and messages and omits tools and tool_choice`() = runBlocking {
        val capture = CapturingClient()
        val provider = OpenRouterProvider(capture.client, newRepository())
        val secret = "sk-or-test-secret-sentinel"

        provider.complete(
            LLMRequest(
                systemPrompt = "You are helpful.",
                messages = listOf(ChatMessage("m1", "cf", ChatMessage.Sender.USER)),
                model = "openrouter/auto",
                responseFormat = ResponseFormat.TEXT,
                tools = null,
                providerConfig = ProviderRequestConfig(apiKey = secret, endpoint = ""),
            )
        )

        val raw = requireNotNull(capture.body) { "No request body was captured." }
        val json = JsonParser.parseString(raw).asJsonObject

        assertTrue("model must be present", json.has("model"))
        assertEquals("openrouter/auto", json.get("model").asString)
        assertTrue("messages must be present", json.has("messages"))
        val messages = json.getAsJsonArray("messages")
        assertTrue(messages.size() >= 2)
        assertEquals("cf", messages[messages.size() - 1].asJsonObject.get("content").asString)

        assertFalse("chat-only turn must not send a tools key", json.has("tools"))
        assertFalse("chat-only turn must not send a tool_choice key", json.has("tool_choice"))
        assertFalse("the API key must never appear in the body", raw.contains(secret))

        // Report the captured body with the (absent) secret already removed.
        println("CAPTURED_OPENROUTER_BODY=$raw")
    }
}
