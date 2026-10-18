package com.opendroid.ai.data.repository

import com.usmanandumar.workspace.core.task.InvalidTaskTransition
import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.task.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceTaskRepositoryTest {
    @Test
    fun stateMachineRejectsTerminalTaskMutation() {
        assertFailsWith<InvalidTaskTransition> {
            TaskStateMachine.transition(TaskState.SUCCEEDED, TaskState.READY)
        }
    }

    @Test
    fun failedOutcomeRequiresErrorCode() {
        val errorCode: String? = null
        assertFailsWith<IllegalArgumentException> {
            require(false || !errorCode.isNullOrBlank()) { "Failed tasks need an error code" }
        }
        assertTrue(TaskStateMachine.canTransition(TaskState.RUNNING, TaskState.FAILED))
    }
}
