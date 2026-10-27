package com.opendroid.ai.core.agent

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Best-effort syntax recovery for model-generated plan JSON; it does not invent field values. */
internal object PlanJsonRepair {
    private val kotlinxJson = Json { isLenient = true }
    private val openingFence = Regex("(?is)^\\s*```(?:json)?\\s*")
    private val closingFence = Regex("(?is)\\s*```\\s*$")
    private const val MAX_UNWRAP_DEPTH = 4

    fun parse(raw: String): JsonElement = parse(raw, depth = 0)

    private fun parse(raw: String, depth: Int): JsonElement {
        require(depth < MAX_UNWRAP_DEPTH) { "Too many nested JSON string wrappers." }
        val cleaned = stripMarkdownFences(raw).trim()
        require(cleaned.isNotEmpty()) { "LLM returned empty JSON." }

        // First handle a valid JSON string containing another encoded JSON document.
        parseStrict(cleaned)?.let { element ->
            if (element is JsonPrimitive && element.isString) return parse(element.content, depth + 1)
            return element
        }

        val fragments = extractJsonFragments(cleaned)
        for (fragment in fragments) {
            parseStrict(fragment)?.let { element ->
                if (element is JsonPrimitive && element.isString) return parse(element.content, depth + 1)
                return element
            }

            val repaired = closeOpenContainers(fragment)
            if (repaired != fragment) {
                parseStrict(repaired)?.let { element ->
                    if (element is JsonPrimitive && element.isString) return parse(element.content, depth + 1)
                    return element
                }
                parseWithGsonLenient(repaired)?.let { element ->
                    if (element is JsonPrimitive && element.isString) return parse(element.content, depth + 1)
                    return element
                }
            }

            parseWithGsonLenient(fragment)?.let { element ->
                if (element is JsonPrimitive && element.isString) return parse(element.content, depth + 1)
                return element
            }

            // If generation stopped in the middle of a property, retry with the last fully
            // emitted property removed. This only discards an incomplete suffix; no values are made up.
            val safePrefix = removeIncompleteSuffix(fragment)
            if (safePrefix != null) {
                val prefixRepaired = closeOpenContainers(safePrefix)
                parseStrict(prefixRepaired)?.let { return it }
                parseWithGsonLenient(prefixRepaired)?.let { return it }
            }
        }
        throw IllegalArgumentException("Could not parse or repair a complete JSON document from LLM response.")
    }

    private fun stripMarkdownFences(raw: String): String = raw.trim()
        .replaceFirst(openingFence, "")
        .replaceFirst(closingFence, "")
        .trim()

    private fun parseStrict(source: String): JsonElement? =
        runCatching { kotlinxJson.parseToJsonElement(source) }.getOrNull()

    private fun parseWithGsonLenient(source: String): JsonElement? = runCatching {
        JsonReader(StringReader(source)).use { reader ->
            reader.isLenient = true
            val gsonElement = JsonParser.parseReader(reader)
            kotlinxJson.parseToJsonElement(gsonElement.toString())
        }
    }.getOrNull()

    /** Returns candidate top-level JSON fragments, preserving incomplete suffixes for repair. */
    private fun extractJsonFragments(source: String): List<String> {
        val fragments = mutableListOf<String>()
        source.forEachIndexed { index, char ->
            if (char == '{' || char == '[') {
                val fragment = readRootFragment(source, index)
                if (fragment.isNotBlank() && fragment !in fragments) fragments += fragment
            }
        }
        return fragments
    }

    private fun readRootFragment(source: String, start: Int): String {
        val stack = mutableListOf<Char>()
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> stack += char
                '}' -> if (stack.lastOrNull() == '{') stack.removeAt(stack.lastIndex)
                ']' -> if (stack.lastOrNull() == '[') stack.removeAt(stack.lastIndex)
            }
            if (stack.isEmpty()) return source.substring(start, index + 1)
        }
        return source.substring(start).trim()
    }

    /** Append only syntactically required quote/bracket/brace terminators. */
    private fun closeOpenContainers(source: String): String {
        val stack = mutableListOf<Char>()
        var inString = false
        var escaped = false
        for (char in source) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> stack += char
                '}' -> if (stack.lastOrNull() == '{') stack.removeAt(stack.lastIndex)
                ']' -> if (stack.lastOrNull() == '[') stack.removeAt(stack.lastIndex)
            }
        }
        if (stack.isEmpty() && !inString) return source
        return buildString {
            append(source.trimEnd())
            if (inString) {
                if (escaped) append('\\')
                append('"')
            }
            stack.asReversed().forEach { opener -> append(if (opener == '{') '}' else ']') }
        }
    }

    private fun removeIncompleteSuffix(source: String): String? {
        var inString = false
        var escaped = false
        var lastComma = -1
        source.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    ',' -> lastComma = index
                }
            }
        }
        return if (lastComma > 0) source.substring(0, lastComma).trimEnd() else null
    }
}
