package com.opendroid.ai.core.browser

import com.opendroid.ai.data.repository.CloudServerConfig
import com.opendroid.ai.data.repository.SettingsRepository
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Result of a live `/health` probe. Only explicit full backend readiness is eligible for work. */
data class CloudServerHealth(
    val serverId: String,
    val serverName: String,
    val online: Boolean,
    val latencyMs: Long? = null,
    val httpCode: Int? = null,
    val error: String? = null,
    val backendStatus: String? = null,
    val reportedServerId: String? = null,
    val instanceId: String? = null,
    val uptimeSeconds: Long? = null,
    val acceptingNewTasks: Boolean? = null,
    val draining: Boolean? = null,
    val driveReady: Boolean? = null,
    val checkpointStorageReady: Boolean? = null,
    val leaseCoordinatorReady: Boolean? = null,
    val activeTasks: Int? = null,
    val recoveryState: String? = null,
    val recoveryReason: String? = null,
)

/** Strictly parses `/health`; absent or mistyped readiness flags always make a server ineligible. */
internal fun parseCloudServerHealth(
    serverId: String,
    serverName: String,
    httpCode: Int,
    body: String,
    latencyMs: Long? = null,
    secrets: List<String> = emptyList(),
): CloudServerHealth {
    val json = runCatching { JSONObject(body) }.getOrNull()
    fun text(key: String): String? = (json?.opt(key) as? String)?.trim()
        ?.takeIf { it.isNotBlank() }?.let { sanitizeCloudDiagnostic(it, secrets) }
    fun bool(key: String): Boolean? = json?.opt(key) as? Boolean
    fun long(key: String): Long? = (json?.opt(key) as? Number)?.toLong()
    fun int(key: String): Int? = (json?.opt(key) as? Number)?.toInt()

    val backendStatus = text("status")
    val reportedServerId = text("server_id")
    val instanceId = text("instance_id")
    val accepting = bool("accepting_new_tasks")
    val draining = bool("draining")
    val driveReady = bool("drive_ready")
    val checkpointReady = bool("checkpoint_storage_ready")
    val leaseReady = bool("lease_coordinator_ready")
    val recoveryState = text("recovery_state")
    val recoveryReason = text("recovery_reason")
    val ready = httpCode == 200 && backendStatus == "ready" && accepting == true && draining == false &&
        driveReady == true && checkpointReady == true && leaseReady == true
    val readinessIssues = buildList {
        if (httpCode != 200) add("HTTP $httpCode")
        if (backendStatus != "ready") add("status=${backendStatus ?: "missing"}")
        if (accepting != true) add("accepting_new_tasks=${accepting ?: "missing"}")
        if (draining != false) add("draining=${draining ?: "missing"}")
        if (driveReady != true) add("drive_ready=${driveReady ?: "missing"}")
        if (checkpointReady != true) add("checkpoint_storage_ready=${checkpointReady ?: "missing"}")
        if (leaseReady != true) add("lease_coordinator_ready=${leaseReady ?: "missing"}")
    }
    val reportedError = text("error")
    val reason = recoveryReason ?: reportedError ?: readinessIssues.joinToString(", ").takeIf { it.isNotEmpty() }
    return CloudServerHealth(
        serverId = serverId,
        serverName = serverName,
        online = ready,
        latencyMs = latencyMs,
        httpCode = httpCode,
        error = reason?.takeIf { !ready } ?: if (ready) null else readinessIssues.joinToString(", "),
        backendStatus = backendStatus,
        reportedServerId = reportedServerId,
        instanceId = instanceId,
        uptimeSeconds = long("uptime_seconds"),
        acceptingNewTasks = accepting,
        draining = draining,
        driveReady = driveReady,
        checkpointStorageReady = checkpointReady,
        leaseCoordinatorReady = leaseReady,
        activeTasks = int("active_tasks"),
        recoveryState = recoveryState,
        recoveryReason = reason,
    )
}


/** Agent metadata returned by a live OpenSwarm backend. */
data class RemoteOpenSwarmAgent(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: List<String>,
    val requiresModel: Boolean,
    val adapterConfigured: Boolean,
)

internal const val CLOUD_LOG_TAG = "CloudOpenSwarm"

