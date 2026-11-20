package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agentplatform.AgentPlatform
import com.opendroid.ai.core.agentplatform.AgentPlatformCatalog
import com.opendroid.ai.core.agentplatform.AgentPlatformOrchestrator
import com.opendroid.ai.core.agentplatform.AgentTaskState
import com.opendroid.ai.core.agentplatform.AgentPlatformTaskEntity
import com.opendroid.ai.core.agentplatform.AgentPlatformTaskRepository
import com.opendroid.ai.core.agentplatform.OhEvent
import com.opendroid.ai.core.agentplatform.OpenHandsClient
import com.opendroid.ai.core.agentplatform.OpenSwarmClient
import com.opendroid.ai.core.agentplatform.PlatformConnection
import com.opendroid.ai.core.agentplatform.PlatformConnectionStore
import com.opendroid.ai.core.agentplatform.PlatformResult
import com.opendroid.ai.core.agentplatform.PlatformUrlPolicy
import com.opendroid.ai.core.agentplatform.SessionKeyHint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject

/** One line in the live timeline, already reduced to display text. */
data class TimelineEntry(
    val id: String,
    val kind: String,
    val text: String,
    val isError: Boolean,
)

data class AgentPlatformUiState(
    val query: String = "",
    val platforms: List<AgentPlatform> = AgentPlatformCatalog.all,
    val connection: PlatformConnection? = null,
    /** The stored OpenSwarm backend connection, kept separate from the OpenHands one. */
    val openSwarmConnection: PlatformConnection? = null,
    val connecting: Boolean = false,
    val connectionError: String? = null,
    val prompt: String = "",
    val workingDir: String = "",
    val submitting: Boolean = false,
    val submitError: String? = null,
    val activeTaskId: String? = null,
    val activeConversationId: String? = null,
    val taskState: String = "IDLE",
    val timeline: List<TimelineEntry> = emptyList(),
    val finalResponse: String? = null,
    val attachmentRemotePath: String? = null,
    val fileDownloadPath: String = "",
    val fileTransferBusy: Boolean = false,
    val fileTransferMessage: String? = null,
    val history: List<AgentPlatformTaskEntity> = emptyList(),
    val cancelling: Boolean = false,
    /** Platform the last orchestrated run targeted, so the UI can name it. */
    val activePlatformId: String? = null,
    /** Result line of the last orchestrated run; null until one runs. */
    val orchestrationMessage: String? = null,
) {
    val isConnected: Boolean get() = connection != null
    val isRunning: Boolean get() = taskState == "running" || taskState == "queued"
}

/**
 * Drives the agent-platform feature against a real server.
 *
 * Every transition in this class comes from an HTTP response or a persisted record. There is no
 * simulated success path: if the server is unreachable, the state carries the server's message.
 */
