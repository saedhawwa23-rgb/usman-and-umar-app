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
     * Platform ids that have a real adapter. Must stay in step with [create]; OpenHands is
     * deliberately absent because its conversation lifecycle is driven by its own client.
     */
    fun supportedIds(): Set<String> = setOf(AgentPlatformCatalog.openSwarm.id)

    fun create(platformId: String, baseUrl: String, secret: String): AgentPlatformAdapter? =
        when (platformId) {
            AgentPlatformCatalog.openSwarm.id -> OpenSwarmAdapter(AgentPlatformCatalog.openSwarm, OpenSwarmClient(baseUrl, secret))
            // OpenHands is driven by its own richer client/view-model path; it has no adapter
            // because that path already implements the full conversation lifecycle.
            else -> null
        }
}
