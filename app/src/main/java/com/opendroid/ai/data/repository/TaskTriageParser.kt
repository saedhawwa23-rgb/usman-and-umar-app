package com.opendroid.ai.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class TriagedTask(
    val title: String,
    val description: String,
    val expectedOutcome: String,
    val category: String,
    val priority: Int,
    val deadlineAt: Long?,
    val dependsOn: List<Int>,
)

/** Parses untrusted model text only; task creation remains a separate, validated step. */
object TaskTriageParser {
    private const val MAX_TASKS = 30
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_DESCRIPTION_CHARS = 5_000
    private val validCategories = setOf("Work", "Coding", "Personal")

    fun parse(response: String, zoneId: ZoneId = ZoneId.systemDefault()): List<TriagedTask> {
        require(response.length <= 100_000) { "AI triage response is too large to process." }
        val candidate = extractJson(response)
        val root = runCatching { Json.parseToJsonElement(candidate) }
            .getOrElse { throw IllegalArgumentException("AI triage did not return valid JSON. Keep the inbox text and retry.") }
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tasks"] as? JsonArray
                ?: throw IllegalArgumentException("AI triage JSON must contain a 'tasks' array.")
            else -> throw IllegalArgumentException("AI triage must return a JSON object or task array.")
        }
        require(items.isNotEmpty()) { "AI triage did not identify any actionable tasks." }
        require(items.size <= MAX_TASKS) { "AI triage returned more than $MAX_TASKS tasks; split the inbox text and retry." }
        return items.mapIndexed { index, element -> parseTask(element, index, zoneId) }
    }

    private fun parseTask(element: JsonElement, index: Int, zoneId: ZoneId): TriagedTask {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("AI task ${index + 1} is not a JSON object.")
        fun text(key: String, max: Int): String {
            val raw = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            require(raw.length <= max) { "AI task ${index + 1} field '$key' is too long." }
            return raw
        }
        val title = text("title", MAX_TITLE_CHARS)
        require(title.isNotBlank()) { "AI task ${index + 1} has a blank title; no task was saved." }
        val requestedCategory = text("category", 40)
        val category = validCategories.firstOrNull { it.equals(requestedCategory, ignoreCase = true) } ?: "Personal"
        val priority = (obj["priority"] as? JsonPrimitive)?.intOrNull?.coerceIn(-10, 10) ?: 0
        val deadline = (obj["dueDate"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?.let { parseDeadline(it, zoneId) }
        val dependsOn = (obj["dependsOn"] as? JsonArray)?.mapIndexed { dependencyIndex, dependency ->
            (dependency as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("AI task ${index + 1} has an invalid prerequisite index at position ${dependencyIndex + 1}.")
        }.orEmpty()
        require(dependsOn.distinct().size == dependsOn.size) { "AI task ${index + 1} repeats a prerequisite." }
        require(dependsOn.all { it in 0 until index }) {
            "AI task ${index + 1} contains a self, future, or invalid prerequisite index. Retry triage or link tasks manually."
        }
        return TriagedTask(
            title = title,
            description = text("description", MAX_DESCRIPTION_CHARS),
            expectedOutcome = text("expectedOutcome", MAX_DESCRIPTION_CHARS),
            category = category,
            priority = priority,
            deadlineAt = deadline,
            dependsOn = dependsOn,
        )
    }

    private fun parseDeadline(value: String, zoneId: ZoneId): Long = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(value).atStartOfDay(zoneId).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("AI triage returned an invalid dueDate '$value'; retry or set the deadline manually.")
            }
        }
    }

    private fun extractJson(response: String): String {
        val trimmed = response.trim()
        val unfenced = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val firstObject = unfenced.indexOf('{')
        val firstArray = unfenced.indexOf('[')
        val start = listOf(firstObject, firstArray).filter { it >= 0 }.minOrNull()
            ?: throw IllegalArgumentException("AI triage returned no JSON. Keep the inbox text and retry.")
        val last = maxOf(unfenced.lastIndexOf('}'), unfenced.lastIndexOf(']'))
        require(last >= start) { "AI triage returned incomplete JSON." }
        return unfenced.substring(start, last + 1)
    }
}
