package com.opendroid.ai.core.browser

import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One production network path for chat and workspace cloud tasks. */
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

    suspend fun execute(
        taskId: String,
        prompt: String,
        pluginId: String = "openswarm_agent",
        pluginIds: List<String> = listOf(pluginId),
        targetId: String? = null,
        thinkHarder: Boolean = false,
        imageBase64: String? = null,
        onLog: (String) -> Unit = {},
    ): String {
        val configured = settingsRepository.cloudServers.first()
        val candidates = configured.filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
        check(candidates.isNotEmpty()) { "Enable and configure at least one cloud server in Remote settings." }
        val ordered = targetId?.let { id ->
            val selected = candidates.firstOrNull { it.id == id }
                ?: error("The selected cloud server is disabled or not configured.")
            listOf(selected) + candidates.filterNot { it.id == selected.id }
        } ?: candidates.drop((System.currentTimeMillis() / 60_000L % candidates.size).toInt()) +
            candidates.take((System.currentTimeMillis() / 60_000L % candidates.size).toInt())

        var lastError: Throwable? = null
        for ((position, server) in ordered.withIndex()) {
            try {
                val body = JSONObject()
                    .put("task_id", taskId)
                    .put("prompt", prompt)
                    .put("plugin_id", pluginId)
                    .put("plugins", org.json.JSONArray(pluginIds.distinct().filter { it.isNotBlank() }))
                    .put("browser_max_steps", if (thinkHarder) 30 else 15)
                    .put("think_harder", thinkHarder)
                    .apply { imageBase64?.takeIf { it.isNotBlank() }?.let { put("image_base64", it) } }
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val baseUrl = server.url.trimEnd('/').removeSuffix("/execute").trimEnd('/')
                val request = Request.Builder()
                    .url("$baseUrl/execute")
                    .header("Authorization", "Bearer ${server.token}")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) return responseBody
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
                onLog("${server.name} timed out or returned an unavailable error. Failing over to ${ordered[position + 1].name}...")
            }
        }
        throw lastError ?: IOException("All configured cloud servers failed.")
    }
}
