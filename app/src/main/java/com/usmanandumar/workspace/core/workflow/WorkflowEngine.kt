package com.usmanandumar.workspace.core.workflow

import com.usmanandumar.workspace.core.tool.ToolRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A durable, deterministic workflow engine.
 *
 * [WorkflowExecutionEngine] runs a workflow once, in memory. This engine adds the three things an
 * unattended, multi-plugin workflow actually needs, and nothing else:
 *
 *  - **Persistence.** Every step transition is written to a [WorkflowRunStore] before the next step
 *    starts, so a run survives a process death and can be resumed.
 *  - **Recovery.** [resume] re-attempts a run that was interrupted. A step that already SUCCEEDED is
 *    never executed twice: the persisted run is the single source of truth for what has run.
 *  - **Cancellation.** [cancel] is observed between steps, not only at the end, so a long run stops
 *    at the next step boundary.
 *
 * Execution is deterministic: the same definition and the same tool outputs produce the same run.
 * Steps run in dependency order; a step becomes runnable only when every dependency has succeeded.
 * A failure fails the run fast and leaves the failed step re-armable with [retryStep].
 */
@Serializable
enum class WorkflowRunState {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
enum class WorkflowStepRunState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
}

/** The persisted state of one step. [attempts] counts every execution try, across retries and resumes. */
@Serializable
data class WorkflowStepRun(
    val stepId: String,
    val toolId: String,
    val state: WorkflowStepRunState = WorkflowStepRunState.PENDING,
    val attempts: Int = 0,
    val output: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
)

/** The persisted state of a whole run. It embeds the definition so a resumed run needs no other input. */
@Serializable
data class WorkflowRun(
    val runId: String,
    val workflowId: String,
    val workflow: WorkflowDefinition,
    val state: WorkflowRunState = WorkflowRunState.PENDING,
    val steps: List<WorkflowStepRun> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun step(stepId: String): WorkflowStepRun? = steps.firstOrNull { it.stepId == stepId }

    fun isTerminal(): Boolean =
        state == WorkflowRunState.SUCCEEDED || state == WorkflowRunState.FAILED || state == WorkflowRunState.CANCELLED

    /** Steps that will not run again: succeeded, or skipped by a condition. */
    fun settledStepIds(): Set<String> = steps
        .filter { it.state == WorkflowStepRunState.SUCCEEDED || it.state == WorkflowStepRunState.SKIPPED }
        .map { it.stepId }
        .toSet()

    fun withStep(updated: WorkflowStepRun): WorkflowRun =
        copy(steps = steps.map { if (it.stepId == updated.stepId) updated else it })

    /** A short, secret-free line per step, for a log or the UI. */
    fun summary(): String = steps.joinToString("; ") { step ->
        "${step.stepId}(${step.toolId})=${step.state}${step.errorCode?.let { ":$it" } ?: ""}"
    }
}

/** Where runs live. The file store makes the engine durable; the in-memory one is for tests. */
interface WorkflowRunStore {
    fun load(runId: String): WorkflowRun?
    fun save(run: WorkflowRun)
    fun list(): List<WorkflowRun>
    fun remove(runId: String): Boolean
}

class InMemoryWorkflowRunStore : WorkflowRunStore {
    private val runs = LinkedHashMap<String, WorkflowRun>()

    @Synchronized
    override fun load(runId: String): WorkflowRun? = runs[runId]

    @Synchronized
    override fun save(run: WorkflowRun) {
        runs[run.runId] = run
    }

    @Synchronized
    override fun list(): List<WorkflowRun> = runs.values.toList()

    @Synchronized
    override fun remove(runId: String): Boolean = runs.remove(runId) != null
}

/**
 * A run store backed by one JSON file. It writes to a temporary file and renames it into place, so
 * a crash during a write cannot leave a half-written run that would fail to parse on the next start.
 * A file that cannot be parsed is treated as empty rather than fatal.
 */
class FileWorkflowRunStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : WorkflowRunStore {

    private val cache = LinkedHashMap<String, WorkflowRun>()

    init {
        loadFromDisk().forEach { cache[it.runId] = it }
    }

    @Synchronized
    override fun load(runId: String): WorkflowRun? = cache[runId]

    @Synchronized
    override fun save(run: WorkflowRun) {
        cache[run.runId] = run
        flush()
    }

    @Synchronized
    override fun list(): List<WorkflowRun> = cache.values.toList()

