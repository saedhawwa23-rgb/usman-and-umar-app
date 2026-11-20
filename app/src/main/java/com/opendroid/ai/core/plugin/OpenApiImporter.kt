package com.opendroid.ai.core.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool

/**
 * Imports an OpenAPI 3.1 document (https://spec.openapis.org/oas/v3.1.0.html) into the plugin
 * model.
 *
 * 3.1 is a JSON Schema 2020-12 dialect, so parameter schemas are copied through unchanged rather
 * than re-interpreted. Only `http(s)` servers are accepted, and only `GET`/`POST`/`PUT`/`PATCH`/
 * `DELETE` operations become tools: `TRACE` is refused outright, and `DELETE` is classified as
 * destructive so the approval gate always stops for it.
 */
object OpenApiImporter {

    private val SUPPORTED_METHODS = setOf("get", "post", "put", "patch", "delete")
    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")

    /** Where one imported operation sends its request. */
    data class ApiRoute(val method: String, val pathTemplate: String)

    data class ImportedApi(
        val manifest: PluginManifest,
        val tools: List<ConnectorTool>,
        val baseUrl: String,
        val routes: Map<String, ApiRoute>,
        val warnings: List<String>,
    )

    sealed interface Result {
        data class Success(val api: ImportedApi) : Result
        data class Failure(val reason: String) : Result
    }

    fun import(
        documentJson: String,
        pluginId: String,
        displayName: String? = null,
        version: String = "1.0.0",
        appVersion: String = "0.0.0",
    ): Result {
        if (!ID_PATTERN.matches(pluginId)) {
            return Result.Failure("Plugin id must be lowercase, namespaced, and 1-128 characters")
        }
        val root = runCatching { JsonParser.parseString(documentJson).asJsonObject }.getOrNull()
            ?: return Result.Failure("The document is not a JSON object")
        val openapiVersion = root.get("openapi")?.asString
            ?: return Result.Failure("The document has no 'openapi' field, so it is not an OpenAPI 3.x document")
        if (!openapiVersion.startsWith("3.1")) {
            return Result.Failure("Only OpenAPI 3.1 documents are supported; this one declares $openapiVersion")
        }
        val info = root.getAsJsonObject("info")
        if (info == null || info.get("title")?.asString.isNullOrBlank()) {
            return Result.Failure("The document has no info.title")
        }
        val servers = root.getAsJsonArray("servers")
        val baseUrl = servers?.firstOrNullBaseUrl()
            ?: return Result.Failure("The document declares no server URL")
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            return Result.Failure("Only http(s) server URLs are supported; found $baseUrl")
        }
        val paths = root.getAsJsonObject("paths")
            ?: return Result.Failure("The document has no 'paths' object")

        val warnings = mutableListOf<String>()
        if (baseUrl.startsWith("http://")) {
            warnings.add("Server URL is plain HTTP; traffic will not be encrypted")
        }
        val tools = mutableListOf<ConnectorTool>()
        val toolNames = mutableSetOf<String>()
        val routes = LinkedHashMap<String, ApiRoute>()

        for ((rawPath, pathItem) in paths.entrySet()) {
            if (!pathItem.isJsonObject) continue
            val pathObj = pathItem.asJsonObject
            // Path-level parameters apply to every operation beneath the path.
            val sharedParams = pathObj.getAsJsonArray("parameters") ?: JsonArray()
            for (method in SUPPORTED_METHODS) {
                val operation = pathObj.getAsJsonObject(method) ?: continue
                val operationId = operation.get("operationId")?.asString
                val name = sanitizeToolName(
                    operationId ?: "${method}_${rawPath.trim('/').replace(Regex("[^A-Za-z0-9]+"), "_")}"
                )
                if (name.isBlank()) continue
                if (!toolNames.add(name)) {
                    warnings.add("Skipped duplicate operation id '$name'")
                    continue
                }
                val operationParams = operation.getAsJsonArray("parameters") ?: JsonArray()
                val merged = mergeParameters(sharedParams, operationParams)
                val (schema, routePath) = buildParameterSchema(
                    merged, rawPath, operation.getAsJsonObject("requestBody"), warnings, name,
                )
                tools.add(
                    ConnectorTool(
                        connectorId = pluginId,
                        name = name,
                        description = operation.get("summary")?.asString
                            ?: operation.get("description")?.asString
                            ?: "$method ${rawPath}",
                        parametersJsonSchema = schema,
                        risk = riskFor(method),
                        credential = null,
                        toolNameOverride = null,
                        host = null,
                    )
                )
                routes[name] = ApiRoute(method.uppercase(), routePath)
            }
        }
        if (tools.isEmpty()) {
            return Result.Failure("The document declares no usable operations (GET/POST/PUT/PATCH/DELETE)")
        }

