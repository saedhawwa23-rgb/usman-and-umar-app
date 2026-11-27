package com.opendroid.ai.core.agentplatform

/**
 * Production [AgentPlatformAdapter] over [OpenHandsClient].
 *
 * OpenHands was previously reachable only through its own view-model path, which meant the
 * "connect, submit, observe, cancel" flow existed twice: once for OpenSwarm through this contract
 * and once, hand-rolled, for OpenHands. This adapter puts OpenHands behind the same contract so an
 * orchestrator can drive either platform without knowing which one it holds.
 *
 * Only the operations the catalog declares are forwarded. `listArtifacts` stays unimplemented
 * because the Agent Server API exposes no file enumeration; `downloadArtifact` takes a server-side
 * path, which is what `downloadFile` actually accepts.
 */
class OpenHandsAdapter(
    override val platform: AgentPlatform,
    private val client: OpenHandsClient,
    /** The directory the agent server writes to. Required by the conversation API. */
    private val workingDir: String,
) : AgentPlatformAdapter {

    /** Conversation created by [createSession], reused by [submitTask] when no session is given. */
    private var sessionConversationId: String? = null

    override suspend fun validateConnection(): PlatformResult<Unit> = client.validateConnection()

    override suspend fun createSession(): PlatformResult<String> =
        when (val settings = client.fetchAgentSettings()) {
            is PlatformResult.Failure -> settings
            else -> {
                val conversation = client.startConversation(
                    prompt = "",
                    workingDir = workingDir,
                    agentSettings = (settings as? PlatformResult.Success)?.value,
                    run = false,
                )
                when (conversation) {
                    is PlatformResult.Failure -> conversation
                    is PlatformResult.Success -> {
                        sessionConversationId = conversation.value.id
                        PlatformResult.Success(conversation.value.id)
                    }
                    else -> PlatformResult.Failure("Unexpected response creating the conversation")
                }
            }
        }

    override suspend fun submitTask(sessionId: String, prompt: String): PlatformResult<String> {
        val conversationId = sessionId.ifBlank { sessionConversationId.orEmpty() }
        if (conversationId.isBlank()) {
            return PlatformResult.Failure(
                "No OpenHands conversation is open. Create a session before submitting a task.",
            )
        }
        return when (val sent = client.sendMessage(conversationId, prompt, run = true)) {
            is PlatformResult.Failure -> sent
            is PlatformResult.Success -> PlatformResult.Success(conversationId)
            else -> PlatformResult.Failure("Unexpected response submitting the task")
        }
    }

    override suspend fun getTask(taskId: String): PlatformResult<AgentTaskState> =
        when (val conversation = client.getConversation(taskId)) {
            is PlatformResult.Failure -> conversation
            is PlatformResult.Success -> PlatformResult.Success(
                conversation.value.executionStatus.toAgentTaskState(),
            )
            else -> PlatformResult.Failure("Unexpected response reading the conversation")
        }

    override suspend fun observeTaskEvents(
        taskId: String,
        sinceSequence: Long,
    ): PlatformResult<List<AgentTaskEvent>> =
        when (val events = client.searchEvents(taskId)) {
            is PlatformResult.Failure -> events
            is PlatformResult.Success -> PlatformResult.Success(
                events.value.mapIndexed { index, event ->
                    AgentTaskEvent(
                        sequence = index.toLong(),
                        // The event stream does not carry a task state per event, so state is
                        // reported by getTask and each event is marked UNKNOWN rather than guessed.
                        state = AgentTaskState.UNKNOWN,
                        message = event.summary(),
                        timestampMillis = event.timestamp?.let(::parseIsoMillis) ?: 0L,
                    )
                }.filter { it.sequence >= sinceSequence },
            )
            else -> PlatformResult.Failure("Unexpected response reading the events")
        }

    override suspend fun cancelTask(taskId: String): PlatformResult<Unit> = client.interrupt(taskId)

    override suspend fun downloadArtifact(taskId: String, artifactId: String): PlatformResult<ByteArray> =
        client.downloadFile(artifactId)

    /**
     * The Agent Server reports execution status as a lower-case string. An unrecognised value maps
     * to [AgentTaskState.UNKNOWN] instead of being coerced into a state the server did not report.
     */
    private fun String?.toAgentTaskState(): AgentTaskState = when (this?.trim()?.lowercase()) {
        null, "" -> AgentTaskState.UNKNOWN
        "idle", "queued", "pending", "created" -> AgentTaskState.QUEUED
        "running", "busy", "in_progress" -> AgentTaskState.RUNNING
        "awaiting_user_input", "waiting_for_input", "paused" -> AgentTaskState.WAITING_FOR_INPUT
        "finished", "complete", "completed", "success" -> AgentTaskState.COMPLETE
        "error", "failed" -> AgentTaskState.FAILED
        "cancelled", "canceled", "stopped" -> AgentTaskState.CANCELLED
        else -> AgentTaskState.UNKNOWN
    }

    /** ISO-8601 with a trailing `Z` or an offset; anything else yields 0 rather than a guess. */
    private fun parseIsoMillis(raw: String): Long = runCatching {
        java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.Instant.parse(raw).toEpochMilli()
    }.getOrDefault(0L)
}
