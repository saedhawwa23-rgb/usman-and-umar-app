package com.opendroid.ai.accessibility

import android.content.Context
import com.opendroid.ai.core.agent.InstalledAiAppDiscovery
import com.opendroid.ai.core.agent.SmartLocalAiRouter
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import com.usmanandumar.workspace.core.task.TaskState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a prompt through a user-installed AI app using the explicitly enabled
 * Accessibility Service. No private API, credentials, or hidden app channel is used.
 */
@Singleton
class LocalAiAppExecutor @Inject constructor(
    private val workspaceTaskRepository: WorkspaceTaskRepository,
) {
    suspend fun execute(
        context: Context,
        appName: String?,
        packageName: String?,
        prompt: String,
    ): String {
        require(prompt.isNotBlank()) { "A prompt is required for the local AI app." }
        val packageManager = context.packageManager
        val installed = InstalledAiAppDiscovery.scan(packageManager)
        val explicit = installed.firstOrNull { candidate ->
            (!packageName.isNullOrBlank() && candidate.packageName.equals(packageName, ignoreCase = true)) ||
                (!appName.isNullOrBlank() && candidate.displayName.equals(appName, ignoreCase = true))
        }
        val app = explicit ?: if (
            packageName.isNullOrBlank() || packageName.equals(SmartLocalAiRouter.AUTO_TARGET, ignoreCase = true) ||
                appName.isNullOrBlank() || appName.equals("auto", ignoreCase = true) || appName.equals("Smart AI Selection", ignoreCase = true)
        ) {
            SmartLocalAiRouter.select(prompt, installed)
        } else {
            null
        } ?: error("No launchable installed AI app matched '${packageName ?: appName ?: "(unspecified)"}'.")

        check(OpenDroidAccessibilityService.getInstance() != null) {
            "Enable USMAN AND UMAR Accessibility Service in Android Settings before using local AI apps."
        }

        val taskId = "local-ai-${UUID.randomUUID()}"
        workspaceTaskRepository.create(
            id = taskId,
            title = "Use ${app.displayName}",
            description = prompt.take(4_000),
            toolId = "local_ai:${app.packageName}",
            timeoutMs = 180_000,
        )
        workspaceTaskRepository.transition(taskId, TaskState.RUNNING, event = "local_ai_dispatch")
        check(
            OpenDroidAccessibilityService.startLocalAiTask(
                taskId = taskId,
                packageName = app.packageName,
                appLabel = app.displayName,
                prompt = prompt,
            )
        ) { "Could not start ${app.displayName}. Confirm its launch activity and Accessibility Service." }

        val completed = withTimeoutOrNull(185_000) {
            workspaceTaskRepository.observeTasks().first { tasks ->
                tasks.firstOrNull { it.id == taskId }?.state in setOf(
                    TaskState.SUCCEEDED.name,
                    TaskState.FAILED.name,
                    TaskState.WAITING_USER.name,
                )
            }
        } ?: error("${app.displayName} did not return a response before timeout.")
        val task = completed.first { it.id == taskId }
        check(task.state == TaskState.SUCCEEDED.name) {
            task.notes.substringAfter("Execution output:\n", task.notes).ifBlank {
                "${app.displayName} did not return a usable response."
            }
        }
        return task.notes.substringAfter("Execution output:\n", task.notes).take(20_000)
    }
}
