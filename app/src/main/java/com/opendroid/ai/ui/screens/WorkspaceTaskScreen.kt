package com.opendroid.ai.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.opendroid.ai.core.drive.GoogleDriveUploader
import com.opendroid.ai.core.agent.InstalledAiAppDiscovery
import com.opendroid.ai.core.agent.InstalledAiAppPreferences
import com.usmanandumar.workspace.core.task.TaskExecutionMode
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.ui.theme.AppTheme
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
    val installedAiApps = remember(context.packageManager) {
        InstalledAiAppDiscovery.scan(context.packageManager)
    }
    val tasks by viewModel.tasks.collectAsState()
    val serverUrl by viewModel.openSwarmServerUrl.collectAsState()
    val clientToken by viewModel.openSwarmClientToken.collectAsState()
    val autoRunCode by viewModel.autoRunGeneratedCode.collectAsState()
    val message by viewModel.message.collectAsState()
    var rawText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(TaskExecutionMode.LOCAL_AI_APP) }
    var providerPackage by remember(installedAiApps) {
        val saved = InstalledAiAppPreferences.selectedPackage(context)
        mutableStateOf(installedAiApps.firstOrNull { it.packageName == saved }?.packageName
            ?: installedAiApps.firstOrNull()?.packageName.orEmpty())
    }
    var scriptLanguage by remember { mutableStateOf("bash") }
    var page by remember { mutableStateOf(TaskuaryPage.INBOX) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var urlDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var taskPendingExport by remember { mutableStateOf<WorkspaceTaskEntity?>(null) }
    val scope = rememberCoroutineScope()
    fun uploadTaskOutput(task: WorkspaceTaskEntity, account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        scope.launch {
            runCatching {
                GoogleDriveUploader.uploadTaskOutput(
                    context,
                    account,
                    taskOutputFilename(task),
                    taskOutput(task),
                )
            }.onSuccess { link ->
                Toast.makeText(context, if (link.startsWith("http")) "Saved to Google Drive" else link, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Could not upload to Google Drive", Toast.LENGTH_LONG).show()
            }
        }
    }
    val driveSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = taskPendingExport
        taskPendingExport = null
        if (result.resultCode == Activity.RESULT_OK && task != null) {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .addOnSuccessListener { account ->
                    uploadTaskOutput(task, account)
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Google Drive authorization failed", Toast.LENGTH_LONG).show()
                }
        }
    }
    fun beginDriveUpload(task: WorkspaceTaskEntity) {
        if (taskOutput(task).isBlank()) {
            Toast.makeText(context, "There is no completed task output to save yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val existingAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (existingAccount != null && GoogleSignIn.hasPermissions(existingAccount, Scope("https://www.googleapis.com/auth/drive.file"))) {
            uploadTaskOutput(task, existingAccount)
            return
        }
        taskPendingExport = task
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        driveSignInLauncher.launch(GoogleSignIn.getClient(context, options).signInIntent)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("TASK ORCHESTRATOR") }) },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TaskuaryPage.entries.forEach { candidate ->
                        FilterChip(selected = page == candidate, onClick = { page = candidate }, label = { Text(candidate.label) })
                    }
                }
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
                    ModeMenu(mode = mode, onSelect = { mode = it }, modifier = Modifier.weight(1f))
                    if (mode == TaskExecutionMode.LOCAL_AI_APP) {
                        ProviderMenu(
                            providerPackage = providerPackage,
                            installedApps = installedAiApps,
                            onSelect = {
                                providerPackage = it
                                InstalledAiAppPreferences.setSelectedPackage(context, it)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (mode == TaskExecutionMode.TERMUX_SCRIPT) {
                        ScriptLanguageMenu(language = scriptLanguage, onSelect = { scriptLanguage = it }, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Button(
                    onClick = { viewModel.createTask(rawText, mode, providerPackage, scriptLanguage) { rawText = "" } },
                    enabled = rawText.isNotBlank() &&
                        (mode != TaskExecutionMode.LOCAL_AI_APP || installedAiApps.any { it.packageName == providerPackage }),
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
                            "PackageManager found ${installedAiApps.size} launchable AI-app candidate(s) from installed app names. Select Manus, Genspark, or another recognized AI client."
                        }
                        TaskExecutionMode.CLOUD_OPENSWARM -> "Cloud Browser Use sends the full prompt to the Colab executor. Include a public URL to browse; do not include secrets or private data."
                        TaskExecutionMode.TERMUX_SCRIPT -> "Only the explicit script text you enter is sent to Termux; ordinary task prose is never interpreted as shell."
                    },
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("CLOUD BROWSER USE SETTINGS", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { settingsExpanded = !settingsExpanded }) {
                            Text(if (settingsExpanded) "Hide server settings" else "Configure cloud backend")
                        }
                        if (settingsExpanded) {
                            OutlinedTextField(
                                value = urlDraft,
                                onValueChange = { urlDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Cloud Backend URL") },
                                placeholder = { Text("https://your-cloud-backend.example") },
                                supportingText = { Text("Enter the server base URL; the app appends /execute. A URL ending in /execute is also accepted.") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.saveOpenSwarmServerUrl(urlDraft) }) { Text("Save URL") }
                                Button(onClick = { urlDraft = ""; viewModel.saveOpenSwarmServerUrl("") }) { Text("Clear") }
                            }
                            var tokenDraft by remember(clientToken) { mutableStateOf(clientToken) }
                            OutlinedTextField(
                                value = tokenDraft,
                                onValueChange = { tokenDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                label = { Text("APP_CLIENT_TOKEN") },
                                supportingText = { Text("Match APP_CLIENT_TOKEN configured as a backend secret. Stored with Android Keystore encryption.") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.saveOpenSwarmClientToken(tokenDraft) }) { Text("Save token") }
                                Button(onClick = { tokenDraft = ""; viewModel.saveOpenSwarmClientToken("") }) { Text("Clear token") }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("GOOGLE DRIVE", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                        Text(
                            "Connect an account in Drive settings, then save completed task output into USMAN_AND_UMAR_Tasks using drive.file access.",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onOpenDriveSettings) { Text("Google Drive settings") }
                    }
                }
            }
            item {
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
                    val inbox = tasks.filter { it.state in setOf(TaskState.DRAFT.name, TaskState.WAITING_FOR_NETWORK.name, TaskState.FAILED.name, TaskState.WAITING_USER.name) }
                    if (inbox.isEmpty()) item { Text("Inbox is clear.", color = AppTheme.colors.textSecondary) }
                    items(inbox, key = { "inbox-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload) }
                }
                TaskuaryPage.DASHBOARD -> {
                    val today = startOfToday()
                    val tomorrow = startOfTomorrow()
                    val todayTasks = tasks.filter { it.deadlineAt != null && it.deadlineAt in today until tomorrow }
                    val overdue = tasks.filter { it.deadlineAt != null && it.deadlineAt < today && it.state != TaskState.SUCCEEDED.name }
                    item { SectionTitle("TODAY") }
                    if (todayTasks.isEmpty()) item { Text("Nothing due today.", color = AppTheme.colors.textSecondary) }
                    items(todayTasks, key = { "today-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload) }
                    item { SectionTitle("OVERDUE") }
                    if (overdue.isEmpty()) item { Text("No overdue tasks.", color = AppTheme.colors.textSecondary) }
                    items(overdue, key = { "overdue-${it.id}" }) { task -> TaskCard(task, viewModel::runTask, ::beginDriveUpload) }
                }
                TaskuaryPage.BOARD -> {
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            KanbanColumn("DRAFT", tasks.filter { it.state == TaskState.DRAFT.name }, viewModel::runTask, ::beginDriveUpload)
                            KanbanColumn("RUNNING", tasks.filter { it.state == TaskState.RUNNING.name }, viewModel::runTask, ::beginDriveUpload)
                            KanbanColumn("SUCCEEDED", tasks.filter { it.state == TaskState.SUCCEEDED.name }, viewModel::runTask, ::beginDriveUpload)
                        }
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
private fun ModeMenu(mode: TaskExecutionMode, onSelect: (TaskExecutionMode) -> Unit, modifier: Modifier = Modifier) {
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
            TaskExecutionMode.entries.forEach { item ->
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
            Text(installedApps.firstOrNull { it.packageName == providerPackage }?.displayName ?: "No installed app", fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
private fun TaskCard(
    task: WorkspaceTaskEntity,
    onRun: (WorkspaceTaskEntity) -> Unit,
    onSaveOutput: (WorkspaceTaskEntity) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(task.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("${task.state} · ${modeLabel(task.toolId)}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (task.deadlineAt != null) {
                val formatted = remember(task.deadlineAt) {
                    Instant.ofEpochMilli(task.deadlineAt).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
                }
                Text("Due $formatted", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (task.state == TaskState.WAITING_FOR_NETWORK.name) {
                Text("Offline — this task will wait. No AI app or server was contacted.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (task.state == TaskState.WAITING_USER.name && task.notes.contains("AI code blocks were NOT executed")) {
                Text("Code awaits review. Nothing from the model response was executed.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (task.notes.startsWith("Execution output:")) {
                Spacer(Modifier.height(6.dp))
                Text(task.notes.take(1_000), color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (task.state in setOf(TaskState.DRAFT.name, TaskState.WAITING_FOR_NETWORK.name, TaskState.FAILED.name)) {
                Button(onClick = { onRun(task) }) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (task.state == TaskState.WAITING_FOR_NETWORK.name) " Retry when online" else " Run")
                }
            }
            Button(
                onClick = { onSaveOutput(task) },
                enabled = taskOutput(task).isNotBlank(),
            ) { Text("Save to Drive") }
        }
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    tasks: List<WorkspaceTaskEntity>,
    onRun: (WorkspaceTaskEntity) -> Unit,
    onSaveOutput: (WorkspaceTaskEntity) -> Unit,
) {
    Column(Modifier.width(280.dp).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("$title · ${tasks.size}")
        if (tasks.isEmpty()) Text("No tasks", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        tasks.forEach { task -> TaskCard(task, onRun, onSaveOutput) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppTheme.colors.textSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
}

private fun modeLabel(toolId: String?): String = when {
    toolId?.startsWith("hybrid:LOCAL_AI_APP:") == true -> "Local AI · ${toolId.substringAfterLast(':')}"
    toolId == "hybrid:CLOUD_OPENSWARM" -> "Cloud Browser Use"
    toolId?.startsWith("hybrid:TERMUX_SCRIPT") == true -> "Local Termux · ${toolId.substringAfterLast(':')}"
    else -> "No execution mode"
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
