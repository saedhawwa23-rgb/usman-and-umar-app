package com.opendroid.ai.core.connector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.net.URLEncoder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/** Connector families are intentionally generic so new services do not require agent changes. */
enum class ConnectorKind { MCP, REST, OAUTH, CLI, WEBHOOK, DEVELOPER_PLATFORM, CLOUD, HOSTING }
enum class ConnectorRisk(val severity: Int) { READ_ONLY(1), REVERSIBLE(2), SENSITIVE(3), DESTRUCTIVE(4) }

data class ConnectorCredentialRef(val id: String, val kind: String = "api_key")

data class ConnectorTool(
    val connectorId: String,
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val risk: ConnectorRisk = ConnectorRisk.READ_ONLY,
    val credential: ConnectorCredentialRef? = null
) {
    val qualifiedName: String get() = "connector_${connectorId}__${name}"
}

data class ConnectorInvocation(
    val invocationId: String = UUID.randomUUID().toString(),
    val tool: ConnectorTool,
    val argumentsJson: String,
    val credential: ConnectorCredentialRef? = tool.credential
)

data class ConnectorResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null,
    val redacted: Boolean = true
)

enum class ApprovalDecision { ALLOW, DENY, NEEDS_USER_APPROVAL }

interface ConnectorApprovalGate {
    suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision
}

/** Default-safe gate: only read-only and reversible actions can run unattended. */
object DefaultConnectorApprovalGate : ConnectorApprovalGate {
    override suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision = when {
        invocation.tool.risk.severity <= ConnectorRisk.REVERSIBLE.severity -> ApprovalDecision.ALLOW
        else -> ApprovalDecision.NEEDS_USER_APPROVAL
    }
}

/** The secret value is available only inside this callback and is never passed to Needle. */
interface ConnectorCredentialResolver {
    suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T
}

interface UniversalConnector {
    val id: String
    val displayName: String
    val kind: ConnectorKind
    fun tools(): List<ConnectorTool>
    suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult
}

interface ConnectorExecutionLog {
    fun append(record: ConnectorExecutionRecord)
    fun recent(limit: Int = 50): List<ConnectorExecutionRecord>
}

