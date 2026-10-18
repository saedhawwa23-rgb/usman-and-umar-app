package com.usmanandumar.workspace.core.workflow

import com.usmanandumar.workspace.core.tool.ToolRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

fun interface WorkflowToolExecutor {
    suspend fun execute(toolId: String, input: Map<String, String>): WorkflowToolResult
}

data class WorkflowToolResult(
    val success: Boolean,
    val output: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

enum class WorkflowApprovalDecision { ALLOW, DENY, WAITING_FOR_USER }

fun interface WorkflowApprovalGate {
    suspend fun decide(step: WorkflowStep): WorkflowApprovalDecision
}

object AllowSafeWorkflowSteps : WorkflowApprovalGate {
    override suspend fun decide(step: WorkflowStep): WorkflowApprovalDecision =
        if (step.requiresApproval) WorkflowApprovalDecision.WAITING_FOR_USER else WorkflowApprovalDecision.ALLOW
}

data class WorkflowStepResult(
    val stepId: String,
    val toolId: String,
    val status: WorkflowStepStatus,
    val attempts: Int,
    val output: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

enum class WorkflowStepStatus { SUCCEEDED, FAILED, SKIPPED, APPROVAL_REQUIRED }

data class WorkflowRunResult(
    val workflowId: String,
    val status: WorkflowRunStatus,
    val steps: List<WorkflowStepResult>,
)

enum class WorkflowRunStatus { SUCCEEDED, FAILED, WAITING_FOR_APPROVAL }

/** Executes a validated workflow without allowing unknown tools or unbounded retries. */
class WorkflowExecutionEngine(
    private val registry: ToolRegistry,
    private val executor: WorkflowToolExecutor,
    private val approvalGate: WorkflowApprovalGate = AllowSafeWorkflowSteps,
) {
    suspend fun execute(workflow: WorkflowDefinition): WorkflowRunResult {
        val validation = workflow.validate()
        if (validation.isNotEmpty()) {
            return WorkflowRunResult(
                workflow.id,
                WorkflowRunStatus.FAILED,
                listOf(WorkflowStepResult("__validation__", "", WorkflowStepStatus.FAILED, 0, errorCode = "INVALID_WORKFLOW", errorMessage = validation.joinToString("; "))),
            )
        }
        val unknown = workflow.steps.filter { registry.get(it.toolId) == null }
        if (unknown.isNotEmpty()) {
            return WorkflowRunResult(
                workflow.id,
                WorkflowRunStatus.FAILED,
                listOf(WorkflowStepResult("__validation__", "", WorkflowStepStatus.FAILED, 0, errorCode = "UNKNOWN_TOOL", errorMessage = unknown.joinToString { it.toolId })),
            )
        }

        val byId = workflow.steps.associateBy { it.id }
        val completed = linkedMapOf<String, WorkflowStepResult>()
        val pending = workflow.steps.map { it.id }.toMutableSet()
        while (pending.isNotEmpty()) {
            val ready = workflow.steps.filter { step ->
                step.id in pending && step.dependsOn.all { it in completed }
            }
            if (ready.isEmpty()) {
                return WorkflowRunResult(workflow.id, WorkflowRunStatus.FAILED, completed.values.toList() + WorkflowStepResult("__schedule__", "", WorkflowStepStatus.FAILED, 0, errorCode = "DEPENDENCY_DEADLOCK", errorMessage = "No executable workflow step remains"))
            }
            for (step in ready) {
                pending.remove(step.id)
                if (!conditionAllows(step, completed)) {
                    completed[step.id] = WorkflowStepResult(step.id, step.toolId, WorkflowStepStatus.SKIPPED, 0)
                    continue
                }
                when (val approval = approvalGate.decide(step)) {
                    WorkflowApprovalDecision.DENY -> {
                        completed[step.id] = WorkflowStepResult(step.id, step.toolId, WorkflowStepStatus.FAILED, 0, errorCode = "APPROVAL_DENIED", errorMessage = "User denied workflow step")
                        return WorkflowRunResult(workflow.id, WorkflowRunStatus.FAILED, completed.values.toList())
                    }
                    WorkflowApprovalDecision.WAITING_FOR_USER -> {
                        completed[step.id] = WorkflowStepResult(step.id, step.toolId, WorkflowStepStatus.APPROVAL_REQUIRED, 0, errorCode = "APPROVAL_REQUIRED", errorMessage = "User approval is required")
                        return WorkflowRunResult(workflow.id, WorkflowRunStatus.WAITING_FOR_APPROVAL, completed.values.toList())
                    }
                    WorkflowApprovalDecision.ALLOW -> Unit
                }
                val input = resolveInputs(step, completed)
                var attempt = 0
                var last: WorkflowToolResult? = null
                while (attempt <= step.maxRetries) {
                    attempt++
                    last = try {
                        withTimeout(step.timeoutMs) { executor.execute(step.toolId, input) }
                    } catch (_: TimeoutCancellationException) {
                        WorkflowToolResult(false, errorCode = "TIMEOUT", errorMessage = "Workflow step exceeded ${step.timeoutMs}ms")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        WorkflowToolResult(false, errorCode = "EXECUTION_ERROR", errorMessage = failure.message ?: "Workflow step failed")
                    }
                    if (last.success) break
                }
                val result = last ?: WorkflowToolResult(false, errorCode = "NO_RESULT", errorMessage = "Workflow tool returned no result")
                completed[step.id] = WorkflowStepResult(step.id, step.toolId, if (result.success) WorkflowStepStatus.SUCCEEDED else WorkflowStepStatus.FAILED, attempt, result.output, result.errorCode, result.errorMessage)
                if (!result.success) return WorkflowRunResult(workflow.id, WorkflowRunStatus.FAILED, completed.values.toList())
            }
        }
        return WorkflowRunResult(workflow.id, WorkflowRunStatus.SUCCEEDED, completed.values.toList())
    }

    private fun conditionAllows(step: WorkflowStep, completed: Map<String, WorkflowStepResult>): Boolean {
        val condition = step.condition?.trim().orEmpty()
        if (condition.isBlank() || condition.equals("always", true)) return true
        if (condition.startsWith("success:")) return completed[condition.removePrefix("success:")]?.status == WorkflowStepStatus.SUCCEEDED
        if (condition.startsWith("failed:")) return completed[condition.removePrefix("failed:")]?.status == WorkflowStepStatus.FAILED
        return false
    }

    private fun resolveInputs(step: WorkflowStep, completed: Map<String, WorkflowStepResult>): Map<String, String> =
        step.inputMappings.mapValues { (_, expression) ->
            val match = Regex("^([a-z0-9._-]+)\\.([a-zA-Z0-9._-]+)$").matchEntire(expression)
            if (match == null) expression else completed[match.groupValues[1]]?.output?.get(match.groupValues[2]).orEmpty()
        }
}
