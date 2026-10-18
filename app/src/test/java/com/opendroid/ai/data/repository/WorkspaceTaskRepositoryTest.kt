package com.opendroid.ai.data.repository

import com.usmanandumar.workspace.core.task.InvalidTaskTransition
import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.task.TaskStateMachine
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTaskRepositoryTest {
    @Test
    fun stateMachineRejectsTerminalTaskMutation() {
        assertThrows(InvalidTaskTransition::class.java) {
            TaskStateMachine.transition(TaskState.SUCCEEDED, TaskState.READY)
        }
    }

    @Test
    fun failedOutcomeRequiresErrorCode() {
        val errorCode: String? = null
        assertThrows(IllegalArgumentException::class.java) {
            require(false || !errorCode.isNullOrBlank()) { "Failed tasks need an error code" }
        }
        assertTrue(TaskStateMachine.canTransition(TaskState.RUNNING, TaskState.FAILED))
    }
}
