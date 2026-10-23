package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.actions.ActionDispatcher
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import com.opendroid.ai.data.repository.WorkspaceTaskToolRunner
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionDefinition
import com.opendroid.ai.core.agent.ActionSchema
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

@HiltViewModel
class WorkspaceTaskViewModel @Inject constructor(
    private val repository: WorkspaceTaskRepository,
    private val toolRunner: WorkspaceTaskToolRunner,
    private val actionDispatcher: ActionDispatcher,
) : ViewModel() {
    val tasks: StateFlow<List<WorkspaceTaskEntity>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val runningToolTasks = ConcurrentHashMap<String, Job>()
    private val _dependenciesByTask = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val dependenciesByTask: StateFlow<Map<String, List<String>>> = _dependenciesByTask.asStateFlow()
    val availableActions: List<ActionDefinition> = ActionSchema.ALL_ACTIONS
        .filter { actionDispatcher.isRegistered(it.name) }
        .sortedWith(compareBy<ActionDefinition> { it.category.name }.thenBy { it.name })

    init {
        viewModelScope.launch {
            tasks.collectLatest { currentTasks ->
                _dependenciesByTask.value = currentTasks.associate { task ->
                    task.id to repository.dependencyIds(task.id)
                }
            }
        }
    }

    fun createTask(
        title: String,
        description: String = "",
        expectedOutcome: String = "",
        priority: Int = 0,
        dependencies: List<String> = emptyList(),
        onError: (String) -> Unit = {},
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.create(
                    title = title.trim(),
                    description = description.trim(),
                    expectedOutcome = expectedOutcome.trim(),
                    priority = priority,
                    dependencies = dependencies,
                )
            }.onFailure { onError(it.message ?: "Task could not be saved") }
        }
    }

    fun createToolTask(
        title: String,
        toolId: String,
        inputJson: String,
        description: String = "",
        expectedOutcome: String = "",
        priority: Int = 0,
        dependencies: List<String> = emptyList(),
        onError: (String) -> Unit = {},
    ) {
        if (title.isBlank() || toolId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.create(
                    title = title.trim(),
                    description = description.trim(),
                    expectedOutcome = expectedOutcome.trim(),
                    priority = priority,
                    dependencies = dependencies,
                    toolId = toolId.trim(),
                    inputJson = inputJson,
                    requiresApproval = true,
                )
            }.onFailure { onError(it.message ?: "Task could not be saved") }
        }
    }

    fun runToolTask(task: WorkspaceTaskEntity, onResult: (ActionResult) -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val unmet = repository.unmetDependencies(task.id)
                if (unmet.isNotEmpty()) {
                    onResult(ActionResult.Failure("Blocked by prerequisite task(s): ${unmet.joinToString { "${it.title} (${it.state})" }}"))
                    return@launch
                }
                onResult(toolRunner.run(task))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val current = repository.getTask(task.id)
                    if (current != null && current.state !in setOf(TaskState.SUCCEEDED.name, TaskState.CANCELLED.name)) {
                        runCatching { repository.transition(task.id, TaskState.CANCELLED, "user_cancelled_execution") }
                    }
                }
                onResult(ActionResult.Failure("Task cancelled. An already-started external action may have taken effect."))
            } catch (failure: Exception) {
                onResult(ActionResult.Failure(failure.message ?: "Task execution failed"))
            } finally {
                runningToolTasks.remove(task.id)
            }
        }
        if (runningToolTasks.putIfAbsent(task.id, job) == null) job.start() else job.cancel()
    }

    fun cancelToolTask(task: WorkspaceTaskEntity) {
        val active = runningToolTasks[task.id]
        active?.cancel(CancellationException("User requested task cancellation"))
        if (active != null) runningToolTasks.remove(task.id, active)
        // Persist outside the cancellable job: a lazy coroutine cancelled before its
        // first dispatch never enters its body or executes its finally block.
        viewModelScope.launch {
            val latest = repository.getTask(task.id) ?: return@launch
            if (latest.state in setOf(
                    TaskState.DRAFT.name,
                    TaskState.READY.name,
                    TaskState.QUEUED.name,
                    TaskState.RUNNING.name,
                    TaskState.WAITING_APPROVAL.name,
                    TaskState.WAITING_USER.name,
                    TaskState.BLOCKED.name,
                    TaskState.FAILED.name,
                )
            ) {
                runCatching { repository.transition(task.id, TaskState.CANCELLED, "user_cancelled_execution") }
            }
        }
    }

    fun markManualTaskCompleted(task: WorkspaceTaskEntity, onError: (String) -> Unit = {}) {
        if (!task.toolId.isNullOrBlank() || task.state != TaskState.RUNNING.name) {
            onError("Only a running manual task can be marked complete here")
            return
        }
        transition(task, TaskState.SUCCEEDED, onError, event = "user_reported_manual_completion")
    }

    fun transition(
        task: WorkspaceTaskEntity,
        target: TaskState,
        onError: (String) -> Unit = {},
        event: String = "state_transition",
    ) {
        viewModelScope.launch {
            runCatching { repository.transition(task.id, target, event) }
                .onFailure { onError(it.message ?: "Task update failed") }
        }
    }
}
