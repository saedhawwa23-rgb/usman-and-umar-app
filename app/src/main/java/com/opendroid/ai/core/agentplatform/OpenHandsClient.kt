package com.opendroid.ai.core.agentplatform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wire types for the OpenHands agent-server REST API (verified against v1.60.1).
 *
 * Only fields the app actually reads are modelled. `ignoreUnknownKeys` is on so a server upgrade
 * that adds fields cannot break the client.
 */
@Serializable
data class OhConversation(
    val id: String,
    @SerialName("execution_status") val executionStatus: String? = null,
    val title: String? = null,
    val workspace: OhWorkspaceRef? = null,
    @SerialName("persistence_dir") val persistenceDir: String? = null,
)

@Serializable
data class OhWorkspaceRef(val working_dir: String? = null, val kind: String? = null)

@Serializable
data class OhFinalResponse(val response: String = "")

/** One event from `/events/search`. Kept raw so every server event kind is preserved. */
data class OhEvent(
    val id: String,
    val kind: String,
    val timestamp: String?,
    val source: String?,
    val raw: JsonObject,
) {
    /** Human-readable text for the timeline: agent messages, tool calls, observations, errors. */
    fun summary(): String = when (kind) {
        "MessageEvent" -> messageText(raw) ?: "(message)"
        "ActionEvent" -> {
            val tool = raw.str("tool_name") ?: "action"
            val args = (raw["tool_call"] as? JsonObject)?.str("arguments")
            "$tool ${args.orEmpty()}".trim().take(400)
        }
        "ObservationEvent" -> observationText(raw) ?: "(observation)"
        "ConversationErrorEvent" -> {
            val code = raw.str("code") ?: "Error"
            val detail = raw.str("detail").orEmpty()
            "$code: $detail"
        }
        "ConversationStateUpdateEvent" -> {
            val key = raw.str("key")
            // A state value can be any JSON type, so read it defensively.
            val value = raw["value"].str() ?: raw["value"]?.toString()
            "$key = $value"
        }
        else -> kind
    }

    /** True when this event represents a hard failure the user must see. */
    fun isError(): Boolean = kind == "ConversationErrorEvent"

    private fun messageText(o: JsonObject): String? {
        val content = (o["llm_message"] as? JsonObject)?.get("content") as? JsonArray ?: return null
        return content.mapNotNull { el -> (el as? JsonObject)?.str("text") }
            .joinToString("\n").ifBlank { null }
    }

    private fun observationText(o: JsonObject): String? {
        val content = (o["observation"] as? JsonObject)?.get("content") as? JsonArray
            ?: o["content"] as? JsonArray ?: return null
        return content.mapNotNull { el -> (el as? JsonObject)?.str("text") }
            .joinToString("\n").ifBlank { null }
    }
}

/** Reads a string field without the throwing `jsonPrimitive` accessor, which fails on objects. */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

private fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/**
 * A real HTTP client for one OpenHands agent server.
 *
 * Every method performs an actual network call and maps failures to [PlatformResult.Failure] with
 * the server's own message, so the UI never shows a fabricated success. Credentials are passed by
 * header only and are never placed in a URL, a log line, or an error string.
 */
