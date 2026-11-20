package com.opendroid.ai.core.agentplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A real HTTP client for the USMAN AND UMAR OpenSwarm task backend (`cloud-backend/main.py`).
 *
 * Every method performs an actual call and maps failures to [PlatformResult.Failure] carrying the
 * backend's own message. The bearer token is passed by header only; it is never placed in a URL,
 * a log line or an error string.
 *
 * Verified against a locally launched instance: `GET /health` (no auth) and `GET /agents`,
 * `GET /capabilities`, `GET /tasks/{id}`, `GET /tasks/{id}/logs` (bearer auth) answer as expected,
 * a missing or wrong token returns 401 and an unknown task returns 404. `POST /execute` refuses to
 * run until the backend's own readiness checks pass (Upstash Redis configured), returning
 * `BACKEND_NOT_READY`; that precondition is surfaced rather than hidden.
 */
class OpenSwarmClient(
    private val baseUrl: String,
    private val appToken: String,
    okHttpClient: OkHttpClient? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun newRequest(path: String, body: String? = null, authorized: Boolean = true): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("Accept", "application/json")
        if (authorized) builder.header("Authorization", "Bearer $appToken")
        if (body != null) builder.post(body.toRequestBody(jsonMedia))
        return builder.build()
    }

    private fun execute(request: Request): PlatformResult<String> = try {
        http.newCall(request).execute().use { response: Response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) PlatformResult.Failure(describeFailure(response.code, text))
            else PlatformResult.Success(text)
        }
    } catch (e: IOException) {
        PlatformResult.Failure("Network error: ${e.message ?: e::class.simpleName}")
    } catch (e: RuntimeException) {
        PlatformResult.Failure("Client error: ${e.message ?: e::class.simpleName}")
    }

    /** The backend's own error shape: `{status, error, message, error_code, recovery_action}`. */
    private fun describeFailure(code: Int, body: String): String {
        if (body.isBlank()) return "HTTP $code: (empty response)"
        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return "HTTP $code: ${body.take(300)}"
        val message = parsed.str("error") ?: parsed.str("message") ?: parsed.str("detail")
        val errorCode = parsed.str("error_code")
        val recovery = parsed.str("recovery_action")
        val parts = listOfNotNull(
            errorCode?.let { "[$it]" },
            message,
            recovery,
        )
        return "HTTP $code: " + parts.joinToString(" — ").ifBlank { body.take(300) }
    }

    /**
     * Reads `/health`, which needs no token and reports the backend's readiness.
     *
     * [ready] is the backend's own verdict, so the UI can show "not ready" instead of claiming a
     * working connection. [reason] carries the backend's explanation when it is not ready.
     */
    data class Health(val ready: Boolean, val authReady: Boolean, val reason: String?)

    fun health(): PlatformResult<Health> =
        when (val r = execute(newRequest("/health", authorized = false))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val o = json.parseToJsonElement(r.value).jsonObject
                PlatformResult.Success(
                    Health(
                        ready = o.boolean("ready"),
                        authReady = o.boolean("auth_ready"),
                        reason = o.str("recovery_reason"),
                    )
                )
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed health JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response reading health")
        }

    /** Validates the token against an authenticated endpoint. */
    fun validateConnection(): PlatformResult<Unit> =
        when (val r = execute(newRequest("/capabilities"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(Unit)
            else -> PlatformResult.Failure("Unexpected response validating the token")
        }

    /** One registered specialist agent as the backend describes it. */
    data class SwarmAgent(val id: String, val name: String, val capabilities: List<String>)

    fun agents(): PlatformResult<List<SwarmAgent>> =
        when (val r = execute(newRequest("/agents"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val list = json.parseToJsonElement(r.value).jsonObject["agents"] as? JsonArray
                    ?: return PlatformResult.Failure("Backend returned no agents list")
                PlatformResult.Success(
                    list.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val id = o.str("id") ?: return@mapNotNull null
                        SwarmAgent(
                            id = id,
                            name = o.str("name") ?: id,
                            capabilities = (o["capabilities"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                        )
                    }
                )
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed agents JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response listing agents")
        }

    /** The backend's task record. Only the fields the app displays are modelled. */
    data class SwarmTask(val id: String, val state: String)

    fun task(taskId: String): PlatformResult<SwarmTask> =
        when (val r = execute(newRequest("/tasks/${enc(taskId)}"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val o = json.parseToJsonElement(r.value).jsonObject
                PlatformResult.Success(
                    SwarmTask(
                        id = o.str("task_id") ?: o.str("id") ?: taskId,
                        state = o.str("state") ?: "UNKNOWN",
                    )
                )
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed task JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response reading the task")
        }

    /** The task's event log, reduced to display text. */
    fun taskEvents(taskId: String): PlatformResult<List<String>> =
        when (val r = execute(newRequest("/tasks/${enc(taskId)}/logs"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val events = json.parseToJsonElement(r.value).jsonObject["events"] as? JsonArray
                PlatformResult.Success(
                    events?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        o.str("message") ?: o.str("event") ?: o.toString().take(300)
                    } ?: emptyList()
                )
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed logs JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response reading the logs")
        }

    /**
     * Submits a task. The backend rejects the request when its readiness checks fail, so the
     * caller receives the backend's own reason (for example `BACKEND_NOT_READY`) rather than a
     * fabricated acceptance.
     */
    fun execute(taskId: String, prompt: String): PlatformResult<String> {
        val body = buildJsonObject {
            put("task_id", taskId)
            put("prompt", prompt)
        }.toString()
        return when (val r = execute(newRequest("/execute", body))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> {
                val result = try {
                    json.parseToJsonElement(r.value).jsonObject.str("result")
                } catch (_: Exception) {
                    null
                }
                PlatformResult.Success(result ?: "")
            }
            else -> PlatformResult.Failure("Unexpected response submitting the task")
        }
    }

    /** Requests cancellation. The backend answers with the task's new state. */
    fun cancel(taskId: String): PlatformResult<Unit> =
        when (val r = execute(newRequest("/tasks/${enc(taskId)}/cancel", "{}"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(Unit)
            else -> PlatformResult.Failure("Unexpected response cancelling the task")
        }

    /** Percent-encodes a path segment so an id cannot inject a different route. */
    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Reads a string field without the throwing `jsonPrimitive` accessor, which fails on objects. */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** Reads a boolean that may arrive as `true`, `"true"` or be absent. */
private fun JsonObject.boolean(key: String): Boolean = when (val el = this[key]) {
    is JsonPrimitive -> el.content.equals("true", ignoreCase = true)
    else -> false
}
