package com.usmanandumar.workspace.core

import com.usmanandumar.workspace.core.task.TaskDependencyPolicy
import com.usmanandumar.workspace.core.task.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDependencyPolicyTest {
    @Test
    fun noPrerequisitesAreReadyAndAllSucceededPrerequisitesAreReady() {
        assertTrue(TaskDependencyPolicy.isReady(emptyList()))
        assertTrue(TaskDependencyPolicy.isReady(listOf(TaskState.SUCCEEDED, TaskState.SUCCEEDED)))
    }

    @Test
    fun failedRunningAndCancelledPrerequisitesRemainBlocked() {
        assertFalse(TaskDependencyPolicy.isReady(listOf(TaskState.SUCCEEDED, TaskState.RUNNING)))
        assertEquals(
            listOf("failed" to TaskState.FAILED, "cancelled" to TaskState.CANCELLED),
            TaskDependencyPolicy.unmet(
                listOf(
                    "done" to TaskState.SUCCEEDED,
                    "failed" to TaskState.FAILED,
                    "cancelled" to TaskState.CANCELLED,
                ),
            ),
        )
    }
}
