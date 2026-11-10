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
    fun `health parser requires every explicit readiness field and retains recovery diagnostics`() {
        val ready = parseCloudServerHealth("configured-1", "Server 1", 200, readyHealthBody)
        val legacy = parseCloudServerHealth("legacy", "Legacy", 200, "{}")
        val draining = parseCloudServerHealth(
            "draining", "Draining", 200,
            readyHealthBody.replace("\"draining\":false", "\"draining\":true"),
        )

        assertTrue(ready.online)
        assertEquals("backend-1", ready.reportedServerId)
        assertEquals("instance-a", ready.instanceId)
        assertEquals("recovering", ready.recoveryState)
        assertEquals("restoring checkpoint", ready.recoveryReason)
        assertEquals(3, ready.activeTasks)
        assertFalse(legacy.online)
        assertTrue(legacy.error.orEmpty().contains("missing"))
        assertFalse(draining.online)
    }

    @Test
    fun `health requires the exact Colab readiness flags`() {
        val health = parseCloudServerHealth(
            "redis-node", "Redis node", 200,
            """{"accepting_new_tasks":true,"draining":false,"checkpoint_ready":true,"lease_ready":true}""",
        )

        assertFalse(health.online)
        assertEquals(true, health.checkpointReady)
        assertEquals(true, health.leaseReady)
        assertTrue(health.error.orEmpty().contains("ready="))
    }

    @Test
    fun `legacy checkpoint and lease aliases do not satisfy exact readiness gates`() {
        val health = parseCloudServerHealth(
            "redis-node", "Redis node", 200,
            """{"status":"ready","accepting_new_tasks":true,"draining":false,"drive_ready":false,"checkpoint_storage_ready":true,"lease_coordinator_ready":true}""",
        )

        assertFalse(health.online)
        assertEquals(true, health.checkpointReady)
        assertEquals(true, health.leaseReady)
        assertTrue(health.error.orEmpty().contains("ready="))
    }

    @Test
    fun `redis health eligibility rejects each missing or false required readiness flag`() {
        val healthy = readyHealthBody
        val notAccepting = healthy.replace("\"accepting_new_tasks\":true", "\"accepting_new_tasks\":false")
        val draining = healthy.replace("\"draining\":false", "\"draining\":true")
        val missingCheckpoint = healthy.replace("\"checkpoints_ready\":true,", "")
        val missingLease = healthy.replace(",\"lease_ready\":true", "")

        listOf(notAccepting, draining, missingCheckpoint, missingLease).forEach { body ->
            assertFalse(parseCloudServerHealth("redis-node", "Redis node", 200, body).online)
        }
    }

    @Test
    fun `HTTP 200 legacy health is not routed to for execution`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.start()
        try {
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), "legacy-health-token")
            val failure = runCatching {
                client.execute(taskId = "not-ready", prompt = "Do not dispatch", pluginId = "openswarm_agent")
            }.exceptionOrNull()
            assertTrue(failure is CloudRequestException)
            assertTrue(failure?.message.orEmpty().contains("stage=health_check"))
            assertTrue(failure?.message.orEmpty().contains("OpenSwarm server offline. Start the Colab notebook and try again."))
            assertEquals("/health", server.takeRequest().target)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `connection test GETs health with Bearer token and preserves backend message`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().code(401)
                .body("""{"status":"error","message":"APP_CLIENT_TOKEN rejected by Colab."}""").build(),
        )
        server.start()
        try {
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), "connection-test-secret")
            val health = client.testOpenSwarmConnection("https://test-node.loca.lt", "connection-test-secret")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/health", request.target)
            assertEquals("Bearer connection-test-secret", request.headers["Authorization"])
            assertEquals(401, health.httpCode)
            assertEquals("APP_CLIENT_TOKEN rejected by Colab.", health.readinessMessage)
            assertFalse(health.online)
        } finally {
            server.close()
        }
    }

    @Test
    fun `OpenSwarm manifest and execute use authenticated existing endpoints`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("""{"tools":[{"id":"browser.extract","description":"Extract page","permissions":["network"],"risk":"low","adapter_configured":true},{"id":"files.read","risk":"low","adapter_configured":false}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"executed","capability_id":"browser.extract","result":{"verified":true}}""").build())
        server.start()
        try {
            val token = "manifest-bearer-secret"
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), token)
            val tools = client.fetchOpenSwarmTools()
            assertEquals(listOf("browser.extract", "files.read"), tools.map { it.id })
            assertTrue(tools.first().adapterConfigured)
            assertFalse(tools.last().adapterConfigured)
            assertEquals(tools, OpenSwarmToolManifestCache.snapshot())
            val listRequest = server.takeRequest()
            assertEquals("/tools", listRequest.target)
            assertEquals("Bearer $token", listRequest.headers["Authorization"])

            val response = client.executeOpenSwarmTool("browser.extract", JSONObject("""{"url":"https://example.com"}"""))
            assertEquals("executed", response.getString("status"))
            val execute = server.takeRequest()
            assertEquals("POST", execute.method)
            assertEquals("/tools/browser.extract/execute", execute.target)
            assertEquals("Bearer $token", execute.headers["Authorization"])
            val body = JSONObject(execute.body!!.utf8())
            assertFalse(body.getBoolean("approved"))
            assertEquals("https://example.com", body.getJSONObject("payload").getString("url"))
            assertFalse(execute.body!!.utf8().contains(token))
        } finally {
            server.close()
        }
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
    fun `control cancellation waits for terminal confirmation instead of trusting accepted request`() = runBlocking {
        val server = MockWebServer()
        val drainingHealth = readyHealthBody.replace("\"accepting_new_tasks\":true", "\"accepting_new_tasks\":false")
            .replace("\"draining\":false", "\"draining\":true")
        server.enqueue(MockResponse.Builder().code(200).body(drainingHealth).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"task_id":"task-cancel","state":"CANCEL_REQUESTED"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"task_id":"task-cancel","state":"CANCEL_REQUESTED"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"task_id":"task-cancel","state":"CANCELLED"}""").build())
        server.start()
        try {
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), "cancel-token")
            val result = JSONObject(client.controlTask("task-cancel", "cancel", "legacy-1"))
            assertEquals("CANCELLED", result.getString("state"))
            assertEquals("/health", server.takeRequest().target)
            assertEquals("/tasks/task-cancel/cancel", server.takeRequest().target)
            assertEquals("/tasks/task-cancel", server.takeRequest().target)
            assertEquals("/tasks/task-cancel", server.takeRequest().target)
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
            errorCode = "EXECUTION_OUTCOME_UNKNOWN",
            backendMessage = "Task outcome requires reconciliation.",
        )

        val message = failure.message.orEmpty()
        assertEquals("EXECUTION_OUTCOME_UNKNOWN", failure.errorCode)
        assertEquals("Task outcome requires reconciliation.", failure.userFacingSummary())
        assertTrue(failure.technicalDetails().contains("stage=http_response"))
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
        server.enqueue(MockResponse.Builder().code(200).body(readyHealthBody).build())
        server.enqueue(MockResponse.Builder().code(200).body("{\"result\":\"Example Domain\"}").build())
        server.start()
        try {
            val token = "test-bearer-that-must-not-enter-json"
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), token)
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
        server.enqueue(MockResponse.Builder().code(200).body(readyHealthBody).build())
        server.enqueue(MockResponse.Builder().code(401).body("{\"status\":\"error\",\"error\":\"Invalid application token.\"}").build())
        server.start()
        try {
            val token = "do-not-expose-this-bearer"
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), token)
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
    fun `execute displays structured recovery error fields and redacts credentials`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body(readyHealthBody).build())
        server.enqueue(
            MockResponse.Builder().code(503).body(
                """{"status":"error","code":"TASK_RECOVERING","message":"runtime is restoring","task_id":"task-42","recovery_state":"checkpoint_restore","recovery_action":"wait","token":"structured-secret"}""",
            ).build(),
        )
        server.start()
        try {
            val token = "structured-secret"
            val client = newCloudClient(server.url("/").toString().trimEnd('/'), token)
            val failure = runCatching {
                client.execute(taskId = "task-42", prompt = "Run task", pluginId = "openswarm_agent")
            }.exceptionOrNull()
            val message = failure?.message.orEmpty()
            assertTrue(failure is CloudRequestException)
            assertTrue(message.contains("http_status=503"))
            assertTrue(message.contains("code=TASK_RECOVERING"))
            assertTrue(message.contains("message=runtime is restoring"))
            assertTrue(message.contains("task_id=task-42"))
            assertTrue(message.contains("recovery_state=checkpoint_restore"))
            assertTrue(message.contains("recovery_action=wait"))
            assertFalse(message.contains(token))
        } finally {
            server.close()
        }
    }

    @Test
    fun `unavailable tunnel is reported at the health check stage without attempting execute`() = runBlocking {
        val client = newCloudClient("http://127.0.0.1:1", "offline-test-token")

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

    private fun newCloudClient(endpoint: String, token: String): CloudServerClient =
        CloudServerClient(newSettingsRepository("https://mock-node.loca.lt", token)).apply {
            endpointBaseOverrideForTests = endpoint
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

    private val readyHealthBody = """{"status":"ready","ready":true,"auth_ready":true,"server_id":"backend-1","instance_id":"instance-a","uptime_seconds":1234,"accepting_new_tasks":true,"draining":false,"redis_ready":true,"checkpoints_ready":true,"checkpoint_storage_ready":true,"lease_ready":true,"lease_coordinator_ready":true,"active_tasks":3,"recovery_state":"recovering","recovery_reason":"restoring checkpoint"}"""

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
