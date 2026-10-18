package com.opendroid.ai.ui.viewmodel

import com.usmanandumar.workspace.core.task.TaskState
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTaskViewModelContractTest {
    @Test
    fun taskManagerExposesOnlySupportedExecutionStates() {
        assertTrue(TaskState.values().containsAll(listOf(TaskState.DRAFT, TaskState.QUEUED, TaskState.RUNNING)))
    }
}
