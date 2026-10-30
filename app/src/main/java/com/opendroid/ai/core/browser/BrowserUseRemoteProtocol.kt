package com.opendroid.ai.core.browser

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class BrowserUseRemoteResult(
    val result: String,
    val steps: Int?,
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
        val serverError = json?.get("error")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        check(serverError.isBlank()) { "Browser Use returned an error: $serverError" }
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

    fun httpError(code: Int, body: String): String {
        val detail = runCatching {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                ?.get("error")?.takeIf { !it.isJsonNull }?.asString?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return "Browser Use returned HTTP $code${detail?.let { ": $it" }.orEmpty()}"
    }
}
