package com.opendroid.ai.core.agentplatform

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Drives a full task lifecycle on any connected agent platform through [AgentPlatformAdapter].
 *
 * Before this existed, "submit a task and follow it to completion" lived inside the OpenHands view
 * model and, separately, inside the OpenSwarm adapter's callers. Every platform added after that
 * would have needed its own copy. This class holds the sequence once: preflight, create, submit,
 * poll to a terminal state, collect events. A platform that cannot do one of those steps says so
 * through [PlatformResult.Unsupported] and the run stops with that reason instead of hanging.
 *
 * Nothing here invents progress. If the platform does not report a state, the outcome is
 * [AgentTaskState.UNKNOWN] and the caller is told so.
 */
class AgentPlatformOrchestrator(
    private val connectionStore: PlatformConnectionStore,
    private val createAdapter: (platformId: String, baseUrl: String, secret: String, workingDir: String) -> AgentPlatformAdapter? =
        { platformId, baseUrl, secret, workingDir ->
            AgentPlatformAdapters.create(platformId, baseUrl, secret, workingDir)
        },
    private val pollIntervalMillis: Long = 2_000L,
    private val maxWaitMillis: Long = 10 * 60 * 1000L,
) {

    /** What one platform run produced. [message] is always safe to show; it never holds a secret. */
    data class Outcome(
        val platformId: String,
        val platformName: String,
        val state: AgentTaskState,
        val sessionId: String? = null,
        val events: List<AgentTaskEvent> = emptyList(),
        val finalMessage: String? = null,
        val failure: String? = null,
    ) {
        val succeeded: Boolean get() = failure == null && state == AgentTaskState.COMPLETE
    }

    /** The checks a run must pass before anything is sent to a platform. */
    sealed interface Preflight {
        data class Ready(val adapter: AgentPlatformAdapter, val connection: PlatformConnection) : Preflight

        data class Blocked(val reason: String) : Preflight
    }

    /**
     * Resolves a stored connection into a ready adapter, or states exactly what is missing.
     *
     * The stored key is read from the Keystore-backed record and never returned to the caller, so a
     * caller cannot accidentally log it.
     */
    fun preflight(platformId: String, workingDir: String = AgentPlatformAdapters.DEFAULT_WORKING_DIR): Preflight {
        val platform = AgentPlatformCatalog.byId(platformId)
            ?: return Preflight.Blocked("Unknown agent platform \"$platformId\".")

        val connection = connectionStore.connection(platformId)
            ?: return Preflight.Blocked(
                "Connect ${platform.displayName} before running a task. Open the Web AI platform center and add its URL and key.",
            )

        if (PlatformCapability.SUBMIT_TASK !in platform.supported) {
            return Preflight.Blocked(
                "${platform.displayName} does not support submitting tasks. " +
                    "It offers: ${platform.supported.joinToString { it.name.lowercase() }.ifEmpty { "nothing" }}.",
            )
        }

        val secret = connectionStore.sessionKey(platformId)
        if (secret.isNullOrBlank()) {
            return Preflight.Blocked(
                "The stored key for ${platform.displayName} is unavailable. Reconnect the platform to store it again.",
            )
        }

        val adapter = createAdapter(platformId, connection.baseUrl, secret, workingDir)
            ?: return Preflight.Blocked("${platform.displayName} has no adapter in this build.")

        return Preflight.Ready(adapter, connection)
    }

    /**
     * Runs [prompt] to a terminal state on one platform.
     *
     * Stops early, with a specific reason, on a missing connection, an unsupported step, or a real
     * platform failure. Waiting is bounded by [maxWaitMillis]; exceeding it is reported as a timeout
     * rather than a success or a silent stop.
     */
    suspend fun runTask(
        platformId: String,
        prompt: String,
        workingDir: String = AgentPlatformAdapters.DEFAULT_WORKING_DIR,
    ): Outcome {
        if (prompt.isBlank()) {
            return Outcome(platformId, platformName(platformId), AgentTaskState.FAILED, failure = "The task prompt is empty.")
        }

        val ready = when (val p = preflight(platformId, workingDir)) {
            is Preflight.Blocked -> return Outcome(
                platformId,
                platformName(platformId),
                AgentTaskState.FAILED,
                failure = p.reason,
            )
            is Preflight.Ready -> p
        }
        val platform = ready.adapter.platform

        when (val valid = ready.adapter.validateConnection()) {
            is PlatformResult.Failure -> return Outcome(
                platform.id, platform.displayName, AgentTaskState.FAILED, failure = valid.message,
            )
            is PlatformResult.Unsupported -> return Outcome(
                platform.id, platform.displayName, AgentTaskState.FAILED,
                failure = "${platform.displayName} cannot validate a connection in this build.",
            )
            is PlatformResult.Success -> Unit
        }

        val sessionId = if (PlatformCapability.CREATE_SESSION in platform.supported) {
            when (val created = ready.adapter.createSession()) {
                is PlatformResult.Failure -> return Outcome(
                    platform.id, platform.displayName, AgentTaskState.FAILED, failure = created.message,
                )
                is PlatformResult.Unsupported -> return Outcome(
                    platform.id, platform.displayName, AgentTaskState.FAILED,
                    failure = "${platform.displayName} did not open a session.",
                )
                is PlatformResult.Success -> created.value
            }
        } else {
            null
        }

        val taskId = when (val submitted = ready.adapter.submitTask(sessionId.orEmpty(), prompt)) {
            is PlatformResult.Failure -> return Outcome(
                platform.id, platform.displayName, AgentTaskState.FAILED, sessionId, failure = submitted.message,
            )
            is PlatformResult.Unsupported -> return Outcome(
                platform.id, platform.displayName, AgentTaskState.FAILED, sessionId,
                failure = "${platform.displayName} does not accept tasks.",
            )
            is PlatformResult.Success -> submitted.value
        }

        val finalState = awaitTerminalState(ready.adapter, taskId)

        val events = when (val observed = ready.adapter.observeTaskEvents(taskId, 0)) {
            is PlatformResult.Success -> observed.value
            else -> emptyList()
        }

        return Outcome(
            platformId = platform.id,
            platformName = platform.displayName,
            state = finalState,
            sessionId = sessionId,
            events = events,
            failure = if (finalState == AgentTaskState.FAILED) {
                "${platform.displayName} reported the task as failed."
            } else {
                null
            },
        )
    }

    /**
     * Polls until the platform reports a terminal state.
     *
     * A platform that never reports a state is polled until the deadline, then reported as
     * [AgentTaskState.UNKNOWN]: "we could not tell" is the honest answer, and it is distinguishable
     * from both success and failure.
     */
    private suspend fun awaitTerminalState(adapter: AgentPlatformAdapter, taskId: String): AgentTaskState {
        val terminal = setOf(
            AgentTaskState.COMPLETE,
            AgentTaskState.FAILED,
            AgentTaskState.CANCELLED,
            AgentTaskState.UNKNOWN,
        )
        val deadline = System.currentTimeMillis() + maxWaitMillis
        var last: AgentTaskState = AgentTaskState.QUEUED
        while (System.currentTimeMillis() < deadline) {
            when (val polled = adapter.getTask(taskId)) {
                is PlatformResult.Success -> {
                    last = polled.value
                    if (polled.value in terminal) return polled.value
                }
                // A read failure is not progress; keep the last known state and retry until the deadline.
                is PlatformResult.Failure, is PlatformResult.Unsupported -> Unit
            }
            delay(pollIntervalMillis)
        }
        return if (last == AgentTaskState.QUEUED) AgentTaskState.UNKNOWN else last
    }

    /**
     * Runs the same prompt on several platforms concurrently and returns one outcome per platform.
     *
     * A failure on one platform never cancels the others: a partial result is more useful than
     * nothing, and each outcome carries its own reason.
     */
    suspend fun runAcross(
        platformIds: Collection<String>,
        prompt: String,
        workingDir: String = AgentPlatformAdapters.DEFAULT_WORKING_DIR,
    ): List<Outcome> {
        val ids = platformIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return coroutineScope {
            ids.map { id ->
                async { runTask(id, prompt, workingDir) }
            }.map { it.await() }
        }
    }

    /** Cancels a running task, reporting the platform's own refusal if it has one. */
    suspend fun cancel(platformId: String, taskId: String): PlatformResult<Unit> {
        val ready = when (val p = preflight(platformId)) {
            is Preflight.Blocked -> return PlatformResult.Failure(p.reason)
            is Preflight.Ready -> p
        }
        return ready.adapter.cancelTask(taskId)
    }

    private fun platformName(platformId: String): String =
        AgentPlatformCatalog.byId(platformId)?.displayName ?: platformId
}
