package com.opendroid.ai.data.repository

import android.content.Context
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowExecutionEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRunResult
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
) {
    suspend fun run(task: WorkspaceTaskEntity, workflow: WorkflowDefinition): WorkflowRunResult {
        require(task.workflowId == null || task.workflowId == workflow.id) { "Task is linked to another workflow" }
        var current = task
        if (current.state == TaskState.DRAFT.name) current = taskRepository.transition(current.id, TaskState.READY, "workflow_ready")
        if (current.state == TaskState.READY.name) current = taskRepository.transition(current.id, TaskState.QUEUED, "workflow_queued")
        if (current.state == TaskState.QUEUED.name) current = taskRepository.transition(current.id, TaskState.RUNNING, "workflow_started")

        val registry = ToolRegistry(workflow.steps.map { ToolDefinition(it.toolId, it.toolId, "Workflow action ${it.toolId}", "android") })
        val engine = WorkflowExecutionEngine(registry, WorkflowToolExecutor { toolId, input ->
            when (val result = actionDispatcher.execute(toolId, input, context)) {
                is ActionResult.Success -> WorkflowToolResult(true, mapOf("message" to (result.data ?: "")))
                else -> WorkflowToolResult(false, errorCode = "ACTION_FAILED", errorMessage = result.error ?: "Android action did not complete")
            }
        })
        val result = engine.execute(workflow)
        if (result.status.name == "SUCCEEDED") {
            taskRepository.recordOutcome(current.id, true, outputRedacted = result.steps.joinToString { it.output.toString() }, event = "workflow_completed")
        } else if (result.status.name == "FAILED") {
            taskRepository.recordOutcome(current.id, false, errorCode = result.steps.lastOrNull()?.errorCode ?: "WORKFLOW_FAILED", errorMessageRedacted = result.steps.lastOrNull()?.errorMessage, event = "workflow_failed")
        } else {
            taskRepository.transition(current.id, TaskState.WAITING_APPROVAL, "workflow_approval_required")
        }
        return result
    }
}
