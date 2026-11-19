package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.agentplatform.AgentPlatform
import com.opendroid.ai.core.agentplatform.PlatformCapability
import com.opendroid.ai.core.agentplatform.IntegrationMode
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.BrandOutlineButton
import com.opendroid.ai.ui.components.EmptyState
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.SectionHeading
import com.opendroid.ai.ui.components.StatusPill
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.AgentPlatformViewModel

/**
 * Web AI agent platforms: discover, connect, submit a task, watch it run, and read the result.
 *
 * Everything shown here is either the server's own response or a persisted record. When a step
 * fails the screen shows the server's message verbatim instead of a generic success.
 */
@Composable
fun AgentPlatformScreen(viewModel: AgentPlatformViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = AppTheme.colors
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::uploadAttachment)
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.downloadRemoteFile(state.fileDownloadPath, it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BrandHeader(
                title = "Agent Platforms",
                subtitle = "Run tasks on a real web AI agent server",
                icon = Icons.Default.Hub,
                trailing = {
                    StatusPill(
                        text = if (state.isConnected) "Connected" else "Not connected",
                        status = if (state.isConnected) PlatformStatus.HEALTHY else PlatformStatus.ERROR,
                    )
                },
            )
        }

        // ── Discovery ──────────────────────────────────────────────────────────
        item { SectionHeading("Discover", subtitle = "Search platforms and check what each one supports") }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Search platforms") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        if (state.platforms.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = "No platforms match",
                    hint = "Try a different search term.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(state.platforms, key = { it.id }) { platform ->
            PlatformDiscoveryCard(platform)
        }

        // ── Connection ─────────────────────────────────────────────────────────
        item { SectionHeading("Connection", subtitle = "Session keys are stored in the device keystore") }
        item {
            val connected = state.connection
            if (connected != null) {
                PlatformCard(
                    title = connected.displayName,
                    subtitle = connected.baseUrl,
                    status = "Verified" to PlatformStatus.HEALTHY,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabeledValue("Key", connected.keyHint)
                        connected.serverModel?.let { LabeledValue("Server model", it) }
                        LabeledValue(
                            "Last validated",
                            connected.lastValidatedAtMillis?.let { formatMillis(it) } ?: "never",
                        )
                        Spacer(Modifier.height(4.dp))
                        BrandOutlineButton(
                            text = "Disconnect",
                            onClick = viewModel::disconnect,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                ConnectForm(
                    connecting = state.connecting,
                    error = state.connectionError,
                    onConnect = viewModel::connect,
                    onDismissError = viewModel::dismissConnectionError,
                )
            }
        }

        // ── OpenSwarm backend ──────────────────────────────────────────────────
        item {
            SectionHeading(
                "OpenSwarm backend",
                subtitle = "The app's own task backend, connected separately from OpenHands",
            )
        }
        item {
            val swarm = state.openSwarmConnection
            if (swarm != null) {
                PlatformCard(
                    title = swarm.displayName,
                    subtitle = swarm.baseUrl,
                    status = "Token verified" to PlatformStatus.HEALTHY,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabeledValue("Token", swarm.keyHint)
                        LabeledValue(
                            "Last validated",
                            swarm.lastValidatedAtMillis?.let { formatMillis(it) } ?: "never",
                        )
                        // The backend reports its own readiness; showing it prevents the screen
                        // from implying tasks can run when the server says they cannot.
                        swarm.serverModel?.let {
                            Text(
                                "Backend reports: $it",
                                fontSize = 11.sp,
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            } else {
                OpenSwarmConnectForm(
                    connecting = state.connecting,
                    error = state.connectionError,
                    onConnect = viewModel::connectOpenSwarm,
                    onDismissError = viewModel::dismissConnectionError,
                )
            }
        }

        // ── Submit ─────────────────────────────────────────────────────────────
        item { SectionHeading("Submit a task", subtitle = "The agent runs these instructions on the server") }
        item {
            PlatformCard(title = "New task") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = viewModel::onPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Instructions") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = state.isConnected && !state.isRunning,
                    )
                    OutlinedTextField(
                        value = state.workingDir,
                        onValueChange = viewModel::onWorkingDirChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Working directory on the server") },
                        singleLine = true,
                        enabled = state.isConnected && !state.isRunning,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrandOutlineButton(
                            text = if (state.fileTransferBusy) "Transferring…" else "Attach file",
                            onClick = { uploadPicker.launch(arrayOf("*/*")) },
                            enabled = state.isConnected && !state.fileTransferBusy,
                            modifier = Modifier.weight(1f),
                        )
                        state.attachmentRemotePath?.let { path ->
                            Text("Uploaded: $path", fontSize = 11.sp, color = colors.textSecondary, modifier = Modifier.weight(2f))
                        }
                    }
                    state.fileTransferMessage?.let { message ->
                        Text(message, fontSize = 11.sp, color = colors.textSecondary)
                    }
                    state.submitError?.let { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                message,
                                color = colors.accentRed,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::dismissSubmitError) { Text("Dismiss") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandButton(
                            text = if (state.submitting) "Starting…" else "Run task",
                            onClick = viewModel::submitTask,
                            enabled = state.isConnected && !state.submitting && !state.isRunning,
                            icon = Icons.Default.PlayArrow,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.isRunning) {
                            BrandOutlineButton(
                                text = if (state.cancelling) "Stopping…" else "Stop",
                                onClick = viewModel::cancelActiveTask,
                                enabled = !state.cancelling,
                                icon = Icons.Default.Stop,
                            )
                        } else {
                            BrandOutlineButton(
                                text = "Refresh",
                                onClick = viewModel::refreshActiveTask,
                                enabled = state.activeConversationId != null,
                                icon = Icons.Default.Refresh,
                            )
                        }
                    }
                }
            }
        }

        item {
            PlatformCard(
                title = "Download a workspace file",
                subtitle = "Enter the exact absolute path on the OpenHands server; remote file listing is not exposed by its API.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.fileDownloadPath,
                        onValueChange = viewModel::onFileDownloadPathChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Remote file path") },
                        placeholder = { Text("/tmp/agent-workspace/result.txt") },
                        singleLine = true,
                        enabled = state.isConnected && !state.fileTransferBusy,
                    )
                    BrandOutlineButton(
                        text = if (state.fileTransferBusy) "Downloading…" else "Save file to device",
                        onClick = {
                            val suggestedName = state.fileDownloadPath.substringAfterLast('/').ifBlank { "agent-file.bin" }
                            downloadPicker.launch(suggestedName)
                        },
                        enabled = state.isConnected && !state.fileTransferBusy && state.fileDownloadPath.startsWith('/'),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ── Live execution ─────────────────────────────────────────────────────
        val conversationId = state.activeConversationId
        if (conversationId != null) {
            item {
                SectionHeading(
                    "Execution",
                    subtitle = "Conversation ${conversationId.take(8)} · ${state.taskState}",
                )
            }
            item {
                PlatformCard(
                    title = "Live status",
                    status = state.taskState to statusFor(state.taskState),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabeledValue("State", state.taskState)
                        LabeledValue("Events", state.timeline.size.toString())
                        if (state.isRunning) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.emerald,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Agent is working…", fontSize = 12.sp, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
            if (state.timeline.isNotEmpty()) {
                item { SectionHeading("Timeline", subtitle = "Server events, newest last") }
                items(state.timeline, key = { it.id }) { entry ->
                    val color = if (entry.isError) colors.accentRed else colors.textSecondary
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(colors.cardBackground, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(entry.kind, fontSize = 10.sp, color = colors.electricBlueSoft, fontWeight = FontWeight.SemiBold)
                        Text(
                            entry.text,
                            fontSize = 12.sp,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 12,
                        )
                    }
                }
            }
            state.finalResponse?.let { response ->
                item { SectionHeading("Result", subtitle = "The agent's final answer") }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(colors.emerald.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            response,
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        // ── History ────────────────────────────────────────────────────────────
        item { SectionHeading("History", subtitle = "Kept on this device across restarts") }
        if (state.history.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Refresh,
                    title = "No tasks yet",
                    hint = "Connect a platform and run a task to build history.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(state.history, key = { it.id }) { task ->
            PlatformCard(
                title = task.prompt.ifBlank { "(follow-up task)" }.take(70),
                subtitle = "${task.state} · ${formatMillis(task.updatedAtMillis)}",
                status = task.state to statusFor(task.state),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    task.finalResponse?.let {
                        Text(
                            it.take(400),
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 6,
                        )
                    }
                    task.lastError?.let {
                        Text(it.take(300), fontSize = 11.sp, color = colors.accentRed, maxLines = 4)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandOutlineButton(
                            text = "Open",
                            onClick = { viewModel.openHistoricalTask(task) },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.deleteHistoricalTask(task.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete task record")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformDiscoveryCard(platform: AgentPlatform) {
    val colors = AppTheme.colors
    PlatformCard(
        title = platform.displayName,
        subtitle = platform.integrationMode.displayName,
        status = when (platform.integrationMode) {
            IntegrationMode.DOCUMENTED_API -> "API" to PlatformStatus.HEALTHY
            IntegrationMode.SDK -> "SDK" to PlatformStatus.HEALTHY
            IntegrationMode.MCP -> "MCP" to PlatformStatus.HEALTHY
            IntegrationMode.REMOTE_WORKER -> "Worker" to PlatformStatus.WARNING
            IntegrationMode.APPROVED_BROWSER_AUTOMATION -> "Browser only" to PlatformStatus.ERROR
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledValue("Auth", platform.authKind.name.replace('_', ' ').lowercase())
            LabeledValue("Trust", platform.trustLevel.name.replace('_', ' ').lowercase())
            LabeledValue(
                "Supports",
                if (platform.supported.isEmpty()) "nothing unattended"
                else platform.supported.joinToString(", ") { it.displayName() },
            )
            platform.limitations.forEach { limitation ->
                Text("• $limitation", fontSize = 11.sp, color = colors.textSecondary)
            }
            if (platform.documentationUrl.isNotBlank()) {
                Text(platform.documentationUrl, fontSize = 11.sp, color = colors.electricBlueSoft)
            }
        }
    }
}

@Composable
private fun ConnectForm(
    connecting: Boolean,
    error: String?,
    onConnect: (String, String) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = AppTheme.colors
    var url by remember { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }

    PlatformCard(title = "Connect OpenHands", subtitle = "Point the app at your own agent server") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("https://agent.example.com (or local http://127.0.0.1:8000)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Session key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "The key is sent only in the X-Session-API-Key header and stored encrypted on this device.",
                fontSize = 11.sp,
                color = colors.textSecondary,
            )
            error?.let {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = colors.accentRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
            BrandButton(
                text = if (connecting) "Checking…" else "Connect",
                onClick = { onConnect(url, key) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OpenSwarmConnectForm(
    connecting: Boolean,
    error: String?,
    onConnect: (String, String) -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = AppTheme.colors
    var url by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }

    PlatformCard(
        title = "Connect OpenSwarm",
        subtitle = "Point the app at your deployed task backend",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                placeholder = { Text("https://your-backend.example.com (or local http://127.0.0.1:8000)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App client token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "The token is sent only in the Authorization header and stored encrypted on this device. " +
                    "Task execution stays disabled until the backend reports itself ready.",
                fontSize = 11.sp,
                color = colors.textSecondary,
            )
            error?.let {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = colors.accentRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
            BrandButton(
                text = if (connecting) "Checking…" else "Connect",
                onClick = { onConnect(url, token) },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    val colors = AppTheme.colors
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp)) {
        Text(label, fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = colors.textPrimary, fontWeight = FontWeight.Medium)
    }
}

private fun PlatformCapability.displayName(): String = when (this) {
    PlatformCapability.CREATE_SESSION -> "sessions"
    PlatformCapability.SUBMIT_TASK -> "tasks"
    PlatformCapability.OBSERVE_EVENTS -> "live events"
    PlatformCapability.CANCEL_TASK -> "cancel"
    PlatformCapability.UPLOAD_FILE -> "upload files"
    PlatformCapability.LIST_ARTIFACTS -> "artifacts"
    PlatformCapability.DOWNLOAD_ARTIFACT -> "downloads"
}

private fun statusFor(state: String): PlatformStatus = when (state) {
    "finished", "complete" -> PlatformStatus.HEALTHY
    "running", "queued", "idle" -> PlatformStatus.WARNING
    "paused", "stopping" -> PlatformStatus.INFO
    "error", "disconnected" -> PlatformStatus.ERROR
    else -> PlatformStatus.WARNING
}

private fun formatMillis(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}
