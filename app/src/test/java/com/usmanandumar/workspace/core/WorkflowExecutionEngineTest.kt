package com.usmanandumar.workspace.core

import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.AllowSafeWorkflowSteps
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowExecutionEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRunStatus
import com.usmanandumar.workspace.core.workflow.WorkflowStep
import com.usmanandumar.workspace.core.workflow.WorkflowStepStatus
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowExecutionEngineTest {
    @Test
    fun executesDependenciesAndMapsOutputs() = runBlocking {
        val registry = ToolRegistry(listOf(
            ToolDefinition("fetch", "Fetch", "Fetches", "test"),
            ToolDefinition("parse", "Parse", "Parses", "test"),
        ))
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val engine = WorkflowExecutionEngine(registry, WorkflowToolExecutor { tool, input ->
            calls += tool to input
            if (tool == "fetch") WorkflowToolResult(true, mapOf("url" to "https://example.test"))
            else WorkflowToolResult(true, mapOf("done" to "yes"))
        })
        val result = engine.execute(WorkflowDefinition("research", "Research", listOf(
            WorkflowStep("fetch", "fetch"),
            WorkflowStep("parse", "parse", dependsOn = listOf("fetch"), inputMappings = mapOf("source" to "fetch.url")),
        )))
        assertEquals(WorkflowRunStatus.SUCCEEDED, result.status)
        assertEquals("https://example.test", calls[1].second["source"])
        assertTrue(result.steps.all { it.status == WorkflowStepStatus.SUCCEEDED })
    }

    @Test
    fun retriesFailedStepWithinBound() = runBlocking {
        val registry = ToolRegistry(listOf(ToolDefinition("unstable", "Unstable", "", "test")))
        var attempts = 0
        val engine = WorkflowExecutionEngine(registry, WorkflowToolExecutor { _, _ ->
            attempts++
            if (attempts < 3) WorkflowToolResult(false, errorCode = "TEMP", errorMessage = "retry")
            else WorkflowToolResult(true)
        })
        val result = engine.execute(WorkflowDefinition("retry", "Retry", listOf(WorkflowStep("one", "unstable", maxRetries = 2))))
        assertEquals(WorkflowRunStatus.SUCCEEDED, result.status)
        assertEquals(3, result.steps.single().attempts)
    }

    @Test
    fun approvalRequiredStopsBeforeExecution() = runBlocking {
        val registry = ToolRegistry(listOf(ToolDefinition("write", "Write", "", "test", requiresApproval = true)))
        val engine = WorkflowExecutionEngine(registry, WorkflowToolExecutor { _, _ -> error("must not execute") }, AllowSafeWorkflowSteps)
        val result = engine.execute(WorkflowDefinition("approval", "Approval", listOf(WorkflowStep("write", "write", requiresApproval = true))))
        assertEquals(WorkflowRunStatus.WAITING_FOR_APPROVAL, result.status)
        assertEquals(WorkflowStepStatus.APPROVAL_REQUIRED, result.steps.single().status)
    }
}
