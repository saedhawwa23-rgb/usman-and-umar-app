package com.usmanandumar.workspace.core

import com.usmanandumar.workspace.core.tool.ToolDefinition
import com.usmanandumar.workspace.core.tool.ToolRegistry
import com.usmanandumar.workspace.core.workflow.ExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.FileExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.InMemoryExecutionReceiptStore
import com.usmanandumar.workspace.core.workflow.WorkflowDefinition
import com.usmanandumar.workspace.core.workflow.WorkflowEngine
import com.usmanandumar.workspace.core.workflow.WorkflowRunState
import com.usmanandumar.workspace.core.workflow.WorkflowStep
import com.usmanandumar.workspace.core.workflow.WorkflowToolExecutor
import com.usmanandumar.workspace.core.workflow.WorkflowToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Requirement 4: a workflow never performs the same external action twice, even across a crash and a
 * resume. The receipt is written before the effect is requested, so an interrupted step does not
 * repeat when the run is resumed after a process death or a phone restart.
 */
class WorkflowReceiptTest {

    private fun registry(vararg ids: String) = ToolRegistry(ids.map { ToolDefinition(it, it, it, "test") })

    private fun workflow(vararg steps: WorkflowStep) = WorkflowDefinition("wf", "wf", steps.toList())

    @Test
    fun aResumeAfterACrashDoesNotRepeatASucceededEffect() = runBlocking {
        val receipts = InMemoryExecutionReceiptStore()
        var calls = 0
        val executor = WorkflowToolExecutor { _, _ ->
            calls++
            WorkflowToolResult(true, mapOf("ok" to "yes"))
        }
        val engine = WorkflowEngine(
            registry = registry("a", "b"),
            executor = executor,
            receipts = receipts,
        )
        // Start and run step a, then simulate a crash before b by advancing a fresh engine over the
        // persisted run.
        val started = engine.start(workflow(WorkflowStep("a", "a"), WorkflowStep("b", "b", dependsOn = listOf("a"))))
        engine.resume(started.runId)
        assertEquals(2, calls)

        // A second resume must not re-run a or b: their receipts say the effects already happened.
        val resumed = engine.resume(started.runId)
        assertEquals(WorkflowRunState.SUCCEEDED, resumed.state)
        assertEquals(2, calls)
    }

    @Test
    fun anInterruptedStepIsNotRepeatedOnResume() = runBlocking {
        val receipts = InMemoryExecutionReceiptStore()
        var calls = 0
        val executor = WorkflowToolExecutor { _, _ ->
            calls++
            WorkflowToolResult(true, mapOf("ok" to "yes"))
        }
        val engine = WorkflowEngine(registry = registry("a"), executor = executor, receipts = receipts)
        val started = engine.start(workflow(WorkflowStep("a", "a")))
        // Claim the effect without completing it: this is what a crash mid-step leaves behind.
        receipts.record(
            com.usmanandumar.workspace.core.workflow.ExecutionReceipt(
                receiptKey = "${started.runId}:a",
                runId = started.runId,
                stepId = "a",
                toolId = "a",
                recordedAtMs = 0L,
                success = false,
            )
        )
        val resumed = engine.resume(started.runId)
        // The tool must not be called again: the receipt proves the effect was already requested.
        assertEquals(0, calls)
        assertEquals(WorkflowRunState.FAILED, resumed.state)
        assertEquals(WorkflowEngine.RECEIPT_FAILED, resumed.errorCode)
    }

    @Test
    fun aReceiptSurvivesAFreshEngineFromTheSameFile() = runBlocking {
        val dir = Files.createTempDirectory("receipts").toFile()
        val receiptFile = File(dir, "receipts.json")
        val runFile = File(dir, "runs.json")
        var calls = 0
        val executor = WorkflowToolExecutor { _, _ ->
            calls++
            WorkflowToolResult(true)
        }
        val engine1 = WorkflowEngine(
            registry = registry("a"),
            executor = executor,
            store = com.usmanandumar.workspace.core.workflow.FileWorkflowRunStore(runFile),
            receipts = FileExecutionReceiptStore(receiptFile),
        )
        val started = engine1.start(workflow(WorkflowStep("a", "a")))
        engine1.resume(started.runId)
        assertEquals(1, calls)

        // A fresh engine, as after a phone restart, reads the same run and receipt files.
        val engine2 = WorkflowEngine(
            registry = registry("a"),
            executor = executor,
            store = com.usmanandumar.workspace.core.workflow.FileWorkflowRunStore(runFile),
            receipts = FileExecutionReceiptStore(receiptFile),
        )
        val resumed = engine2.resume(started.runId)
        assertEquals(WorkflowRunState.SUCCEEDED, resumed.state)
        assertEquals(1, calls)
    }

    @Test
    fun anExplicitRetryIsTheOwnersDecisionToRunAgain() = runBlocking {
        var calls = 0
        val executor = WorkflowToolExecutor { _, _ ->
            calls++
            WorkflowToolResult(true)
        }
        val engine = WorkflowEngine(
            registry = registry("a"),
            executor = executor,
            receipts = InMemoryExecutionReceiptStore(),
        )
        val started = engine.start(workflow(WorkflowStep("a", "a")))
        engine.resume(started.runId)
        assertEquals(1, calls)

        // The owner explicitly retries the step, which clears its receipt and re-runs it.
        engine.retryStep(started.runId, "a")
        engine.resume(started.runId)
        assertEquals(2, calls)
    }

    @Test
    fun distinctStepsGetDistinctReceiptKeys() = runBlocking {
        val receipts = InMemoryExecutionReceiptStore()
        val executor = WorkflowToolExecutor { _, _ -> WorkflowToolResult(true) }
        val engine = WorkflowEngine(
            registry = registry("a", "b"),
            executor = executor,
            receipts = receipts,
        )
        val started = engine.start(
            workflow(WorkflowStep("a", "a"), WorkflowStep("b", "b", receiptKey = "external-b"))
        )
        engine.resume(started.runId)
        assertEquals(setOf("${started.runId}:a", "${started.runId}:external-b"), receipts.all().map { it.receiptKey }.toSet())
        assertTrue(receipts.all().all { it.success })
    }
}
