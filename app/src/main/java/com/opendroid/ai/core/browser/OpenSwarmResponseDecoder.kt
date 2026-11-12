package com.opendroid.ai.core.browser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Decoded OpenSwarm task output. Only [Completed] represents a verified final result. */
sealed interface OpenSwarmResponse {
    data class Completed(val output: String) : OpenSwarmResponse
    data class InProgress(val message: String, val status: String) : OpenSwarmResponse
    data class Failed(val message: String, val status: String) : OpenSwarmResponse
    /** Older non-OpenSwarm cloud plugins may return plain text, but it is never verified completion. */
    data class LegacyText(val text: String) : OpenSwarmResponse
}

/** Parses OpenSwarm's JSON report before chat delivery; never treats unverified data as success. */
object OpenSwarmResponseDecoder {
    fun decode(response: String): OpenSwarmResponse {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return OpenSwarmResponse.Failed(
            "OpenSwarm returned an empty task response.", "EMPTY_RESPONSE",
        )

        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            return if (looksLikeJson(trimmed)) {
                OpenSwarmResponse.Failed("OpenSwarm returned an unreadable task response.", "MALFORMED_RESPONSE")
            } else {
                OpenSwarmResponse.LegacyText(trimmed)
            }
        }

        val report = root.asJsonObject
        val status = text(report, "status")?.uppercase().orEmpty()
        val errorText = errorMessage(report)
        if (errorText != null || status in FAILURE_STATUSES) {
            return OpenSwarmResponse.Failed(
                safeFailure(errorText) ?: failureMessage(status),
                status.ifBlank { "FAILED" },
            )
        }
        if (status == "CONTROL_ONLY") {
            return OpenSwarmResponse.InProgress("Manager Agent is planning the task...", status)
        }
        if (status in WAITING_STATUSES) {
            return OpenSwarmResponse.InProgress(progressMessage(status), status)
        }

        val verified = report.get("verified")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean == true
        if (!verified) {
            return OpenSwarmResponse.InProgress(
                if (status == "RUNNING" || status == "EXECUTING" || status == "VERIFYING") {
                    "Manager Agent is working on the task..."
                } else {
                    "Manager Agent is planning the task..."
                },
                status.ifBlank { "UNVERIFIED" },
            )
        }
        if (status.isNotBlank() && status !in SUCCESS_STATUSES) {
            return OpenSwarmResponse.InProgress("Manager Agent is working on the task...", status)
        }

        val output = finalOutput(report)
        return if (output.isNullOrBlank()) {
            OpenSwarmResponse.Failed(
                "OpenSwarm could not provide a verified final result.", "MISSING_FINAL_OUTPUT",
            )
        } else {
            OpenSwarmResponse.Completed(output)
        }
    }

    /** Gives callers a safe, readable error summary even when a backend field contains serialized JSON. */
    fun cleanFailureMessage(message: String?, fallback: String = "The OpenSwarm task could not be completed."): String {
        return safeFailure(message) ?: fallback
    }

    private fun finalOutput(report: JsonObject): String? {
        val direct = sequenceOf("final_output", "output", "answer", "result")
            .mapNotNull { text(report, it)?.let(::safeOutput) }
            .firstOrNull()
        if (direct != null) return direct

        val results = report.get("results")
        if (results?.isJsonArray == true) {
            val outputs = results.asJsonArray.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val entry = item.asJsonObject
                if (entry.get("verified")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true) {
                    return@mapNotNull null
                }
                sequenceOf("output", "answer", "result")
                    .mapNotNull { text(entry, it)?.let(::safeOutput) }
                    .firstOrNull()
            }.distinct()
            if (outputs.isNotEmpty()) return outputs.joinToString("\n\n")
        }
        // A manager summary is planning metadata, not evidence of execution. Never
        // promote it to a final chat answer when specialist outputs are absent.
        return null
    }

    private fun errorMessage(report: JsonObject): String? {
        val error = report.get("error")
        val nested = error?.takeIf { it.isJsonObject }?.asJsonObject
        return nested?.let { text(it, "message") ?: text(it, "detail") }
            ?: text(report, "message")
            ?: text(report, "detail")
            ?: error?.takeIf { it.isJsonPrimitive }?.let { primitiveText(it) }
                ?.takeIf { it.isNotBlank() }
    }

    private fun safeFailure(message: String?): String? {
        val value = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (looksLikeJson(value)) return null
        return sanitizeCloudDiagnostic(value).take(300).ifBlank { null }
    }

    private fun safeOutput(value: String): String? {
        val clean = value.trim()
        if (clean.isEmpty() || looksLikeJson(clean)) return null
        return clean
    }

    private fun failureMessage(status: String): String = when (status) {
        "CANCELLED" -> "The OpenSwarm task was cancelled before completion."
        else -> "OpenSwarm could not complete the task."
    }

    private fun progressMessage(status: String): String = when (status) {
        "WAITING_FOR_USER", "PAUSED" -> "Manager Agent is waiting for your approval or input."
        "CANCEL_REQUESTED" -> "Cancellation was requested; the task is not complete yet."
        else -> "Manager Agent is working on the task..."
    }

    private fun text(json: JsonObject, key: String): String? = json.get(key)
        ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean && !it.asJsonPrimitive.isNumber }
        ?.let(::primitiveText)?.trim()?.takeIf { it.isNotBlank() }

    private fun primitiveText(element: JsonElement): String? = runCatching { element.asString }.getOrNull()

    private fun looksLikeJson(value: String): Boolean {
        val first = value.firstOrNull { !it.isWhitespace() } ?: return false
        if (first == '{' || first == '[') return true
        if (first != '"') return false
        val decodedString = runCatching { JsonParser.parseString(value).asString.trim() }.getOrNull()
            ?: return false
        return decodedString.startsWith('{') || decodedString.startsWith('[')
    }

    private val FAILURE_STATUSES = setOf("FAILED", "ERROR", "FAILURE", "CANCELLED")
    private val WAITING_STATUSES = setOf("PAUSED", "WAITING_FOR_USER", "CANCEL_REQUESTED")
    private val SUCCESS_STATUSES = setOf("COMPLETED", "SUCCESS", "VERIFIED")
}
