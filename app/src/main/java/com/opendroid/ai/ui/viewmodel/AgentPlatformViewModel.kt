package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agentplatform.AgentPlatform
import com.opendroid.ai.core.agentplatform.AgentPlatformCatalog
import com.opendroid.ai.core.agentplatform.AgentPlatformTaskEntity
import com.opendroid.ai.core.agentplatform.AgentPlatformTaskRepository
import com.opendroid.ai.core.agentplatform.OhEvent
import com.opendroid.ai.core.agentplatform.OpenHandsClient
import com.opendroid.ai.core.agentplatform.PlatformConnection
import com.opendroid.ai.core.agentplatform.PlatformConnectionStore
import com.opendroid.ai.core.agentplatform.PlatformResult
import com.opendroid.ai.core.agentplatform.SessionKeyHint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val history: List<AgentPlatformTaskEntity> = emptyList(),
    val cancelling: Boolean = false,
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
) : ViewModel() {

    private val _state = MutableStateFlow(AgentPlatformUiState())
    val state: StateFlow<AgentPlatformUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        val existing = connectionStore.connection(AgentPlatformCatalog.openHands.id)
        _state.value = _state.value.copy(
            connection = existing,
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
        val url = baseUrl.trim()
        val key = sessionKey.trim()
        if (url.isBlank()) {
            _state.value = _state.value.copy(connectionError = "Enter the agent server URL, for example http://192.168.1.10:3000")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.value = _state.value.copy(connectionError = "The server URL must start with http:// or https://")
            return
        }
        if (key.isBlank()) {
            _state.value = _state.value.copy(connectionError = "Enter the agent server session key")
            return
        }
        _state.value = _state.value.copy(connecting = true, connectionError = null)
        viewModelScope.launch {
            val client = OpenHandsClient(url, key)
            when (val validation = client.validateConnection()) {
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
                    val model = ((client.fetchAgentSettings() as? PlatformResult.Success)
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
        )
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
            val settings = (client.fetchAgentSettings() as? PlatformResult.Success)?.value
            when (val created = client.startConversation(prompt, workingDir, settings, run = true)) {
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
                val conversation = client.getConversation(conversationId)
                if (conversation is PlatformResult.Failure) {
                    _state.value = _state.value.copy(
                        submitError = "Lost contact with the server: ${conversation.message}",
                        taskState = "disconnected",
                    )
                    return@launch
                }
                val status = (conversation as PlatformResult.Success).value.executionStatus ?: "unknown"

                val eventsResult = client.searchEvents(conversationId)
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
                    (client.finalResponse(conversationId) as? PlatformResult.Success)?.value
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
            val conversation = client.getConversation(conversationId)
            if (conversation is PlatformResult.Success) {
                val events = (client.searchEvents(conversationId) as? PlatformResult.Success)?.value ?: emptyList()
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
            when (val result = client.interrupt(conversationId)) {
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

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val MAX_POLLS = 200

        /** `paused` is what the server reports after a successful interrupt. */
        val TERMINAL_STATES = setOf("finished", "error", "stopped", "paused")
    }
}
