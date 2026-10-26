package com.usmanandumar.workspace.core.task

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

/** Execution choices for the hybrid task orchestrator. */
enum class TaskExecutionMode {
    LOCAL_AI_APP,
    CLOUD_OPENSWARM,
    TERMUX_SCRIPT,
}

enum class LocalAiProvider(val label: String, val packageName: String) {
    GROK("Grok", "ai.x.grok"),
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    GEMINI("Gemini", "com.google.android.apps.bard"),
    DEEPSEEK("DeepSeek", "com.deepseek.chat"),
}

data class ParsedQuickTask(
    val title: String,
    val deadlineAt: Long?,
)

/** A deliberately small, local regex parser. It never calls an AI service. */
object TaskQuickAddParser {
    private val dateAndTime = Regex(
        "\\b(today|tomorrow)\\b(?:\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?)?",
        RegexOption.IGNORE_CASE,
    )
    private val timeOnly = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE)

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedQuickTask {
        val text = raw.trim()
        val match = dateAndTime.find(text)
        val timeMatch = if (match == null) timeOnly.find(text) else null
        val deadline = when {
            match != null -> {
                val date = when (match.groupValues[1].lowercase(Locale.ROOT)) {
                    "tomorrow" -> now.toLocalDate().plusDays(1)
                    else -> now.toLocalDate()
                }
                buildDeadline(date, match.groupValues.getOrNull(2).orEmpty(), match.groupValues.getOrNull(3).orEmpty(), match.groupValues.getOrNull(4).orEmpty(), now)
            }
            timeMatch != null -> buildDeadline(
                now.toLocalDate(), timeMatch.groupValues[1], timeMatch.groupValues[2], timeMatch.groupValues[3], now,
            )
            else -> null
        }
        val removed = when {
            match != null -> text.removeRange(match.range)
            timeMatch != null -> text.removeRange(timeMatch.range)
            else -> text
        }
        return ParsedQuickTask(removed.replace(Regex("\\s+"), " ").trim(' ', ',', '-', '—'), deadline?.toInstant()?.toEpochMilli())
    }

    private fun buildDeadline(
        date: LocalDate,
        hourText: String,
        minuteText: String,
        meridiemText: String,
        reference: ZonedDateTime,
    ): ZonedDateTime {
        if (hourText.isBlank()) return date.atStartOfDay(reference.zone)
        var hour = hourText.toInt().coerceIn(0, 23)
        val minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
        when (meridiemText.lowercase(Locale.ROOT)) {
            "pm" -> if (hour in 1..11) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        return date.atTime(hour, minute).atZone(reference.zone)
    }
}
