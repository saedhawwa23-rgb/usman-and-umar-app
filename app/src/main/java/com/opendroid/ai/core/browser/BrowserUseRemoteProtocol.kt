package com.opendroid.ai.core.browser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class BrowserUseRemoteResult(
    val result: String,
    val steps: Int?,
)

data class RemoteBackendError(
    val code: String? = null,
    val message: String? = null,
    val taskId: String? = null,
    val recoveryState: String? = null,
    val recoveryAction: String? = null,
    val httpStatus: Int? = null,
)

object BrowserUseRemoteProtocol {
    const val MAX_STEPS = 15
    const val THINK_HARDER_MAX_STEPS = 30
    const val FMHY_PLUGIN_ID = "fmhy_browser"
    const val GENERIC_PLUGIN_ID = "browser_use"

    fun buildRequestJson(
        taskId: String,
        prompt: String,
        useFmhy: Boolean,
        maxSteps: Int = MAX_STEPS,
        thinkHarder: Boolean = false,
    ): JsonObject {
        require(taskId.isNotBlank()) { "Browser task ID must not be blank." }
        require(prompt.isNotBlank()) { "Browser prompt must not be blank." }
        return JsonObject().apply {
            addProperty("task_id", taskId)
            addProperty("prompt", prompt.trim())
            addProperty("plugin_id", if (useFmhy) FMHY_PLUGIN_ID else GENERIC_PLUGIN_ID)
            addProperty("browser_max_steps", if (thinkHarder) THINK_HARDER_MAX_STEPS else maxSteps.coerceIn(1, THINK_HARDER_MAX_STEPS))
            addProperty("think_harder", thinkHarder)
        }
    }

    fun parseSuccess(body: String): BrowserUseRemoteResult {
        require(body.isNotBlank()) { "Browser Use returned an empty response." }
        val json = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        val error = parseBackendError(null, body)
        check(error == null) { formatBackendError(error, body, "Browser Use returned an error") }
        val resultElement = json?.get("result")
        val outputElement = json?.get("output")
        val result = when {
            resultElement?.isJsonPrimitive == true -> resultElement.asString
            outputElement?.isJsonPrimitive == true -> outputElement.asString
            else -> body
        }.ifBlank { body }
        return BrowserUseRemoteResult(
            result = result,
            steps = json?.get("steps")?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt?.takeIf { it > 0 },
        )
    }

    /** Parses the stable error contract whether fields are top-level or nested under `error`. */
    fun parseBackendError(code: Int?, body: String, secrets: List<String> = emptyList()): RemoteBackendError? {
        val json = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
            ?: return null
        val errorElement = json.get("error")
        val nested = errorElement?.takeIf { it.isJsonObject }?.asJsonObject
        val errorText = errorElement?.takeIf { it.isJsonPrimitive }?.safeText()
        val message = firstText(nested, "message", "detail", "error_description")
            ?: firstText(json, "message", "detail", "error_description")
            ?: errorText
        val errorCode = firstText(nested, "code", "error_code")
            ?: firstText(json, "code", "error_code")
        val taskId = firstText(nested, "task_id") ?: firstText(json, "task_id")
        val recoveryState = firstText(nested, "recovery_state") ?: firstText(json, "recovery_state")
        val recoveryAction = firstText(nested, "recovery_action") ?: firstText(json, "recovery_action")
        val status = firstText(json, "status")
        val hasErrorValue = errorElement != null && !(errorElement.isJsonPrimitive && errorText.isNullOrBlank())
        if (!hasErrorValue && status != "error" && errorCode == null && recoveryState == null && recoveryAction == null) return null
        return RemoteBackendError(
            code = errorCode?.let { sanitizeCloudDiagnostic(it, secrets) },
            message = message?.let { sanitizeCloudDiagnostic(it, secrets) },
            taskId = taskId?.let { sanitizeCloudDiagnostic(it, secrets) },
            recoveryState = recoveryState?.let { sanitizeCloudDiagnostic(it, secrets) },
            recoveryAction = recoveryAction?.let { sanitizeCloudDiagnostic(it, secrets) },
            httpStatus = code,
        )
    }

    fun httpError(code: Int, body: String, secrets: List<String> = emptyList()): String {
        val error = parseBackendError(code, body, secrets)
        if (error == null) {
            val detail = runCatching {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return "Browser Use returned HTTP $code${detail?.let { ": ${sanitizeCloudDiagnostic(it, secrets)}" }.orEmpty()}"
        }
        return formatBackendError(error, body, "Browser Use returned HTTP $code", secrets)
    }

    private fun formatBackendError(
        error: RemoteBackendError?,
        body: String,
        prefix: String,
        secrets: List<String> = emptyList(),
    ): String {
        if (error == null) return "$prefix: ${sanitizeCloudDiagnostic(body, secrets)}"
        val fields = buildList {
            error.httpStatus?.let { add("HTTP $it") }
            error.code?.let { add("code=${sanitizeCloudDiagnostic(it, secrets)}") }
            error.message?.let { add("message=${sanitizeCloudDiagnostic(it, secrets)}") }
            error.taskId?.let { add("task_id=${sanitizeCloudDiagnostic(it, secrets)}") }
            error.recoveryState?.let { add("recovery_state=${sanitizeCloudDiagnostic(it, secrets)}") }
            error.recoveryAction?.let { add("recovery_action=${sanitizeCloudDiagnostic(it, secrets)}") }
            add("diagnostics=${sanitizeCloudDiagnostic(body, secrets)}")
        }
        return "$prefix: ${fields.joinToString("; ")}"
    }

    private fun firstText(json: JsonObject?, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        json?.get(key)?.safeText()?.takeIf { it.isNotBlank() }
    }

    private fun JsonElement.safeText(): String? = takeIf { !it.isJsonNull && it.isJsonPrimitive }
        ?.asString?.trim()?.takeIf { it.isNotBlank() }
}
