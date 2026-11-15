package com.opendroid.ai.core.remote

import com.opendroid.ai.core.agent.AgentToolEnvironment
import com.opendroid.ai.core.agent.AgentToolRegistry
import com.opendroid.ai.core.agent.ChatToolCall

/**
 * The outcome of one remote request, end to end.
 *
 * [executed] is false for every rejection and for an owner denial: those are correct outcomes,
 * not errors. [detail] carries the tool output or the reason, and never contains a secret.
 */
data class RemoteRequestOutcome(
    val executed: Boolean,
    val toolName: String,
    val detail: String,
)

/**
 * Turns an authenticated remote request into an executed action.
 *
 * Before this existed the security core authenticated and approved requests that nothing could
 * send and nothing executed: `authenticate`/`requestApproval` had no callers outside their own
 * file. This handler is the missing link. It deliberately does not re-implement any security
 * check - [RemoteControlService] remains the only authority on pairing, replay, the clock window
 * and the kill switch.
 *
 * Order is fixed and fail-closed: authenticate, then ask the owner, then execute. A denial, a
 * timeout, or a rejection all stop before execution.
 */
class RemoteRequestHandler(
    private val service: RemoteControlService,
    private val registry: AgentToolRegistry = AgentToolRegistry.standard(),
) {
    /**
     * @param ownerDecision the owner's answer, or null to let [RemoteControlService.requestApproval]
     *   apply its deny-on-timeout default. A headless caller should pass null.
     */
    suspend fun handle(
        request: SignedRemoteRequest,
        environment: AgentToolEnvironment,
        ownerDecision: RemoteApprovalDecision? = null,
        approvalTimeoutMs: Long = RemoteControlService.DEFAULT_APPROVAL_TIMEOUT_MS,
    ): RemoteRequestOutcome {
        val action = request.action

        when (val auth = service.authenticate(request)) {
            is RemoteAuthentication.Rejected ->
                return RemoteRequestOutcome(false, action.toolName, "Rejected: ${auth.reason}")
            RemoteAuthentication.Authenticated -> Unit
        }

        // A remote action must name a tool that actually exists, otherwise the owner would be
        // asked to approve something that can only fail afterwards.
        if (registry.find(action.toolName) == null) {
            return RemoteRequestOutcome(false, action.toolName, "Unknown tool '${action.toolName}'. Nothing was executed.")
        }

        val decision = service.requestApproval(
            deviceId = request.deviceId,
            action = action,
            ownerDecision = ownerDecision,
            timeoutMs = approvalTimeoutMs,
        )
        if (decision != RemoteApprovalDecision.APPROVED) {
            return RemoteRequestOutcome(false, action.toolName, "Not approved (${decision.name.lowercase()}). Nothing was executed.")
        }

        val call = ChatToolCall(action = action.toolName, rawJson = action.argumentsJson)
        return runCatching { registry.execute(call, environment) }.fold(
            onSuccess = { RemoteRequestOutcome(true, action.toolName, it) },
            onFailure = { RemoteRequestOutcome(false, action.toolName, "Execution failed: ${it.message ?: "unknown error"}") },
        )
    }
}
