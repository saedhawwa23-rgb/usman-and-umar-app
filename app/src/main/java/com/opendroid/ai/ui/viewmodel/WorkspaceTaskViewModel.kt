package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usmanandumar.workspace.core.task.LocalAiProvider
import com.usmanandumar.workspace.core.task.TaskExecutionMode
import com.usmanandumar.workspace.core.task.TaskQuickAddParser
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class BrowserUseRunState(
    val phase: Phase = Phase.IDLE,
    val result: String = "",
    val error: String = "",
    val steps: Int? = null,
) {
    enum class Phase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

@HiltViewModel
class WorkspaceTaskViewModel @Inject constructor(
    private val repository: WorkspaceTaskRepository,
    private val settingsRepository: SettingsRepository,
    private val termux: TermuxIntegration,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val tasks: StateFlow<List<WorkspaceTaskEntity>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val openSwarmServerUrl: StateFlow<String> = settingsRepository.openSwarmServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val openSwarmClientToken: StateFlow<String> = settingsRepository.openSwarmClientToken
    val autoRunGeneratedCode: StateFlow<Boolean> = settingsRepository.autoRunGeneratedCode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(65, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(65, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build()

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _browserUseState = kotlinx.coroutines.flow.MutableStateFlow(BrowserUseRunState())
    val browserUseState: StateFlow<BrowserUseRunState> = _browserUseState
    @Volatile private var browserCall: okhttp3.Call? = null

    fun saveOpenSwarmServerUrl(url: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.saveOpenSwarmServerUrl(url) }
                .onSuccess { _message.value = if (url.isBlank()) "Cloud server URL cleared." else "Cloud server URL saved." }
                .onFailure { _message.value = it.message ?: "Unable to save the cloud server URL." }
        }
    }

    fun saveOpenSwarmClientToken(token: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.saveOpenSwarmClientToken(token) }
                .onSuccess { _message.value = if (token.isBlank()) "Bridge token cleared from secure storage." else "Bridge token saved in Android secure storage." }
                .onFailure { _message.value = it.message ?: "Unable to save the bridge token." }
        }
    }

    fun setAutoRunGeneratedCode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoRunGeneratedCode(enabled) }
    }

    fun runBrowserUse(prompt: String, useFmhy: Boolean, maxSteps: Int = 15) {
        val requestPrompt = prompt.trim()
        if (requestPrompt.isBlank()) {
            _browserUseState.value = BrowserUseRunState(BrowserUseRunState.Phase.FAILED, error = "Enter a browser task first.")
            return
        }
        cancelBrowserUse(silent = true)
        _browserUseState.value = BrowserUseRunState(phase = BrowserUseRunState.Phase.RUNNING)
        viewModelScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    val configuredUrl = settingsRepository.openSwarmServerUrl.first().trim().trimEnd('/')
                    val baseUrl = configuredUrl.removeSuffix("/execute").trimEnd('/')
                    check(baseUrl.isNotBlank()) { "Set the Cloud Backend URL first." }
                    val clientToken = settingsRepository.openSwarmClientToken.value
                    check(clientToken.isNotBlank()) { "Save the backend APP_CLIENT_TOKEN first." }
                    val payload = JSONObject()
                        .put("task_id", "browser-${System.currentTimeMillis()}")
                        .put("prompt", requestPrompt)
                        .put("plugin_id", if (useFmhy) "fmhy_browser" else "browser_use")
                        .put("browser_max_steps", maxSteps.coerceIn(1, 15))
                        .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url("$baseUrl/execute")
                        .header("Authorization", "Bearer $clientToken").post(payload).build()
                    val call = cloudClient.newCall(request)
                    browserCall = call
                    call.execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val detail = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                            error("Browser Use returned HTTP ${response.code}${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
                        }
                        check(body.isNotBlank()) { "Browser Use returned an empty response." }
                        val json = JSONObject(body)
                        BrowserUseRunState(
                            phase = BrowserUseRunState.Phase.SUCCEEDED,
                            result = json.optString("result").ifBlank { json.optString("output") }.ifBlank { body }.take(20_000),
                            steps = json.optInt("steps").takeIf { it > 0 },
                        )
                    }
                }
                _browserUseState.value = state
                _message.value = "Remote Browser Use completed."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _browserUseState.value = BrowserUseRunState(
                    phase = if (browserCall?.isCanceled() == true || _browserUseState.value.phase == BrowserUseRunState.Phase.CANCELLED) BrowserUseRunState.Phase.CANCELLED else BrowserUseRunState.Phase.FAILED,
                    error = failure.message ?: "Browser Use failed.",
                )
            } finally {
                browserCall = null
            }
        }
    }

    fun cancelBrowserUse(silent: Boolean = false) {
        browserCall?.cancel()
        if (!silent && _browserUseState.value.phase == BrowserUseRunState.Phase.RUNNING) {
            _browserUseState.value = BrowserUseRunState(phase = BrowserUseRunState.Phase.CANCELLED)
            _message.value = "Remote Browser Use cancelled."
        }
    }

    fun createTask(
        rawText: String,
        mode: TaskExecutionMode,
        providerPackage: String,
        scriptLanguage: String = "bash",
        onSaved: () -> Unit = {},
    ) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            val parsed = TaskQuickAddParser.parse(rawText)
            val title = parsed.title.ifBlank { rawText.trim() }
            val tool = when (mode) {
                TaskExecutionMode.LOCAL_AI_APP -> "hybrid:LOCAL_AI_APP:$providerPackage"
                TaskExecutionMode.CLOUD_OPENSWARM -> "hybrid:CLOUD_OPENSWARM"
                TaskExecutionMode.TERMUX_SCRIPT -> "hybrid:TERMUX_SCRIPT:${scriptLanguage.lowercase()}"
            }
            runCatching {
                repository.create(
                    title = title,
                    description = rawText.trim(),
                    category = "orchestrator",
                    toolId = tool,
                    inputJson = JSONObject().put("prompt", rawText.trim()).toString(),
                    deadlineAt = parsed.deadlineAt,
                    requiresApproval = false,
                )
            }.onSuccess {
                _message.value = "Task saved as DRAFT. Choose Run when ready."
                onSaved()
            }.onFailure { _message.value = it.message ?: "The task could not be saved; your draft is still in the editor." }
        }
    }

    fun runTask(task: WorkspaceTaskEntity) {
        viewModelScope.launch {
            val mode = modeOf(task) ?: run {
                _message.value = "This task has no supported execution mode."
                return@launch
            }
            if (mode != TaskExecutionMode.TERMUX_SCRIPT && !hasValidatedInternet()) {
                moveToNetworkWait(task)
                return@launch
            }
            try {
                val running = prepareRunning(task)
                if (mode != TaskExecutionMode.TERMUX_SCRIPT && !hasValidatedInternet()) {
                    repository.transition(running.id, TaskState.WAITING_FOR_NETWORK, "network_lost_before_dispatch")
                    _message.value = "Offline — this task is WAITING_FOR_NETWORK. No AI app was opened and no cloud request was sent."
                    return@launch
                }
                _message.value = if (mode == TaskExecutionMode.CLOUD_OPENSWARM) "Waking up cloud server..." else "Starting ${mode.name.lowercase().replace('_', ' ')}..."
                when (mode) {
                    TaskExecutionMode.CLOUD_OPENSWARM -> executeCloud(running)
                    TaskExecutionMode.TERMUX_SCRIPT -> executeTermux(running)
                    TaskExecutionMode.LOCAL_AI_APP -> executeLocalAi(running)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val fresh = tasks.value.firstOrNull { it.id == task.id } ?: task
                if (fresh.state == TaskState.RUNNING.name) {
                    runCatching {
                        repository.recordOutcome(
                            taskId = task.id,
                            success = false,
                            errorCode = "EXECUTION_FAILED",
                            errorMessageRedacted = failure.message?.take(500) ?: "Execution failed without a reported message.",
                            event = "hybrid_execution",
                        )
                    }
                }
                _message.value = failure.message ?: "Execution failed; no result was fabricated."
            }
        }
    }

    private suspend fun prepareRunning(task: WorkspaceTaskEntity): WorkspaceTaskEntity {
        var current = repository.run {
            when (task.state) {
                TaskState.DRAFT.name -> transition(task.id, TaskState.READY, "task_prepared")
                TaskState.FAILED.name -> transition(task.id, TaskState.READY, "task_retry_prepared")
                TaskState.WAITING_FOR_NETWORK.name -> transition(task.id, TaskState.QUEUED, "network_restored")
                TaskState.READY.name -> task
                TaskState.QUEUED.name -> task
                else -> error("Task cannot run from ${task.state}.")
            }
        }
        if (current.state == TaskState.READY.name) current = repository.transition(task.id, TaskState.QUEUED, "task_queued")
        if (current.state == TaskState.QUEUED.name) current = repository.transition(task.id, TaskState.RUNNING, "task_started")
        return current
    }

    private suspend fun moveToNetworkWait(task: WorkspaceTaskEntity) {
        val current = repository.observeTasks().first().firstOrNull { it.id == task.id } ?: task
        if (current.state == TaskState.WAITING_FOR_NETWORK.name) {
            _message.value = "Waiting for network. No AI app was opened and no server request was sent."
            return
        }
        val target = when (current.state) {
            TaskState.DRAFT.name, TaskState.QUEUED.name, TaskState.READY.name, TaskState.FAILED.name -> TaskState.WAITING_FOR_NETWORK
            else -> null
        }
        if (target != null) repository.transition(task.id, target, "waiting_for_network")
        _message.value = "Offline — this task is WAITING_FOR_NETWORK. No AI app was opened and no cloud request was sent."
    }

    private suspend fun executeCloud(task: WorkspaceTaskEntity) = withContext(Dispatchers.IO) {
        val configuredUrl = settingsRepository.openSwarmServerUrl.first().trim().trimEnd('/')
        val baseUrl = configuredUrl.removeSuffix("/execute").trimEnd('/')
        check(baseUrl.isNotBlank()) { "Set the Cloud Backend URL in Task Manager → Cloud settings." }
        val clientToken = settingsRepository.openSwarmClientToken.value
        check(clientToken.isNotBlank()) { "Save the backend APP_CLIENT_TOKEN in Task Manager → Cloud settings before running cloud tasks." }
        val prompt = promptOf(task)
        val payload = JSONObject().put("task_id", task.id).put("prompt", prompt)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$baseUrl/execute").header("Authorization", "Bearer $clientToken").post(payload).build()
        cloudClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val json = JSONObject(body)
                    listOf("error", "detail", "message")
                        .asSequence()
                        .map { json.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                }.getOrNull()
                val diagnostic = detail?.replace(Regex("\\s+"), " ")?.take(300)
                    ?: if (body.isBlank()) null else "Non-JSON response body; check the configured URL and tunnel response."
                val suffix = diagnostic?.let { ": $it" }.orEmpty()
                error("Cloud Browser Use returned HTTP ${response.code}$suffix")
            }
            check(body.isNotBlank()) { "Browser Use returned an empty response; the task was not marked successful." }
            val result = runCatching {
                val json = JSONObject(body)
                json.optString("result").takeIf { it.isNotBlank() }
                    ?: json.optString("response").takeIf { it.isNotBlank() }
                    ?: json.optString("output").takeIf { it.isNotBlank() }
                    ?: body
            }.getOrDefault(body)
            repository.recordOutcome(task.id, true, outputRedacted = result.take(20_000), event = "openswarm_cloud")
            _message.value = "Browser Use completed with an actual result."
        }
    }

    private suspend fun executeTermux(task: WorkspaceTaskEntity) = withContext(Dispatchers.IO) {
        val source = promptOf(task)
        val language = task.toolId.orEmpty().substringAfterLast(':', "bash").let { if (it == "TERMUX_SCRIPT") "bash" else it }
        val id = termux.runCode(language, source).getOrThrow()
        val result = withTimeout(120_000) { termux.results.first { rows -> rows.any { it.executionId == id } }.first { it.executionId == id } }
        val output = buildString {
            append("exitCode=").append(result.exitCode ?: "unknown")
            if (result.stdout.isNotBlank()) append("\nstdout:\n").append(result.stdout)
            if (result.stderr.isNotBlank()) append("\nstderr:\n").append(result.stderr)
            result.error?.let { append("\nerror:\n").append(it) }
        }.take(20_000)
        check(result.succeeded) { "Termux script failed: ${result.error ?: "exit code ${result.exitCode}"}" }
        repository.recordOutcome(task.id, true, outputRedacted = output, event = "termux_script")
        _message.value = "Termux script completed; actual stdout/stderr and exit status were captured."
    }

    private fun executeLocalAi(task: WorkspaceTaskEntity) {
        val storedTarget = task.toolId.orEmpty().substringAfterLast(':')
        val packageName = LocalAiProvider.entries.firstOrNull { it.name == storedTarget }?.packageName ?: storedTarget
        require(packageName.isNotBlank()) { "Select an installed AI app before running this task." }
        @Suppress("DEPRECATION")
        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }
            .getOrElse { error("The selected app is no longer installed. Select another installed AI app.") }
        val appLabel = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        val started = OpenDroidAccessibilityService.startLocalAiTask(
            taskId = task.id,
            packageName = packageName,
            appLabel = appLabel,
            prompt = promptOf(task),
        )
        check(started) {
            "Accessibility automation is not connected, or $appLabel has no launchable activity. Enable the service and retry."
        }
        _message.value = "$appLabel opened. Waiting for its real response."
    }

    private fun modeOf(task: WorkspaceTaskEntity): TaskExecutionMode? = when {
        task.toolId?.startsWith("hybrid:LOCAL_AI_APP:") == true -> TaskExecutionMode.LOCAL_AI_APP
        task.toolId == "hybrid:CLOUD_OPENSWARM" -> TaskExecutionMode.CLOUD_OPENSWARM
        task.toolId?.startsWith("hybrid:TERMUX_SCRIPT") == true -> TaskExecutionMode.TERMUX_SCRIPT
        else -> null
    }

    private fun promptOf(task: WorkspaceTaskEntity): String = runCatching {
        JSONObject(task.inputJson).optString("prompt").ifBlank { task.description.ifBlank { task.title } }
    }.getOrDefault(task.description.ifBlank { task.title })

    private fun hasValidatedInternet(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
