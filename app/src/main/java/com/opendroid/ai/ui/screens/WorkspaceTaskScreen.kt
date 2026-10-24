package com.opendroid.ai.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.crash.CrashLogRedactor
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.repository.InstalledAiApp
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.TaskArtifactReview
import com.opendroid.ai.ui.viewmodel.WorkspaceTaskViewModel
import com.usmanandumar.workspace.core.task.TaskOrchestrationStage
import com.usmanandumar.workspace.core.task.TaskState
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TASK_CATEGORIES = listOf("Work", "Coding", "Personal")
private val TASK_TABS = listOf("Tasks", "Inbox", "Dashboard", "Analytics")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTaskScreen(
    viewModel: WorkspaceTaskViewModel,
    modifier: Modifier = Modifier,
) {
    val tasks by viewModel.tasks.collectAsState()
    val dependenciesByTask by viewModel.dependenciesByTask.collectAsState()
    val installedAiApps by viewModel.installedAiApps.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var expectedOutcome by rememberSaveable { mutableStateOf("") }
    var priority by remember { mutableFloatStateOf(0f) }
    var category by rememberSaveable { mutableStateOf("Personal") }
    var deadlineAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedAiAppPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDependencies = remember { mutableStateListOf<String>() }
    var actionSearch by rememberSaveable { mutableStateOf("") }
    var toolId by rememberSaveable { mutableStateOf("") }
    var inputJson by rememberSaveable { mutableStateOf("{}") }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    var triageRunning by remember { mutableStateOf(false) }
    var inboxText by rememberSaveable { mutableStateOf("") }
    var pendingOutputTask by remember { mutableStateOf<WorkspaceTaskEntity?>(null) }
    var reviewTask by remember { mutableStateOf<WorkspaceTaskEntity?>(null) }
    var artifactReview by remember { mutableStateOf<TaskArtifactReview?>(null) }
    val context = LocalContext.current
    val taskTitles = remember(tasks) { tasks.associate { it.id to it.title } }
    val taskStates = remember(tasks) { tasks.associate { it.id to it.state } }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val task = pendingOutputTask
        pendingOutputTask = null
        if (uri != null && task != null) {
            viewModel.inspectOutputArtifact(
                task = task,
                uri = uri,
                onReviewed = { review ->
                    reviewTask = task
                    artifactReview = review
                },
                onError = { statusMessage = it },
            )
        }
    }

    fun resetTaskForm() {
        title = ""
        description = ""
        expectedOutcome = ""
        priority = 0f
        category = "Personal"
        deadlineAt = null
        selectedAiAppPackage = null
        selectedDependencies.clear()
        actionSearch = ""
        toolId = ""
        inputJson = "{}"
        statusMessage = "Task saved."
    }

    fun shareTaskPrompt(task: WorkspaceTaskEntity) {
        viewModel.beginAiAppTask(task, onPromptReady = { prompt, captureArmed ->
            val packageName = task.aiAppPackage
            if (packageName.isNullOrBlank()) {
                viewModel.failAiAppLaunch(task)
                statusMessage = "No AI app is assigned to this task."
                return@beginAiAppTask
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                setPackage(packageName)
            }
            try {
                context.startActivity(shareIntent)
                statusMessage = "Prompt shared with ${task.aiAppPackage}. Review it and tap Send in that app if needed. " +
                    if (captureArmed) "Accessibility capture is armed for that app for up to 10 minutes." else "Accessibility is unavailable; you can paste the response manually."
            } catch (_: Exception) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Task prompt", prompt))
                    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launch == null) {
                        viewModel.failAiAppLaunch(task)
                        statusMessage = "The assigned AI app is no longer installed. The prompt was copied to the clipboard."
                    } else {
                        context.startActivity(launch)
                        statusMessage = "This AI app did not accept a text-share intent. The prompt was copied; paste it into the app and send manually. " +
                            if (captureArmed) "Accessibility capture is armed." else "Use manual response paste because Accessibility is unavailable."
                    }
                } catch (_: Exception) {
                    viewModel.failAiAppLaunch(task)
                    statusMessage = "Could not open the AI app. The task was not completed."
                }
            }
        }, onError = { statusMessage = it })
    }

    val candidate = artifactReview
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = { artifactReview = null; reviewTask = null },
            title = { Text("Review task output") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File: ${candidate.displayName}")
                    Text("Type: ${candidate.mimeType ?: "unknown"} · Size: ${candidate.byteCount} bytes")
                    Text("SHA-256: ${candidate.sha256.take(16)}…")
                    candidate.textPreview?.let { Text("Text preview:\n${it.take(1_000)}") }
                    Text("The app has read this non-empty file. Please inspect the actual output and confirm it belongs to this task. This check does not establish semantic correctness.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val task = reviewTask
                    if (task != null) viewModel.confirmOutputArtifact(task, candidate) { statusMessage = it }
                    artifactReview = null
                    reviewTask = null
                }, enabled = reviewTask != null) { Text("Confirm output & complete") }
            },
            dismissButton = {
                TextButton(onClick = { artifactReview = null; reviewTask = null }) { Text("Not yet") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("TASK MANAGER") }) },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                TASK_TABS.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
            when (selectedTab) {
                0 -> TasksTab(
                    modifier = Modifier.weight(1f),
                    tasks = tasks,
                    taskTitles = taskTitles,
                    taskStates = taskStates,
                    dependenciesByTask = dependenciesByTask,
                    availableActions = viewModel.availableActions,
                    installedAiApps = installedAiApps,
                    title = title,
                    onTitle = { title = it },
                    description = description,
                    onDescription = { description = it },
                    expectedOutcome = expectedOutcome,
                    onExpectedOutcome = { expectedOutcome = it },
                    priority = priority,
                    onPriority = { priority = it },
                    category = category,
                    onCategory = { category = it },
                    deadlineAt = deadlineAt,
                    onDeadlineAt = { deadlineAt = it },
                    selectedAiAppPackage = selectedAiAppPackage,
                    onSelectedAiApp = { selectedAiAppPackage = it },
                    selectedDependencies = selectedDependencies,
                    actionSearch = actionSearch,
                    onActionSearch = { actionSearch = it; if (toolId.isNotBlank()) toolId = "" },
                    toolId = toolId,
                    onSelectAction = { action ->
                        toolId = action.name
                        actionSearch = action.name
                        selectedAiAppPackage = null
                        inputJson = action.params.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "{\n  ", postfix = "\n}", separator = ",\n  ") { "\"${it.name}\": \"\"" }
                            ?: "{}"
                    },
                    inputJson = inputJson,
                    onInputJson = { inputJson = it },
                    statusMessage = statusMessage,
                    onCreate = {
                        if (toolId.isBlank()) {
                            viewModel.createTask(
                                title = title,
                                description = description,
                                expectedOutcome = expectedOutcome,
                                priority = priority.toInt(),
                                dependencies = selectedDependencies.toList(),
                                category = category,
                                deadlineAt = deadlineAt,
                                aiAppPackage = selectedAiAppPackage,
                                onError = { statusMessage = it },
                                onCreated = ::resetTaskForm,
                            )
                        } else {
                            viewModel.createToolTask(
                                title = title,
                                toolId = toolId,
                                inputJson = inputJson,
                                description = description,
                                expectedOutcome = expectedOutcome,
                                priority = priority.toInt(),
                                dependencies = selectedDependencies.toList(),
                                category = category,
                                deadlineAt = deadlineAt,
                                onError = { statusMessage = it },
                                onCreated = ::resetTaskForm,
                            )
                        }
                    },
                    onTransition = { task, target -> viewModel.transition(task, target, onError = { statusMessage = it }) },
                    onCancel = { viewModel.cancelToolTask(it) },
                    onManualComplete = {
                        viewModel.markManualTaskCompleted(it) { statusMessage = it }
                    },
                    onRunTool = { task -> viewModel.runToolTask(task) { result ->
                        statusMessage = when (result) {
                            is ActionResult.Success -> "Tool result: ${result.data ?: "Completed"}"
                            else -> result.error ?: "Action did not complete"
                        }
                    } },
                    onStartAi = ::shareTaskPrompt,
                    onCaptureAi = { task -> viewModel.captureVisibleAiResponse(task) { statusMessage = it } },
                    onStoreManualAiResponse = { task, response -> viewModel.recordManualAiResponse(task, response) { statusMessage = it } },
                    onChooseOutput = { task ->
                        pendingOutputTask = task
                        outputPicker.launch(arrayOf("*/*"))
                    },
                    onUploadResponse = { task -> viewModel.uploadAiResponseToDrive(task) { statusMessage = it } },
                    onUploadArtifact = { task -> viewModel.uploadVerifiedArtifactToDrive(task) { statusMessage = it } },
                )
                1 -> InboxTab(
                    modifier = Modifier.weight(1f),
                    inboxText = inboxText,
                    onInboxText = { inboxText = it },
                    triageRunning = triageRunning,
                    statusMessage = statusMessage,
                    onTriage = {
                        triageRunning = true
                        statusMessage = "Running AI triage with your active provider…"
                        viewModel.triageInbox(
                            inboxText,
                            onCreated = { count ->
                                triageRunning = false
                                inboxText = ""
                                statusMessage = "AI Triage created $count task${if (count == 1) "" else "s"}. Review their titles, categories, deadlines and prerequisite plan links in Tasks."
                                selectedTab = 0
                            },
                            onError = { triageRunning = false; statusMessage = it },
                        )
                    },
                )
                2 -> DashboardTab(modifier = Modifier.weight(1f), tasks = tasks)
                else -> AnalyticsTab(modifier = Modifier.weight(1f), tasks = tasks)
            }
        }
    }
}

