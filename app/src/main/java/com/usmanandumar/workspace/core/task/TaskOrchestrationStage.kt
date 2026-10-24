package com.usmanandumar.workspace.core.task

/**
 * A separate workflow lifecycle. WorkspaceTaskEntity.state remains the existing
 * DRAFT/READY/QUEUED/RUNNING/SUCCEEDED state machine for compatibility.
 */
enum class TaskOrchestrationStage {
    PENDING,
    PLANNING,
    RUNNING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object TaskOrchestrationStageMachine {
    private val allowed: Map<TaskOrchestrationStage, Set<TaskOrchestrationStage>> = mapOf(
        TaskOrchestrationStage.PENDING to setOf(TaskOrchestrationStage.PLANNING, TaskOrchestrationStage.CANCELLED),
        TaskOrchestrationStage.PLANNING to setOf(TaskOrchestrationStage.RUNNING, TaskOrchestrationStage.FAILED, TaskOrchestrationStage.CANCELLED),
        TaskOrchestrationStage.RUNNING to setOf(TaskOrchestrationStage.VERIFYING, TaskOrchestrationStage.FAILED, TaskOrchestrationStage.CANCELLED),
        TaskOrchestrationStage.VERIFYING to setOf(TaskOrchestrationStage.COMPLETED, TaskOrchestrationStage.FAILED, TaskOrchestrationStage.CANCELLED),
        TaskOrchestrationStage.COMPLETED to emptySet(),
        TaskOrchestrationStage.FAILED to setOf(TaskOrchestrationStage.PENDING, TaskOrchestrationStage.CANCELLED),
        TaskOrchestrationStage.CANCELLED to emptySet(),
    )

    fun canTransition(from: TaskOrchestrationStage, to: TaskOrchestrationStage): Boolean = to in allowed.getValue(from)

    fun transition(from: TaskOrchestrationStage, to: TaskOrchestrationStage): TaskOrchestrationStage {
        require(canTransition(from, to)) { "Illegal orchestration transition: $from -> $to" }
        return to
    }
}
