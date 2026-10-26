package com.usmanandumar.workspace.core.task

import kotlinx.serialization.Serializable

@Serializable
enum class TaskState {
    DRAFT,
    READY,
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    WAITING_FOR_NETWORK,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

class InvalidTaskTransition(
    from: TaskState,
    to: TaskState,
) : IllegalStateException("Illegal task transition: $from -> $to")

object TaskStateMachine {
    private val transitions: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.DRAFT to setOf(TaskState.READY, TaskState.WAITING_FOR_NETWORK, TaskState.CANCELLED),
        TaskState.READY to setOf(TaskState.QUEUED, TaskState.WAITING_FOR_NETWORK, TaskState.WAITING_APPROVAL, TaskState.BLOCKED, TaskState.CANCELLED),
        TaskState.QUEUED to setOf(TaskState.RUNNING, TaskState.WAITING_FOR_NETWORK, TaskState.CANCELLED, TaskState.BLOCKED),
        TaskState.RUNNING to setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.WAITING_FOR_NETWORK, TaskState.WAITING_APPROVAL, TaskState.WAITING_USER, TaskState.CANCELLED),
        TaskState.WAITING_APPROVAL to setOf(TaskState.READY, TaskState.QUEUED, TaskState.CANCELLED, TaskState.BLOCKED),
        TaskState.WAITING_USER to setOf(TaskState.READY, TaskState.QUEUED, TaskState.CANCELLED, TaskState.BLOCKED),
        TaskState.WAITING_FOR_NETWORK to setOf(TaskState.READY, TaskState.QUEUED, TaskState.CANCELLED, TaskState.BLOCKED),
        TaskState.BLOCKED to setOf(TaskState.READY, TaskState.CANCELLED),
        TaskState.SUCCEEDED to emptySet(),
        TaskState.FAILED to setOf(TaskState.READY, TaskState.QUEUED, TaskState.WAITING_FOR_NETWORK, TaskState.CANCELLED),
        TaskState.CANCELLED to emptySet(),
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean = to in transitions.getValue(from)

    fun transition(from: TaskState, to: TaskState): TaskState {
        if (!canTransition(from, to)) throw InvalidTaskTransition(from, to)
        return to
    }
}