data class ConnectorExecutionRecord(
    val invocationId: String,
    val connectorId: String,
    val toolName: String,
    val risk: ConnectorRisk,
    val status: String,
    val error: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/** In-memory log for the process; callers can persist redacted records through Room later. */
@Singleton
class InMemoryConnectorExecutionLog @Inject constructor() : ConnectorExecutionLog {
    private val records = ArrayDeque<ConnectorExecutionRecord>()
    @Synchronized override fun append(record: ConnectorExecutionRecord) {
        records.addLast(record)
        while (records.size > 200) records.removeFirst()
    }
    @Synchronized override fun recent(limit: Int): List<ConnectorExecutionRecord> =
        records.toList().takeLast(limit.coerceIn(1, 200))
}

@Singleton
class UniversalToolRegistry @Inject constructor() {
    private val connectors = LinkedHashMap<String, UniversalConnector>()

    @Synchronized fun register(connector: UniversalConnector) {
        require(connector.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid connector id" }
        require(connector.tools().map { it.name }.distinct().size == connector.tools().size) {
            "Connector tool names must be unique"
        }
        connectors[connector.id] = connector
    }

    @Synchronized fun unregister(id: String) { connectors.remove(id) }
    @Synchronized fun all(): List<UniversalConnector> = connectors.values.toList()
    @Synchronized fun allTools(): List<ConnectorTool> = connectors.values.flatMap { it.tools() }
    @Synchronized fun findTool(qualifiedName: String): ConnectorTool? = allTools().find { it.qualifiedName == qualifiedName }
    @Synchronized fun connectorFor(id: String): UniversalConnector? = connectors[id]

    /** Register a generic HTTP-backed manifest; no agent or model code changes are required. */
    fun registerHttpManifest(
        id: String,
        displayName: String,
        kind: ConnectorKind,
        baseUrl: String,
        tools: List<ConnectorTool>,
        methodByTool: Map<String, String> = emptyMap(),
        pathByTool: Map<String, String> = emptyMap(),
        staticHeaders: Map<String, String> = emptyMap()
    ) = register(HttpConnector(id, displayName, kind, baseUrl, tools, methodByTool, pathByTool, staticHeaders))

    fun registerMcp(
        id: String,
        displayName: String,
        endpoint: String,
        tools: List<ConnectorTool>
    ) = register(McpConnector(id, displayName, endpoint, tools))

    suspend fun execute(
        invocation: ConnectorInvocation,
        credentials: ConnectorCredentialResolver,
        approval: ConnectorApprovalGate = DefaultConnectorApprovalGate,
        log: ConnectorExecutionLog = InMemoryConnectorExecutionLog()
    ): ConnectorResult {
        if (invocation.argumentsJson.length > 64_000) {
            return ConnectorResult(false, error = "Tool arguments exceed the 64 KB safety limit")
        }
        val registeredTool = findTool(invocation.tool.qualifiedName)
        if (registeredTool == null || registeredTool.connectorId != invocation.tool.connectorId) {
            log.append(ConnectorExecutionRecord(invocation.invocationId, invocation.tool.connectorId, invocation.tool.name, invocation.tool.risk, "rejected", "Tool is not registered"))
            return ConnectorResult(false, error = "Tool is not registered")
        }
        val arguments = runCatching { JsonParser.parseString(invocation.argumentsJson) }.getOrNull()
        if (arguments == null || !arguments.isJsonObject) {
            return ConnectorResult(false, error = "Tool arguments must be a JSON object")
        }
        val decision = approval.decide(invocation)
        if (decision != ApprovalDecision.ALLOW) {
            val status = if (decision == ApprovalDecision.DENY) "denied" else "approval_required"
            log.append(ConnectorExecutionRecord(invocation.invocationId, invocation.tool.connectorId, invocation.tool.name, invocation.tool.risk, status))
            return ConnectorResult(false, error = if (decision == ApprovalDecision.DENY) "Connector action denied" else "User approval required")
        }
        val connector = connectorFor(invocation.tool.connectorId)
            ?: return ConnectorResult(false, error = "Connector is not registered")
        return try {
            connector.execute(invocation, credentials).also {
                log.append(ConnectorExecutionRecord(invocation.invocationId, connector.id, invocation.tool.name, invocation.tool.risk, if (it.success) "success" else "failed", it.error))
            }
        } catch (t: Throwable) {
            log.append(ConnectorExecutionRecord(invocation.invocationId, connector.id, invocation.tool.name, invocation.tool.risk, "failed", t.message ?: "connector error"))
            ConnectorResult(false, error = t.message ?: "Connector execution failed")
        }
    }
}

/** Generic REST/webhook connector. Secrets are added only to the transport request. */
class HttpConnector(
    override val id: String,
    override val displayName: String,
    override val kind: ConnectorKind,
    private val baseUrl: String,
    private val definitions: List<ConnectorTool>,
    private val methodByTool: Map<String, String> = emptyMap(),
    private val pathByTool: Map<String, String> = emptyMap(),
    private val staticHeaders: Map<String, String> = emptyMap()
) : UniversalConnector {
    override fun tools(): List<ConnectorTool> = definitions
    private val gson = Gson()

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult =
        withContext(Dispatchers.IO) {
            credentials.withSecret(invocation.credential) { secret ->
                val pathTemplate = pathByTool[invocation.tool.name] ?: "/"
                val args = runCatching { gson.fromJson(invocation.argumentsJson, JsonObject::class.java) }.getOrNull()
                    ?: return@withSecret ConnectorResult(false, error = "Tool arguments were not a JSON object")
                val missingPathArgument = Regex("\\{([A-Za-z0-9_]+)}").findAll(pathTemplate)
                    .map { it.groupValues[1] }.firstOrNull { args.get(it)?.asString.isNullOrBlank() }
                if (missingPathArgument != null) {
                    return@withSecret ConnectorResult(false, error = "Required path argument is missing: $missingPathArgument")
                }
                val path = Regex("\\{([A-Za-z0-9_]+)}").replace(pathTemplate) { match ->
                    val value = args.get(match.groupValues[1]).asString
                    require(value.length <= 200 && value.none { it.isISOControl() }) { "Path parameter is invalid" }
                    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                }
                val requestUrl = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = methodByTool[invocation.tool.name] ?: "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    doInput = true
                    if (requestMethod != "GET") doOutput = true
                    staticHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
                    if (!secret.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $secret")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    if (connection.doOutput) connection.outputStream.use { it.write(invocation.argumentsJson.toByteArray()) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(32_000)
                    if (code in 200..299) ConnectorResult(true, body) else ConnectorResult(false, error = "HTTP $code: $body")
                } finally { connection.disconnect() }
            }
        }
}
