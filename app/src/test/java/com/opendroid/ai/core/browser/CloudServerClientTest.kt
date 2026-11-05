package com.opendroid.ai.core.browser

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class CloudServerClientTest {
    @Test
    fun `health ranking puts fastest HTTP 200 server first and offline servers last`() {
        val ranked = listOf(
            CloudServerHealth("slow", "Server 1", online = true, latencyMs = 240, httpCode = 200),
            CloudServerHealth("offline", "Server 2", online = false, latencyMs = 15, httpCode = 503),
            CloudServerHealth("fast", "Server 3", online = true, latencyMs = 32, httpCode = 200),
        ).rankCloudServerHealth()

        assertEquals(listOf("fast", "slow", "offline"), ranked.map { it.serverId })
    }

    @Test
    fun `OpenSwarm payload matches realistic execute schema and never serializes bearer credentials`() {
        val body = buildOpenSwarmPayload(
            taskId = "chat-1730838420000",
            prompt = "Go to https://example.com and return the page title",
            pluginId = "openswarm_agent",
            pluginIds = listOf("openswarm_agent", "github_search", "openswarm_agent"),
            thinkHarder = true,
            imageBase64 = null,
            approvedStepIds = listOf("step-a", "step-a", "step-b"),
        )
        val json = JSONObject(body)

        assertEquals("chat-1730838420000", json.getString("task_id"))
        assertEquals("Go to https://example.com and return the page title", json.getString("prompt"))
        assertEquals("openswarm_agent", json.getString("plugin_id"))
        assertTrue(json.getBoolean("think_harder"))
        assertEquals(30, json.getInt("browser_max_steps"))
        assertEquals(2, json.getJSONArray("plugins").length())
        assertEquals(2, json.getJSONArray("approved_step_ids").length())
        assertEquals("step-a", json.getJSONArray("approved_step_ids").getString(0))
        assertFalse(json.has("image_base64"))
        assertFalse(json.has("APP_CLIENT_TOKEN"))
        assertFalse(json.has("authorization"))
    }

    @Test
    fun `cloud execute client applies Localtunnel header and long execution timeouts`() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.start()
        try {
            val client = cloudExecuteOkHttpClient()
            assertEquals(60_000, client.connectTimeoutMillis)
            assertEquals(180_000, client.readTimeoutMillis)
            assertEquals(200_000, client.callTimeoutMillis)
            client.newCall(Request.Builder().url(server.url("/execute")).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals("true", server.takeRequest().headers["Bypass-Tunnel-Reminder"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `cloud diagnostics retain stage status body and URL without credentials`() {
        val failure = CloudRequestException(
            stage = "http_response",
            requestUrl = "https://tunnel.example/execute?token=do-not-log",
            httpStatus = 502,
            responseBody = "{\"error\":\"bad gateway\",\"token\":\"do-not-log\"}",
            secrets = listOf("do-not-log"),
        )

        val message = failure.message.orEmpty()
        assertTrue(message.contains("stage=http_response"))
        assertTrue(message.contains("exception_class=CloudRequestException"))
        assertTrue(message.contains("request_url=https://tunnel.example/execute"))
        assertTrue(message.contains("http_status=502"))
        assertTrue(message.contains("bad gateway"))
        assertFalse(message.contains("do-not-log"))
    }

    @Test
    fun `execute sends authenticated realistic OpenSwarm POST with Localtunnel header`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("{\"status\":\"ok\"}").build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"result\":\"Example Domain\"}").build())
        server.start()
        try {
            val token = "test-bearer-that-must-not-enter-json"
            val client = CloudServerClient(newSettingsRepository(server.url("/").toString().trimEnd('/'), token))
            val response = client.execute(
                taskId = "chat-1730838420000",
                prompt = "Go to https://example.com and return the page title",
                pluginId = "openswarm_agent",
                pluginIds = listOf("openswarm_agent"),
                thinkHarder = true,
            )

            assertTrue(JSONObject(response).getString("result") == "Example Domain")
            val healthRequest = server.takeRequest()
            assertEquals("/health", healthRequest.target)
            assertEquals("true", healthRequest.headers["Bypass-Tunnel-Reminder"])
            val executeRequest = server.takeRequest()
            assertEquals("POST", executeRequest.method)
            assertEquals("/execute", executeRequest.target)
            assertEquals("Bearer $token", executeRequest.headers["Authorization"])
            assertEquals("true", executeRequest.headers["Bypass-Tunnel-Reminder"])
            val body = JSONObject(executeRequest.body?.utf8().orEmpty())
            assertEquals("chat-1730838420000", body.getString("task_id"))
            assertEquals("Go to https://example.com and return the page title", body.getString("prompt"))
            assertEquals("openswarm_agent", body.getString("plugin_id"))
            assertTrue(body.getBoolean("think_harder"))
            assertFalse(body.has("APP_CLIENT_TOKEN"))
            assertFalse(body.toString().contains(token))
        } finally {
            server.close()
        }
    }

    @Test
    fun `execute surfaces real HTTP status and response body without leaking bearer token`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.enqueue(MockResponse.Builder().code(401).body("{\"status\":\"error\",\"error\":\"Invalid application token.\"}").build())
        server.start()
        try {
            val token = "do-not-expose-this-bearer"
            val client = CloudServerClient(newSettingsRepository(server.url("/").toString().trimEnd('/'), token))
            val failure = runCatching {
                client.execute(taskId = "invalid-auth-1", prompt = "Check status", pluginId = "openswarm_agent")
            }.exceptionOrNull()

            assertTrue(failure is CloudRequestException)
            assertTrue(failure?.message.orEmpty().contains("stage=http_response"))
            assertTrue(failure?.message.orEmpty().contains("http_status=401"))
            assertTrue(failure?.message.orEmpty().contains("Invalid application token."))
            assertFalse(failure?.message.orEmpty().contains(token) == true)
            val health = server.takeRequest()
            assertEquals("/health", health.target)
            val execute = server.takeRequest()
            assertEquals("Bearer $token", execute.headers["Authorization"])
            assertFalse(execute.body?.utf8().orEmpty().contains(token))
        } finally {
            server.close()
        }
    }

    @Test
    fun `unavailable tunnel is reported at the health check stage without attempting execute`() = runBlocking {
        val client = CloudServerClient(newSettingsRepository("http://127.0.0.1:1", "offline-test-token"))

        val failure = runCatching {
            client.execute(taskId = "offline-1", prompt = "Check status", pluginId = "openswarm_agent")
        }.exceptionOrNull()

        assertTrue(failure is CloudRequestException)
        assertTrue(failure?.message.orEmpty().contains("stage=health_check"))
        assertTrue(failure?.message.orEmpty().contains("request_url=http://127.0.0.1:1/health"))
        assertFalse(failure?.message.orEmpty().contains("offline-test-token") == true)
    }

    @Test
    fun `slow response triggers the configured client read timeout`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("delayed response")
                .bodyDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .addInterceptor(LocalTunnelReminderInterceptor)
                .build()
            val failure = runCatching {
                client.newCall(Request.Builder().url(server.url("/execute")).build()).execute().use { it.body.string() }
            }.exceptionOrNull()
            assertTrue("Expected a read timeout, got $failure", failure is SocketTimeoutException)
        } finally {
            server.close()
        }
    }

    private fun newSettingsRepository(url: String, token: String): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("cloud-client-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            },
        )
        runBlocking {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("openswarm_server_1_url")] = url
                preferences[booleanPreferencesKey("openswarm_server_1_enabled")] = true
            }
        }
        return SettingsRepository(dataStore, TestCredentialStore(token), runStartupMigration = false)
    }

    private class TestCredentialStore(private val serverToken: String) : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(if (credential == ProviderCredentialId.OpenSwarmServer1Token) serverToken else null)

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
}
