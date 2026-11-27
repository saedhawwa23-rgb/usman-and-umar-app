package com.opendroid.ai.core.connector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Built-in integrations that ship with the app and need no configuration to work.
 *
 * These exist so the workflow engine has real tools to run before the owner registers any external
 * plugin. Each one is a real [UniversalConnector]: it declares its tools, validates its arguments,
 * and does the work. None of them fabricates a result:
 *
 *  - [BuiltInTimeIntegration] is deterministic and pure.
 *  - [BuiltInTextIntegration] is deterministic and pure.
 *  - [BuiltInHttpIntegration] performs a real HTTP request and reports the real status code.
 *
 * The two deterministic integrations take no network and no secret, so they are the ones the tests
 * drive end to end. The HTTP one takes an injectable transport so its success and failure paths are
 * tested without reaching the internet.
 */
object BuiltInIntegrationIds {
    const val TIME = "builtin_time"
    const val TEXT = "builtin_text"
    const val HTTP = "builtin_http"
}

/** A local, deterministic clock/format integration. Read-only, no network. */
class BuiltInTimeIntegration(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : UniversalConnector {
    override val id: String = BuiltInIntegrationIds.TIME
    override val displayName: String = "Built-in Date & Time"
    override val kind: ConnectorKind = ConnectorKind.CLI

    override fun tools(): List<ConnectorTool> = listOf(
        ConnectorTool(
            connectorId = id,
            name = "now",
            description = "Return the current instant as an epoch-milliseconds value and an ISO-8601 timestamp.",
            parametersJsonSchema = """{"type":"object","properties":{},"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
        ConnectorTool(
            connectorId = id,
            name = "format",
            description = "Format an epoch-milliseconds instant using a java.time pattern in a named zone.",
            parametersJsonSchema = """{"type":"object","properties":{"epochMs":{"type":"integer"},"pattern":{"type":"string"},"zone":{"type":"string"}},"required":["epochMs","pattern"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
        ConnectorTool(
            connectorId = id,
            name = "add_duration",
            description = "Add a number of seconds to an epoch-milliseconds instant and return the new instant.",
            parametersJsonSchema = """{"type":"object","properties":{"epochMs":{"type":"integer"},"seconds":{"type":"integer"}},"required":["epochMs","seconds"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
    )

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult {
        val args = ConnectorArguments.parse(invocation.argumentsJson)
            ?: return ConnectorResult(false, error = "Tool arguments must be a JSON object")
        return when (invocation.tool.name) {
            "now" -> {
                val now = clock()
                ConnectorResult(true, ConnectorArguments.json(mapOf(
                    "epochMs" to now.toString(),
                    "iso" to Instant.ofEpochMilli(now).atZone(zone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )))
            }
            "format" -> {
                val epochMs = args["epochMs"]?.toLongOrNull()
                    ?: return ConnectorResult(false, error = "epochMs must be an integer")
                val pattern = args["pattern"]?.takeIf { it.isNotBlank() }
                    ?: return ConnectorResult(false, error = "pattern must not be blank")
                val targetZone = args["zone"]?.takeIf { it.isNotBlank() }?.let {
                    runCatching { ZoneId.of(it) }.getOrNull()
                        ?: return ConnectorResult(false, error = "Unknown time zone: $it")
                } ?: zone()
                val formatted = runCatching {
                    Instant.ofEpochMilli(epochMs).atZone(targetZone).format(DateTimeFormatter.ofPattern(pattern, Locale.US))
                }.getOrElse { return ConnectorResult(false, error = "Invalid date pattern: $pattern") }
                ConnectorResult(true, ConnectorArguments.json(mapOf("formatted" to formatted, "zone" to targetZone.id)))
            }
            "add_duration" -> {
                val epochMs = args["epochMs"]?.toLongOrNull()
                    ?: return ConnectorResult(false, error = "epochMs must be an integer")
                val seconds = args["seconds"]?.toLongOrNull()
                    ?: return ConnectorResult(false, error = "seconds must be an integer")
                val result = epochMs + seconds * 1000L
                ConnectorResult(true, ConnectorArguments.json(mapOf(
                    "epochMs" to result.toString(),
                    "iso" to Instant.ofEpochMilli(result).atZone(zone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )))
            }
            else -> ConnectorResult(false, error = "Tool '${invocation.tool.name}' is not part of this integration")
        }
    }
}

/** A local, deterministic text integration. Read-only, no network. */
class BuiltInTextIntegration : UniversalConnector {
    override val id: String = BuiltInIntegrationIds.TEXT
    override val displayName: String = "Built-in Text Tools"
    override val kind: ConnectorKind = ConnectorKind.CLI

    override fun tools(): List<ConnectorTool> = listOf(
        ConnectorTool(
            connectorId = id,
            name = "transform",
            description = "Apply one text transform: upper, lower, trim, reverse, length, or slug.",
            parametersJsonSchema = """{"type":"object","properties":{"text":{"type":"string"},"operation":{"type":"string"}},"required":["text","operation"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
        ConnectorTool(
            connectorId = id,
            name = "split",
            description = "Split text on a separator and return the parts.",
            parametersJsonSchema = """{"type":"object","properties":{"text":{"type":"string"},"separator":{"type":"string"},"limit":{"type":"integer"}},"required":["text","separator"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
        ConnectorTool(
            connectorId = id,
            name = "replace",
            description = "Replace every occurrence of a literal substring and return the result and the count.",
            parametersJsonSchema = """{"type":"object","properties":{"text":{"type":"string"},"find":{"type":"string"},"replaceWith":{"type":"string"}},"required":["text","find","replaceWith"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
    )

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult {
        val args = ConnectorArguments.parse(invocation.argumentsJson)
            ?: return ConnectorResult(false, error = "Tool arguments must be a JSON object")
        return when (invocation.tool.name) {
            "transform" -> {
                val text = args["text"] ?: return ConnectorResult(false, error = "text is required")
                val operation = args["operation"]?.lowercase(Locale.US)
                    ?: return ConnectorResult(false, error = "operation is required")
                val result = when (operation) {
                    "upper" -> text.uppercase(Locale.US)
                    "lower" -> text.lowercase(Locale.US)
                    "trim" -> text.trim()
                    "reverse" -> text.reversed()
                    "length" -> text.length.toString()
                    "slug" -> text.lowercase(Locale.US).trim()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                    else -> return ConnectorResult(false, error = "Unknown operation: $operation")
                }
                ConnectorResult(true, ConnectorArguments.json(mapOf("result" to result, "operation" to operation)))
            }
            "split" -> {
                val text = args["text"] ?: return ConnectorResult(false, error = "text is required")
                val separator = args["separator"]
                    ?: return ConnectorResult(false, error = "separator is required")
                if (separator.isEmpty()) return ConnectorResult(false, error = "separator must not be empty")
                val limit = args["limit"]?.toIntOrNull()?.coerceAtLeast(1)
                val parts = if (limit != null) text.split(separator, limit = limit) else text.split(separator)
                ConnectorResult(true, ConnectorArguments.jsonArray(mapOf(
                    "count" to parts.size.toString(),
                    "parts" to parts.joinToString("\u0001"),
                )))
            }
            "replace" -> {
                val text = args["text"] ?: return ConnectorResult(false, error = "text is required")
                val find = args["find"] ?: return ConnectorResult(false, error = "find is required")
                val replaceWith = args["replaceWith"] ?: return ConnectorResult(false, error = "replaceWith is required")
                if (find.isEmpty()) return ConnectorResult(false, error = "find must not be empty")
                val count = Regex(Regex.escape(find)).findAll(text).count()
                ConnectorResult(true, ConnectorArguments.json(mapOf(
                    "result" to text.replace(find, replaceWith),
                    "replacements" to count.toString(),
                )))
            }
            else -> ConnectorResult(false, error = "Tool '${invocation.tool.name}' is not part of this integration")
        }
    }
}

/** The response of an HTTP request, so the network call is testable without a socket. */
data class HttpFetchResponse(val statusCode: Int, val body: String)

fun interface HttpFetchTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpFetchResponse
}

/** A real HTTP transport over [HttpURLConnection]. */
class UrlConnectionHttpFetchTransport : HttpFetchTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpFetchResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            doInput = true
            setRequestProperty("User-Agent", "USMAN-AND-UMAR/3.10.1")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpFetchResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(64_000))
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A real HTTP integration. It is read-only and refuses any scheme other than http(s), so it cannot
 * be pointed at `file:` or another local resource. The body is truncated, and no credential is ever
 * attached unless the tool declares one.
 */
class BuiltInHttpIntegration(
    private val transport: HttpFetchTransport = UrlConnectionHttpFetchTransport(),
) : UniversalConnector {
    override val id: String = BuiltInIntegrationIds.HTTP
    override val displayName: String = "Built-in HTTP Fetch"
    override val kind: ConnectorKind = ConnectorKind.REST

    override fun tools(): List<ConnectorTool> = listOf(
        ConnectorTool(
            connectorId = id,
            name = "get",
            description = "Perform an HTTP GET and return the status code and (truncated) body.",
            parametersJsonSchema = """{"type":"object","properties":{"url":{"type":"string"},"accept":{"type":"string"}},"required":["url"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
        ConnectorTool(
            connectorId = id,
            name = "status",
            description = "Return only the HTTP status code for a URL.",
            parametersJsonSchema = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}""",
            risk = ConnectorRisk.READ_ONLY,
        ),
    )

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult {
        val args = ConnectorArguments.parse(invocation.argumentsJson)
            ?: return ConnectorResult(false, error = "Tool arguments must be a JSON object")
        val url = args["url"]?.trim().orEmpty()
        if (url.isEmpty()) return ConnectorResult(false, error = "url is required")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return ConnectorResult(false, error = "Only http(s) URLs are allowed; found $url")
        }
        val headers = buildMap {
            args["accept"]?.takeIf { it.isNotBlank() }?.let { put("Accept", it) }
        }
        val response = runCatching { transport.get(url, headers) }
            .getOrElse { return ConnectorResult(false, error = "Request failed: ${it.message ?: "unknown error"}") }
        return when (invocation.tool.name) {
            "get" -> ConnectorResult(true, ConnectorArguments.json(mapOf(
                "status" to response.statusCode.toString(),
                "body" to response.body,
            )))
            "status" -> ConnectorResult(true, ConnectorArguments.json(mapOf("status" to response.statusCode.toString())))
            else -> ConnectorResult(false, error = "Tool '${invocation.tool.name}' is not part of this integration")
        }
    }
}

/** The set of built-in integrations, in registration order. */
fun builtInIntegrations(): List<UniversalConnector> = listOf(
    BuiltInTimeIntegration(),
    BuiltInTextIntegration(),
    BuiltInHttpIntegration(),
)

/** Shared argument parsing and JSON building for the built-in integrations. */
internal object ConnectorArguments {

    fun parse(json: String): Map<String, String>? {
        val objectValue = runCatching {
            com.google.gson.JsonParser.parseString(json)
        }.getOrNull() ?: return null
        if (!objectValue.isJsonObject) return null
        return objectValue.asJsonObject.entrySet().associate { (key, value) ->
            key to when {
                value.isJsonNull -> ""
                value.isJsonPrimitive -> value.asJsonPrimitive.asString
                else -> value.toString()
            }
        }
    }

    fun json(values: Map<String, String>): String {
        val obj = com.google.gson.JsonObject()
        values.forEach { (key, value) -> obj.addProperty(key, value) }
        return obj.toString()
    }

    /** A JSON object carrying an array, encoded as a delimited string to keep the shape simple. */
    fun jsonArray(values: Map<String, String>): String {
        val obj = com.google.gson.JsonObject()
        values.forEach { (key, value) ->
            if (key == "parts") {
                val array = com.google.gson.JsonArray()
                value.split("\u0001").forEach { array.add(it) }
                obj.add(key, array)
            } else {
                obj.addProperty(key, value)
            }
        }
        return obj.toString()
    }
}
