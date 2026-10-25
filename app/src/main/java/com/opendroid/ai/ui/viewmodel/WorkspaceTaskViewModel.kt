package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.accessibility.GenericAppAutomator
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionDefinition
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.GoogleDriveAuthorizationException
import com.opendroid.ai.data.repository.GoogleDriveRepository
import com.opendroid.ai.data.repository.GoogleDriveSession
import com.opendroid.ai.data.repository.InstalledAiApp
import com.opendroid.ai.data.repository.InstalledAiAppCatalog
import com.opendroid.ai.data.repository.TaskTriageParser
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import com.opendroid.ai.data.repository.WorkspaceTaskToolRunner
import com.usmanandumar.workspace.core.task.TaskOrchestrationStage
import com.usmanandumar.workspace.core.task.TaskState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TaskArtifactReview(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val byteCount: Long,
    val sha256: String,
    val textPreview: String?,
)

@HiltViewModel
class WorkspaceTaskViewModel @Inject constructor(
    private val repository: WorkspaceTaskRepository,
    private val toolRunner: WorkspaceTaskToolRunner,
    private val actionDispatcher: ActionDispatcher,
    private val llmProviderFactory: LLMProviderFactory,
    private val aiAppCatalog: InstalledAiAppCatalog,
    private val drive: GoogleDriveRepository,
    private val driveSession: GoogleDriveSession,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val tasks: StateFlow<List<WorkspaceTaskEntity>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val runningToolTasks = ConcurrentHashMap<String, Job>()
    private val _dependenciesByTask = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val dependenciesByTask: StateFlow<Map<String, List<String>>> = _dependenciesByTask.asStateFlow()
    private val _installedAiApps = MutableStateFlow<List<InstalledAiApp>>(emptyList())
    val installedAiApps: StateFlow<List<InstalledAiApp>> = _installedAiApps.asStateFlow()
    private val _driveStatus = MutableStateFlow<String?>(null)
    val driveStatus: StateFlow<String?> = _driveStatus.asStateFlow()

    val availableActions: List<ActionDefinition> = ActionSchema.ALL_ACTIONS
        .filter { actionDispatcher.isRegistered(it.name) }
        .sortedWith(compareBy<ActionDefinition> { it.category.name }.thenBy { it.name })

    init {
        viewModelScope.launch(Dispatchers.IO) { _installedAiApps.value = aiAppCatalog.discover() }
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
        category: String = "Personal",
        deadlineAt: Long? = null,
        aiAppPackage: String? = null,
        onError: (String) -> Unit = {},
        onCreated: () -> Unit = {},
    ) {
        if (title.isBlank()) {
            onError("Task title cannot be empty.")
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.create(
                    title = title.trim(),
                    description = description.trim(),
                    expectedOutcome = expectedOutcome.trim(),
                    priority = priority,
                    dependencies = dependencies,
                    category = category,
                    deadlineAt = deadlineAt,
                    aiAppPackage = aiAppPackage,
                )
            }.onSuccess { onCreated() }
                .onFailure { onError(it.message ?: "Task could not be saved") }
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
        category: String = "Personal",
        deadlineAt: Long? = null,
        onError: (String) -> Unit = {},
        onCreated: () -> Unit = {},
    ) {
        if (title.isBlank() || toolId.isBlank()) {
            onError("A task title and registered action are required.")
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.create(
                    title = title.trim(),
                    description = description.trim(),
                    expectedOutcome = expectedOutcome.trim(),
                    priority = priority,
                    dependencies = dependencies,
                    category = category,
                    deadlineAt = deadlineAt,
                    toolId = toolId.trim(),
                    inputJson = inputJson,
                    requiresApproval = true,
                )
            }.onSuccess { onCreated() }
                .onFailure { onError(it.message ?: "Task could not be saved") }
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
        viewModelScope.launch {
            val latest = repository.getTask(task.id) ?: return@launch
            latest.aiAppPackage?.let(GenericAppAutomator::cancelExternalAiCapture)
            val orchestration = runCatching { TaskOrchestrationStage.valueOf(latest.orchestrationStage) }.getOrNull()
            if (orchestration != null && orchestration !in setOf(TaskOrchestrationStage.COMPLETED, TaskOrchestrationStage.CANCELLED)) {
                runCatching { repository.moveOrchestrationStage(task.id, TaskOrchestrationStage.CANCELLED) }
            }
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
        if (!task.aiAppPackage.isNullOrBlank()) {
            onError("AI-assisted tasks require a captured response and a user-selected, non-empty output file before completion.")
            return
        }
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

    /** Uses only the provider already configured by the user, including local Ollama. */
    fun triageInbox(rawText: String, onCreated: (Int) -> Unit, onError: (String) -> Unit) {
        if (rawText.isBlank()) {
            onError("Paste email or notes before running AI Triage.")
            return
        }
        if (rawText.length > MAX_INBOX_CHARS) {
            onError("Inbox text is longer than $MAX_INBOX_CHARS characters. Split it into smaller items; the text has not been changed.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val provider = llmProviderFactory.getActiveProvider()
                val response = provider.complete(
                    LLMRequest(
                        systemPrompt = """Turn the inbox text into a small, ordered execution plan of actionable tasks. Return only JSON with shape {"tasks":[{"title":"...","description":"...","expectedOutcome":"...","category":"Work|Coding|Personal","priority":0,"dueDate":null,"dependsOn":[]}]}. Each dependsOn entry is a zero-based index of a prerequisite task in this same array; only reference earlier tasks (a dependency index must be smaller than the current task index). Use dependencies only when the supplied goal requires that order; otherwise use an empty array. Extract only tasks clearly requested or implied by the supplied text. Never invent deadlines, people, commitments, or completion. Use null for missing due dates. Keep titles concise and descriptions faithful to the source.""",
                        messages = listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = rawText,
                                sender = ChatMessage.Sender.USER,
                            ),
                        ),
                        temperature = 0.2f,
                        maxTokens = 1_500,
                        responseFormat = ResponseFormat.JSON,
                    ),
                )
                val triaged = TaskTriageParser.parse(response.content)
                val createdTaskIds = mutableListOf<String>()
                triaged.forEach { item ->
                    val created = repository.create(
                        title = item.title,
                        description = item.description,
                        expectedOutcome = item.expectedOutcome,
                        category = item.category,
                        priority = item.priority,
                        deadlineAt = item.deadlineAt,
                        dependencies = item.dependsOn.map { createdTaskIds[it] },
                    )
                    createdTaskIds += created.id
                }
                withContext(Dispatchers.Main) { onCreated(triaged.size) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    onError(failure.message ?: "AI Triage failed. The inbox text has not been cleared.")
                }
            }
        }
    }

    /** Progresses through the separate orchestration stages, while the legacy TaskState remains intact. */
    fun beginAiAppTask(task: WorkspaceTaskEntity, onPromptReady: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val current = repository.getTask(task.id) ?: error("Task no longer exists.")
                val appPackage = current.aiAppPackage?.takeIf(String::isNotBlank)
                    ?: error("Assign an installed AI app to this task first.")
                val unmet = repository.unmetDependencies(current.id)
                require(unmet.isEmpty()) {
                    "Blocked until prerequisites succeed: ${unmet.joinToString { "${it.title} (${it.state})" }}"
                }
                var latest = repository.getTask(current.id) ?: error("Task no longer exists.")
                var stage = TaskOrchestrationStage.valueOf(latest.orchestrationStage)
                if (stage == TaskOrchestrationStage.FAILED) {
                    repository.moveOrchestrationStage(latest.id, TaskOrchestrationStage.PENDING)
                    stage = TaskOrchestrationStage.PENDING
                }
                val recoveredState = TaskState.valueOf(latest.state)
                val mayResumeInterruptedTask = stage == TaskOrchestrationStage.RUNNING && recoveredState == TaskState.WAITING_USER
                require(stage == TaskOrchestrationStage.PENDING || stage == TaskOrchestrationStage.PLANNING || mayResumeInterruptedTask) {
                    "This AI task is already running, awaiting verification, or complete."
                }
                if (stage == TaskOrchestrationStage.PENDING) {
                    repository.moveOrchestrationStage(latest.id, TaskOrchestrationStage.PLANNING)
                }
                latest = repository.getTask(current.id) ?: error("Task no longer exists.")
                when (TaskState.valueOf(latest.state)) {
                    TaskState.DRAFT -> {
                        repository.transition(latest.id, TaskState.READY, "ai_task_prepared")
                        repository.transition(latest.id, TaskState.QUEUED, "ai_task_approved_and_queued")
                        repository.transition(latest.id, TaskState.RUNNING, "ai_app_task_started")
                    }
                    TaskState.READY -> {
                        repository.transition(latest.id, TaskState.QUEUED, "ai_task_approved_and_queued")
                        repository.transition(latest.id, TaskState.RUNNING, "ai_app_task_started")
                    }
                    TaskState.QUEUED -> repository.transition(latest.id, TaskState.RUNNING, "ai_app_task_started")
                    TaskState.RUNNING -> Unit
                    TaskState.FAILED, TaskState.WAITING_USER, TaskState.WAITING_APPROVAL, TaskState.BLOCKED -> {
                        repository.transition(latest.id, TaskState.READY, "ai_task_user_retry")
                        repository.transition(latest.id, TaskState.QUEUED, "ai_task_approved_and_queued")
                        repository.transition(latest.id, TaskState.RUNNING, "ai_app_task_restarted")
                    }
                    TaskState.SUCCEEDED, TaskState.CANCELLED -> error("A completed or cancelled task cannot be sent to an AI app.")
                }
                val afterState = repository.getTask(current.id) ?: error("Task no longer exists.")
                if (TaskOrchestrationStage.valueOf(afterState.orchestrationStage) == TaskOrchestrationStage.PLANNING) {
                    repository.moveOrchestrationStage(afterState.id, TaskOrchestrationStage.RUNNING)
                }
                val prompt = buildString {
                    appendLine("Task: ${afterState.title}")
                    if (afterState.description.isNotBlank()) appendLine("Instructions: ${afterState.description}")
                    if (afterState.expectedOutcome.isNotBlank()) appendLine("Expected outcome: ${afterState.expectedOutcome}")
                    appendLine("Work on this task and provide your response. Do not claim a file was created unless it exists and can be selected in the app for verification.")
                }.trim()
                val captureArmed = GenericAppAutomator.armExternalAiCapture(appPackage)
                onPromptReady(prompt, captureArmed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(failure.message ?: "Could not start the AI-app task.")
            }
        }
    }

    fun failAiAppLaunch(task: WorkspaceTaskEntity) {
        viewModelScope.launch {
            val current = repository.getTask(task.id) ?: return@launch
            current.aiAppPackage?.let(GenericAppAutomator::cancelExternalAiCapture)
            if (current.state == TaskState.RUNNING.name) {
                runCatching { repository.transition(current.id, TaskState.FAILED, "ai_app_launch_failed") }
            }
            if (current.orchestrationStage == TaskOrchestrationStage.RUNNING.name) {
                runCatching { repository.moveOrchestrationStage(current.id, TaskOrchestrationStage.FAILED) }
            }
        }
    }

    fun captureVisibleAiResponse(task: WorkspaceTaskEntity, onResult: (String) -> Unit) {
        val packageName = task.aiAppPackage
        if (packageName.isNullOrBlank()) {
            onResult("No AI app is assigned to this task. Paste the response manually instead.")
            return
        }
        val response = GenericAppAutomator.consumeExternalAiCapture(packageName).orEmpty().trim()
        if (response.isBlank()) {
            onResult("No readable external-app text was captured. Accessibility may be disabled or the app may hide its screen text; paste the response manually instead.")
            return
        }
        storeAiResponse(task, response, onResult)
    }

    fun recordManualAiResponse(task: WorkspaceTaskEntity, response: String, onResult: (String) -> Unit) {
        if (response.isBlank()) {
            onResult("Paste the AI response before saving it.")
            return
        }
        storeAiResponse(task, response, onResult)
    }

    private fun storeAiResponse(task: WorkspaceTaskEntity, response: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            task.aiAppPackage?.let(GenericAppAutomator::cancelExternalAiCapture)
            runCatching { repository.recordExternalAiResponse(task.id, response) }
                .onSuccess { onResult("Response saved locally. The task remains VERIFYING; select and inspect a non-empty output file before marking it complete.") }
                .onFailure { onResult(it.message ?: "Could not save the AI response.") }
        }
    }

    fun inspectOutputArtifact(
        task: WorkspaceTaskEntity,
        uri: Uri,
        onReviewed: (TaskArtifactReview) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mimeType = context.contentResolver.getType(uri)
                val isText = mimeType?.startsWith("text/") == true || mimeType?.contains("json", ignoreCase = true) == true ||
                    mimeType?.contains("xml", ignoreCase = true) == true || mimeType?.contains("csv", ignoreCase = true) == true
                val previewBytes = ByteArrayOutputStream()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_VERIFY_BYTES) throw IOException("The selected file exceeds the 100 MiB verification limit.")
                        digest.update(buffer, 0, count)
                        if (isText && previewBytes.size() < MAX_PREVIEW_BYTES) {
                            previewBytes.write(buffer, 0, minOf(count, MAX_PREVIEW_BYTES - previewBytes.size()))
                        }
                    }
                    total
                } ?: throw IOException("The selected output file could not be opened.")
                require(bytes > 0L) { "The selected output file is empty; the task was not completed." }
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val displayName = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "Selected output"
                val preview = if (isText) previewBytes.toByteArray().toString(Charsets.UTF_8)
                    .replace("\u0000", "").take(1_000).takeIf(String::isNotBlank) else null
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val review = TaskArtifactReview(uri, displayName, mimeType, bytes, sha256, preview)
                withContext(Dispatchers.Main) {
                    onReviewed(review)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) { onError(failure.message ?: "Output inspection failed; task remains incomplete.") }
            }
        }
    }

    /** Re-reads the same output after an explicit user confirmation, then records completion. */
    fun confirmOutputArtifact(task: WorkspaceTaskEntity, review: TaskArtifactReview, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val bytes = context.contentResolver.openInputStream(review.uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_VERIFY_BYTES) throw IOException("The selected file exceeds the 100 MiB verification limit.")
                        digest.update(buffer, 0, count)
                    }
                    total
                } ?: throw IOException("The selected output file can no longer be opened.")
                require(bytes > 0L) { "The selected output file is empty; the task was not completed." }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                require(bytes == review.byteCount && actualSha256 == review.sha256) {
                    "The selected file changed after inspection. Select it again and review the updated contents."
                }
                repository.completeWithVerifiedArtifact(task.id, review.uri.toString(), bytes)
                withContext(Dispatchers.Main) {
                    onResult("Output re-read and confirmed: ${review.displayName} ($bytes bytes). Task marked complete. File presence/readability is verified; semantic correctness depends on your review.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) { onResult(failure.message ?: "Output verification failed; task remains incomplete.") }
            }
        }
    }

    fun uploadVerifiedArtifactToDrive(task: WorkspaceTaskEntity, onResult: (String) -> Unit) {
        val session = driveSession.snapshots.value
        val token = session.accessToken
        if (token.isNullOrBlank()) {
            onResult("Connect and authorize Google Drive first. The Drive token is held in memory only.")
            return
        }
        val artifact = task.verifiedArtifactUri?.let(Uri::parse)
        if (artifact == null) {
            onResult("Verify an output file before uploading it to Drive.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { drive.uploadFile(token, artifact) }
                .onSuccess { item ->
                    val message = if (driveSession.currentGeneration() == session.generation) {
                        "Drive upload verified for ${session.accountEmail}: ${item.name} (ID ${item.id})."
                    } else "Upload finished for the previously connected account: ${item.name} (ID ${item.id})."
                    withContext(Dispatchers.Main) { onResult(message) }
                }
                .onFailure { failure ->
                    if (failure is GoogleDriveAuthorizationException && driveSession.currentGeneration() == session.generation) driveSession.clear()
                    withContext(Dispatchers.Main) { onResult(failure.message ?: "Drive upload failed; the verified local file remains available.") }
                }
        }
    }

    fun uploadAiResponseToDrive(task: WorkspaceTaskEntity, onResult: (String) -> Unit) {
        val session = driveSession.snapshots.value
        val token = session.accessToken
        if (token.isNullOrBlank()) {
            onResult("Connect and authorize Google Drive first. The Drive token is held in memory only.")
            return
        }
        val body = task.notes.trim()
        if (body.isBlank()) {
            onResult("Capture or paste an AI response before saving it to Drive.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { drive.uploadText(token, "task-${task.id}-ai-response.txt", body) }
                .onSuccess { item -> withContext(Dispatchers.Main) { onResult("Drive upload verified: ${item.name} (ID ${item.id}).") } }
                .onFailure { failure ->
                    if (failure is GoogleDriveAuthorizationException && driveSession.currentGeneration() == session.generation) driveSession.clear()
                    withContext(Dispatchers.Main) { onResult(failure.message ?: "Drive upload failed; the local response remains available.") }
                }
        }
    }

    private companion object {
        const val MAX_INBOX_CHARS = 20_000
        const val MAX_VERIFY_BYTES = 100L * 1024L * 1024L
        const val MAX_PREVIEW_BYTES = 4_000
    }
}
