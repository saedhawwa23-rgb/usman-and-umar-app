package com.opendroid.ai.data.repository

import com.opendroid.ai.core.task.InvalidTaskTransition
import com.opendroid.ai.core.task.TaskState
import com.opendroid.ai.core.task.TaskStateMachine
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

    suspend fun create(
        title: String,
        description: String = "",
        projectId: String? = null,
        category: String = "general",
        priority: Int = 0,
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
        val task = WorkspaceTaskEntity(
            id = id,
            title = title.trim(),
            description = description,
            projectId = projectId,
            category = category,
            tagsJson = "[]",
            priority = priority,
            deadlineAt = null,
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

    suspend fun recordOutcome(
        taskId: String,
        success: Boolean,
        outputRedacted: String? = null,
        errorCode: String? = null,
        errorMessageRedacted: String? = null,
        attempt: Int = 0,
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
                event = "execution",
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
}