        val manifest = PluginManifest(
            id = pluginId,
            displayName = displayName ?: info.get("title").asString,
            version = version,
            publisher = info.getAsJsonObject("contact")?.get("name")?.asString ?: "unknown",
            homepageUrl = info.get("termsOfService")?.asString
                ?: info.getAsJsonObject("contact")?.get("url")?.asString ?: "",
            kind = "SERVICE",
            interop = listOf("OPENAPI_31"),
            capabilities = listOf("NETWORK_FETCH"),
            dependencies = emptyList(),
            compatibility = PluginCompatibility(minAppVersion = appVersion),
        )
        return Result.Success(ImportedApi(manifest, tools, baseUrl, routes, warnings))
    }

    private fun JsonArray.firstOrNullBaseUrl(): String? =
        this.mapNotNull { element ->
            runCatching { element.asJsonObject.get("url")?.asString }.getOrNull()
        }.firstOrNull { it.isNotBlank() }?.trimEnd('/')

    private fun mergeParameters(shared: JsonArray, own: JsonArray): List<JsonObject> {
        val byKey = LinkedHashMap<String, JsonObject>()
        for (array in listOf(shared, own)) {
            for (element in array) {
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
                val name = obj.get("name")?.asString ?: continue
                val location = obj.get("in")?.asString ?: continue
                byKey["$location:$name"] = obj
            }
        }
        return byKey.values.toList()
    }

    /**
     * Build a JSON Schema object whose properties are the operation's parameters, and the route
     * path the connector should call.
     *
     * Query parameters are folded into the path as `{name}` placeholders and tagged with
     * `Location: query` in their description; the connector substitutes them and appends them as a
     * query string. Path parameters are already `{name}` in the route. Header parameters are
     * surfaced as properties but not placed in the URL, since the generic connector sends no
     * caller-supplied headers.
     */
    private fun buildParameterSchema(
        parameters: List<JsonObject>,
        rawPath: String,
        requestBody: JsonObject?,
        warnings: MutableList<String>,
        toolName: String,
    ): Pair<String, String> {
        val properties = JsonObject()
        val required = JsonArray()
        val queryNames = mutableListOf<String>()
        for (param in parameters) {
            val name = param.get("name")?.asString ?: continue
            val location = param.get("in")?.asString ?: "query"
            val schema = param.getAsJsonObject("schema")?.deepCopy()
                ?: JsonObject().apply { addProperty("type", "string") }
            val description = param.get("description")?.asString
            val existing = schema.get("description")?.asString
            schema.addProperty(
                "description",
                listOfNotNull(description, existing, "Location: $location").joinToString(" - ")
            )
            properties.add(name, schema)
            if (param.get("required")?.asBoolean == true) required.add(name)
            when (location) {
                "query" -> queryNames += name
                "header" -> warnings += "Operation '$toolName' declares a header parameter '$name'; the generic connector does not send caller headers"
            }
        }
        if (requestBody != null) {
            val content = requestBody.getAsJsonObject("content")
            val jsonContent = content?.getAsJsonObject("application/json")
            val bodySchema = jsonContent?.getAsJsonObject("schema")
            if (bodySchema != null) {
                properties.add("body", bodySchema.deepCopy())
                if (requestBody.get("required")?.asBoolean == true) required.add("body")
            } else if (content != null) {
                warnings.add("Operation '$toolName' has a request body that is not application/json; it was not imported")
            }
        }
        val schema = JsonObject().apply {
            addProperty("\$schema", "https://json-schema.org/draft/2020-12/schema")
            addProperty("type", "object")
            add("properties", properties)
            if (required.size() > 0) add("required", required)
            addProperty("additionalProperties", false)
        }
        // Query parameters are appended as placeholders so the connector places them in the URL.
        val routePath = if (queryNames.isEmpty()) rawPath
        else rawPath + "?" + queryNames.joinToString("&") { "$it={$it}" }
        return schema.toString() to routePath
    }

    private fun riskFor(method: String): ConnectorRisk = when (method.lowercase()) {
        "get" -> ConnectorRisk.READ_ONLY
        "post", "put", "patch" -> ConnectorRisk.SENSITIVE
        "delete" -> ConnectorRisk.DESTRUCTIVE
        else -> ConnectorRisk.SENSITIVE
    }

    internal fun sanitizeToolName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').take(64)
}
