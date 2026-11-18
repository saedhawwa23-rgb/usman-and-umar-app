package com.opendroid.ai.ui.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Assignment
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.MetricTile
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.PlatformTab
import com.opendroid.ai.ui.components.SegmentedTabs
import com.opendroid.ai.ui.components.StatusPill
import com.opendroid.ai.ui.screens.taskbar.TaskAction
import com.opendroid.ai.ui.screens.taskbar.TaskBarModel
import com.opendroid.ai.ui.screens.taskbar.TaskBarSummary
import com.opendroid.ai.ui.screens.taskbar.TaskProgress
import com.opendroid.ai.ui.screens.taskbar.TaskStatusKind
import com.opendroid.ai.core.agent.InstalledAiAppDiscovery
import com.opendroid.ai.core.agent.InstalledAiAppPreferences
import com.opendroid.ai.core.agent.SmartLocalAiRouter
import com.opendroid.ai.core.browser.OpenSwarmRouteConfig
import com.usmanandumar.workspace.core.task.TaskExecutionMode
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.viewmodel.BrowserUseRunState
import com.opendroid.ai.ui.viewmodel.GoogleDriveAccountsViewModel
import com.opendroid.ai.ui.viewmodel.WorkspaceTaskViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TaskuaryPage(val label: String) { INBOX("Inbox"), DASHBOARD("Dashboard"), BOARD("Kanban") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTaskScreen(
    viewModel: WorkspaceTaskViewModel,
    modifier: Modifier = Modifier,
    onOpenDriveSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val googleDriveViewModel: GoogleDriveAccountsViewModel = hiltViewModel()
    val driveAccounts by googleDriveViewModel.accounts.collectAsState()
    val installedAiApps = remember(context.packageManager) {
        InstalledAiAppDiscovery.scan(context.packageManager)
    }
    val tasks by viewModel.tasks.collectAsState()
    val serverUrls by viewModel.cloudServerUrls.collectAsState()
    val serverEnabled by viewModel.cloudServerEnabled.collectAsState()
    val serverTokens by viewModel.cloudServerTokens.collectAsState()
    val cloudServers by viewModel.cloudServers.collectAsState()
    val autoRunCode by viewModel.autoRunGeneratedCode.collectAsState()
    val message by viewModel.message.collectAsState()
    val browserState by viewModel.browserUseState.collectAsState()
    val openSwarmAgents by viewModel.openSwarmAgents.collectAsState()
    val openSwarmRosterLoading by viewModel.openSwarmRosterLoading.collectAsState()
    var rawText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(TaskExecutionMode.CLOUD_OPENSWARM) }
    var cloudTarget by remember { mutableStateOf("auto") }
    var providerPackage by remember(installedAiApps) {
        val saved = InstalledAiAppPreferences.selectedPackage(context)
        mutableStateOf(installedAiApps.firstOrNull { it.packageName == saved }?.packageName
            ?: SmartLocalAiRouter.AUTO_TARGET
        )
    }
    var scriptLanguage by remember { mutableStateOf("bash") }
    var page by remember { mutableStateOf(TaskuaryPage.INBOX) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var browserPrompt by remember { mutableStateOf("") }
    var useFmhy by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun uploadTaskOutput(task: WorkspaceTaskEntity) {
        scope.launch {
            runCatching {
                googleDriveViewModel.uploadTaskOutput(taskOutputFilename(task), taskOutput(task))
            }.onSuccess { result ->
                Toast.makeText(context, "Saved to Google Drive (${result.accountEmail})", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Could not upload to Google Drive", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun beginDriveUpload(task: WorkspaceTaskEntity) {
        if (taskOutput(task).isBlank()) {
            Toast.makeText(context, "There is no completed task output to save yet.", Toast.LENGTH_SHORT).show()
            return
        }
        if (driveAccounts.isEmpty()) {
            Toast.makeText(context, "Add a Google account to the storage pool first.", Toast.LENGTH_LONG).show()
            onOpenDriveSettings()
            return
        }
        uploadTaskOutput(task)
    }

    val summary = remember(tasks) { TaskBarModel.summary(tasks) }
    val tabCounts = mapOf(
        TaskuaryPage.INBOX to tasks.count { TaskBarModel.bucketOf(TaskBarModel.parseState(it.state)) in setOf(com.opendroid.ai.ui.screens.taskbar.TaskBucket.QUEUED, com.opendroid.ai.ui.screens.taskbar.TaskBucket.WAITING, com.opendroid.ai.ui.screens.taskbar.TaskBucket.FAILED) },
        TaskuaryPage.DASHBOARD to tasks.count { it.deadlineAt != null },
        TaskuaryPage.BOARD to tasks.count { TaskBarModel.parseState(it.state) == TaskState.RUNNING },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            BrandHeader(
                title = "Task Bar",
                subtitle = "Plan, run and track your automations",
                icon = Icons.Default.Assignment,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TaskBarSummaryRow(summary) }
            item {
                SegmentedTabs(
                    tabs = TaskuaryPage.entries.map { PlatformTab(it.label, tabCounts[it]) },
                    selectedIndex = page.ordinal,
                    onSelect = { page = TaskuaryPage.entries[it] },
                )
            }
            item {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (mode == TaskExecutionMode.TERMUX_SCRIPT) "Script source — paste bash or Python code" else "Quick Add — e.g. Fix bug tomorrow at 5pm")
                    },
                    minLines = 2,
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeMenu(mode = mode, onSelect = { mode = it }, modifier = Modifier.weight(1f), enableNonOpenSwarmRoutes = OpenSwarmRouteConfig.ENABLE_NON_OPENSWARM_ROUTES)
                    if (mode == TaskExecutionMode.LOCAL_AI_APP) {
                        Column(Modifier.weight(1f)) {
                            ProviderMenu(
                                providerPackage = providerPackage,
                                installedApps = installedAiApps,
                                onSelect = {
                                    providerPackage = it
                                    if (it != SmartLocalAiRouter.AUTO_TARGET) {
                                        InstalledAiAppPreferences.setSelectedPackage(context, it)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Switch(
                                    checked = providerPackage == SmartLocalAiRouter.AUTO_TARGET,
                                    onCheckedChange = { enabled ->
                                        providerPackage = if (enabled) SmartLocalAiRouter.AUTO_TARGET
                                        else installedAiApps.firstOrNull()?.packageName.orEmpty()
                                    },
                                )
                                Text("Smart AI Selection", fontSize = 12.sp)
                            }
                        }
                    } else if (mode == TaskExecutionMode.CLOUD_OPENSWARM) {
                        CloudTargetMenu(target = cloudTarget, servers = cloudServers, onSelect = { cloudTarget = it }, modifier = Modifier.weight(1f))
                    } else if (mode == TaskExecutionMode.TERMUX_SCRIPT) {
                        ScriptLanguageMenu(language = scriptLanguage, onSelect = { scriptLanguage = it }, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Button(
                    onClick = { viewModel.createTask(rawText, mode, providerPackage, scriptLanguage, cloudTarget) { rawText = "" } },
                    enabled = rawText.isNotBlank() &&
                        (mode != TaskExecutionMode.LOCAL_AI_APP ||
                            (installedAiApps.isNotEmpty() && (providerPackage == SmartLocalAiRouter.AUTO_TARGET || installedAiApps.any { it.packageName == providerPackage }))),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(Icons.Default.AddTask, contentDescription = null)
                    Text("  Add to Inbox")
                }
            }
            item {
                Text(
                    text = when (mode) {
                        TaskExecutionMode.LOCAL_AI_APP -> if (installedAiApps.isEmpty()) {
                            "No launchable apps were found. Install an AI chat app, then refresh the screen."
                        } else {
                            "PackageManager found ${installedAiApps.size} launchable AI-app candidate(s). Smart AI Selection ranks the installed apps for coding, research, and tool-specific tasks, then falls back when a preferred app is unavailable."
                        }
                        TaskExecutionMode.CLOUD_OPENSWARM -> "Cloud Browser Use sends the task to the remote executor. Use FMHY-first discovery or include a public URL; never include secrets or private data."
                        TaskExecutionMode.TERMUX_SCRIPT -> "Only the explicit script text you enter is sent to Termux; ordinary task prose is never interpreted as shell."
                    },
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("OPEN SWARM AGENT TEAM", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        Text(
                            if (openSwarmAgents.isEmpty()) "Load the real agent roster from a healthy configured cloud server."
                            else "${openSwarmAgents.count { !OpenSwarmRouteConfig.isHiddenAgent(it.id, it.name) }} registered specialist agents",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 14.sp,
                        )
                        Button(
                            onClick = { viewModel.refreshOpenSwarmAgents() },
                            enabled = !openSwarmRosterLoading && cloudServers.any { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (openSwarmRosterLoading) "Checking agent team..." else "Refresh OpenSwarm agents") }
                        openSwarmAgents.filter { !OpenSwarmRouteConfig.isHiddenAgent(it.id, it.name) }.forEach { agent ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(agent.name, color = AppTheme.colors.textPrimary, fontSize = 13.sp)
                                    Text(agent.description, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                                }
                                Text(
                                    if (agent.adapterConfigured) "Adapter ready" else "Not connected",
                                    color = if (agent.adapterConfigured) AccentCyan else AppTheme.colors.textSecondary,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }
            if (OpenSwarmRouteConfig.ENABLE_NON_OPENSWARM_ROUTES) item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("CLOUD BROWSER USE SETTINGS", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { settingsExpanded = !settingsExpanded }) {
                            Text(if (settingsExpanded) "Hide server settings" else "Configure cloud servers")
                        }
                        if (settingsExpanded) {
                            if (cloudServers.isEmpty()) {
                                Text("No cloud servers configured. Open Remote settings to add one.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                            }
                            cloudServers.forEach { server ->
                                var nameDraft by remember(server.id, server.name) { mutableStateOf(server.name) }
                                var urlDraft by remember(server.id, server.url) { mutableStateOf(server.url) }
                                var tokenDraft by remember(server.id, server.token) { mutableStateOf(server.token) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(nameDraft.ifBlank { "Cloud Server" }, fontSize = 13.sp)
                                    Switch(checked = server.enabled, onCheckedChange = { viewModel.setNamedCloudServerEnabled(server.id, it) })
                                }
                                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server name") })
                                OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS backend URL") })
                                OutlinedTextField(value = tokenDraft, onValueChange = { tokenDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), label = { Text("Bearer token") })
                                Button(onClick = { viewModel.saveNamedCloudServer(server.copy(name = nameDraft, url = urlDraft), tokenDraft) }) { Text("Save ${nameDraft.ifBlank { "server" }}") }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("REMOTE BROWSER USE", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                            Text(
                                "Run a remote Browser Use task without first adding it to the task inbox. The backend returns the actual result here.",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = browserPrompt,
                                onValueChange = { browserPrompt = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                label = { Text("Browser task") },
                                placeholder = { Text("Find an image generator on FMHY and generate…") },
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("FMHY-first discovery", fontSize = 13.sp)
                                    Text("Find one matching public tool, then use only that host.", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
                                }
                                Switch(checked = useFmhy, onCheckedChange = { useFmhy = it })
                            }
                            Text("Maximum execution budget: 15 browser steps", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.runBrowserUse(browserPrompt, useFmhy, 15) },
                                    enabled = browserPrompt.isNotBlank() && browserState.phase != BrowserUseRunState.Phase.RUNNING,
                                ) { Text("Run remotely") }
                                if (browserState.phase == BrowserUseRunState.Phase.RUNNING) {
                                    Button(onClick = { viewModel.cancelBrowserUse() }) { Text("Cancel") }
                                }
                            }
                            when (browserState.phase) {
                                BrowserUseRunState.Phase.RUNNING -> Text("Browser Use is running remotely…", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                                BrowserUseRunState.Phase.SUCCEEDED -> {
                                    Text("Completed${browserState.steps?.let { " in $it steps" }.orEmpty()}", fontSize = 12.sp)
                                    Text(browserState.result.take(4_000), fontSize = 12.sp)
                                }
                                BrowserUseRunState.Phase.FAILED -> Text("Failed: ${browserState.error}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                                BrowserUseRunState.Phase.CANCELLED -> Text("Cancelled", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                                BrowserUseRunState.Phase.IDLE -> Unit
                            }
                        }
                    }
                }
            }
            if (OpenSwarmRouteConfig.ENABLE_NON_OPENSWARM_ROUTES) item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("AUTO-RUN AI-GENERATED CODE", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                            Text("High risk: sends returned bash/python blocks to Termux without per-block confirmation.", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        }
                        Switch(checked = autoRunCode, onCheckedChange = viewModel::setAutoRunGeneratedCode)
                    }
                }
            }
            if (message != null) item {
                Text(message.orEmpty(), color = AppTheme.colors.textSecondary, fontSize = 13.sp)
            }
            when (page) {
                TaskuaryPage.INBOX -> {
                    item { SectionTitle("INBOX") }
                    val inbox = tasks.filter { it.state in setOf(TaskState.DRAFT.name, TaskState.WAITING_FOR_NETWORK.name, TaskState.FAILED.name, TaskState.WAITING_USER.name, TaskState.WAITING_APPROVAL.name, TaskState.PAUSED.name) }
                    if (inbox.isEmpty()) item { Text("Inbox is clear.", color = AppTheme.colors.textSecondary) }
                    items(inbox, key = { "inbox-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload, onPause = viewModel::pauseCloudTask, onCancel = viewModel::cancelCloudTask) }
                }
                TaskuaryPage.DASHBOARD -> {
                    val today = startOfToday()
                    val tomorrow = startOfTomorrow()
                    val todayTasks = tasks.filter { it.deadlineAt != null && it.deadlineAt in today until tomorrow }
                    val overdue = tasks.filter { it.deadlineAt != null && it.deadlineAt < today && it.state != TaskState.SUCCEEDED.name }
                    item { SectionTitle("TODAY") }
                    if (todayTasks.isEmpty()) item { Text("Nothing due today.", color = AppTheme.colors.textSecondary) }
                    items(todayTasks, key = { "today-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload, onPause = viewModel::pauseCloudTask, onCancel = viewModel::cancelCloudTask) }
                    item { SectionTitle("OVERDUE") }
                    if (overdue.isEmpty()) item { Text("No overdue tasks.", color = AppTheme.colors.textSecondary) }
                    items(overdue, key = { "overdue-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload, onPause = viewModel::pauseCloudTask, onCancel = viewModel::cancelCloudTask) }
                }
                TaskuaryPage.BOARD -> {
                    item {
                        val columnBounds = remember { mutableStateMapOf<String, Rect>() }
                        var draggedTask by remember { mutableStateOf<WorkspaceTaskEntity?>(null) }
                        var dragPosition by remember { mutableStateOf(Offset.Unspecified) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            KanbanColumn("DRAFT", tasks.filter { it.state == TaskState.DRAFT.name }, viewModel::runTask, ::beginDriveUpload, columnBounds, draggedTask, dragPosition, { draggedTask = it; dragPosition = Offset.Unspecified }, { amount -> dragPosition = if (dragPosition != Offset.Unspecified) dragPosition + amount else amount }, { task, column -> viewModel.dropTaskOnColumn(task, column); draggedTask = null; dragPosition = Offset.Unspecified })
                            KanbanColumn("RUNNING", tasks.filter { it.state == TaskState.RUNNING.name }, viewModel::runTask, ::beginDriveUpload, columnBounds, draggedTask, dragPosition, { draggedTask = it; dragPosition = Offset.Unspecified }, { amount -> dragPosition = if (dragPosition != Offset.Unspecified) dragPosition + amount else amount }, { task, column -> viewModel.dropTaskOnColumn(task, column); draggedTask = null; dragPosition = Offset.Unspecified }, viewModel::pauseCloudTask, viewModel::cancelCloudTask)
                            KanbanColumn("SUCCEEDED", tasks.filter { it.state == TaskState.SUCCEEDED.name }, viewModel::runTask, ::beginDriveUpload, columnBounds, draggedTask, dragPosition, { draggedTask = it; dragPosition = Offset.Unspecified }, { amount -> dragPosition = if (dragPosition != Offset.Unspecified) dragPosition + amount else amount }, { task, column -> viewModel.dropTaskOnColumn(task, column); draggedTask = null; dragPosition = Offset.Unspecified })
                        }
                        if (draggedTask != null) Text("Drop ${draggedTask?.title} on a column to move it", color = AccentCyan, fontSize = 12.sp)
                    }
                    val waiting = tasks.filter { it.state == TaskState.WAITING_FOR_NETWORK.name }
                    if (waiting.isNotEmpty()) {
                        item { SectionTitle("WAITING FOR NETWORK") }
                        items(waiting, key = { "waiting-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskBarSummaryRow(summary: TaskBarSummary, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile(value = summary.active.toString(), label = "Running", accent = AppTheme.colors.electricBlue, modifier = Modifier.weight(1f))
        MetricTile(value = summary.queued.toString(), label = "Queued", accent = AppTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        MetricTile(value = summary.waiting.toString(), label = "Waiting", accent = AppTheme.colors.accentOrange, modifier = Modifier.weight(1f))
        MetricTile(value = summary.done.toString(), label = "Done", accent = AppTheme.colors.emerald, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModeMenu(mode: TaskExecutionMode, onSelect: (TaskExecutionMode) -> Unit, modifier: Modifier = Modifier, enableNonOpenSwarmRoutes: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(when (mode) {
                TaskExecutionMode.LOCAL_AI_APP -> "Mode A · Local AI"
                TaskExecutionMode.CLOUD_OPENSWARM -> "Mode B · Cloud"
                TaskExecutionMode.TERMUX_SCRIPT -> "Termux · Local"
            }, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TaskExecutionMode.entries.filter { enableNonOpenSwarmRoutes || it == TaskExecutionMode.CLOUD_OPENSWARM }.forEach { item ->
                DropdownMenuItem(text = { Text(item.name.replace('_', ' ')) }, onClick = { onSelect(item); expanded = false })
            }
        }
    }
}

@Composable
private fun ProviderMenu(
    providerPackage: String,
    installedApps: List<InstalledAiAppDiscovery.InstalledAiApp>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Button(onClick = { expanded = true }, enabled = installedApps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(
                if (providerPackage == SmartLocalAiRouter.AUTO_TARGET) "Auto / Smart Select"
                else installedApps.firstOrNull { it.packageName == providerPackage }?.displayName ?: "No installed app",
                fontSize = 12.sp,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto / Smart Select") },
                onClick = { onSelect(SmartLocalAiRouter.AUTO_TARGET); expanded = false },
            )
            installedApps.forEach { app ->
                DropdownMenuItem(
                    text = { Text(if (app.isAiCandidate) "${app.displayName} · AI candidate" else app.displayName) },
                    onClick = { onSelect(app.packageName); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ScriptLanguageMenu(language: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(language, fontSize = 12.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("bash", "python").forEach { item ->
                DropdownMenuItem(text = { Text(item) }, onClick = { onSelect(item); expanded = false })
            }
        }
    }
}

@Composable
private fun CloudTargetMenu(
    target: String,
    servers: List<com.opendroid.ai.data.repository.CloudServerConfig>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("auto" to "OpenSwarm (online server)") + servers
        .filter { it.enabled && it.role == "OPENSWARM" }
        .map { it.id to it.name.ifBlank { "Cloud Server" } }
    val selectedLabel = options.firstOrNull { it.first == target }?.second
        ?: if (target == "auto") "OpenSwarm (online server)" else "Selected server"
    Column(modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedLabel, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: WorkspaceTaskEntity,
    onRun: (WorkspaceTaskEntity) -> Unit,
    onSaveOutput: (WorkspaceTaskEntity) -> Unit,
    modifier: Modifier = Modifier,
    onPause: (WorkspaceTaskEntity) -> Unit = {},
    onCancel: (WorkspaceTaskEntity) -> Unit = {},
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    val item = remember(task) { TaskBarModel.itemOf(task) }
    Card(
        modifier = modifier.pointerInput(task.id) {
            if (onDragStart != null && onDrag != null && onDragEnd != null) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { _, amount -> onDrag(amount) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
        },
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(item.modeLabel, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
                StatusPill(text = item.statusLabel, status = item.statusKind.toPlatformStatus())
            }
            when (item.progress) {
                TaskProgress.Indeterminate -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppTheme.colors.electricBlue,
                    trackColor = AppTheme.colors.electricBlue.copy(alpha = 0.15f),
                )
                TaskProgress.Complete -> LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppTheme.colors.emerald,
                    trackColor = AppTheme.colors.emerald.copy(alpha = 0.15f),
                )
                TaskProgress.None -> Unit
            }
            if (item.dueAtMs != null) {
                val formatted = remember(item.dueAtMs) {
                    Instant.ofEpochMilli(item.dueAtMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
                }
                Text("Due $formatted", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (item.note.isNotBlank()) {
                Text(item.note, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (item.output.isNotBlank()) {
                Text(item.output.take(1_000), color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (item.actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.actions.forEach { action ->
                        when (action) {
                            TaskAction.RUN, TaskAction.RETRY -> Button(onClick = { onRun(task) }) {
                                androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(if (action == TaskAction.RETRY) " Retry" else " Run")
                            }
                            TaskAction.RESUME -> Button(onClick = { onRun(task) }) { Text("Resume") }
                            TaskAction.APPROVE -> Button(onClick = { onRun(task) }) { Text("Approve & resume") }
                            TaskAction.PAUSE -> OutlinedButton(onClick = { onPause(task) }) { Text("Pause") }
                            TaskAction.CANCEL -> OutlinedButton(onClick = { onCancel(task) }) { Text("Cancel") }
                            TaskAction.SAVE_OUTPUT -> OutlinedButton(onClick = { onSaveOutput(task) }) { Text("Save to Drive") }
                        }
                    }
                }
            }
        }
    }
}

private fun TaskStatusKind.toPlatformStatus(): PlatformStatus = when (this) {
    TaskStatusKind.ACTIVE -> PlatformStatus.ACTIVE
    TaskStatusKind.WARNING -> PlatformStatus.WARNING
    TaskStatusKind.SUCCESS -> PlatformStatus.HEALTHY
    TaskStatusKind.ERROR -> PlatformStatus.ERROR
    TaskStatusKind.IDLE -> PlatformStatus.IDLE
}

@Composable
private fun KanbanColumn(
    title: String,
    tasks: List<WorkspaceTaskEntity>,
    onRun: (WorkspaceTaskEntity) -> Unit,
    onSaveOutput: (WorkspaceTaskEntity) -> Unit,
    columnBounds: MutableMap<String, Rect>,
    draggedTask: WorkspaceTaskEntity?,
    dragPosition: Offset,
    onDragStart: (WorkspaceTaskEntity) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: (WorkspaceTaskEntity, String) -> Unit,
    onPause: (WorkspaceTaskEntity) -> Unit = {},
    onCancel: (WorkspaceTaskEntity) -> Unit = {},
) {
    val highlighted = draggedTask != null && columnBounds[title]?.contains(dragPosition) == true
    Column(
        Modifier
            .width(280.dp)
            .padding(end = 10.dp)
            .onGloballyPositioned { columnBounds[title] = it.boundsInWindow() }
            .then(if (highlighted) Modifier.padding(4.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("$title · ${tasks.size}")
        if (tasks.isEmpty()) Text("No tasks", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        tasks.forEach { task ->
            TaskCard(
                task, onRun, onSaveOutput, onPause = onPause, onCancel = onCancel,
                modifier = if (draggedTask?.id == task.id) Modifier else Modifier,
                onDragStart = {
                    onDragStart(task)
                    columnBounds[title]?.let { bounds -> onDrag(Offset(bounds.center.x, bounds.center.y)) }
                },
                onDrag = onDrag,
                onDragEnd = {
                    val destination = columnBounds.entries.firstOrNull { it.value.contains(dragPosition) }?.key
                    if (destination != null && draggedTask != null) onDrop(draggedTask, destination)
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppTheme.colors.textSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
}


private fun startOfToday(): Long = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun startOfTomorrow(): Long = java.time.LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun taskOutput(task: WorkspaceTaskEntity): String =
    task.notes.takeIf { task.state == TaskState.SUCCEEDED.name && it.startsWith("Execution output:") }
        ?.removePrefix("Execution output:")
        ?.trim()
        .orEmpty()

private fun taskOutputFilename(task: WorkspaceTaskEntity): String =
    task.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "task-output" } + ".txt"
