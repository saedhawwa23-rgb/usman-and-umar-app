package com.opendroid.ai.core.browser

import com.opendroid.ai.data.repository.CloudServerConfig
import com.opendroid.ai.data.repository.SettingsRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Result of a live `/health` probe. A healthy server is strictly an HTTP 200 response. */
data class CloudServerHealth(
    val serverId: String,
    val serverName: String,
    val online: Boolean,
    val latencyMs: Long? = null,
    val httpCode: Int? = null,
    val error: String? = null,
)


/** Agent metadata returned by a live OpenSwarm backend. */
data class RemoteOpenSwarmAgent(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: List<String>,
    val requiresModel: Boolean,
    val adapterConfigured: Boolean,
)

/** Shared network path for cluster health checks, chat, and workspace cloud tasks. */
@Singleton
class CloudServerClient @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(65, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(65, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build()

    private val controlClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Probes every enabled configured server concurrently and orders healthy servers by latency. */
    suspend fun pingAll(includeDisabled: Boolean = false): List<CloudServerHealth> = probeServers(
        settingsRepository.cloudServers.first().filter { (includeDisabled || it.enabled) && it.url.isNotBlank() },
    )

    suspend fun execute(
        taskId: String,
        prompt: String,
        pluginId: String = "openswarm_agent",
        pluginIds: List<String> = listOf(pluginId),
        targetId: String? = null,
        thinkHarder: Boolean = false,
        imageBase64: String? = null,
        approvedStepIds: List<String> = emptyList(),
        onLog: (String) -> Unit = {},
    ): String {
        val configured = settingsRepository.cloudServers.first()
        val candidates = configured.filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        check(candidates.isNotEmpty()) { "Enable and configure at least one cloud server in Remote settings." }

        val health = probeServers(candidates)
        val healthyIds = health.filter { it.online }.mapTo(hashSetOf()) { it.serverId }
        check(healthyIds.isNotEmpty()) { "No enabled OpenSwarm server returned HTTP 200 from /health." }
        val rankedHealthy = health.asSequence()
            .filter { it.online }
            .mapNotNull { result -> candidates.firstOrNull { it.id == result.serverId } }
            .toList()
        val ordered = targetId?.let { id ->
            val selected = candidates.firstOrNull { it.id == id }
                ?: error("The selected cloud server is disabled or not configured.")
            if (selected.id !in healthyIds) {
                if (approvedStepIds.isNotEmpty()) {
                    error("The original OpenSwarm server ${selected.name} is unavailable. Approval resume is pinned to its original task store; no cross-server retry was attempted.")
                }
                onLog("${selected.name} failed its /health check. Failing over to ${rankedHealthy.first().name}...")
                rankedHealthy
            } else {
                listOf(selected) + rankedHealthy.filterNot { it.id == selected.id }
            }
        } ?: rankedHealthy

        // Construct once, before failover, so every server receives byte-for-byte identical JSON.
        val clientToken = settingsRepository.currentOpenSwarmClientToken()
        val payload = buildOpenSwarmPayload(
            taskId = taskId,
            prompt = prompt,
            pluginId = pluginId,
            pluginIds = pluginIds,
            thinkHarder = thinkHarder,
            imageBase64 = imageBase64,
            appClientToken = clientToken,
            approvedStepIds = approvedStepIds,
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastError: Throwable? = null
        for ((position, server) in ordered.withIndex()) {
            try {
                val baseUrl = normalizeServerBase(server.url)
                val request = Request.Builder()
                    .url("$baseUrl/execute")
                    .header("Authorization", "Bearer ${server.token}")
                    .post(payload)
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val enriched = runCatching {
                            JSONObject(responseBody).put("_cloud_server_id", server.id).toString()
                        }.getOrDefault(responseBody)
                        return enriched
                    }
                    val retryable = response.code == 500 || response.code == 503 || response.code == 504
                    if (!retryable || position == ordered.lastIndex) {
                        error(BrowserUseRemoteProtocol.httpError(response.code, responseBody))
                    }
                    lastError = IOException("HTTP ${response.code}")
                }
            } catch (failure: SocketTimeoutException) {
                lastError = failure
            } catch (failure: IOException) {
                lastError = failure
            }
            if (position < ordered.lastIndex) {
                onLog("${server.name} failed. Failing over to ${ordered[position + 1].name}...")
            }
        }
        throw lastError ?: IOException("All configured healthy cloud servers failed.")
    }



    /** Sends pause/cancel/resume to the server that owns the task; without a pin, finds it across the configured cluster. */
    suspend fun controlTask(taskId: String, action: String, targetId: String? = null): String {
        require(taskId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid cloud task ID." }
        require(action in setOf("pause", "cancel", "resume")) { "Unsupported cloud task control action." }
        val configured = settingsRepository.cloudServers.first()
            .filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        check(configured.isNotEmpty()) { "Enable and configure at least one cloud server in Remote settings." }
        val healthy = probeServers(configured).filter { it.online }
        check(healthy.isNotEmpty()) { "No enabled OpenSwarm server returned HTTP 200 from /health." }
        val ordered = if (targetId != null) {
            val selected = configured.firstOrNull { it.id == targetId }
                ?: error("The original cloud server is no longer configured.")
            check(healthy.any { it.serverId == selected.id }) {
                "The original cloud server ${selected.name} is offline; task control will not move its local task state to another server."
            }
            listOf(selected)
        } else {
            healthy.mapNotNull { health -> configured.firstOrNull { it.id == health.serverId } }
        }
        var lastError: Throwable? = null
        for (server in ordered) {
            try {
                val baseUrl = normalizeServerBase(server.url)
                val request = Request.Builder()
                    .url("$baseUrl/tasks/$taskId/$action")
                    .header("Authorization", "Bearer ${server.token}")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                var successfulBody: String? = null
                var shouldTryNextServer = false
                controlClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        successfulBody = runCatching {
                            JSONObject(responseBody).put("_cloud_server_id", server.id).toString()
                        }.getOrDefault(responseBody)
                    } else if (response.code == 404 || response.code == 409 ||
                        response.code in setOf(500, 503, 504)) {
                        lastError = IOException("HTTP ${response.code}: ${responseBody.take(250)}")
                        shouldTryNextServer = true
                    } else {
                        error(BrowserUseRemoteProtocol.httpError(response.code, responseBody))
                    }
                }
                if (successfulBody != null) return successfulBody!!
                if (!shouldTryNextServer || targetId != null) break
            } catch (failure: IOException) {
                lastError = failure
                if (targetId != null) break
            }
        }
        throw lastError ?: IOException("No configured server owns cloud task $taskId.")
    }

    /** Reads the real agent roster and adapter readiness from the fastest healthy configured backend. */
    suspend fun fetchOpenSwarmAgents(): List<RemoteOpenSwarmAgent> {
        val configured = settingsRepository.cloudServers.first()
            .filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        check(configured.isNotEmpty()) { "Enable and configure a cloud server first." }
        val healthy = probeServers(configured).filter { it.online }
        check(healthy.isNotEmpty()) { "No configured OpenSwarm server is healthy." }
        val server = configured.first { candidate -> candidate.id == healthy.first().serverId }
        val baseUrl = normalizeServerBase(server.url)
        fun getJson(path: String): JSONObject {
            val request = Request.Builder().url("$baseUrl$path")
                .header("Authorization", "Bearer ${server.token}").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("OpenSwarm $path returned HTTP ${response.code}: ${body.take(300)}")
                return JSONObject(body)
            }
        }
        val capabilityItems = getJson("/capabilities").optJSONArray("capabilities") ?: JSONArray()
        val readiness = buildMap<String, Boolean> {
            for (index in 0 until capabilityItems.length()) {
                val item = capabilityItems.optJSONObject(index) ?: continue
                put(item.optString("id"), item.optBoolean("adapter_configured", false))
            }
        }
        val agentItems = getJson("/agents").optJSONArray("agents") ?: JSONArray()
        return buildList {
            for (index in 0 until agentItems.length()) {
                val item = agentItems.optJSONObject(index) ?: continue
                val caps = item.optJSONArray("capabilities") ?: JSONArray()
                add(RemoteOpenSwarmAgent(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    description = item.optString("description"),
                    capabilities = (0 until caps.length()).map { caps.optString(it) },
                    requiresModel = item.optBoolean("requires_model", false),
                    adapterConfigured = readiness[item.optString("id")] == true,
                ))
            }
        }
    }

    private suspend fun probeServers(servers: List<CloudServerConfig>): List<CloudServerHealth> = coroutineScope {
        servers.map { server ->
            async(Dispatchers.IO) { probe(server) }
        }.awaitAll().rankCloudServerHealth()
    }

    private fun probe(server: CloudServerConfig): CloudServerHealth {
        val started = System.nanoTime()
        return try {
            val builder = Request.Builder().url("${normalizeServerBase(server.url)}/health").get()
            server.token.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            healthClient.newCall(builder.build()).execute().use { response ->
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                CloudServerHealth(
                    serverId = server.id,
                    serverName = server.name,
                    online = response.code == 200,
                    latencyMs = elapsed,
                    httpCode = response.code,
                    error = if (response.code == 200) null else "HTTP ${response.code}",
                )
            }
        } catch (failure: Exception) {
            CloudServerHealth(
                serverId = server.id,
                serverName = server.name,
                online = false,
                latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    private fun normalizeServerBase(raw: String): String = raw.trimEnd('/')
        .removeSuffix("/execute")
        .removeSuffix("/health")
        .trimEnd('/')

}

internal fun buildOpenSwarmPayload(
    taskId: String,
    prompt: String,
    pluginId: String,
    pluginIds: List<String>,
    thinkHarder: Boolean,
    imageBase64: String?,
    appClientToken: String,
    approvedStepIds: List<String> = emptyList(),
): String = JSONObject()
    .put("task_id", taskId)
    .put("prompt", prompt)
    .put("plugin_id", pluginId)
    .put("plugins", JSONArray(pluginIds.distinct().filter { it.isNotBlank() }))
    .put("browser_max_steps", if (thinkHarder) 30 else 15)
    .put("think_harder", thinkHarder)
    .put("approved_step_ids", JSONArray(approvedStepIds.distinct().filter { it.isNotBlank() }))
    .put("APP_CLIENT_TOKEN", appClientToken)
    .apply { imageBase64?.takeIf { it.isNotBlank() }?.let { put("image_base64", it) } }
    .toString()

internal fun List<CloudServerHealth>.rankCloudServerHealth(): List<CloudServerHealth> = sortedWith(
    compareBy<CloudServerHealth> { if (it.online) 0 else 1 }
        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        .thenBy { it.serverName.lowercase() },
)
