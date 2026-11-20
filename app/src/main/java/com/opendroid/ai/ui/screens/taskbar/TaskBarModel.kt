package com.opendroid.ai.ui.screens.taskbar

import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.usmanandumar.workspace.core.task.TaskState

/**
 * v3.10.5: the Task Bar's presentation logic, kept free of Compose and Android so it can be
 * asserted directly in JVM tests.
 *
 * The screen renders whatever this reports. That keeps the derived status, the progress line and
 * the set of buttons on each card in one place, so a state cannot render two contradictory things
 * and a new state cannot silently lose its actions.
 */

/** The bucket a task falls into on the Task Bar. Order here is the display order of the summary. */
enum class TaskBucket { ACTIVE, QUEUED, WAITING, DONE, FAILED }

/** The semantic status of a task, mapped to the shared PlatformStatus palette by the screen. */
enum class TaskStatusKind { ACTIVE, WARNING, SUCCESS, ERROR, IDLE }

/** The actions a card may offer. A state exposes exactly the actions that can legally follow it. */
enum class TaskAction { RUN, RETRY, RESUME, PAUSE, CANCEL, APPROVE, SAVE_OUTPUT }

/** One task, resolved to everything the card needs to draw itself. */
data class TaskBarItem(
    val id: String,
    val title: String,
    val state: TaskState,
    val bucket: TaskBucket,
    val statusLabel: String,
    val statusKind: TaskStatusKind,
    val modeLabel: String,
    val progress: TaskProgress,
    val dueAtMs: Long?,
    val note: String,
    val output: String,
    val actions: List<TaskAction>,
)

/** How far along a task is, when that can be stated truthfully. */
sealed interface TaskProgress {
    /** Running work with no reliable percentage; the UI shows an indeterminate indicator. */
    data object Indeterminate : TaskProgress

    /** Finished work: a full, static bar. */
    data object Complete : TaskProgress

    /** No progress to show (a draft, a queued item, a failure). */
    data object None : TaskProgress
}

/** The counts shown in the summary strip. */
data class TaskBarSummary(
    val active: Int,
    val queued: Int,
    val waiting: Int,
    val done: Int,
    val failed: Int,
) {
    val total: Int get() = active + queued + waiting + done + failed
}

object TaskBarModel {

    /** Maps a persisted state string to a known [TaskState]; unknown values read as [TaskState.DRAFT]. */
    fun parseState(raw: String): TaskState =
        runCatching { TaskState.valueOf(raw) }.getOrDefault(TaskState.DRAFT)

    fun bucketOf(state: TaskState): TaskBucket = when (state) {
        TaskState.RUNNING -> TaskBucket.ACTIVE
        TaskState.DRAFT, TaskState.READY, TaskState.QUEUED -> TaskBucket.QUEUED
        TaskState.WAITING_FOR_NETWORK, TaskState.WAITING_APPROVAL,
        TaskState.WAITING_USER, TaskState.BLOCKED, TaskState.PAUSED -> TaskBucket.WAITING
        TaskState.SUCCEEDED, TaskState.CANCELLED -> TaskBucket.DONE
        TaskState.FAILED -> TaskBucket.FAILED
    }

    fun summary(tasks: List<WorkspaceTaskEntity>): TaskBarSummary {
        var active = 0; var queued = 0; var waiting = 0; var done = 0; var failed = 0
        tasks.forEach { task ->
            when (bucketOf(parseState(task.state))) {
                TaskBucket.ACTIVE -> active++
                TaskBucket.QUEUED -> queued++
                TaskBucket.WAITING -> waiting++
                TaskBucket.DONE -> done++
                TaskBucket.FAILED -> failed++
            }
        }
        return TaskBarSummary(active, queued, waiting, done, failed)
    }

    /** Human-readable status for a state. Deliberately plain: no claim beyond what the state means. */
    fun statusLabel(state: TaskState): String = when (state) {
        TaskState.DRAFT -> "Draft"
        TaskState.READY -> "Ready"
        TaskState.QUEUED -> "Queued"
        TaskState.RUNNING -> "Running"
        TaskState.PAUSED -> "Paused"
        TaskState.WAITING_APPROVAL -> "Needs approval"
        TaskState.WAITING_USER -> "Needs your input"
        TaskState.WAITING_FOR_NETWORK -> "Waiting for network"
        TaskState.BLOCKED -> "Blocked"
        TaskState.SUCCEEDED -> "Succeeded"
        TaskState.FAILED -> "Failed"
        TaskState.CANCELLED -> "Cancelled"
    }