/** Structured failure details shown to the user and written to Logcat without credentials. */
internal class CloudRequestException(
    val stage: String,
    requestUrl: String?,
    val httpStatus: Int? = null,
    responseBody: String? = null,
    cause: Throwable? = null,
    secrets: List<String> = emptyList(),
    val errorCode: String? = null,
    private val backendMessage: String? = null,
) : IOException(
    buildString {
        append("stage=").append(safeDiagnosticValue(stage))
        append(" exception_class=").append(cause?.javaClass?.simpleName ?: "CloudRequestException")
        append(" request_url=").append(requestUrl?.let(::safeDiagnosticUrl) ?: "not-built")
        append(" http_status=").append(httpStatus?.toString() ?: "none")
        append(" response_body=").append(sanitizeCloudDiagnostic(responseBody.orEmpty(), secrets).ifBlank { "<empty>" })
    },
    cause,
) {
    fun technicalDetails(): String = message.orEmpty()

    fun userFacingSummary(): String = backendMessage?.takeIf { it.isNotBlank() } ?: when (httpStatus) {
        401, 403 -> "The cloud server rejected the configured credentials."
        409 -> "The cloud task could not safely continue because its state or ownership conflicted."
        503 -> "The cloud backend is unavailable, recovering, or not accepting new tasks."
        else -> "The cloud task request failed${httpStatus?.let { " (HTTP $it)" }.orEmpty()}."
    }
}

internal fun sanitizeCloudDiagnostic(value: String, secrets: List<String> = emptyList()): String {
    var safe = value
    secrets.filter { it.isNotBlank() }.forEach { secret -> safe = safe.replace(secret, "[REDACTED]", ignoreCase = false) }
    safe = safe
        .replace(Regex("(?i)bearer\\s+[^\\s\\\"']+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|app_client_token|password|secret|token)(\\\"?\\s*[:=]\\s*\\\"?)[^\\s,\\\"}]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@"), "$1[REDACTED]@")
        .replace(Regex("[\\r\\n\\t]+"), " ")
    return safe.take(1_200)
}

internal fun safeDiagnosticValue(value: String): String =
    sanitizeCloudDiagnostic(value).replace(Regex("\\s+"), "_").take(160)

internal fun safeDiagnosticUrl(raw: String): String = runCatching {
    val parsed = URI(raw)
    val port = if (parsed.port >= 0) ":${parsed.port}" else ""
    "${parsed.scheme}://${parsed.host.orEmpty()}$port${parsed.path.orEmpty()}"
}.getOrDefault(raw.substringBefore('?').substringBefore('#').take(300))

internal object LocalTunnelReminderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder()
            .header("Bypass-Tunnel-Reminder", "true")
            .build()
        return chain.proceed(request)
    }
}

internal fun cloudExecuteOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(200, TimeUnit.SECONDS)
    .addInterceptor(LocalTunnelReminderInterceptor)
    .build()

