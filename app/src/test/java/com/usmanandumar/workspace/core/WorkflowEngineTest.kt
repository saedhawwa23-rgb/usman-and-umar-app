package com.usmanandumar.workspace.core

import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.AllowSafeWorkflowSteps
import com.usmanandumar.workspace.core.workflow.FileWorkflowRunStore
import com.usmanandumar.workspace.core.workflow.WorkflowApprovalDecision
import com.usmanandumar.workspace.core.workflow.WorkflowApprovalGate
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRunState
import com.usmanandumar.workspace.core.workflow.WorkflowStep
import com.usmanandumar.workspace.core.workflow.WorkflowStepRunState
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val registry = ToolRegistry(listOf(
        ToolDefinition("fetch", "Fetch", "Fetches", "test"),
        ToolDefinition("parse", "Parse", "Parses", "test"),
        ToolDefinition("store", "Store", "Stores", "test"),
    ))

    private fun threeStepWorkflow() = WorkflowDefinition("pipeline", "Pipeline", listOf(
        WorkflowStep("fetch", "fetch"),
        WorkflowStep("parse", "parse", dependsOn = listOf("fetch"), inputMappings = mapOf("source" to "fetch.url")),
        WorkflowStep("store", "store", dependsOn = listOf("parse"), inputMappings = mapOf("value" to "parse.value")),
    ))

    @Test
    fun runsThreeStepsInDependencyOrderAndMapsOutputs() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, input ->
            calls += tool
            when (tool) {
                "fetch" -> WorkflowToolResult(true, mapOf("url" to "https://example.test"))
                "parse" -> WorkflowToolResult(true, mapOf("value" to "42"))
                else -> WorkflowToolResult(true, mapOf("stored" to "yes"))
            }
        })
        val run = engine.run(threeStepWorkflow())
        assertEquals(WorkflowRunState.SUCCEEDED, run.state)
        assertEquals(listOf("fetch", "parse", "store"), calls)
        assertTrue(run.steps.all { it.state == WorkflowStepRunState.SUCCEEDED })
    }

    @Test
    fun inputMappingFeedsEarlierStepOutputIntoLaterStep() = runBlocking {
        val seen = mutableMapOf<String, Map<String, String>>()
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, input ->
            seen[tool] = input
            when (tool) {
                "fetch" -> WorkflowToolResult(true, mapOf("url" to "https://a.test"))
                "parse" -> WorkflowToolResult(true, mapOf("value" to "v-${input["source"]}"))
                else -> WorkflowToolResult(true)
            }
        })
        engine.run(threeStepWorkflow())
        assertEquals("https://a.test", seen["parse"]?.get("source"))
        assertEquals("v-https://a.test", seen["store"]?.get("value"))
    }

    @Test
    fun persistsRunToFileStoreAndReloads() = runBlocking {
        val file = temporaryFolder.newFile("runs.json")
        val store = FileWorkflowRunStore(file)
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ ->
            WorkflowToolResult(true, mapOf("tool" to tool))
        }, store)
        val run = engine.run(threeStepWorkflow())
        assertEquals(WorkflowRunState.SUCCEEDED, run.state)

        // A fresh store over the same file must see the persisted run.
        val reloaded = FileWorkflowRunStore(file).load(run.runId)
        assertNotNull(reloaded)
        assertEquals(WorkflowRunState.SUCCEEDED, reloaded!!.state)
        assertEquals(3, reloaded.steps.size)
    }

    @Test
    fun retriesThenSucceeds() = runBlocking {
        val single = WorkflowDefinition("retry", "Retry", listOf(WorkflowStep("one", "fetch", maxRetries = 2)))
        var attempts = 0
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { _, _ ->
            attempts++
            if (attempts < 3) WorkflowToolResult(false, errorCode = "TEMP", errorMessage = "retry") else WorkflowToolResult(true)
        })
        val run = engine.run(single)
        assertEquals(WorkflowRunState.SUCCEEDED, run.state)
        assertEquals(3, run.steps.single().attempts)
    }

    @Test
    fun failureFailsFastAndSkipsDependents() = runBlocking {
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ ->
            if (tool == "parse") WorkflowToolResult(false, errorCode = "BAD", errorMessage = "parse failed")
            else WorkflowToolResult(true)
        })
        val run = engine.run(threeStepWorkflow())
        assertEquals(WorkflowRunState.FAILED, run.state)
        assertEquals("BAD", run.errorCode)
        assertEquals(WorkflowStepRunState.FAILED, run.step("parse")?.state)
        assertEquals(WorkflowStepRunState.SKIPPED, run.step("store")?.state)
    }

    @Test
    fun cancelStopsRunAtNextBoundary() = runBlocking {
        lateinit var engine: WorkflowEngine
        var runId: String? = null
        engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ ->
            if (tool == "fetch") {
                // Cancel from "another thread" while step one is running.
                runId?.let { engine.cancel(it) }
            }
            WorkflowToolResult(true)
        })
        val started = engine.start(threeStepWorkflow())
        runId = started.runId
        val result = engine.resume(started.runId)
        assertEquals(WorkflowRunState.CANCELLED, result.state)
    }

    @Test
    fun resumeRecoversFromInterruptedStepWithoutReRunningSucceededSteps() = runBlocking {
        val file = temporaryFolder.newFile("recover.json")
        val store = FileWorkflowRunStore(file)
        val executed = mutableListOf<String>()

        // First engine fails on the second step, leaving the run FAILED with step one SUCCEEDED.
        val failing = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ ->
            executed += tool
            if (tool == "parse") WorkflowToolResult(false, errorCode = "TEMP", errorMessage = "transient") else WorkflowToolResult(true)
        }, store)
        val failed = failing.run(threeStepWorkflow())
        assertEquals(WorkflowRunState.FAILED, failed.state)

        // Re-arm the failed step, then resume with an engine that now succeeds.
        failing.retryStep(failed.runId, "parse")
        executed.clear()
        val healthy = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ ->
            executed += tool
            WorkflowToolResult(true)
        }, store)
        val recovered = healthy.resume(failed.runId)
        assertEquals(WorkflowRunState.SUCCEEDED, recovered.state)
        // fetch already succeeded, so recovery must not run it again.
        assertFalse("fetch must not be re-executed on resume", "fetch" in executed)
        assertTrue("parse" in executed)
    }

    @Test
    fun waitingForApprovalPausesAndPersists() = runBlocking {
        val workflow = WorkflowDefinition("approval", "Approval", listOf(WorkflowStep("write", "store", requiresApproval = true)))
        val gate = WorkflowApprovalGate { step ->
            if (step.requiresApproval) WorkflowApprovalDecision.WAITING_FOR_USER else WorkflowApprovalDecision.ALLOW
        }
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { _, _ -> error("must not run") }, approval = gate)
        val run = engine.run(workflow)
        assertEquals(WorkflowRunState.WAITING_APPROVAL, run.state)
        assertEquals(WorkflowStepRunState.PENDING, run.step("write")?.state)
    }

    @Test
    fun unknownToolFailsBeforeAnyExecution() = runBlocking {
        var called = false
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { _, _ -> called = true; WorkflowToolResult(true) })
        val run = engine.run(WorkflowDefinition("bad", "Bad", listOf(WorkflowStep("x", "not-a-tool"))))
        assertEquals(WorkflowRunState.FAILED, run.state)
        assertEquals(WorkflowEngine.UNKNOWN_TOOL, run.errorCode)
        assertFalse(called)
    }

    @Test
    fun cycleFailsValidation() = runBlocking {
        val engine = WorkflowEngine(registry, WorkflowToolExecutor { _, _ -> WorkflowToolResult(true) })
        val cyclic = WorkflowDefinition("cycle", "Cycle", listOf(
            WorkflowStep("a", "fetch", dependsOn = listOf("b")),
            WorkflowStep("b", "parse", dependsOn = listOf("a")),
        ))
        val run = engine.run(cyclic)
        assertEquals(WorkflowRunState.FAILED, run.state)
        assertEquals(WorkflowEngine.INVALID_WORKFLOW, run.errorCode)
    }

    @Test
    fun deterministicAcrossTwoRunsWithSameInputs() = runBlocking {
        fun runOnce(): List<String> {
            val order = mutableListOf<String>()
            val engine = WorkflowEngine(registry, WorkflowToolExecutor { tool, _ -> order += tool; WorkflowToolResult(true) })
            runBlocking { engine.run(threeStepWorkflow()) }
            return order
        }
        assertEquals(runOnce(), runOnce())
    }
}
