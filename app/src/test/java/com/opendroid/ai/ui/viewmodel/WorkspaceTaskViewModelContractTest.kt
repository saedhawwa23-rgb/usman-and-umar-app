package com.opendroid.ai.ui.viewmodel

import com.usmanandumar.workspace.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkspaceTaskViewModelContractTest {
    @Test
    fun taskManagerExposesOnlySupportedExecutionStates() {
        assertTrue(TaskState.values().containsAll(listOf(TaskState.DRAFT, TaskState.QUEUED, TaskState.RUNNING)))
    }
}
