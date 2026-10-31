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
import com.opendroid.ai.core.browser.BrowserUseRemoteProtocol
import com.opendroid.ai.core.browser.CloudServerClient
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.data.repository.CloudServerConfig
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.io.IOException
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
    private val cloudServerClient: CloudServerClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val tasks: StateFlow<List<WorkspaceTaskEntity>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val openSwarmServerUrl: StateFlow<String> = settingsRepository.openSwarmServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val openSwarmClientToken: StateFlow<String> = settingsRepository.openSwarmClientToken
    val cloudServerUrls: StateFlow<List<String>> = settingsRepository.cloudServerUrls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf("", "", ""))
    val cloudServerEnabled: StateFlow<List<Boolean>> = settingsRepository.cloudServerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(true, false, false))
    val cloudServerTokens: StateFlow<List<String>> = settingsRepository.cloudServerTokens
    val cloudServers: StateFlow<List<CloudServerConfig>> = settingsRepository.cloudServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

    fun saveCloudServerUrl(index: Int, url: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.saveCloudServerUrl(index, url) }
                .onFailure { _message.value = it.message ?: "Unable to save server URL." }
        }
    }

    fun setCloudServerEnabled(index: Int, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudServerEnabled(index, enabled) }
    }

    fun saveNamedCloudServer(server: CloudServerConfig, token: String) {
        viewModelScope.launch { runCatching { settingsRepository.saveCloudServer(server, token) }.onFailure { _message.value = it.message } }
    }

    fun setNamedCloudServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudServerEnabled(id, enabled) }
    }

    fun saveOpenSwarmClientToken(token: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.saveOpenSwarmClientToken(token) }
                .onSuccess { _message.value = if (token.isBlank()) "Bridge token cleared from secure storage." else "Bridge token saved in Android secure storage." }
                .onFailure { _message.value = it.message ?: "Unable to save the bridge token." }
        }
    }

    fun saveCloudServerToken(index: Int, token: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.saveCloudServerToken(index, token) }
                .onFailure { _message.value = it.message ?: "Unable to save server token." }
        }
    }

    fun setAutoRunGeneratedCode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoRunGeneratedCode(enabled) }
    }

    fun runBrowserUse(prompt: String, useFmhy: Boolean, maxSteps: Int = 15, thinkHarder: Boolean = false) {
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
                    val body = postToCloudServers(
                        taskId = "browser-${System.currentTimeMillis()}",
                        prompt = requestPrompt,
                        pluginId = if (useFmhy) BrowserUseRemoteProtocol.FMHY_PLUGIN_ID else "openswarm_agent",
                        target = "auto",
                        maxSteps = maxSteps,
                        thinkHarder = thinkHarder,
                    )
                    val parsed = BrowserUseRemoteProtocol.parseSuccess(body)
                    BrowserUseRunState(BrowserUseRunState.Phase.SUCCEEDED, parsed.result.take(20_000), steps = parsed.steps)
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
        cloudTarget: String = "auto",
        onSaved: () -> Unit = {},
    ) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            val parsed = TaskQuickAddParser.parse(rawText)
            val title = parsed.title.ifBlank { rawText.trim() }
            val tool = when (mode) {
                TaskExecutionMode.LOCAL_AI_APP -> "hybrid:LOCAL_AI_APP:$providerPackage"
                TaskExecutionMode.CLOUD_OPENSWARM -> "hybrid:CLOUD_OPENSWARM:${cloudTarget.lowercase()}"
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

    /** Handles a Kanban drop using the same guarded state machine as the Run action. */
    fun dropTaskOnColumn(task: WorkspaceTaskEntity, column: String) {
        when (column.uppercase()) {
            "RUNNING" -> runTask(task)
            "DRAFT" -> _message.value = "Draft is the inbox state; move a task to RUNNING to execute it."
            "SUCCEEDED" -> _message.value = "Succeeded is recorded only after the task returns a real result."
            else -> _message.value = "Unsupported Kanban column."
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
        val target = task.toolId.orEmpty().substringAfterLast(':', "auto")
        val body = postToCloudServers(task.id, promptOf(task), "openswarm_agent", target, 15, thinkHarder = false)
        val result = runCatching { JSONObject(body).optString("result").takeIf { it.isNotBlank() } ?: body }.getOrDefault(body)
        repository.recordOutcome(task.id, true, outputRedacted = result.take(20_000), event = "openswarm_cloud")
        _message.value = "OpenSwarm completed with an actual result."
    }

    private suspend fun postToCloudServers(taskId: String, prompt: String, pluginId: String, target: String, maxSteps: Int, thinkHarder: Boolean = false): String {
        val targetId = when (target.lowercase()) {
            "server1", "server 1" -> "legacy-1"
            "server2", "server 2" -> "legacy-2"
            "server3", "server 3" -> "legacy-3"
            else -> null
        }
        return cloudServerClient.execute(
            taskId = taskId,
            prompt = prompt,
            pluginId = pluginId,
            targetId = targetId,
            thinkHarder = thinkHarder,
            onLog = { log -> _message.value = log },
        )
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
        task.toolId?.startsWith("hybrid:CLOUD_OPENSWARM") == true -> TaskExecutionMode.CLOUD_OPENSWARM
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
