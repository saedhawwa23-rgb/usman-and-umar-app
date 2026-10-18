package com.usmanandumar.workspace.core

import com.usmanandumar.workspace.core.task.InvalidTaskTransition
import com.usmanandumar.workspace.core.task.TaskState
import com.usmanandumar.workspace.core.task.TaskStateMachine
import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class FoundationContractsTest {
    @Test
    fun registryRejectsDuplicateIdsAndKeepsStableOrder() {
        val registry = ToolRegistry()
        registry.register(ToolDefinition("files.checksum", "Checksum", "Hash files", "files"))
        assertEquals(listOf("files.checksum"), registry.all().map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(ToolDefinition("files.checksum", "Other", "Duplicate", "files"))
        }
    }

    @Test
    fun taskStateMachineAllowsApprovalAndExecutionPath() {
        assertTrue(TaskStateMachine.canTransition(TaskState.DRAFT, TaskState.READY))
        assertEquals(TaskState.QUEUED, TaskStateMachine.transition(TaskState.READY, TaskState.QUEUED))
        assertThrows(InvalidTaskTransition::class.java) {
            TaskStateMachine.transition(TaskState.SUCCEEDED, TaskState.RUNNING)
        }
    }

    @Test
    fun workflowValidationFindsMissingDependenciesAndCycles() {
        val invalid = WorkflowDefinition(
            id = "research",
            name = "Research",
            steps = listOf(
                WorkflowStep("fetch", "web.fetch", dependsOn = listOf("missing")),
                WorkflowStep("parse", "web.parse", dependsOn = listOf("fetch")),
            ),
        )
        assertTrue(invalid.validate().any { "missing step" in it })

        val cycle = WorkflowDefinition(
            id = "cycle",
            name = "Cycle",
            steps = listOf(
                WorkflowStep("a", "tool.a", dependsOn = listOf("b")),
                WorkflowStep("b", "tool.b", dependsOn = listOf("a")),
            ),
        )
        assertTrue(cycle.validate().any { "cycle" in it })
    }
}
