package com.opendroid.ai.data.repository

import android.content.Context
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.ExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRun
import com.usmanandumar.workspace.core.workflow.WorkflowRunResult
import com.usmanandumar.workspace.core.workflow.WorkflowRunState
import com.usmanandumar.workspace.core.workflow.WorkflowRunStatus
import com.usmanandumar.workspace.core.workflow.WorkflowRunStore
import com.usmanandumar.workspace.core.workflow.WorkflowStepResult
import com.usmanandumar.workspace.core.workflow.WorkflowStepRunState
import com.usmanandumar.workspace.core.workflow.WorkflowStepStatus
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Runs a workflow as a persistent task while preserving the task state machine. */
@Singleton
class WorkspaceWorkflowRunner @Inject constructor(
    private val taskRepository: WorkspaceTaskRepository,
    private val actionDispatcher: ActionDispatcher,
    @ApplicationContext private val context: Context,
    private val runStore: WorkflowRunStore,
    private val receiptStore: ExecutionReceiptStore,
) {
    suspend fun run(task: WorkspaceTaskEntity, workflow: WorkflowDefinition): WorkflowRunResult {
        require(task.workflowId == null || task.workflowId == workflow.id) { "Task is linked to another workflow" }
        var current = task
        if (current.state == TaskState.DRAFT.name) current = taskRepository.transition(current.id, TaskState.READY, "workflow_ready")
        if (current.state == TaskState.READY.name) current = taskRepository.transition(current.id, TaskState.QUEUED, "workflow_queued")
        if (current.state == TaskState.QUEUED.name) current = taskRepository.transition(current.id, TaskState.RUNNING, "workflow_started")

        val engine = durableEngine(workflow)
        val run = engine.run(workflow)
        val result = run.toResult()
        if (result.status == WorkflowRunStatus.SUCCEEDED) {
            taskRepository.recordOutcome(current.id, true, outputRedacted = result.steps.joinToString { it.output.toString() }, event = "workflow_completed")
        } else if (result.status == WorkflowRunStatus.FAILED) {
            taskRepository.recordOutcome(current.id, false, errorCode = result.steps.lastOrNull()?.errorCode ?: "WORKFLOW_FAILED", errorMessageRedacted = result.steps.lastOrNull()?.errorMessage, event = "workflow_failed")
        } else {
            taskRepository.transition(current.id, TaskState.WAITING_APPROVAL, "workflow_approval_required")
        }
        return result
    }

    /**
     * Re-drive any run that a process death left unfinished. Each run is resumed through the durable
     * engine, which reads its persisted run and receipts, so a step whose external effect already
     * happened is not repeated. Returns the ids that were resumed.
     */
    suspend fun resumeInterrupted(): List<String> {
        val interrupted = runStore.list().filterNot { it.isTerminal() }
        val resumed = mutableListOf<String>()
        for (run in interrupted) {
            val engine = durableEngine(run.workflow)
            engine.resume(run.runId)
            resumed += run.runId
        }
        return resumed
    }

    private fun durableEngine(workflow: WorkflowDefinition): WorkflowEngine {
        val registry = ToolRegistry(workflow.steps.map { ToolDefinition(it.toolId, it.toolId, "Workflow action ${it.toolId}", "android") })
        return WorkflowEngine(
            registry = registry,
            executor = WorkflowToolExecutor { toolId, input ->
                when (val result = actionDispatcher.execute(toolId, input, context)) {
                    is ActionResult.Success -> WorkflowToolResult(true, mapOf("message" to (result.data ?: "")))
                    else -> WorkflowToolResult(false, errorCode = "ACTION_FAILED", errorMessage = result.error ?: "Android action did not complete")
                }
            },
            store = runStore,
            receipts = receiptStore,
        )
    }

    private fun WorkflowRun.toResult(): WorkflowRunResult = WorkflowRunResult(
        workflowId = workflowId,
        status = when (state) {
            WorkflowRunState.SUCCEEDED -> WorkflowRunStatus.SUCCEEDED
            WorkflowRunState.WAITING_APPROVAL -> WorkflowRunStatus.WAITING_FOR_APPROVAL
            else -> WorkflowRunStatus.FAILED
        },
        steps = steps.map { step ->
            WorkflowStepResult(
                stepId = step.stepId,
                toolId = step.toolId,
                status = when (step.state) {
                    WorkflowStepRunState.SUCCEEDED -> WorkflowStepStatus.SUCCEEDED
                    WorkflowStepRunState.SKIPPED -> WorkflowStepStatus.SKIPPED
                    else -> WorkflowStepStatus.FAILED
                },
                attempts = step.attempts,
                output = step.output,
                errorCode = step.errorCode,
                errorMessage = step.errorMessage,
            )
        },
    )
}