@HiltViewModel
class AgentPlatformViewModel @Inject constructor(
    private val connectionStore: PlatformConnectionStore,
    private val taskRepository: AgentPlatformTaskRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentPlatformUiState())
    val state: StateFlow<AgentPlatformUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    private val orchestrator = AgentPlatformOrchestrator(connectionStore)

    /**
     * Runs the current prompt through the shared adapter contract on [platformId].
     *
     * This is the unified path: the same code drives OpenHands, OpenSwarm and an MCP server. It
     * reports the platform's own reason for a refusal, and never ends with a silent "stopped".
     */
    fun runOnPlatform(platformId: String) {
        val prompt = _state.value.prompt.trim()
        if (prompt.isEmpty()) {
            _state.value = _state.value.copy(submitError = "Enter a task before running it.")
            return
        }
        _state.value = _state.value.copy(
            submitting = true,
            submitError = null,
            orchestrationMessage = null,
            activePlatformId = platformId,
        )
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                orchestrator.runTask(platformId, prompt, _state.value.workingDir.ifBlank { "/workspace" })
            }
            _state.value = _state.value.copy(
                submitting = false,
                taskState = outcome.state.name.lowercase(),
                activeConversationId = outcome.sessionId,
                timeline = outcome.events.map { event ->
                    TimelineEntry(
                        id = "${outcome.platformId}-${event.sequence}",
                        kind = event.state.name.lowercase(),
                        text = event.message,
                        isError = event.state == AgentTaskState.FAILED,
                    )
                },
                orchestrationMessage = outcome.failure ?: when (outcome.state) {
                    AgentTaskState.COMPLETE -> "${outcome.platformName} finished the task."
                    AgentTaskState.UNKNOWN ->
                        "${outcome.platformName} did not report a state, so the outcome is unknown."
                    AgentTaskState.CANCELLED -> "${outcome.platformName} cancelled the task."
                    else -> "${outcome.platformName} reported ${outcome.state.name.lowercase()}."
                },
                submitError = outcome.failure,
            )
        }
    }

    fun dismissOrchestrationMessage() {
        _state.value = _state.value.copy(orchestrationMessage = null)
    }

    init {
        val existing = connectionStore.connection(AgentPlatformCatalog.openHands.id)
        _state.value = _state.value.copy(
            connection = existing,
            openSwarmConnection = connectionStore.connection(AgentPlatformCatalog.openSwarm.id),
            workingDir = existing?.let { "/tmp/agent-workspace" } ?: "",
        )
        viewModelScope.launch {
            taskRepository.observeAll().collect { tasks ->
                _state.value = _state.value.copy(history = tasks)
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query, platforms = AgentPlatformCatalog.search(query))
    }

    fun onPromptChange(prompt: String) {
        _state.value = _state.value.copy(prompt = prompt)
    }

    fun onWorkingDirChange(dir: String) {
        _state.value = _state.value.copy(workingDir = dir)
    }

    fun onFileDownloadPathChange(path: String) {
        _state.value = _state.value.copy(fileDownloadPath = path)
    }

    fun dismissConnectionError() {
        _state.value = _state.value.copy(connectionError = null)
    }

    fun dismissSubmitError() {
        _state.value = _state.value.copy(submitError = null)
    }

    /**
     * Validates and stores a connection. The key is verified against the server first; a bad key is
     * never persisted, so the UI cannot show "connected" for credentials that do not work.
     */
    fun connect(baseUrl: String, sessionKey: String) {
        val key = sessionKey.trim()
        val url = when (
            val verdict = PlatformUrlPolicy.check(
                baseUrl,
                "Enter an HTTPS agent-server URL. HTTP is suitable only for local loopback testing.",
            )
        ) {
            is PlatformUrlPolicy.Verdict.Rejected -> {
                _state.value = _state.value.copy(connectionError = verdict.message)
                return
            }
            is PlatformUrlPolicy.Verdict.Accepted -> verdict.url.toString().trimEnd('/')
        }
        if (key.isBlank()) {
            _state.value = _state.value.copy(connectionError = "Enter the agent server session key")
            return
        }
        _state.value = _state.value.copy(connecting = true, connectionError = null)
        viewModelScope.launch {
            val client = OpenHandsClient(url, key)
            when (val validation = withContext(Dispatchers.IO) { client.validateConnection() }) {
                is PlatformResult.Unsupported -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        connectionError = "This platform does not support ${validation.operation}",
                    )
                }
                is PlatformResult.Failure -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        connectionError = "Could not connect: ${validation.message}",
                    )
                }
                else -> {
                    val model = ((withContext(Dispatchers.IO) { client.fetchAgentSettings() } as? PlatformResult.Success)
                        ?.value?.get("llm") as? kotlinx.serialization.json.JsonObject)
                        ?.get("model")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    val connection = PlatformConnection(
                        platformId = AgentPlatformCatalog.openHands.id,
                        displayName = AgentPlatformCatalog.openHands.displayName,
                        baseUrl = url,
                        connectedAtMillis = System.currentTimeMillis(),
                        lastValidatedAtMillis = System.currentTimeMillis(),
                        lastValidationOk = true,
                        keyHint = SessionKeyHint.of(key),
                        serverModel = model,
                    )
                    val saved = connectionStore.save(connection, key)
                    _state.value = _state.value.copy(
                        connecting = false,
                        connection = if (saved) connection else null,
                        connectionError = if (saved) null else "Could not store the key in the device keystore",
                    )
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        connectionStore.remove(AgentPlatformCatalog.openHands.id)
        _state.value = _state.value.copy(
            connection = null,
            activeTaskId = null,
            activeConversationId = null,
            taskState = "IDLE",
            timeline = emptyList(),
            finalResponse = null,
            attachmentRemotePath = null,
        )
    }

    /**
     * Validates and stores an OpenSwarm backend connection.
     *
     * The token is checked against `/capabilities` before anything is persisted, and the backend's
     * `/health` verdict is kept so the screen can show that task execution is still blocked until
     * the server reports ready. Nothing here implies a working task pipeline when it is not.
     */
    fun connectOpenSwarm(baseUrl: String, appToken: String) {
        val token = appToken.trim()
        val url = when (
            val verdict = PlatformUrlPolicy.check(baseUrl, "Enter the OpenSwarm backend URL.")
        ) {
            is PlatformUrlPolicy.Verdict.Rejected -> {
                _state.value = _state.value.copy(connectionError = verdict.message)
                return
            }
            is PlatformUrlPolicy.Verdict.Accepted -> verdict.url.toString().trimEnd('/')
        }
        if (token.isBlank()) {
            _state.value = _state.value.copy(connectionError = "Enter the backend's APP_CLIENT_TOKEN")
            return
        }
        _state.value = _state.value.copy(connecting = true, connectionError = null)
        viewModelScope.launch {
            val client = OpenSwarmClient(url, token)
            when (val validation = withContext(Dispatchers.IO) { client.validateConnection() }) {
                is PlatformResult.Failure -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        connectionError = "Could not connect: ${validation.message}",
                    )
                }
                is PlatformResult.Unsupported -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        connectionError = "This platform does not support ${validation.operation}",
                    )
                }
                else -> {
                    val health = (withContext(Dispatchers.IO) { client.health() } as? PlatformResult.Success)?.value
                    val connection = PlatformConnection(
                        platformId = AgentPlatformCatalog.openSwarm.id,
                        displayName = AgentPlatformCatalog.openSwarm.displayName,
                        baseUrl = url,
                        connectedAtMillis = System.currentTimeMillis(),
                        lastValidatedAtMillis = System.currentTimeMillis(),
                        lastValidationOk = true,
                        keyHint = SessionKeyHint.of(token),
                        serverModel = health?.reason,
                    )
                    val saved = connectionStore.save(connection, token)
                    _state.value = _state.value.copy(
                        connecting = false,
                        openSwarmConnection = if (saved) connection else null,
                        connectionError = when {
                            !saved -> "Could not store the token in the device keystore"
                            health != null && !health.ready ->
                                "Connected, but the backend reports it is not ready: ${health.reason ?: "readiness checks failed"}"
                            else -> null
                        },
                    )
                }
            }
        }
    }

    /** Submits the current prompt to the real server and starts polling. */
    fun submitTask() {
        val connection = _state.value.connection
        val prompt = _state.value.prompt.trim()
        val workingDir = _state.value.workingDir.trim()
        if (connection == null) {
            _state.value = _state.value.copy(submitError = "Connect an agent platform first")
            return
        }
        if (prompt.isBlank()) {
            _state.value = _state.value.copy(submitError = "Enter the instructions for the agent")
            return
        }
        if (workingDir.isBlank() || !workingDir.startsWith("/")) {
            _state.value = _state.value.copy(submitError = "Enter an absolute working directory on the server, for example /tmp/agent-workspace")
            return
        }
        val key = connectionStore.sessionKey(connection.platformId)
        if (key == null) {
            _state.value = _state.value.copy(submitError = "The stored session key is unavailable. Reconnect the platform.")
            return
        }

        _state.value = _state.value.copy(
            submitting = true, submitError = null, timeline = emptyList(), finalResponse = null,
        )
        viewModelScope.launch {
            val client = OpenHandsClient(connection.baseUrl, key)
            val settingsResult = withContext(Dispatchers.IO) { client.fetchAgentSettings() }
            if (settingsResult is PlatformResult.Failure) {
                // The server rejects a conversation with no agent (HTTP 422 "One of `agent`,
                // `agent_settings`, or `agent_profile_id` must be provided"), so surface the
                // real reason instead of forwarding an opaque validation error.
                _state.value = _state.value.copy(
                    submitting = false,
                    submitError = "Could not read the server's agent settings: ${settingsResult.message}",
                )
                return@launch
            }
            val settings = (settingsResult as PlatformResult.Success).value
            when (val created = withContext(Dispatchers.IO) {
                client.startConversation(prompt, workingDir, settings, run = true)
            }) {
                is PlatformResult.Unsupported -> {
                    _state.value = _state.value.copy(
                        submitting = false,
                        submitError = "This platform does not support ${created.operation}",
                    )
                }
                is PlatformResult.Failure -> {
                    _state.value = _state.value.copy(
                        submitting = false,
                        submitError = "Could not start the task: ${created.message}",
                    )
                }
                is PlatformResult.Success -> {
                    val conversation = created.value
                    val localId = UUID.randomUUID().toString()
                    _state.value = _state.value.copy(
                        submitting = false,
                        activeTaskId = localId,
                        activeConversationId = conversation.id,
                        taskState = conversation.executionStatus ?: "queued",
                        prompt = "",
                    )
                    taskRepository.record(
                        id = localId,
                        platformId = connection.platformId,
                        conversationId = conversation.id,
                        prompt = prompt,
                        workingDir = workingDir,
                        state = conversation.executionStatus ?: "queued",
                    )
                    startPolling(client, conversation.id, localId)
                }
            }
        }
    }

    private fun startPolling(client: OpenHandsClient, conversationId: String, localTaskId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var lastCount = 0
            var terminalSeen = false
            repeat(MAX_POLLS) {
                val conversation = withContext(Dispatchers.IO) { client.getConversation(conversationId) }
                if (conversation is PlatformResult.Failure) {
                    _state.value = _state.value.copy(
                        submitError = "Lost contact with the server: ${conversation.message}",
                        taskState = "disconnected",
                    )
                    return@launch
                }
                val status = (conversation as PlatformResult.Success).value.executionStatus ?: "unknown"

                val eventsResult = withContext(Dispatchers.IO) { client.searchEvents(conversationId) }
                val events: List<OhEvent> =
                    (eventsResult as? PlatformResult.Success)?.value ?: emptyList()
                val entries = events.map {
                    TimelineEntry(
                        id = it.id,
                        kind = it.kind,
                        text = it.summary(),
                        isError = it.isError(),
                    )
                }
                val errorEvent = events.lastOrNull { it.isError() }

                val finalText = if (status in TERMINAL_STATES) {
                    (withContext(Dispatchers.IO) { client.finalResponse(conversationId) } as? PlatformResult.Success)?.value
                } else null

                _state.value = _state.value.copy(
                    taskState = status,
                    timeline = entries,
                    finalResponse = finalText?.ifBlank { null } ?: _state.value.finalResponse,
                )

                if (status in TERMINAL_STATES) {
                    terminalSeen = true
                    taskRepository.record(
                        id = localTaskId,
                        platformId = AgentPlatformCatalog.openHands.id,
                        conversationId = conversationId,
                        prompt = "",
                        workingDir = _state.value.workingDir,
                        state = status,
                        finalResponse = finalText,
                        lastError = errorEvent?.summary(),
                        eventCount = events.size,
                    )
                    return@launch
                }
                if (events.size != lastCount) lastCount = events.size
                delay(POLL_INTERVAL_MS)
            }
            if (!terminalSeen) {
                _state.value = _state.value.copy(
                    submitError = "Still running after ${MAX_POLLS * POLL_INTERVAL_MS / 1000}s. Pull to refresh, or open the task from history.",
                )
            }
        }
    }

    /** Re-reads the active conversation once, for a manual refresh. */
    fun refreshActiveTask() {
        val connection = _state.value.connection ?: return
        val conversationId = _state.value.activeConversationId ?: return
        val key = connectionStore.sessionKey(connection.platformId) ?: return
        viewModelScope.launch {
            val client = OpenHandsClient(connection.baseUrl, key)
            val conversation = withContext(Dispatchers.IO) { client.getConversation(conversationId) }
            if (conversation is PlatformResult.Success) {
                val events = (withContext(Dispatchers.IO) { client.searchEvents(conversationId) } as? PlatformResult.Success)?.value ?: emptyList()
                _state.value = _state.value.copy(
                    taskState = conversation.value.executionStatus ?: _state.value.taskState,
                    timeline = events.map { TimelineEntry(it.id, it.kind, it.summary(), it.isError()) },
                )
            }
        }
    }

    /** Asks the server to stop. The UI shows the server's answer rather than assuming success. */
    fun cancelActiveTask() {
        val connection = _state.value.connection ?: return
        val conversationId = _state.value.activeConversationId ?: return
        val key = connectionStore.sessionKey(connection.platformId) ?: return
        _state.value = _state.value.copy(cancelling = true)
        viewModelScope.launch {
            val client = OpenHandsClient(connection.baseUrl, key)
            when (val result = withContext(Dispatchers.IO) { client.interrupt(conversationId) }) {
                is PlatformResult.Unsupported -> _state.value = _state.value.copy(
                    cancelling = false,
                    submitError = "This platform does not support ${result.operation}",
                )
                is PlatformResult.Failure -> _state.value = _state.value.copy(
                    cancelling = false,
                    submitError = "Could not cancel: ${result.message}",
                )
                else -> _state.value = _state.value.copy(cancelling = false, taskState = "stopping")
            }
        }
    }

    fun openHistoricalTask(task: AgentPlatformTaskEntity) {
        _state.value = _state.value.copy(
            activeTaskId = task.id,
            activeConversationId = task.conversationId,
            taskState = task.state,
            finalResponse = task.finalResponse,
            submitError = task.lastError,
        )
        refreshActiveTask()
    }

    fun deleteHistoricalTask(id: String) {
        viewModelScope.launch { taskRepository.delete(id) }
    }

    /** Uploads a user-selected file to the server workspace over the authenticated API. */
    fun uploadAttachment(uri: Uri) {
        val connection = _state.value.connection ?: run {
            _state.value = _state.value.copy(fileTransferMessage = "Connect OpenHands before uploading a file")
            return
        }
        val directory = _state.value.workingDir.trim().trimEnd('/')
        if (!directory.startsWith('/')) {
            _state.value = _state.value.copy(fileTransferMessage = "Set an absolute server working directory first")
            return
        }
        val key = connectionStore.sessionKey(connection.platformId) ?: run {
            _state.value = _state.value.copy(fileTransferMessage = "The stored session key is unavailable. Reconnect OpenHands.")
            return
        }
        _state.value = _state.value.copy(fileTransferBusy = true, fileTransferMessage = null)
        viewModelScope.launch {
            var uploadedName = "attachment.bin"
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = selectedFileName(uri).also { uploadedName = it }
                    val bytes = readSelectedFile(uri)
                    OpenHandsClient(connection.baseUrl, key).uploadFile("$directory/$name", name, bytes)
                } catch (e: Exception) {
                    PlatformResult.Failure("Could not read or upload file: ${e.message ?: e::class.simpleName}")
                }
            }
            _state.value = when (result) {
                is PlatformResult.Success -> _state.value.copy(
                    fileTransferBusy = false,
                    attachmentRemotePath = "$directory/$uploadedName",
                    fileTransferMessage = "Uploaded to $directory/$uploadedName. Include this path in your task instructions.",
                )
                is PlatformResult.Failure -> _state.value.copy(fileTransferBusy = false, fileTransferMessage = result.message)
                is PlatformResult.Unsupported -> _state.value.copy(
                    fileTransferBusy = false,
                    fileTransferMessage = "This platform does not support ${result.operation}",
                )
            }
        }
    }

    /** Downloads a known remote file path and writes it to a user-selected document URI. */
    fun downloadRemoteFile(remotePath: String, destination: Uri) {
        val connection = _state.value.connection ?: run {
            _state.value = _state.value.copy(fileTransferMessage = "Connect OpenHands before downloading a file")
            return
        }
        val key = connectionStore.sessionKey(connection.platformId) ?: run {
            _state.value = _state.value.copy(fileTransferMessage = "The stored session key is unavailable. Reconnect OpenHands.")
            return
        }
        _state.value = _state.value.copy(fileTransferBusy = true, fileTransferMessage = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val download = OpenHandsClient(connection.baseUrl, key).downloadFile(remotePath.trim())) {
                    is PlatformResult.Success -> try {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("The selected destination is not writable")
                        output.use { it.write(download.value) }
                        PlatformResult.Success(download.value.size)
                    } catch (e: Exception) {
                        PlatformResult.Failure("Downloaded the file but could not save it: ${e.message ?: e::class.simpleName}")
                    }
                    is PlatformResult.Failure -> download
                    is PlatformResult.Unsupported -> download
                }
            }
            _state.value = when (result) {
                is PlatformResult.Success -> _state.value.copy(
                    fileTransferBusy = false,
                    fileTransferMessage = "Saved ${result.value} bytes from $remotePath",
                )
                is PlatformResult.Failure -> _state.value.copy(fileTransferBusy = false, fileTransferMessage = result.message)
                is PlatformResult.Unsupported -> _state.value.copy(
                    fileTransferBusy = false,
                    fileTransferMessage = "This platform does not support ${result.operation}",
                )
            }
        }
    }

    private fun selectedFileName(uri: Uri): String {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "attachment.bin"
        return displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(120)
            .ifBlank { "attachment.bin" }
    }

    private fun readSelectedFile(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The selected file could not be opened")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("Files larger than 25 MB are not supported")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val MAX_POLLS = 200
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

        /** `paused` is what the server reports after a successful interrupt. */
        val TERMINAL_STATES = setOf("finished", "error", "stopped", "paused")
    }
}
