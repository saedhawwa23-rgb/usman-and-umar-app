package com.opendroid.ai.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.ChatToolCall
import com.opendroid.ai.core.agent.ChatErrorPrimaryAction
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.primaryAction
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.data.repository.ChatSession
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.core.termux.TermuxIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val termuxIntegration: TermuxIntegration,
) : ViewModel() {

    val llmConfig: StateFlow<LLMConfig> = settingsRepository.llmConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LLMConfig())

    // Scoped to whichever session is current; re-collects automatically when the
    // user switches chats since the repository's flow is driven off the same
    // reactive "current session" pointer that switchToSession() flips.
    val conversationHistory: StateFlow<List<ChatMessage>> = conversationRepository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Every chat session, most recently active first. */
    val sessions: StateFlow<List<ChatSession>> = conversationRepository.sessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Id of whichever session is current; null only before the very first session exists. */
    val currentSessionId: StateFlow<String?> = conversationRepository.currentSessionId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val agentState: StateFlow<AgentState> = agentLoop.agentState

    /**
     * [AgentLoop.chatError], but scoped to whichever chat is on screen - the same rule
     * [visibleAgentState] applies to the shared agent state. An error raised by a task
     * in chat A must never render its recovery card inside chat B; the underlying error
     * stays published so switching back to its own chat shows it again.
     */
    val chatError: StateFlow<ChatErrorUiState?> = combine(
        agentLoop.chatError, sessions
    ) { error, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        error?.takeIf { it.sessionId == current }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun dismissChatError() {
        agentLoop.dismissChatError()
    }

    fun retryAfterChatError(context: Context) {
        val error = agentLoop.chatError.value ?: return
        when (error.primaryAction()) {
            ChatErrorPrimaryAction.RETRY -> {
                agentLoop.dismissChatError()
                // Re-execute the request the error actually describes, in ITS session -
                // never re-send the visible chat's last message, and never insert a
                // duplicate user bubble: the original message is already persisted.
                _taskSessionId.value = error.sessionId
                agentLoop.retryRequest(error.requestId, error.sessionId, context)
            }
            else -> agentLoop.dismissChatError()
        }
    }

    // Session the most recently started task was pinned to. Set once, right when a task
    // is kicked off (sendMessage for a genuinely new query, approvePlan, or
    // editAndResend's resend) - mirroring AgentLoop's own "resolve the session once, at
    // task start" rule so [agentState], which is a single field shared by every chat,
    // can be attributed back to the chat it actually belongs to. See [visibleAgentState].
    private val _taskSessionId = MutableStateFlow<String?>(null)

    /**
     * [agentState], but forced to [AgentState.Idle] whenever it actually describes a
     * task pinned to a DIFFERENT chat than whichever one is current. Since a task now
     * keeps running (and writing) in the chat it started in even after the user
     * switches away, the shared [agentState] alone can't distinguish "busy in this
     * chat" from "busy in some other chat" - this is what any UI representing the
     * CURRENTLY VIEWED chat (thinking bubble, plan-approval prompt, orb pulse, status
     * text) must read instead of [agentState] directly, or it would misattribute a
     * background chat's activity to whatever the user happens to be looking at.
     */
    val visibleAgentState: StateFlow<AgentState> = combine(
        agentState, _taskSessionId, sessions
    ) { state, taskSessionId, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        if (taskSessionId == null || taskSessionId == current) state else AgentState.Idle
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AgentState.Idle
    )

    /**
     * Id of whichever chat has an actively in-flight task (thinking or executing a plan
     * step), or null when nothing is running anywhere. Unlike [visibleAgentState] this
     * is NOT scoped to the currently viewed chat - it drives the chat-picker's "still
     * running" indicator, which must stay visible for that chat even while the user has
     * switched away from it.
     */
    val runningSessionId: StateFlow<String?> = combine(agentState, _taskSessionId) { state, sessionId ->
        if (sessionId != null && (state is AgentState.Thinking || state is AgentState.ExecutingPlan)) {
            sessionId
        } else {
            null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Snapshots which chat is current right now and records it as the session the next
    // task will be pinned to. Reads the live-collected [sessions] snapshot for the same
    // reason [isCurrentSession] does - [sessions] is guaranteed to be actively collected
    // by ChatScreen, [currentSessionId] is not. Returns the same id so callers can pass
    // it straight through to AgentLoop.processQuery instead of it re-resolving "current"
    // itself at an arbitrary later moment.
    private fun pinTaskSessionSnapshot(): String? {
        val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
        _taskSessionId.value = sessionId
        return sessionId
    }

    fun sendMessage(query: String, context: Context, imageBase64: String? = null) {
        if (query.isBlank()) return
        val sessionId = pinTaskSessionSnapshot()
        agentLoop.processQuery(query, context, sessionId, imageBase64)
    }

    /** Runs only after the user presses the per-message approval button. */
    fun approveChatToolCall(call: ChatToolCall, context: Context) {
        val targetSession = sessions.value.firstOrNull { it.isCurrent }?.id
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { executeApprovedChatTool(call, context) } }
                .fold(onSuccess = { it }, onFailure = { "Action failed: ${it.message ?: "Unknown error."}" })
            val sessionId = targetSession ?: conversationRepository.ensureCurrentSessionId()
            conversationRepository.insertMessage(
                sessionId,
                ChatMessage(id = UUID.randomUUID().toString(), text = result.take(12_000), sender = ChatMessage.Sender.AGENT),
            )
        }
    }

    private suspend fun executeApprovedChatTool(call: ChatToolCall, context: Context): String {
        val packageManager = context.packageManager
        return when (call.action) {
            "shell" -> {
                val command = call.command?.trim().orEmpty()
                require(command.isNotBlank()) { "The proposed shell action has no command." }
                when {
                    Regex("^pm\\s+list\\s+packages(?:\\s+-3)?$").matches(command) -> {
                        val thirdPartyOnly = command.endsWith("-3")
                        val apps = installedApplications(packageManager)
                            .filter { !thirdPartyOnly || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                            .map { it.packageName }.sorted()
                        "Native PackageManager result (${apps.size} package(s)):\n" +
                            apps.take(500).joinToString("\n").ifBlank { "No packages found." }
                    }
                    command in setOf("getprop ro.build.version.release", "getprop ro.build.version.sdk", "getprop ro.product.model") ->
                        nativeDeviceProperty(command)
                    command == "dumpsys battery" -> nativeBatteryStatus(context)
                    command == "settings get system screen_brightness" -> {
                        val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                        "Screen brightness setting: ${if (value >= 0) value else "unavailable"} (0–255 scale)."
                    }
                    command == "wm size" -> {
                        val metrics = context.resources.displayMetrics
                        "Current app display metrics: ${metrics.widthPixels} x ${metrics.heightPixels} pixels."
                    }
                    Regex("^(python3?|python[0-9.]*|git|npm|node)(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> {
                        require(!Regex("[;&|<>`\\n\\r$]").containsMatchIn(command)) {
                            "Only one simple Linux command may be approved at a time; shell chaining and redirection are blocked."
                        }
                        executeExplicitLinuxTask(command)
                    }
                    command.startsWith("open app ", ignoreCase = true) ->
                        openInstalledApp(context, command.substringAfter("open app ").trim())
                    command.startsWith("open url ", ignoreCase = true) ->
                        openUrl(context, command.substringAfter("open url ").trim())
                    else -> error("Unsupported shell action. Native mode supports read-only Android queries, 'pm list packages', 'open app …', and 'open url …'. Python/Git/npm/Node tasks require Termux.")
                }
            }
            "pm_list_packages", "list_packages" -> {
                val apps = installedApplications(packageManager).map { it.packageName }.sorted()
                "Native PackageManager result (${apps.size} package(s)):\n" +
                    apps.take(500).joinToString("\n").ifBlank { "No packages found." }
            }
            "open_app", "launch_app" -> {
                val target = call.packageName ?: call.appName
                require(!target.isNullOrBlank()) { "Specify an app name or package name." }
                openInstalledApp(context, target)
            }
            "open_url", "open_uri" -> {
                val url = call.url ?: call.appName
                require(!url.isNullOrBlank()) { "Specify an HTTP or HTTPS URL." }
                openUrl(context, url)
            }
            "device_info", "android_device_info" -> nativeDeviceProperty("getprop ro.build.version.release")
            "battery_status", "get_battery_status" -> nativeBatteryStatus(context)
            "network_status", "get_network_status" -> nativeNetworkStatus(context)
            "screen_brightness" -> {
                val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                "Screen brightness setting: ${if (value >= 0) value else "unavailable"} (0–255 scale)."
            }
            else -> error("Unsupported tool action '${call.action}'. No action was executed.")
        }
    }

    private fun nativeDeviceProperty(command: String): String = when (command) {
        "getprop ro.build.version.release" -> "Android release: ${Build.VERSION.RELEASE}."
        "getprop ro.build.version.sdk" -> "Android API level: ${Build.VERSION.SDK_INT}."
        "getprop ro.product.model" -> "Device model: ${Build.MANUFACTURER} ${Build.MODEL}."
        else -> error("Unsupported Android property query.")
    }

    private fun nativeBatteryStatus(context: Context): String {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: error("Battery status is unavailable on this device.")
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown status"
        }
        return "Battery: ${if (percent in 0..100) "$percent%" else "unavailable"}, $statusLabel."
    }

    private fun nativeNetworkStatus(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: error("Network status is unavailable on this device.")
        val network = connectivity.activeNetwork ?: return "No active network connection."
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return "An active network is present, but its capabilities could not be read."
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "Active network: $transport; internet validated: ${if (validated) "yes" else "no"}."
    }

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private suspend fun openInstalledApp(context: Context, target: String): String {
        val packageManager = context.packageManager
        val installed = installedApplications(packageManager)
        val application = installed.firstOrNull { it.packageName.equals(target, ignoreCase = true) }
            ?: installed.filter { packageManager.getApplicationLabel(it).toString().equals(target, ignoreCase = true) }
                .singleOrNull()
            ?: error("No installed app matched '$target' uniquely. Use its exact package name if multiple apps share that label.")
        val intent = packageManager.getLaunchIntentForPackage(application.packageName)
            ?: error("${application.packageName} has no launchable activity.")
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        return "Opened ${packageManager.getApplicationLabel(application)} (${application.packageName}) using an Android launch intent."
    }

    private suspend fun openUrl(context: Context, rawUrl: String): String {
        val uri = Uri.parse(rawUrl.trim())
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Only HTTP and HTTPS URLs can be opened."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "The URL is invalid or contains embedded credentials." }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        require(intent.resolveActivity(context.packageManager) != null) { "No installed app can open this URL." }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        return "Opened URL: $uri"
    }

    private suspend fun executeExplicitLinuxTask(command: String): String {
        check(termuxIntegration.isInstalled()) {
            "This explicitly requested Linux task needs Termux. Install Termux, then approve the action again."
        }
        val id = termuxIntegration.runCode("bash", command).getOrElse { throw it }
        val result = withTimeoutOrNull(120_000) {
            termuxIntegration.results.first { rows -> rows.any { it.executionId == id } }
                .first { it.executionId == id }
        } ?: error("Termux did not return a result before timeout.")
        return buildString {
            append("Termux result · exitCode=").append(result.exitCode ?: "unknown")
            if (result.stdout.isNotBlank()) append("\nstdout:\n").append(result.stdout)
            if (result.stderr.isNotBlank()) append("\nstderr:\n").append(result.stderr)
            result.error?.let { append("\nerror:\n").append(it) }
        }
    }

    /**
     * Edits a previously sent user message and resends it, DeepSeek-style: cancels any
     * in-flight agent task first (so a running/awaiting-input task can never race with
     * or swallow the resend), deletes that message and every message after it in the
     * current session so the conversation visibly rewinds, then sends the edited text
     * as a fresh query.
     */
    fun editAndResend(messageId: String, newText: String, context: Context, imageBase64: String? = null) {
        if (newText.isBlank()) return
        agentLoop.cancelCurrentTask()
        viewModelScope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            conversationRepository.deleteMessageAndAfter(sessionId, messageId)
            _taskSessionId.value = sessionId
            agentLoop.processQuery(newText, context, sessionId, imageBase64)
        }
    }

    /** Deletes one visible message without affecting the rest of the conversation. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
        }
    }

    fun approvePlan(context: Context, grants: Set<String> = emptySet()) {
        pinTaskSessionSnapshot()
        agentLoop.approveProposedPlan(context, grants)
    }

    /** Chip tap: Off <-> Auto only. YOLO is entered solely from Settings behind
     *  its warning dialog and exits to OFF from here (tapping the red chip is
     *  an explicit "get me out of YOLO", which is always safe). */
    fun cycleAutoMode() {
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                val next = when (current.resolvedAutoMode()) {
                    AutoMode.OFF -> AutoMode.AUTO
                    AutoMode.AUTO -> AutoMode.OFF
                    AutoMode.YOLO -> AutoMode.OFF
                }
                current.copy(autoMode = next, autoConfirmPlans = next == AutoMode.YOLO)
            }
        }
    }

    fun rejectPlan() {
        agentLoop.rejectProposedPlan()
    }

    /** Clears the messages of the CURRENT chat only - other chats are untouched. */
    fun clearChat() {
        agentLoop.cancelCurrentTask()
        viewModelScope.launch {
            conversationRepository.clearCurrentSession()
        }
    }

    /**
     * Starts a brand-new, empty chat and switches to it. Deliberately does NOT cancel
     * whatever the agent is currently doing: since a task's session is pinned once at
     * its start and threaded through explicitly, it keeps running - and keeps writing
     * its replies - in the chat it was launched from, not wherever the user navigates
     * to afterwards. [runningSessionId] / [visibleAgentState] are how the UI stays
     * truthful about that chat still being busy while this one is not.
     */
    fun newChat() {
        viewModelScope.launch {
            conversationRepository.createSession()
        }
    }

    /**
     * Switches the active chat; [conversationHistory] re-collects for the new session.
     * Deliberately does NOT cancel an in-flight task for the same reason [newChat] does
     * not - see its doc comment.
     */
    fun switchToSession(sessionId: String) {
        if (isCurrentSession(sessionId)) return
        viewModelScope.launch {
            conversationRepository.switchToSession(sessionId)
        }
    }

    /** Renames a chat without changing which one is current. */
    fun renameSession(sessionId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            conversationRepository.renameSession(sessionId, title.trim())
        }
    }

    /**
     * Deletes a chat and its messages. If it was the currently open chat, OR it is
     * whichever chat [runningSessionId] says has a task actively writing into it right
     * now, that task is cancelled first - deleting it out from under a still-running
     * task would otherwise leave that task writing into a session that no longer
     * exists. The repository guarantees another session becomes current - the user is
     * never left with no chat selected.
     */
    fun deleteChat(sessionId: String) {
        if (isCurrentSession(sessionId) || runningSessionId.value == sessionId) {
            agentLoop.cancelCurrentTask()
        }
        agentLoop.forgetSession(sessionId)
        viewModelScope.launch {
            conversationRepository.deleteSession(sessionId)
            conversationRepository.ensureCurrentSessionId()
        }
    }

    // Reads the live-collected [sessions] snapshot rather than [currentSessionId].value:
    // currentSessionId is exposed for callers that actually collect it, but nothing in
    // this ViewModel guarantees it is being collected, and an un-collected
    // WhileSubscribed StateFlow never advances past its initial value. [sessions] is
    // always actively collected by ChatScreen (it drives the chat picker itself), so by
    // the time a user can tap switch/delete on a session row, its value is current.
    private fun isCurrentSession(sessionId: String): Boolean =
        sessions.value.any { it.id == sessionId && it.isCurrent }

    /**
     * Cancels whatever the agent is currently doing without touching the
     * conversation history. Shared by the Plan tab and the chat UI's stop control.
     */
    fun stopTask() {
        agentLoop.cancelCurrentTask()
    }
}