    @Synchronized
    override fun remove(runId: String): Boolean {
        val removed = cache.remove(runId) != null
        if (removed) flush()
        return removed
    }

    private fun loadFromDisk(): List<WorkflowRun> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<WorkflowRun>>(text) }.getOrDefault(emptyList())
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        val payload = json.encodeToString(cache.values.toList())
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }
}

/**
 * Runs workflows against a [ToolRegistry], persisting every transition. It is deliberately
 * synchronous and single-threaded per run: two concurrent runs of the same run id are not allowed,
 * and cancellation is driven through the store so another thread can stop a run in flight.
 */
class WorkflowEngine(
    private val registry: ToolRegistry,
    private val executor: WorkflowToolExecutor,
    private val store: WorkflowRunStore = InMemoryWorkflowRunStore(),
    private val approval: WorkflowApprovalGate = AllowSafeWorkflowSteps,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val INVALID_WORKFLOW = "INVALID_WORKFLOW"
        const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val DEPENDENCY_DEADLOCK = "DEPENDENCY_DEADLOCK"
        const val TIMEOUT = "TIMEOUT"
        const val EXECUTION_ERROR = "EXECUTION_ERROR"
        const val APPROVAL_DENIED = "APPROVAL_DENIED"
        const val DEPENDENCY_FAILED = "DEPENDENCY_FAILED"

        /** Prefix in an input mapping that passes a literal value instead of an upstream output. */
        const val LITERAL_PREFIX = "literal:"

        /** `{stepId.field}` inside an input value is substituted from an earlier step's output. */
        private val PLACEHOLDER = Regex("\\{([a-z0-9._-]+)\\.([a-zA-Z0-9._-]+)}")
    }

    /** Validate and persist a new run without executing anything. */
    fun start(workflow: WorkflowDefinition): WorkflowRun {
        val now = clock()
        val run = WorkflowRun(
            runId = UUID.randomUUID().toString(),
            workflowId = workflow.id,
            workflow = workflow,
            state = WorkflowRunState.PENDING,
            steps = workflow.steps.map { WorkflowStepRun(it.id, it.toolId) },
            createdAtMs = now,
            updatedAtMs = now,
        )
        store.save(run)
        return run
    }

    fun get(runId: String): WorkflowRun? = store.load(runId)

    fun runs(): List<WorkflowRun> = store.list()

    /** Start and drive a workflow to a terminal state (or to an approval wait). */
    suspend fun run(workflow: WorkflowDefinition): WorkflowRun = advance(start(workflow))

    /**
     * Resume a persisted run. A step left RUNNING by a crash is treated as un-attempted and is
     * re-run; a step that SUCCEEDED is not touched. This is the recovery path.
     */
    suspend fun resume(runId: String): WorkflowRun {
        val run = store.load(runId) ?: throw IllegalArgumentException("Unknown workflow run: $runId")
        if (run.isTerminal()) return run
        val recovered = run.copy(
            state = WorkflowRunState.RUNNING,
            steps = run.steps.map {
                if (it.state == WorkflowStepRunState.RUNNING) it.copy(state = WorkflowStepRunState.PENDING) else it
            },
        )
        store.save(recovered)
        return advance(recovered)
    }

    /**
     * Re-arm the failed and skipped steps so a later [resume] re-attempts them. A re-armed step gets
     * a fresh attempt budget, otherwise a step that already used its retries on the first pass would
     * be refused any new attempt.
     */
    fun retryStep(runId: String, stepId: String): WorkflowRun {
        val run = store.load(runId) ?: throw IllegalArgumentException("Unknown workflow run: $runId")
        val updated = run.copy(
            state = WorkflowRunState.RUNNING,
            errorCode = null,
            errorMessage = null,
            updatedAtMs = clock(),
            steps = run.steps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(state = WorkflowStepRunState.PENDING, attempts = 0, errorCode = null, errorMessage = null)
                } else if (step.state == WorkflowStepRunState.SKIPPED) {
                    step.copy(state = WorkflowStepRunState.PENDING, attempts = 0, errorCode = null, errorMessage = null)
                } else {
                    step
                }
            },
        )
        store.save(updated)
        return updated
    }

    /** Cancel a run. The in-flight loop observes this at the next step boundary. */
    fun cancel(runId: String): WorkflowRun {
        val run = store.load(runId) ?: throw IllegalArgumentException("Unknown workflow run: $runId")
        if (run.isTerminal()) return run
        val cancelled = run.copy(
            state = WorkflowRunState.CANCELLED,
            updatedAtMs = clock(),
            steps = run.steps.map {
                if (it.state == WorkflowStepRunState.PENDING || it.state == WorkflowStepRunState.RUNNING) {
                    it.copy(state = WorkflowStepRunState.CANCELLED)
                } else {
                    it
                }
            },
        )
        store.save(cancelled)
        return cancelled
    }

    // ---- internals ----

    private suspend fun advance(input: WorkflowRun): WorkflowRun {
        var run = store.load(input.runId) ?: input

        val problems = run.workflow.validate()
        if (problems.isNotEmpty()) {
            return finish(run, WorkflowRunState.FAILED, INVALID_WORKFLOW, problems.joinToString("; "))
        }
        val unknown = run.workflow.steps.filter { registry.get(it.toolId) == null }
        if (unknown.isNotEmpty()) {
            return finish(run, WorkflowRunState.FAILED, UNKNOWN_TOOL, unknown.joinToString { it.toolId })
        }

        run = run.copy(state = WorkflowRunState.RUNNING, updatedAtMs = clock())
        store.save(run)

        while (true) {
            // Re-read so a cancel from another thread is honoured before the next step.
            val fresh = store.load(run.runId) ?: run
            if (fresh.state == WorkflowRunState.CANCELLED) return fresh
            run = propagateSkips(fresh)

            run.workflow.steps.firstOrNull { run.step(it.id)?.state == WorkflowStepRunState.FAILED }?.let { failed ->
                return finish(run, WorkflowRunState.FAILED, run.step(failed.id)?.errorCode, run.step(failed.id)?.errorMessage)
            }

            val next = nextRunnable(run)
            if (next == null) {
                val pending = run.steps.any { it.state == WorkflowStepRunState.PENDING || it.state == WorkflowStepRunState.RUNNING }
                return if (pending) {
                    finish(run, WorkflowRunState.FAILED, DEPENDENCY_DEADLOCK, "No runnable step remains")
                } else {
                    finish(run, WorkflowRunState.SUCCEEDED, null, null)
                }
            }

            run = executeStep(run, next)
            // A cancel from another thread may have landed while the step ran. It must win: saving
            // our in-memory copy now would overwrite the persisted CANCELLED state.
            val latest = store.load(run.runId)
            if (latest != null && latest.state == WorkflowRunState.CANCELLED) return latest
            store.save(run)
            if (run.isTerminal()) {
                // A failure leaves dependents un-runnable; mark them SKIPPED so the final state is
                // explicit instead of a set of PENDING steps that can never start.
                if (run.state == WorkflowRunState.FAILED) {
                    run = propagateSkips(run)
                    store.save(run)
                }
                return run
            }
            if (run.state == WorkflowRunState.WAITING_APPROVAL) return run
        }
    }

    private fun nextRunnable(run: WorkflowRun): WorkflowStep? {
        val settled = run.settledStepIds()
        return run.workflow.steps.firstOrNull { definition ->
            val record = run.step(definition.id)
            record != null &&
                record.state == WorkflowStepRunState.PENDING &&
                definition.dependsOn.all { it in settled }
        }
    }

    /**
     * Mark any pending step whose dependency failed or was skipped as SKIPPED, transitively, so a
     * step downstream of a failure can never deadlock the run. Iterates to a fixed point because a
     * step's own skip can make its dependents un-runnable in turn.
     */
    private fun propagateSkips(run: WorkflowRun): WorkflowRun {
        val blocked = run.steps
            .filter { it.state == WorkflowStepRunState.FAILED || it.state == WorkflowStepRunState.SKIPPED }
            .map { it.stepId }
            .toMutableSet()
        if (blocked.isEmpty()) return run
        var changed = false
        val steps = run.steps.map { record ->
            val definition = run.workflow.steps.firstOrNull { it.id == record.stepId }
            if (record.state == WorkflowStepRunState.PENDING && definition != null &&
                definition.dependsOn.any { it in blocked }
            ) {
                changed = true
                blocked += record.stepId
                record.copy(state = WorkflowStepRunState.SKIPPED, errorCode = DEPENDENCY_FAILED, errorMessage = "A dependency did not succeed")
            } else {
                record
            }
        }
        return if (changed) run.copy(steps = steps, updatedAtMs = clock()) else run
    }

    private suspend fun executeStep(run: WorkflowRun, definition: WorkflowStep): WorkflowRun {
        val now = clock()
        val record = run.step(definition.id) ?: return run

        if (!conditionAllows(definition, run)) {
            return run.withStep(record.copy(state = WorkflowStepRunState.SKIPPED, finishedAtMs = now)).copy(updatedAtMs = now)
        }

        when (approval.decide(definition)) {
            WorkflowApprovalDecision.DENY -> {
                val failed = record.copy(
                    state = WorkflowStepRunState.FAILED,
                    errorCode = APPROVAL_DENIED,
                    errorMessage = "User denied workflow step",
                    finishedAtMs = now,
                )
                return run.withStep(failed).copy(state = WorkflowRunState.FAILED, errorCode = APPROVAL_DENIED, errorMessage = "User denied workflow step", updatedAtMs = now)
            }
            WorkflowApprovalDecision.WAITING_FOR_USER ->
                return run.copy(state = WorkflowRunState.WAITING_APPROVAL, updatedAtMs = now)
            WorkflowApprovalDecision.ALLOW -> Unit
        }

        val input = resolveInputs(definition, run)
        var attempts = record.attempts
        var last: WorkflowToolResult? = null
        val started = clock()
        while (attempts <= definition.maxRetries) {
            attempts++
            last = try {
                withTimeout(definition.timeoutMs) { executor.execute(definition.toolId, input) }
            } catch (_: TimeoutCancellationException) {
                WorkflowToolResult(false, errorCode = TIMEOUT, errorMessage = "Workflow step exceeded ${definition.timeoutMs}ms")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                WorkflowToolResult(false, errorCode = EXECUTION_ERROR, errorMessage = failure.message ?: "Workflow step failed")
            }
            if (last.success) break
        }
        val outcome = last ?: WorkflowToolResult(false, errorCode = EXECUTION_ERROR, errorMessage = "Workflow tool returned no result")
        val finished = clock()
        val updated = record.copy(
            state = if (outcome.success) WorkflowStepRunState.SUCCEEDED else WorkflowStepRunState.FAILED,
            attempts = attempts,
            output = outcome.output,
            errorCode = outcome.errorCode,
            errorMessage = outcome.errorMessage,
            startedAtMs = started,
            finishedAtMs = finished,
        )
        val next = run.withStep(updated).copy(updatedAtMs = finished)
        return if (outcome.success) next else next.copy(state = WorkflowRunState.FAILED, errorCode = outcome.errorCode, errorMessage = outcome.errorMessage)
    }

    private fun finish(run: WorkflowRun, state: WorkflowRunState, errorCode: String?, errorMessage: String?): WorkflowRun {
        val finished = run.copy(state = state, errorCode = errorCode, errorMessage = errorMessage, updatedAtMs = clock())
        store.save(finished)
        return finished
    }

    private fun conditionAllows(definition: WorkflowStep, run: WorkflowRun): Boolean {
        val condition = definition.condition?.trim().orEmpty()
        if (condition.isBlank() || condition.equals("always", true)) return true
        if (condition.startsWith("success:")) {
            return run.step(condition.removePrefix("success:"))?.state == WorkflowStepRunState.SUCCEEDED
        }
        if (condition.startsWith("failed:")) {
            return run.step(condition.removePrefix("failed:"))?.state == WorkflowStepRunState.FAILED
        }
        return false
    }

    /**
     * Resolve an input mapping. `stepId.field` reads an earlier step's output; `literal:` carries a
     * constant (so a step can pin an argument the upstream output does not supply); anything else is
     * passed through unchanged. A `{stepId.field}` placeholder inside a value is substituted from an
     * earlier step's output, which lets a step build a value (such as a URL) from two upstream steps.
     */
    private fun resolveInputs(definition: WorkflowStep, run: WorkflowRun): Map<String, String> =
        definition.inputMappings.mapValues { (_, expression) ->
            val base = if (expression.startsWith(LITERAL_PREFIX)) {
                expression.removePrefix(LITERAL_PREFIX)
            } else {
                val match = Regex("^([a-z0-9._-]+)\\.([a-zA-Z0-9._-]+)$").matchEntire(expression)
                if (match == null) expression else run.step(match.groupValues[1])?.output?.get(match.groupValues[2]).orEmpty()
            }
            interpolate(base, run)
        }

    private fun interpolate(value: String, run: WorkflowRun): String =
        PLACEHOLDER.replace(value) { match ->
            val stepId = match.groupValues[1]
            val field = match.groupValues[2]
            run.step(stepId)?.output?.get(field).orEmpty()
        }
}
