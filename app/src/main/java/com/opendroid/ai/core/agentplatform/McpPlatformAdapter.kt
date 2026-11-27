package com.opendroid.ai.core.agentplatform

import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorCredentialResolver
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool
import com.opendroid.ai.core.connector.McpConnector
import com.opendroid.ai.core.connector.McpToolDiscovery

/**
 * Adapter for a Model Context Protocol server the user points the app at.
 *
 * MCP has no conversation lifecycle. It publishes a flat list of tools, and calling one returns a
 * result, so this adapter declares only [PlatformCapability.SUBMIT_TASK] and answers
 * [PlatformResult.Unsupported] for sessions, event streams, cancellation and file transfer rather
 * than imitating them. A tool call is synchronous: a successful call is [AgentTaskState.COMPLETE].
 *
 * The prompt carries the invocation. Its first line is the tool name, optionally followed by the
 * JSON arguments; any further lines are the arguments. That framing is a convention of this
 * adapter, not part of MCP, and the error message says so.
 *
 * An MCP call has no server-side task to poll, so [submitTask] remembers the result in this
 * instance. A caller must keep one adapter per connection for [getTask] to answer; a freshly built
 * adapter reports the invocation as unknown rather than guessing a state.
 */
class McpPlatformAdapter(
    override val platform: AgentPlatform,
    private val endpoint: String,
    private val credentials: ConnectorCredentialResolver,
    private val credentialRef: ConnectorCredentialRef? = null,
    private val discovery: McpToolDiscovery = McpToolDiscovery(),
) : AgentPlatformAdapter {

    private val results = mutableMapOf<String, AgentTaskState>()
    private var callCounter = 0L

    override suspend fun validateConnection(): PlatformResult<Unit> =
        when (val result = discovery.discover(platform.id, endpoint)) {
            is McpToolDiscovery.Result.Success -> PlatformResult.Success(Unit)
            is McpToolDiscovery.Result.Failure -> PlatformResult.Failure(result.reason)
        }

    override suspend fun createSession(): PlatformResult<String> =
        PlatformResult.Unsupported("create_session", platform.id)

    override suspend fun submitTask(sessionId: String, prompt: String): PlatformResult<String> {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return PlatformResult.Failure("The task prompt is empty.")

        val firstLine = trimmed.lineSequence().first().trim()
        val toolName = firstLine.substringBefore(' ').trim()
        if (toolName.isEmpty()) {
            return PlatformResult.Failure(
                "Name the MCP tool on the first line, for example: search {\"q\":\"x\"}",
            )
        }

        val inlineArgs = firstLine.removePrefix(toolName).trim()
        val remaining = trimmed.lineSequence().drop(1).joinToString("\n").trim()
        val arguments = inlineArgs.ifEmpty { remaining }.ifEmpty { "{}" }

        val taskId = "mcp-${++callCounter}"
        val tool = ConnectorTool(
            connectorId = platform.id,
            name = toolName,
            description = "MCP tool $toolName",
            parametersJsonSchema = "{}",
            risk = ConnectorRisk.READ_ONLY,
            credential = credentialRef,
        )
        val connector = McpConnector(
            id = platform.id,
            displayName = platform.displayName,
            endpoint = endpoint,
            definitions = listOf(tool),
        )
        val result = connector.execute(
            ConnectorInvocation(invocationId = taskId, tool = tool, argumentsJson = arguments),
            credentials,
        )
        return if (result.success) {
            results[taskId] = AgentTaskState.COMPLETE
            PlatformResult.Success(taskId)
        } else {
            results[taskId] = AgentTaskState.FAILED
            PlatformResult.Failure("MCP tool \"$toolName\" failed: ${result.error ?: "no reason given"}")
        }
    }

    override suspend fun getTask(taskId: String): PlatformResult<AgentTaskState> {
        val state = results[taskId]
            ?: return PlatformResult.Failure("Unknown MCP invocation \"$taskId\".")
        return PlatformResult.Success(state)
    }

    override suspend fun observeTaskEvents(
        taskId: String,
        sinceSequence: Long,
    ): PlatformResult<List<AgentTaskEvent>> =
        PlatformResult.Unsupported("observe_task_events", platform.id)

    override suspend fun cancelTask(taskId: String): PlatformResult<Unit> =
        PlatformResult.Unsupported("cancel_task", platform.id)

    override suspend fun listArtifacts(taskId: String): PlatformResult<List<AgentArtifact>> =
        PlatformResult.Unsupported("list_artifacts", platform.id)

    override suspend fun downloadArtifact(taskId: String, artifactId: String): PlatformResult<ByteArray> =
        PlatformResult.Unsupported("download_artifact", platform.id)
}