class OpenHandsClient(
    private val baseUrl: String,
    private val sessionKey: String,
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

    private fun newRequest(path: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url(url(path))
            .header("X-Session-API-Key", sessionKey)
            .header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody(jsonMedia))
        return builder.build()
    }

    private fun execute(request: Request): PlatformResult<String> = try {
        http.newCall(request).execute().use { response: Response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                PlatformResult.Failure("HTTP ${response.code}: ${extractDetail(text)}")
            } else {
                PlatformResult.Success(text)
            }
        }
    } catch (e: IOException) {
        // Message only: a stack trace can carry the URL, and headers must never be echoed.
        PlatformResult.Failure("Network error: ${e.message ?: e::class.simpleName}")
    } catch (e: RuntimeException) {
        PlatformResult.Failure("Client error: ${e.message ?: e::class.simpleName}")
    }

    private fun extractDetail(body: String): String {
        if (body.isBlank()) return "(empty response)"
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val detail = root["detail"]
            when {
                detail is JsonObject -> {
                    val message = detail.str("message")
                    val code = detail.str("code")
                    listOfNotNull(code, message).joinToString(" — ").ifBlank { body.take(300) }
                }
                detail is JsonPrimitive -> detail.content
                else -> body.take(300)
            }
        } catch (_: Exception) {
            body.take(300)
        }
    }

    /** Validates the key and returns the server's reported agent settings, or a real failure. */
    fun validateConnection(): PlatformResult<Unit> =
        when (val r = execute(newRequest("/api/settings"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(Unit)
            else -> PlatformResult.Failure("Unexpected response validating connection")
        }

    /** The server's agent settings blob, reused verbatim so the app never invents a model. */
    fun fetchAgentSettings(): PlatformResult<JsonObject> =
        when (val r = execute(newRequest("/api/settings"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val settings = json.parseToJsonElement(r.value).jsonObject["agent_settings"]?.jsonObject
                if (settings == null) PlatformResult.Failure("Server returned no agent_settings")
                else PlatformResult.Success(settings)
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed settings JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response")
        }

    /**
     * Creates a conversation and starts the task.
     *
     * [workingDir] must be a directory the agent server can write to. [agentSettings] is the server's
     * own settings object, optionally with the user's chosen model applied.
     */
    fun startConversation(
        prompt: String,
        workingDir: String,
        agentSettings: JsonObject?,
        run: Boolean = true,
    ): PlatformResult<OhConversation> {
        val body = buildJsonObject {
            put("workspace", buildJsonObject {
                put("working_dir", workingDir)
                put("kind", "LocalWorkspace")
            })
            if (agentSettings != null) put("agent_settings", agentSettings)
            put("initial_message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                })
                put("run", run)
            })
            put("autotitle", false)
        }
        return when (val r = execute(newRequest("/api/conversations", body.toString()))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> parseConversation(r.value)
            else -> PlatformResult.Failure("Unexpected response creating conversation")
        }
    }

    private fun parseConversation(text: String): PlatformResult<OhConversation> = try {
        PlatformResult.Success(json.decodeFromString<OhConversation>(text))
    } catch (e: Exception) {
        PlatformResult.Failure("Malformed conversation JSON: ${e.message}")
    }

    fun getConversation(conversationId: String): PlatformResult<OhConversation> =
        when (val r = execute(newRequest("/api/conversations/$conversationId"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> parseConversation(r.value)
            else -> PlatformResult.Failure("Unexpected response")
        }

    /** Sends a follow-up message to an existing conversation, optionally running it. */
    fun sendMessage(conversationId: String, text: String, run: Boolean = true): PlatformResult<Unit> {
        val body = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("run", run)
        }
        return when (val r = execute(newRequest("/api/conversations/$conversationId/events", body.toString()))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(Unit)
            else -> PlatformResult.Failure("Unexpected response sending message")
        }
    }

    /**
     * Reads the event timeline via `/events/search`, which is the only events endpoint that accepts
     * paging parameters. `GET /events` rejects query params with HTTP 422.
     */
    fun searchEvents(conversationId: String, limit: Int = 100): PlatformResult<List<OhEvent>> {
        val path = "/api/conversations/$conversationId/events/search?limit=${limit.coerceIn(1, 100)}"
        return when (val r = execute(newRequest(path))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                val root = json.parseToJsonElement(r.value).jsonObject
                val items = root["items"] as? JsonArray
                    ?: root["events"] as? JsonArray
                    ?: JsonArray(emptyList())
                PlatformResult.Success(items.mapNotNull { parseEvent(it) })
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed events JSON: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response")
        }
    }

    private fun parseEvent(element: JsonElement): OhEvent? {
        val o = element as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val kind = o.str("kind") ?: return null
        return OhEvent(
            id = id,
            kind = kind,
            timestamp = o.str("timestamp"),
            source = o.str("source"),
            raw = o,
        )
    }

    /** The agent's final answer. Empty string means the task has not produced one yet. */
    fun finalResponse(conversationId: String): PlatformResult<String> =
        when (val r = execute(newRequest("/api/conversations/$conversationId/agent_final_response"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> try {
                PlatformResult.Success(json.decodeFromString<OhFinalResponse>(r.value).response)
            } catch (e: Exception) {
                PlatformResult.Failure("Malformed final response: ${e.message}")
            }
            else -> PlatformResult.Failure("Unexpected response")
        }

    /** Requests cancellation. The server stops at its next safe point, so this is not instant. */
    fun interrupt(conversationId: String): PlatformResult<Unit> =
        when (val r = execute(newRequest("/api/conversations/$conversationId/interrupt", "{}"))) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(Unit)
            else -> PlatformResult.Failure("Unexpected response interrupting")
        }
}
