package com.opendroid.ai.data.repository

import android.content.Context
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.crash.CrashLogRedactor
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.usmanandumar.workspace.core.task.TaskState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Executes one explicitly approved, registered app action as a persistent task. */
@Singleton
class WorkspaceTaskToolRunner @Inject constructor(
    private val taskRepository: WorkspaceTaskRepository,
    private val actionDispatcher: ActionDispatcher,
    @ApplicationContext private val context: Context,
) {
    suspend fun run(task: WorkspaceTaskEntity): ActionResult {
        val toolId = task.toolId?.takeIf(String::isNotBlank)
            ?: return ActionResult.Failure("This task has no tool selected")
        if (!actionDispatcher.isRegistered(toolId)) {
            return ActionResult.UnknownAction(toolId, actionDispatcher.getAllRegisteredActions())
        }
        if (!task.requiresApproval) {
            return ActionResult.Failure("Tool tasks must require explicit user approval")
        }

        val params = try {
            parseStringObject(task.inputJson)
        } catch (failure: IllegalArgumentException) {
            return ActionResult.Failure(failure.message ?: "Tool input is invalid")
        }

        var state = TaskState.valueOf(task.state)
        if (state == TaskState.DRAFT) {
            taskRepository.transition(task.id, TaskState.READY, "tool_task_prepared")
            state = TaskState.READY
        }
        if (state == TaskState.WAITING_APPROVAL || state == TaskState.WAITING_USER || state == TaskState.FAILED) {
            taskRepository.transition(task.id, TaskState.READY, "tool_task_user_approved_retry")
            state = TaskState.READY
        }
        if (state == TaskState.READY) {
            taskRepository.transition(task.id, TaskState.QUEUED, "tool_task_approved_and_queued")
            state = TaskState.QUEUED
        }
        if (state != TaskState.QUEUED) {
            return ActionResult.Failure("Task must be ready or queued before it can run (state: $state)")
        }

        taskRepository.transition(task.id, TaskState.RUNNING, "tool_task_started")
        val result = try {
            actionDispatcher.execute(toolId, params, context)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ActionResult.Failure(failure.message ?: "Tool execution failed")
        }

        when (result) {
            is ActionResult.Success -> taskRepository.recordOutcome(
                task.id,
                success = true,
                outputRedacted = CrashLogRedactor.redact(result.data ?: "Completed").take(MAX_RECORDED_OUTPUT),
                event = "tool_task_succeeded",
            )
            is ActionResult.NeedsInput,
            is ActionResult.PendingUserAction,
            is ActionResult.UserActionRequired -> taskRepository.transition(task.id, TaskState.WAITING_USER, "tool_task_waiting_for_user")
            else -> taskRepository.recordOutcome(
                task.id,
                success = false,
                errorCode = if (result is ActionResult.UnknownAction) "UNKNOWN_TOOL" else "TOOL_FAILED",
                errorMessageRedacted = CrashLogRedactor.redact(result.error ?: "Tool execution failed").take(MAX_RECORDED_OUTPUT),
                event = "tool_task_failed",
            )
        }
        return result
    }

    private fun parseStringObject(raw: String): Map<String, String> {
        require(raw.length <= MAX_INPUT_CHARS) { "Tool input exceeds the $MAX_INPUT_CHARS character limit" }
        val parsed = runCatching { Json.parseToJsonElement(raw) }
            .getOrElse { throw IllegalArgumentException("Tool input must be a valid JSON object") }
        require(parsed is JsonObject) { "Tool input must be a JSON object" }
        return parsed.mapValues { (key, value) ->
            require(value is JsonPrimitive && value.isString) { "Tool input '$key' must be a string" }
            value.contentOrNull.orEmpty()
        }
    }

    private companion object {
        const val MAX_INPUT_CHARS = 16_384
        const val MAX_RECORDED_OUTPUT = 4_000
    }
}
