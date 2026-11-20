package com.opendroid.ai.core.agentplatform

/**
 * Production [AgentPlatformAdapter] for the USMAN AND UMAR OpenSwarm backend.
 *
 * The adapter declares only the operations the backend actually implements and answers
 * [PlatformResult.Unsupported] for the rest, so the UI cannot present a capability the server
 * does not have. Task submission forwards the backend's own readiness refusal instead of
 * pretending the task started.
 */
class OpenSwarmAdapter(
    override val platform: AgentPlatform,
    private val client: OpenSwarmClient,
) : AgentPlatformAdapter {

    override suspend fun validateConnection(): PlatformResult<Unit> = client.validateConnection()

    override suspend fun submitTask(sessionId: String, prompt: String): PlatformResult<String> =
        client.execute(sessionId, prompt)

    override suspend fun getTask(taskId: String): PlatformResult<AgentTaskState> =
        when (val r = client.task(taskId)) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(r.value.state.toAgentTaskState())
            else -> PlatformResult.Failure("Unexpected response reading the task")
        }

    override suspend fun observeTaskEvents(
        taskId: String,
        sinceSequence: Long,
    ): PlatformResult<List<AgentTaskEvent>> =
        when (val r = client.taskEvents(taskId)) {
            is PlatformResult.Failure -> r
            is PlatformResult.Success -> PlatformResult.Success(
                r.value.mapIndexed { index, text ->
                    AgentTaskEvent(
                        sequence = index.toLong(),
                        state = AgentTaskState.UNKNOWN,
                        message = text,
                        timestampMillis = System.currentTimeMillis(),
                    )
                }.filter { it.sequence >= sinceSequence }
            )
            else -> PlatformResult.Failure("Unexpected response reading the events")
        }

    override suspend fun cancelTask(taskId: String): PlatformResult<Unit> = client.cancel(taskId)

    /** The backend reports the task's state as an upper-case string. */
    private fun String.toAgentTaskState(): AgentTaskState = when (uppercase()) {
        "QUEUED", "PENDING" -> AgentTaskState.QUEUED
        "RUNNING", "PAUSE_REQUESTED", "RESUME_REQUESTED" -> AgentTaskState.RUNNING
        "PAUSED" -> AgentTaskState.WAITING_FOR_INPUT
        "COMPLETE", "COMPLETED", "SUCCESS", "SUCCEEDED" -> AgentTaskState.COMPLETE
        "FAILED", "ERROR" -> AgentTaskState.FAILED
        "CANCELLED", "CANCELED" -> AgentTaskState.CANCELLED
        else -> AgentTaskState.UNKNOWN
    }
}

/**
 * Builds the adapter for a platform id from a stored connection.
 *
 * Adding a platform means adding one entry here and one [AgentPlatform] to the catalog; no screen
 * or view-model change is required. An unknown id returns null rather than a fake adapter, so a
 * platform can never appear connectable before its adapter exists.
 */
object AgentPlatformAdapters {

    /**
     * Platform ids that have a real adapter. Must stay in step with [create]; the parity test fails
     * if an id here cannot be built.
     */
    fun supportedIds(): Set<String> = setOf(
        AgentPlatformCatalog.openHands.id,
        AgentPlatformCatalog.openSwarm.id,
        AgentPlatformCatalog.mcpServer.id,
    )

    /**
     * Builds the adapter for [platformId].
     *
     * [workingDir] is only used by platforms that need a server-side workspace (OpenHands); it is
     * ignored by the others, so a caller can pass it unconditionally.
     *
     * [mcpCredentials] is required only by an MCP platform, whose tool calls read their token
     * through the shared credential resolver rather than holding it directly.
     */
    fun create(
        platformId: String,
        baseUrl: String,
        secret: String,
        workingDir: String = DEFAULT_WORKING_DIR,
        mcpCredentials: com.opendroid.ai.core.connector.ConnectorCredentialResolver? = null,
    ): AgentPlatformAdapter? = when (platformId) {
        AgentPlatformCatalog.openSwarm.id ->
            OpenSwarmAdapter(AgentPlatformCatalog.openSwarm, OpenSwarmClient(baseUrl, secret))

        AgentPlatformCatalog.openHands.id ->
            OpenHandsAdapter(
                AgentPlatformCatalog.openHands,
                OpenHandsClient(baseUrl, secret),
                workingDir.ifBlank { DEFAULT_WORKING_DIR },
            )

        AgentPlatformCatalog.mcpServer.id ->
            McpPlatformAdapter(
                platform = AgentPlatformCatalog.mcpServer,
                endpoint = baseUrl,
                credentials = mcpCredentials ?: NoCredentialResolver,
            )

        else -> null
    }

    /**
     * Fallback resolver for an MCP server that needs no token. It never invents a secret: the block
     * receives null, which the connector treats as "no Authorization header".
     */
    private object NoCredentialResolver : com.opendroid.ai.core.connector.ConnectorCredentialResolver {
        override suspend fun <T> withSecret(
            ref: com.opendroid.ai.core.connector.ConnectorCredentialRef?,
            block: suspend (String?) -> T,
        ): T = block(null)
    }

    const val DEFAULT_WORKING_DIR: String = "/workspace"
}
