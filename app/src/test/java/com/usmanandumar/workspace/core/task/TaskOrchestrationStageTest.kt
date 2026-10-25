package com.usmanandumar.workspace.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskOrchestrationStageTest {
    @Test
    fun permitsPlanningRunningVerificationAndCompletionInOrder() {
        assertEquals(TaskOrchestrationStage.PLANNING, TaskOrchestrationStageMachine.transition(TaskOrchestrationStage.PENDING, TaskOrchestrationStage.PLANNING))
        assertEquals(TaskOrchestrationStage.RUNNING, TaskOrchestrationStageMachine.transition(TaskOrchestrationStage.PLANNING, TaskOrchestrationStage.RUNNING))
        assertEquals(TaskOrchestrationStage.VERIFYING, TaskOrchestrationStageMachine.transition(TaskOrchestrationStage.RUNNING, TaskOrchestrationStage.VERIFYING))
        assertEquals(TaskOrchestrationStage.COMPLETED, TaskOrchestrationStageMachine.transition(TaskOrchestrationStage.VERIFYING, TaskOrchestrationStage.COMPLETED))
    }

    @Test
    fun rejectsCompletionDirectlyFromRunningAndPreventsCompletedMutation() {
        assertFalse(TaskOrchestrationStageMachine.canTransition(TaskOrchestrationStage.RUNNING, TaskOrchestrationStage.COMPLETED))
        assertThrows(IllegalArgumentException::class.java) {
            TaskOrchestrationStageMachine.transition(TaskOrchestrationStage.RUNNING, TaskOrchestrationStage.COMPLETED)
        }
        assertFalse(TaskOrchestrationStageMachine.canTransition(TaskOrchestrationStage.COMPLETED, TaskOrchestrationStage.RUNNING))
        assertTrue(TaskOrchestrationStageMachine.canTransition(TaskOrchestrationStage.FAILED, TaskOrchestrationStage.PENDING))
    }
}