@Composable
private fun TasksTab(
    modifier: Modifier,
    tasks: List<WorkspaceTaskEntity>,
    taskTitles: Map<String, String>,
    taskStates: Map<String, String>,
    dependenciesByTask: Map<String, List<String>>,
    availableActions: List<com.opendroid.ai.core.agent.ActionDefinition>,
    installedAiApps: List<InstalledAiApp>,
    title: String,
    onTitle: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    expectedOutcome: String,
    onExpectedOutcome: (String) -> Unit,
    priority: Float,
    onPriority: (Float) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    deadlineAt: Long?,
    onDeadlineAt: (Long?) -> Unit,
    selectedAiAppPackage: String?,
    onSelectedAiApp: (String?) -> Unit,
    selectedDependencies: MutableList<String>,
    actionSearch: String,
    onActionSearch: (String) -> Unit,
    toolId: String,
    onSelectAction: (com.opendroid.ai.core.agent.ActionDefinition) -> Unit,
    inputJson: String,
    onInputJson: (String) -> Unit,
    statusMessage: String,
    onCreate: () -> Unit,
    onTransition: (WorkspaceTaskEntity, TaskState) -> Unit,
    onCancel: (WorkspaceTaskEntity) -> Unit,
    onManualComplete: (WorkspaceTaskEntity) -> Unit,
    onRunTool: (WorkspaceTaskEntity) -> Unit,
    onStartAi: (WorkspaceTaskEntity) -> Unit,
    onCaptureAi: (WorkspaceTaskEntity) -> Unit,
    onStoreManualAiResponse: (WorkspaceTaskEntity, String) -> Unit,
    onChooseOutput: (WorkspaceTaskEntity) -> Unit,
    onUploadResponse: (WorkspaceTaskEntity) -> Unit,
    onUploadArtifact: (WorkspaceTaskEntity) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Create a persistent task", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = title, onValueChange = onTitle, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Task title") })
                    Button(onClick = onCreate, enabled = title.isNotBlank(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.AddTask, contentDescription = "Create task")
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescription,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description / execution instructions (optional)") },
                )
                OutlinedTextField(
                    value = expectedOutcome,
                    onValueChange = onExpectedOutcome,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Expected outcome (optional)") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategorySelector(category, onCategory, Modifier.weight(1f))
                    Button(onClick = { showDeadlinePicker(context, deadlineAt, onDeadlineAt) }) {
                        Text(deadlineAt?.let(::formatDeadline) ?: "Set due date & time")
                    }
                }
                if (deadlineAt != null) TextButton(onClick = { onDeadlineAt(null) }) { Text("Clear deadline") }
                Text("Priority: ${priority.toInt()} · higher values appear first", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                Slider(value = priority, onValueChange = onPriority, valueRange = -10f..10f, steps = 19)
                if (toolId.isBlank()) {
                    AiAppSelector(installedAiApps, selectedAiAppPackage, onSelectedAiApp)
                    if (selectedAiAppPackage != null) {
                        Text("The prompt is handed to the selected app through Android sharing. The app may still require you to tap Send.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = actionSearch,
                    onValueChange = onActionSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Find a registered local action (optional)") },
                )
                if (actionSearch.isNotBlank() && toolId.isBlank()) {
                    val matches = availableActions
                        .filter { it.name.contains(actionSearch, true) || it.description.contains(actionSearch, true) }
                        .take(30)
                    if (matches.isEmpty()) {
                        Text("No registered action matches that search.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    } else {
                        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            matches.forEach { action ->
                                Column(Modifier.fillMaxWidth().clickable { onSelectAction(action) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                                    Text("${action.name} · ${action.category}", color = AppTheme.colors.textPrimary, fontSize = 13.sp)
                                    Text(action.description, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                if (toolId.isNotBlank()) {
                    val definition = availableActions.firstOrNull { it.name == toolId }
                    Text("Selected registered action: $toolId · risk: ${ActionSchema.riskForAction(toolId).name}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    definition?.params?.takeIf { it.isNotEmpty() }?.let { params ->
                        Text("Input fields: " + params.joinToString { "${it.name}${if (it.required) " (required)" else ""}: ${it.description}" }, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                    OutlinedTextField(value = inputJson, onValueChange = onInputJson, modifier = Modifier.fillMaxWidth(), label = { Text("Action inputs (JSON string values)") })
                    Text("Action inputs are saved on this device. Do not put passwords or tokens here. Creating a tool task does not execute it; Approve & Run does.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
                if (tasks.isNotEmpty()) {
                    Text("PREREQUISITES (OPTIONAL)", color = AppTheme.colors.textPrimary, fontSize = 12.sp)
                    tasks.take(20).forEach { candidate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = candidate.id in selectedDependencies,
                                onCheckedChange = { checked ->
                                    if (checked && candidate.id !in selectedDependencies) selectedDependencies.add(candidate.id)
                                    if (!checked) selectedDependencies.remove(candidate.id)
                                },
                            )
                            Text("${candidate.title} · ${candidate.state}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                    if (selectedDependencies.isNotEmpty()) Text("Execution remains blocked until every prerequisite reaches SUCCEEDED.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                }
                if (statusMessage.isNotBlank()) Text(statusMessage, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
        if (tasks.isEmpty()) item { Text("No persistent tasks yet.", color = AppTheme.colors.textSecondary) }
        else items(tasks, key = { it.id }) { task ->
            val unmet = dependenciesByTask[task.id].orEmpty().any { taskStates[it] != TaskState.SUCCEEDED.name }
            TaskRow(
                task = task,
                dependencyIds = dependenciesByTask[task.id].orEmpty(),
                taskTitles = taskTitles,
                taskStates = taskStates,
                unmetDependencies = unmet,
                onTransition = { target -> onTransition(task, target) },
                onCancel = { onCancel(task) },
                onMarkManualComplete = { onManualComplete(task) },
                onRunTool = { onRunTool(task) },
                onStartAi = { onStartAi(task) },
                onCaptureAi = { onCaptureAi(task) },
                onStoreManualAiResponse = { response -> onStoreManualAiResponse(task, response) },
                onChooseOutput = { onChooseOutput(task) },
                onUploadResponse = { onUploadResponse(task) },
                onUploadArtifact = { onUploadArtifact(task) },
            )
        }
    }
}

@Composable
private fun CategorySelector(category: String, onCategory: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Category: $category") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TASK_CATEGORIES.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onCategory(option); expanded = false })
            }
        }
    }
}

@Composable
private fun AiAppSelector(apps: List<InstalledAiApp>, selectedPackage: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = apps.firstOrNull { it.packageName == selectedPackage }
    Column {
        Button(onClick = { expanded = true }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let { "AI app: ${it.family} (${it.label})" } ?: "Assign an installed AI app (optional)")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text("${app.family} · ${app.label}") },
                    onClick = { onSelect(app.packageName); expanded = false },
                )
            }
            if (apps.isEmpty()) DropdownMenuItem(text = { Text("No compatible AI apps currently available.") }, onClick = { expanded = false })
            if (selectedPackage != null) DropdownMenuItem(text = { Text("No external AI app") }, onClick = { onSelect(null); expanded = false })
        }
        if (apps.isEmpty()) Text("Install ChatGPT, Gemini, DeepSeek or Copilot to enable app handoff. API/local-provider triage remains separate.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun InboxTab(
    modifier: Modifier,
    inboxText: String,
    onInboxText: (String) -> Unit,
    triageRunning: Boolean,
    statusMessage: String,
    onTriage: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("INBOX · TRIAGE", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("Paste email text, notes or a raw goal. AI Triage sends it to the active provider configured in Settings, parses structured tasks, then saves them to the task list. No provider is hardcoded. Configure Ollama for a local/no-cloud-key option; cloud-provider billing is controlled by that provider.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = inboxText,
            onValueChange = onInboxText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            label = { Text("Paste inbox text or goal (max 20,000 characters)") },
        )
        Button(onClick = onTriage, enabled = inboxText.isNotBlank() && !triageRunning) { Text("AI Triage · extract and create tasks") }
        if (triageRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (statusMessage.isNotBlank()) Text(statusMessage, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun DashboardTab(modifier: Modifier, tasks: List<WorkspaceTaskEntity>) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val tomorrow = today.plusDays(1)
    val active = tasks.filter { it.state !in setOf(TaskState.SUCCEEDED.name, TaskState.CANCELLED.name) }
    val overdue = active.filter { task -> task.deadlineAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isBefore(today) } == true }
    val dueToday = active.filter { task -> task.deadlineAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true }
    val dueTomorrow = active.filter { task -> task.deadlineAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == tomorrow } == true }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("DEADLINE DASHBOARD", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold) }
        dashboardSection("OVERDUE", overdue)
        dashboardSection("TODAY", dueToday)
        dashboardSection("TOMORROW", dueTomorrow)
    }
}

private fun LazyListScope.dashboardSection(title: String, tasks: List<WorkspaceTaskEntity>) {
    item {
        Text(title, color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (tasks.isEmpty()) Text("No tasks due here.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
    }
    items(tasks, key = { "dashboard-$title-${it.id}" }) { task ->
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(task.title, color = AppTheme.colors.textPrimary)
                Text("${task.category} · ${task.state} · ${task.deadlineAt?.let(::formatDeadline) ?: "No due date"}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AnalyticsTab(modifier: Modifier, tasks: List<WorkspaceTaskEntity>) {
    val groups = remember(tasks) {
        val categories = (TASK_CATEGORIES + tasks.map { it.category }.filter { it !in TASK_CATEGORIES }).distinct()
        categories.map { category -> category to tasks.filter { it.category.equals(category, ignoreCase = true) } }
    }
    val completed = tasks.count { it.state == TaskState.SUCCEEDED.name }
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("COMPLETION ANALYTICS", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text("Overall: ${completionPercent(completed, tasks.size)}% · $completed of ${tasks.size} tasks are SUCCEEDED.", color = AppTheme.colors.textSecondary)
        groups.forEach { (category, group) ->
            val done = group.count { it.state == TaskState.SUCCEEDED.name }
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(category, color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("${completionPercent(done, group.size)}% · $done of ${group.size} completed", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        if (tasks.isEmpty()) Text("Create or triage tasks to see completion rates.", color = AppTheme.colors.textSecondary)
        Text("Rates use all persisted tasks; manual task completions are user-reported, not app-verified.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
    }
}

private fun completionPercent(completed: Int, total: Int): Int = if (total == 0) 0 else (completed * 100 / total)

@Composable
private fun TaskRow(
    task: WorkspaceTaskEntity,
    dependencyIds: List<String>,
    taskTitles: Map<String, String>,
    taskStates: Map<String, String>,
    unmetDependencies: Boolean,
    onTransition: (TaskState) -> Unit,
    onCancel: () -> Unit,
    onMarkManualComplete: () -> Unit,
    onRunTool: () -> Unit,
    onStartAi: () -> Unit,
    onCaptureAi: () -> Unit,
    onStoreManualAiResponse: (String) -> Unit,
    onChooseOutput: () -> Unit,
    onUploadResponse: () -> Unit,
    onUploadArtifact: () -> Unit,
) {
    val state = TaskState.valueOf(task.state)
    val assignedApp = !task.aiAppPackage.isNullOrBlank()
    var manualResponse by remember(task.id) { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${task.state} · ${task.category} · priority ${task.priority}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            task.deadlineAt?.let { Text("Due: ${formatDeadline(it)}", color = AppTheme.colors.textSecondary, fontSize = 12.sp) }
            if (assignedApp) Text("Assigned AI app: ${task.aiAppPackage}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (assignedApp) Text("Orchestration: ${task.orchestrationStage}", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            if (state == TaskState.SUCCEEDED && task.toolId.isNullOrBlank() && !assignedApp) {
                Text("Completed by user report; not verified by an app action.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (assignedApp && state == TaskState.SUCCEEDED) {
                Text("A user-selected file was readable and non-empty. Review file contents for semantic correctness.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (task.description.isNotBlank()) Text(task.description, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (task.expectedOutcome.isNotBlank()) Text("Expected: ${task.expectedOutcome}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (task.notes.isNotBlank()) {
                val visibleNotes = CrashLogRedactor.redact(task.notes).take(500)
                Text("Notes / captured response: $visibleNotes${if (task.notes.length > 500) "…" else ""}", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (dependencyIds.isNotEmpty()) {
                Text("PREREQUISITES", color = AppTheme.colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                dependencyIds.forEach { dependencyId ->
                    val dependencyState = taskStates[dependencyId]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dependencyState == TaskState.SUCCEEDED.name,
                            onCheckedChange = null,
                            enabled = false,
                        )
                        Text(
                            "${taskTitles[dependencyId] ?: "Missing task"} · ${dependencyState ?: "MISSING"}",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (!task.toolId.isNullOrBlank()) {
                val risk = ActionSchema.riskForAction(task.toolId)
                Text("Action: ${task.toolId} · risk: ${risk.name}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                val safeInputs = CrashLogRedactor.redact(task.inputJson)
                Text("Inputs: ${safeInputs.take(1_200)}${if (safeInputs.length > 1_200) "…" else ""}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                Text("Review the action and inputs before approving; approval runs it immediately.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            if (assignedApp && state !in setOf(TaskState.SUCCEEDED, TaskState.CANCELLED)) {
                if (task.orchestrationStage in setOf(TaskOrchestrationStage.PENDING.name, TaskOrchestrationStage.FAILED.name)) {
                    Button(onClick = onStartAi, enabled = !unmetDependencies) {
                        androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" Share prompt with AI app")
                    }
                }
                if (task.orchestrationStage == TaskOrchestrationStage.RUNNING.name && state == TaskState.WAITING_USER) {
                    Button(onClick = onStartAi, enabled = !unmetDependencies) { Text("Resume AI-app task") }
                }
                if (task.orchestrationStage == TaskOrchestrationStage.RUNNING.name) {
                    Text("The app may require you to press Send. Response capture is opt-in and only scoped to the assigned app.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCaptureAi) { Text("Capture accessible response") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    OutlinedTextField(value = manualResponse, onValueChange = { manualResponse = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Manual response paste fallback") })
                    TextButton(onClick = { onStoreManualAiResponse(manualResponse) }, enabled = manualResponse.isNotBlank()) { Text("Save pasted response · do not complete") }
                }
                if (task.orchestrationStage == TaskOrchestrationStage.VERIFYING.name) {
                    Text("AI response captured. A response alone does not complete this task.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Button(onClick = onChooseOutput) { Text("Select output file to review") }
                    TextButton(onClick = onUploadResponse) { Text("Save response text to Google Drive") }
                }
                if (task.orchestrationStage == TaskOrchestrationStage.COMPLETED.name && task.verifiedArtifactUri != null) {
                    TextButton(onClick = onUploadArtifact) { Text("Upload verified output file to Google Drive") }
                }
            }
            when (state) {
                TaskState.DRAFT -> if (!assignedApp) Button(onClick = { onTransition(TaskState.READY) }) { Text("Prepare") }
                TaskState.READY -> if (!assignedApp && !task.toolId.isNullOrBlank()) {
                    Button(onClick = onRunTool) { androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null); Text(" Approve & Run") }
                } else if (!assignedApp) Button(onClick = { onTransition(TaskState.QUEUED) }) { androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null); Text(" Queue") }
                TaskState.QUEUED -> if (!assignedApp && !task.toolId.isNullOrBlank()) Button(onClick = onRunTool) { Text("Approve & Run") }
                    else if (!assignedApp) Button(onClick = { onTransition(TaskState.RUNNING) }) { Text("Start manual task") }
                TaskState.RUNNING -> if (!assignedApp && task.toolId.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Manual task in progress; no app action is running.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onMarkManualComplete) { Text("MARK DONE (USER-REPORTED)") }
                            TextButton(onClick = onCancel) { Text("CANCEL") }
                        }
                    }
                } else if (!assignedApp) Button(onClick = onCancel) { Text("CANCEL ACTION") }
                TaskState.CANCELLED -> Text("Cancelled. If an external action had started, cancellation may not undo it.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                TaskState.FAILED -> if (!assignedApp) Button(onClick = { onTransition(TaskState.READY) }) { Text("Retry") }
                TaskState.WAITING_USER -> if (!assignedApp) Button(onClick = { onTransition(TaskState.READY) }) { Text("Resume") }
                else -> Unit
            }
        }
    }
}

private fun showDeadlinePicker(context: Context, existingAt: Long?, onDeadlineAt: (Long?) -> Unit) {
    val calendar = Calendar.getInstance().apply { if (existingAt != null) timeInMillis = existingAt }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onDeadlineAt(calendar.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                false,
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun formatDeadline(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMillis))
