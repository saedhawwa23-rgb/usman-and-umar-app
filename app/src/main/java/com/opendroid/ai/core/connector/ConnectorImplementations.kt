package com.opendroid.ai.core.connector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** JSON-RPC MCP connector. It never gives the language model access to authorization headers. */
class McpConnector(
    override val id: String,
    override val displayName: String,
    private val endpoint: String,
    private val definitions: List<ConnectorTool>,
    private val transport: HttpConnectorTransport = HttpConnectorTransport()
) : UniversalConnector {
    override val kind: ConnectorKind = ConnectorKind.MCP
    override fun tools(): List<ConnectorTool> = definitions

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult =
        credentials.withSecret(invocation.credential) { secret ->
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", invocation.invocationId)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", invocation.tool.name).put("arguments", JSONObject(invocation.argumentsJson)))
            val response = transport.post(endpoint, request.toString(), secret)
            if (response.code in 200..299) ConnectorResult(true, response.body) else ConnectorResult(false, error = "MCP HTTP ${response.code}: ${response.body}")
        }
}

/** CLI connector intentionally has no shell interpolation or arbitrary command strings. */
class CliConnector(
    override val id: String,
    override val displayName: String,
    private val executable: String,
    private val allowedTools: Map<String, List<String>>,
    private val definitions: List<ConnectorTool>
) : UniversalConnector {
    override val kind: ConnectorKind = ConnectorKind.CLI
    override fun tools(): List<ConnectorTool> = definitions

    init {
        require(executable.matches(Regex("[A-Za-z0-9._/-]+"))) { "CLI executable must be a direct executable path/name" }
        require(allowedTools.keys.all { name -> definitions.any { it.name == name } }) { "CLI tool allowlist contains an unknown tool" }
    }

    override suspend fun execute(invocation: ConnectorInvocation, credentials: ConnectorCredentialResolver): ConnectorResult =
        withContext(Dispatchers.IO) {
            if (invocation.tool.name !in allowedTools) return@withContext ConnectorResult(false, error = "CLI tool is not allowlisted")
            // Arguments are supplied as JSON on stdin. The process cannot interpret a shell command.
            val process = ProcessBuilder(listOf(executable) + allowedTools.getValue(invocation.tool.name))
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write(invocation.argumentsJson) }
            val output = process.inputStream.bufferedReader().use { it.readText().take(32_000) }
            val exit = process.waitFor()
            if (exit == 0) ConnectorResult(true, output) else ConnectorResult(false, error = "CLI exited $exit: $output")
        }
}

data class HttpTransportResponse(val code: Int, val body: String)

class HttpConnectorTransport {
    suspend fun post(url: String, body: String, bearer: String?): HttpTransportResponse = withContext(Dispatchers.IO) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpTransportResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(32_000))
        } finally { connection.disconnect() }
    }
}
