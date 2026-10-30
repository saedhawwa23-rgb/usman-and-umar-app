package com.opendroid.ai.core.browser

import org.json.JSONObject

data class BrowserUseRemoteResult(
    val result: String,
    val steps: Int?,
)

object BrowserUseRemoteProtocol {
    const val MAX_STEPS = 15
    const val FMHY_PLUGIN_ID = "fmhy_browser"
    const val GENERIC_PLUGIN_ID = "browser_use"

    fun buildRequestJson(
        taskId: String,
        prompt: String,
        useFmhy: Boolean,
        maxSteps: Int = MAX_STEPS,
    ): JSONObject {
        require(taskId.isNotBlank()) { "Browser task ID must not be blank." }
        require(prompt.isNotBlank()) { "Browser prompt must not be blank." }
        return JSONObject()
            .put("task_id", taskId)
            .put("prompt", prompt.trim())
            .put("plugin_id", if (useFmhy) FMHY_PLUGIN_ID else GENERIC_PLUGIN_ID)
            .put("browser_max_steps", maxSteps.coerceIn(1, MAX_STEPS))
    }

    fun parseSuccess(body: String): BrowserUseRemoteResult {
        require(body.isNotBlank()) { "Browser Use returned an empty response." }
        val json = runCatching { JSONObject(body) }.getOrNull()
        val serverError = json?.optString("error")?.trim().orEmpty()
        check(serverError.isBlank()) { "Browser Use returned an error: $serverError" }
        val result = json?.optString("result")?.ifBlank { json.optString("output") }
            ?.ifBlank { body }
            ?: body
        return BrowserUseRemoteResult(
            result = result,
            steps = json?.optInt("steps")?.takeIf { it > 0 },
        )
    }

    fun httpError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return "Browser Use returned HTTP $code${detail?.let { ": $it" }.orEmpty()}"
    }
}
