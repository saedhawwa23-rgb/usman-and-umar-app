package com.opendroid.ai.data.repository

import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.task.TaskDependencyPolicy
import com.usmanandumar.workspace.core.task.TaskStateMachine
import com.usmanandumar.workspace.core.task.TaskOrchestrationStage
import com.usmanandumar.workspace.core.task.TaskOrchestrationStageMachine
import com.opendroid.ai.data.db.dao.WorkspaceTaskDao
import com.opendroid.ai.data.db.entities.WorkspaceTaskDependencyEntity
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.db.entities.WorkspaceTaskExecutionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class WorkspaceTaskRepository @Inject constructor(
    private val dao: WorkspaceTaskDao,
) {
    fun observeTasks(): Flow<List<WorkspaceTaskEntity>> = dao.observeAll()

    suspend fun getTask(taskId: String): WorkspaceTaskEntity? = dao.get(taskId)

    suspend fun moveOrchestrationStage(
        taskId: String,
        to: TaskOrchestrationStage,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceTaskEntity {
        val task = dao.get(taskId) ?: error("Task not found: $taskId")
        val from = TaskOrchestrationStage.valueOf(task.orchestrationStage)
        TaskOrchestrationStageMachine.transition(from, to)
        val updated = task.copy(orchestrationStage = to.name, updatedAt = now)
        dao.update(updated)
        dao.recordExecution(
            WorkspaceTaskExecutionEntity(
                taskId = taskId,
                event = "orchestration_${from.name.lowercase()}_to_${to.name.lowercase()}",
                fromState = task.state,
                toState = task.state,
                attempt = 0,
                success = null,
                outputRedacted = null,
                errorCode = null,
                errorMessageRedacted = null,
                startedAt = now,
                finishedAt = now,
            ),
        )
        return updated
    }

    suspend fun dependencyIds(taskId: String): List<String> = dao.dependenciesOf(taskId)

    suspend fun unmetDependencies(taskId: String): List<WorkspaceTaskEntity> {
        val ids = dao.dependenciesOf(taskId)
        val dependencies = ids.map { id -> dao.get(id) ?: error("Prerequisite task is missing: $id") }
        val unmetIds = TaskDependencyPolicy.unmet(dependencies.map { it.id to TaskState.valueOf(it.state) }).map { it.first }.toSet()
        return dependencies.filter { it.id in unmetIds }
    }

    suspend fun create(
        title: String,
        description: String = "",
        projectId: String? = null,
        category: String = "general",
        priority: Int = 0,
        deadlineAt: Long? = null,
        aiAppPackage: String? = null,
        toolId: String? = null,
        workflowId: String? = null,
        inputJson: String = "{}",
        expectedOutcome: String = "",
        notes: String = "",
        retryLimit: Int = 0,
        timeoutMs: Long = 30_000,
        requiresApproval: Boolean = false,
        dependencies: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): WorkspaceTaskEntity {
        require(title.isNotBlank()) { "Task title must not be blank" }
        require(priority in -10..10) { "Task priority must be between -10 and 10" }
        require(retryLimit in 0..5) { "Task retries must be bounded from 0 to 5" }
        require(timeoutMs > 0) { "Task timeout must be positive" }
        require(id !in dependencies) { "A task cannot depend on itself" }
        require(dependencies.distinct().size == dependencies.size) { "Task dependencies must be unique" }
        val missingDependencies = dependencies.filter { dao.get(it) == null }
        require(missingDependencies.isEmpty()) { "Unknown task dependencies: ${missingDependencies.joinToString()}" }
        val task = WorkspaceTaskEntity(
            id = id,
            title = title.trim(),
            description = description,
            projectId = projectId,
            category = category,
            tagsJson = "[]",
            priority = priority,
            deadlineAt = deadlineAt,
            aiAppPackage = aiAppPackage?.trim()?.takeIf(String::isNotEmpty),
            toolId = toolId,
            workflowId = workflowId,
            inputJson = inputJson,
            expectedOutcome = expectedOutcome,
            notes = notes,
            retryLimit = retryLimit,
            timeoutMs = timeoutMs,
            requiresApproval = requiresApproval,
            schedule = null,
            state = TaskState.DRAFT.name,
            orchestrationStage = TaskOrchestrationStage.PENDING.name,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertWithDependencies(task, dependencies.map { WorkspaceTaskDependencyEntity(id, it) })
        return task
    }

    suspend fun transition(
        taskId: String,
        to: TaskState,
        event: String = "state_transition",
        now: Long = System.currentTimeMillis(),
    ): WorkspaceTaskEntity {
        val current = dao.get(taskId) ?: error("Task not found: $taskId")
        if (to == TaskState.QUEUED || to == TaskState.RUNNING) {
            val unmet = unmetDependencies(taskId)
            require(unmet.isEmpty()) {
                "Task is blocked until dependencies succeed: ${unmet.joinToString { it.title }}"
            }
        }
        val from = TaskState.valueOf(current.state)
        TaskStateMachine.transition(from, to)
        val updated = current.copy(state = to.name, updatedAt = now)
        dao.update(updated)
        dao.recordExecution(
            WorkspaceTaskExecutionEntity(
                taskId = taskId,
                event = event,
                fromState = from.name,
                toState = to.name,
                attempt = 0,
                success = null,
                outputRedacted = null,
                errorCode = null,
                errorMessageRedacted = null,
                startedAt = now,
                finishedAt = now,
            ),
        )
        return updated
    }

    /** Saves an external AI response but deliberately leaves the task uncompleted. */
    suspend fun recordExternalAiResponse(
        taskId: String,
        response: String,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceTaskEntity {
        require(response.isNotBlank()) { "AI response cannot be blank." }
        val task = dao.get(taskId) ?: error("Task not found: $taskId")
        val runningState = ensureAiTaskRunningState(task)
        val fromStage = TaskOrchestrationStage.valueOf(task.orchestrationStage)
        TaskOrchestrationStageMachine.transition(fromStage, TaskOrchestrationStage.VERIFYING)
        val newNotes = listOf(task.notes.trim(), "External AI response (captured by user/device):", response.trim())
            .filter(String::isNotBlank).joinToString("\n\n")
        val updated = task.copy(
            state = runningState.name,
            notes = newNotes.take(MAX_NOTES_CHARS),
            orchestrationStage = TaskOrchestrationStage.VERIFYING.name,
            updatedAt = now,
        )
        dao.update(updated)
        dao.recordExecution(
            WorkspaceTaskExecutionEntity(
                taskId = taskId,
                event = "external_ai_response_captured",
                fromState = task.state,
                toState = runningState.name,
                attempt = 0,
                success = null,
                outputRedacted = "Response captured; output verification still required.",
                errorCode = null,
                errorMessageRedacted = null,
                startedAt = now,
                finishedAt = now,
            ),
        )
        return updated
    }

    /** Completes an external-AI task only after a user-selected, non-empty artifact was read. */
    suspend fun completeWithVerifiedArtifact(
        taskId: String,
        artifactUri: String,
        bytes: Long,
        now: Long = System.currentTimeMillis(),
    ): WorkspaceTaskEntity {
        require(artifactUri.isNotBlank()) { "Choose an output file before verification." }
        require(bytes > 0) { "The selected output file is empty." }
        val task = dao.get(taskId) ?: error("Task not found: $taskId")
        ensureAiTaskRunningState(task)
        val fromStage = TaskOrchestrationStage.valueOf(task.orchestrationStage)
        TaskOrchestrationStageMachine.transition(fromStage, TaskOrchestrationStage.COMPLETED)
        TaskStateMachine.transition(TaskState.RUNNING, TaskState.SUCCEEDED)
        val updated = task.copy(
            state = TaskState.SUCCEEDED.name,
            orchestrationStage = TaskOrchestrationStage.COMPLETED.name,
            verifiedArtifactUri = artifactUri,
            notes = (task.notes.trim() + "\n\nVerified output file was readable and non-empty ($bytes bytes). User confirmed its contents.").trim().take(MAX_NOTES_CHARS),
            updatedAt = now,
        )
        dao.update(updated)
        dao.recordExecution(
            WorkspaceTaskExecutionEntity(
                taskId = taskId,
                event = "external_ai_artifact_verified",
                fromState = task.state,
                toState = TaskState.SUCCEEDED.name,
                attempt = 0,
                success = true,
                outputRedacted = "User-selected output file was readable and non-empty ($bytes bytes).",
                errorCode = null,
                errorMessageRedacted = null,
                startedAt = now,
                finishedAt = now,
            ),
        )
        return updated
    }

    /** Returns RUNNING, recovering an interrupted user-wait task through the existing state machine. */
    private suspend fun ensureAiTaskRunningState(task: WorkspaceTaskEntity): TaskState {
        return when (TaskState.valueOf(task.state)) {
            TaskState.RUNNING -> TaskState.RUNNING
            TaskState.WAITING_USER -> {
                val unmet = unmetDependencies(task.id)
                require(unmet.isEmpty()) {
                    "Task is still blocked by prerequisite task(s): ${unmet.joinToString { it.title }}"
                }
                TaskStateMachine.transition(TaskState.WAITING_USER, TaskState.READY)
                TaskStateMachine.transition(TaskState.READY, TaskState.QUEUED)
                TaskStateMachine.transition(TaskState.QUEUED, TaskState.RUNNING)
                TaskState.RUNNING
            }
            else -> error("Only an in-progress or interrupted AI task can capture a response or verify an output file.")
        }
    }

    suspend fun recordOutcome(
        taskId: String,
        success: Boolean,
        outputRedacted: String? = null,
        errorCode: String? = null,
        errorMessageRedacted: String? = null,
        attempt: Int = 0,
        event: String = "execution",
        now: Long = System.currentTimeMillis(),
    ) {
        require(success || !errorCode.isNullOrBlank()) { "Failed tasks need an error code" }
        val task = dao.get(taskId) ?: error("Task not found: $taskId")
        val state = if (success) TaskState.SUCCEEDED else TaskState.FAILED
        TaskStateMachine.transition(TaskState.valueOf(task.state), state)
        dao.update(task.copy(state = state.name, updatedAt = now))
        dao.recordExecution(
            WorkspaceTaskExecutionEntity(
                taskId = taskId,
                event = event,
                fromState = task.state,
                toState = state.name,
                attempt = attempt,
                success = success,
                outputRedacted = outputRedacted,
                errorCode = errorCode,
                errorMessageRedacted = errorMessageRedacted,
                startedAt = now,
                finishedAt = now,
            ),
        )
    }

    suspend fun recoverAfterRestart(now: Long = System.currentTimeMillis()): List<WorkspaceTaskEntity> {
        return dao.findInterruptedOrQueued().map { task ->
            if (task.state == TaskState.RUNNING.name) {
                val recovered = task.copy(state = TaskState.WAITING_USER.name, updatedAt = now)
                dao.update(recovered)
                dao.recordExecution(
                    WorkspaceTaskExecutionEntity(
                        taskId = task.id,
                        event = "restart_recovery",
                        fromState = TaskState.RUNNING.name,
                        toState = TaskState.WAITING_USER.name,
                        attempt = 0,
                        success = null,
                        outputRedacted = null,
                        errorCode = "INTERRUPTED_BY_RESTART",
                        errorMessageRedacted = "Execution was interrupted by application restart",
                        startedAt = now,
                        finishedAt = now,
                    ),
                )
                recovered
            } else task
        }
    }

    private companion object {
        const val MAX_NOTES_CHARS = 20_000
    }
}