    fun statusKind(state: TaskState): TaskStatusKind = when (state) {
        TaskState.RUNNING -> TaskStatusKind.ACTIVE
        TaskState.PAUSED, TaskState.WAITING_APPROVAL, TaskState.WAITING_USER,
        TaskState.WAITING_FOR_NETWORK, TaskState.BLOCKED -> TaskStatusKind.WARNING
        TaskState.SUCCEEDED -> TaskStatusKind.SUCCESS
        TaskState.FAILED -> TaskStatusKind.ERROR
        TaskState.DRAFT, TaskState.READY, TaskState.QUEUED, TaskState.CANCELLED -> TaskStatusKind.IDLE
    }

    fun progressOf(state: TaskState): TaskProgress = when (state) {
        TaskState.RUNNING -> TaskProgress.Indeterminate
        TaskState.SUCCEEDED -> TaskProgress.Complete
        else -> TaskProgress.None
    }

    /**
     * The actions a state may offer. Mirrors the transitions the state machine allows, so the card
     * never shows a button whose transition would be rejected.
     */
    fun actionsFor(state: TaskState, toolId: String?, hasOutput: Boolean): List<TaskAction> {
        val isCloud = toolId?.startsWith("hybrid:CLOUD_OPENSWARM") == true
        val actions = mutableListOf<TaskAction>()
        when (state) {
            TaskState.DRAFT, TaskState.READY, TaskState.QUEUED -> actions += TaskAction.RUN
            TaskState.WAITING_FOR_NETWORK, TaskState.FAILED -> actions += TaskAction.RETRY
            TaskState.PAUSED -> {
                actions += TaskAction.RESUME
                actions += TaskAction.CANCEL
            }
            TaskState.RUNNING -> if (isCloud) {
                actions += TaskAction.PAUSE
                actions += TaskAction.CANCEL
            }
            TaskState.WAITING_APPROVAL -> {
                actions += TaskAction.APPROVE
                actions += TaskAction.CANCEL
            }
            TaskState.WAITING_USER, TaskState.BLOCKED -> actions += TaskAction.RUN
            TaskState.SUCCEEDED, TaskState.CANCELLED -> Unit
        }
        if (hasOutput) actions += TaskAction.SAVE_OUTPUT
        return actions
    }

    /** The execution mode label, from the task's tool id. */
    fun modeLabel(toolId: String?): String = when {
        toolId?.startsWith("hybrid:LOCAL_AI_APP:") == true -> "Local AI · ${toolId.substringAfterLast(':')}"
        toolId?.startsWith("hybrid:CLOUD_OPENSWARM") == true -> "Cloud OpenSwarm"
        toolId?.startsWith("hybrid:TERMUX_SCRIPT") == true -> "Local Termux · ${toolId.substringAfterLast(':')}"
        else -> "No execution mode"
    }

    /** The stored output of a succeeded task, if it recorded one. */
    fun outputOf(task: WorkspaceTaskEntity): String {
        val state = parseState(task.state)
        return task.notes.takeIf { state == TaskState.SUCCEEDED && it.startsWith(OUTPUT_PREFIX) }
            ?.removePrefix(OUTPUT_PREFIX)
            ?.trim()
            .orEmpty()
    }

    /** The one-line explanation shown under the status pill, when a state needs one. */
    fun noteFor(state: TaskState, notes: String): String = when (state) {
        TaskState.WAITING_FOR_NETWORK -> "Offline — this task will wait. No AI app or server was contacted."
        TaskState.PAUSED -> "Pause requested. Completed steps are saved and will not be repeated."
        TaskState.WAITING_APPROVAL ->
            "Paused before a high-impact action. Review the requested steps before resuming.\n" +
                notes.removePrefix(APPROVAL_PREFIX).take(1_200)
        TaskState.WAITING_USER -> if (notes.contains("AI code blocks were NOT executed")) {
            "Code awaits review. Nothing from the model response was executed."
        } else ""
        else -> ""
    }

    fun filenameFor(title: String): String =
        title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "task-output" } + ".txt"

    /** Builds the full card view-model for one task. */
    fun itemOf(task: WorkspaceTaskEntity): TaskBarItem {
        val state = parseState(task.state)
        val output = outputOf(task)
        return TaskBarItem(
            id = task.id,
            title = task.title,
            state = state,
            bucket = bucketOf(state),
            statusLabel = statusLabel(state),
            statusKind = statusKind(state),
            modeLabel = modeLabel(task.toolId),
            progress = progressOf(state),
            dueAtMs = task.deadlineAt,
            note = noteFor(state, task.notes),
            output = output,
            actions = actionsFor(state, task.toolId, output.isNotBlank()),
        )
    }

    const val OUTPUT_PREFIX = "Execution output:"
    const val APPROVAL_PREFIX = "OpenSwarm approval request:\n"
}
