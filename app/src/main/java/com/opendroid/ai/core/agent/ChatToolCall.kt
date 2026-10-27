package com.opendroid.ai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A parsed action proposal. Parsing never executes the proposed action. */
data class ChatToolCall(
    val action: String,
    val command: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val url: String? = null,
    val rawJson: String,
)

object ChatToolCallParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Accepts only a standalone JSON object (optionally fenced); prose examples are never executable. */
    fun parse(text: String): ChatToolCall? {
        val candidate = standaloneObject(text.trim()) ?: return null
        val root = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: return null
        val action = root.string("action") ?: root.string("name") ?: return null
        return ChatToolCall(
            action = action.trim().lowercase(),
            command = root.string("command") ?: root.string("cmd"),
            packageName = root.string("package") ?: root.string("package_name"),
            appName = root.string("app") ?: root.string("app_name") ?: root.string("name"),
            url = root.string("url") ?: root.string("uri"),
            rawJson = candidate,
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun standaloneObject(text: String): String? {
        val fenced = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: text
        return candidate.takeIf { it.startsWith('{') && it.endsWith('}') }
    }
}
