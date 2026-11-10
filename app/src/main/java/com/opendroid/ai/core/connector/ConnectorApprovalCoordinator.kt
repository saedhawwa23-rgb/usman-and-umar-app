package com.opendroid.ai.core.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

 data class PendingConnectorApproval(
    val invocationId: String,
    val toolName: String,
    val argumentsJson: String,
)

@Singleton
class ConnectorApprovalCoordinator @Inject constructor() {
    private val mutablePending = MutableStateFlow<PendingConnectorApproval?>(null)
    val pending: StateFlow<PendingConnectorApproval?> = mutablePending.asStateFlow()
    private var response: CompletableDeferred<Boolean>? = null

    suspend fun request(invocation: ConnectorInvocation): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            check(response == null) { "Another tool approval is already pending." }
            response = deferred
            mutablePending.value = PendingConnectorApproval(
                invocation.invocationId,
                invocation.tool.qualifiedName,
                redactSecretFields(invocation.argumentsJson),
            )
        }
        return try { deferred.await() } finally {
            synchronized(this) {
                if (response === deferred) {
                    response = null
                    mutablePending.value = null
                }
            }
        }
    }

    fun decide(approved: Boolean) {
        synchronized(this) { response?.complete(approved) }
    }

    fun cancelPending() { decide(false) }

    private fun redactSecretFields(json: String): String = runCatching {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        obj.keySet().forEach { key ->
            if (Regex("(?i)(token|secret|password|api.?key|authorization|credential)").containsMatchIn(key)) {
                obj.addProperty(key, "[REDACTED]")
            }
        }
        obj.toString().take(8_000)
    }.getOrDefault("[invalid JSON]")
}

class UserApprovalGate(private val coordinator: ConnectorApprovalCoordinator) : ConnectorApprovalGate {
    override suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision {
        if (!requiresExplicitApproval(invocation.tool)) return ApprovalDecision.ALLOW
        return try {
            if (coordinator.request(invocation)) ApprovalDecision.ALLOW else ApprovalDecision.DENY
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
}

internal fun requiresExplicitApproval(tool: ConnectorTool): Boolean =
    tool.risk.severity >= ConnectorRisk.SENSITIVE.severity ||
        Regex("(?i)(write|create|execute|delete|deploy|publish|send|payment|command)").containsMatchIn(tool.name) ||
        Regex("(?i)(^|[._-])run([._-]|$)").containsMatchIn(tool.name)