/** Shared network path for cluster health checks, chat, and workspace cloud tasks. */
@Singleton
class CloudServerClient @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    private val client = cloudExecuteOkHttpClient()

    private val controlClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .addInterceptor(LocalTunnelReminderInterceptor)
        .build()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(LocalTunnelReminderInterceptor)
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
        onDispatch: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val configured = settingsRepository.cloudServers.first()
        val candidates = configured.filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        if (candidates.isEmpty()) {
            throw CloudRequestException(
                stage = "configuration",
                requestUrl = null,
                responseBody = "Enable and configure at least one cloud server in Remote settings.",
            )
        }

        val health = probeServers(candidates)
        val healthyIds = health.filter { it.online }.mapTo(hashSetOf()) { it.serverId }
        if (healthyIds.isEmpty()) {
            val selected = candidates.first()
            val healthDetail = health.joinToString("; ") { result ->
                "${result.serverName}: ${result.error ?: "offline"}${result.httpCode?.let { " (HTTP $it)" }.orEmpty()}"
            }
            throw CloudRequestException(
                stage = "health_check",
                requestUrl = "${normalizeServerBase(selected.url)}/health",
                httpStatus = health.firstOrNull()?.httpCode,
                responseBody = "No enabled OpenSwarm server reported full readiness from /health. $healthDetail",
            )
        }
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
            } else if (approvedStepIds.isNotEmpty()) {
                // Approval resumes must stay on the original task store even if
                // another healthy node is available.
                listOf(selected)
            } else {
                listOf(selected) + rankedHealthy.filterNot { it.id == selected.id }
            }
        } ?: rankedHealthy

        // Construct once, before failover, so every server receives byte-for-byte identical JSON.
        val payload = try {
            buildOpenSwarmPayload(
                taskId = taskId,
                prompt = prompt,
                pluginId = pluginId,
                pluginIds = pluginIds,
                thinkHarder = thinkHarder,
                imageBase64 = imageBase64,
                approvedStepIds = approvedStepIds,
            ).toRequestBody("application/json; charset=utf-8".toMediaType())
        } catch (failure: Exception) {
            throw CloudRequestException("request_serialization", null, cause = failure)
        }

        var lastError: CloudRequestException? = null
        for ((position, server) in ordered.withIndex()) {
            val baseUrl = normalizeServerBase(server.url)
            val requestUrl = "$baseUrl/execute"
            val safeUrl = safeDiagnosticUrl(requestUrl)
            try {
                onDispatch(server.id)
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Authorization", "Bearer ${server.token}")
                    .post(payload)
                    .build()
                Log.i(CLOUD_LOG_TAG, "request_dispatch stage=http_dispatch method=POST url=$safeUrl task_id=${safeDiagnosticValue(taskId)}")
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    Log.i(CLOUD_LOG_TAG, "request_response stage=http_response url=$safeUrl task_id=${safeDiagnosticValue(taskId)} http_status=${response.code}")
                    if (response.isSuccessful) {
                        val enriched = runCatching {
                            JSONObject(responseBody).put("_cloud_server_id", server.id).toString()
                        }.getOrDefault(responseBody)
                        return@withContext enriched
                    }
                    val backendError = BrowserUseRemoteProtocol.parseBackendError(
                        response.code, responseBody, listOf(server.token),
                    )
                    // A server response may follow partial task execution. Never replay a
                    // task on another node merely because this node returned 5xx.
                    throw CloudRequestException(
                        stage = "http_response",
                        requestUrl = requestUrl,
                        httpStatus = response.code,
                        responseBody = BrowserUseRemoteProtocol.httpError(response.code, responseBody, listOf(server.token)),
                        secrets = listOf(server.token),
                        errorCode = backendError?.code,
                        backendMessage = backendError?.message,
                    )
                }
            } catch (failure: CloudRequestException) {
                Log.e(CLOUD_LOG_TAG, failure.message.orEmpty())
                throw failure
            } catch (failure: ConnectException) {
                // Connection refusal happens before the HTTP request is delivered.
                lastError = CloudRequestException("connect", requestUrl, cause = failure, secrets = listOf(server.token))
            } catch (failure: UnknownHostException) {
                // DNS resolution failed before the request could be delivered.
                lastError = CloudRequestException("dns_resolution", requestUrl, cause = failure, secrets = listOf(server.token))
            } catch (failure: IOException) {
                // Read timeouts and connection resets are ambiguous: the server may
                // have accepted and executed the task. Fail closed to prevent duplicates.
                throw CloudRequestException(
                    stage = "network_io",
                    requestUrl = requestUrl,
                    responseBody = "The request may have reached the server; automatic cross-server retry was stopped to avoid duplicate task execution. Check this server's task history before retrying.",
                    cause = failure,
                    secrets = listOf(server.token),
                )
            }
            if (position < ordered.lastIndex) {
                onLog("${server.name} could not be reached before dispatch. Failing over to ${ordered[position + 1].name}...")
            }
        }
        throw lastError ?: CloudRequestException("dispatch", null, responseBody = "All configured healthy cloud servers failed.")
    }



    /** Sends pause/cancel/resume to the server that owns the task; without a pin, finds it across the configured cluster. */
    suspend fun controlTask(taskId: String, action: String, targetId: String? = null): String {
        require(taskId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid cloud task ID." }
        require(action in setOf("pause", "cancel", "resume")) { "Unsupported cloud task control action." }
        val configured = settingsRepository.cloudServers.first()
            .filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        check(configured.isNotEmpty()) { "Enable and configure at least one cloud server in Remote settings." }
        // Controls target already-running work, so a draining/not-ready server
        // with a live HTTP health endpoint must remain reachable for pause/cancel.
        // New task dispatch continues to require the stricter `online` predicate.
        val reachable = probeServers(configured).filter { it.httpCode == 200 }
        check(reachable.isNotEmpty()) { "No enabled OpenSwarm server returned an HTTP health response for task control." }
        val ordered = if (targetId != null) {
            val selected = configured.firstOrNull { it.id == targetId }
                ?: error("The original cloud server is no longer configured.")
            check(reachable.any { it.serverId == selected.id }) {
                "The original cloud server ${selected.name} is offline; task control will not move its local task state to another server."
            }
            listOf(selected)
        } else {
            reachable.mapNotNull { health -> configured.firstOrNull { it.id == health.serverId } }
        }
        var lastError: Throwable? = null
        for (server in ordered) {
            var serverAcceptedRequest = false
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
                        successfulBody = responseBody
                        serverAcceptedRequest = true
                    } else if (response.code == 404) {
                        lastError = IOException("Task not found on ${server.name} (HTTP 404).")
                        shouldTryNextServer = true
                    } else {
                        error(BrowserUseRemoteProtocol.httpError(response.code, responseBody, listOf(server.token)))
                    }
                }
                if (successfulBody != null) {
                    var completedBody = successfulBody!!
                    if (action == "pause" && runCatching {
                            JSONObject(completedBody).optString("state") == "PAUSE_REQUESTED"
                        }.getOrDefault(false)
                    ) {
                        completedBody = awaitPausedCheckpoint(server, taskId)
                    }
                    if (action == "cancel" && runCatching {
                            JSONObject(completedBody).optString("state") == "CANCEL_REQUESTED"
                        }.getOrDefault(false)
                    ) {
                        completedBody = awaitCancellationConfirmation(server, taskId)
                    }
                    return runCatching {
                        JSONObject(completedBody).put("_cloud_server_id", server.id).toString()
                    }.getOrDefault(completedBody)
                }
                if (!shouldTryNextServer || targetId != null) break
            } catch (failure: ConnectException) {
                lastError = failure
                if (targetId != null || serverAcceptedRequest) break
            } catch (failure: UnknownHostException) {
                lastError = failure
                if (targetId != null || serverAcceptedRequest) break
            } catch (failure: IOException) {
                lastError = failure
                // An I/O timeout/reset can happen after the POST reached the
                // server. Only explicit pre-connect failures are safe to retry.
                break
            }
        }
        throw lastError ?: IOException("No configured server owns cloud task $taskId.")
    }


    /** Wait until the active specialist finishes and the backend persists a PAUSED checkpoint. */
    private suspend fun awaitPausedCheckpoint(server: CloudServerConfig, taskId: String): String {
        val baseUrl = normalizeServerBase(server.url)
        repeat(180) {
            delay(1_000)
            val request = Request.Builder()
                .url("$baseUrl/tasks/$taskId")
                .header("Authorization", "Bearer ${server.token}")
                .get()
                .build()
            val responseBody = withContext(Dispatchers.IO) {
                controlClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code in setOf(500, 502, 503, 504)) return@withContext null
                        throw IOException("Could not confirm the pause checkpoint (HTTP ${response.code}): ${body.take(250)}")
                    }
                    body
                }
            } ?: return@repeat
            val state = runCatching { JSONObject(responseBody).optString("state") }.getOrDefault("")
            when (state) {
                "PAUSED", "CANCELLED" -> return responseBody
                "PAUSE_REQUESTED", "RUNNING" -> Unit
                else -> throw IOException("Pause request ended before a safe checkpoint; remote task state is ${state.ifBlank { "unknown" }}.")
            }
        }
        throw SocketTimeoutException(
            "Pause was accepted, but the current specialist did not reach a checkpoint within 180 seconds. The task may still be running; do not resume it yet."
        )
    }

    /** A successful control POST is only a request; confirmation requires terminal task state. */
    private suspend fun awaitCancellationConfirmation(server: CloudServerConfig, taskId: String): String {
        val baseUrl = normalizeServerBase(server.url)
        repeat(180) {
            delay(1_000)
            val request = Request.Builder()
                .url("$baseUrl/tasks/$taskId")
                .header("Authorization", "Bearer ${server.token}")
                .get()
                .build()
            val body = withContext(Dispatchers.IO) {
                controlClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code in setOf(500, 502, 503, 504)) return@withContext null
                        throw IOException("Could not confirm cancellation status (HTTP ${response.code}).")
                    }
                    text
                }
            } ?: return@repeat
            val state = runCatching { JSONObject(body).optString("state") }.getOrDefault("")
            when (state) {
                "CANCELLED" -> return body
                "CANCEL_REQUESTED", "RUNNING", "PAUSE_REQUESTED" -> Unit
                "COMPLETED", "FAILED", "PAUSED" -> throw IOException("Cancellation was not confirmed; task ended in state $state.")
                else -> throw IOException("Cancellation outcome is unknown; task status could not confirm a safe terminal state.")
            }
        }
        throw SocketTimeoutException("Cancellation was requested, but the server did not confirm a safe checkpoint within 180 seconds. The task may still be running; its outcome is UNKNOWN.")
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
                val body = response.body?.string().orEmpty()
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                parseCloudServerHealth(
                    serverId = server.id,
                    serverName = server.name,
                    httpCode = response.code,
                    body = body,
                    latencyMs = elapsed,
                    secrets = listOf(server.token),
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
    approvedStepIds: List<String> = emptyList(),
): String = JSONObject()
    .put("task_id", taskId)
    .put("prompt", prompt)
    .put("plugin_id", pluginId)
    .put("plugins", JSONArray(pluginIds.distinct().filter { it.isNotBlank() }))
    .put("browser_max_steps", if (thinkHarder) 30 else 15)
    .put("think_harder", thinkHarder)
    .put("approved_step_ids", JSONArray(approvedStepIds.distinct().filter { it.isNotBlank() }))
    .apply { imageBase64?.takeIf { it.isNotBlank() }?.let { put("image_base64", it) } }
    .toString()

internal fun List<CloudServerHealth>.rankCloudServerHealth(): List<CloudServerHealth> = sortedWith(
    compareBy<CloudServerHealth> { if (it.online) 0 else 1 }
        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        .thenBy { it.serverName.lowercase() },
)
