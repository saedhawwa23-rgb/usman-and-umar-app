package com.opendroid.ai.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.crash.CrashLogRedactor
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.WorkspaceTaskViewModel
import com.usmanandumar.workspace.core.task.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTaskScreen(
    viewModel: WorkspaceTaskViewModel,
    modifier: Modifier = Modifier,
) {
    val tasks by viewModel.tasks.collectAsState()
    val dependenciesByTask by viewModel.dependenciesByTask.collectAsState()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var expectedOutcome by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(0f) }
    val selectedDependencies = remember { mutableStateListOf<String>() }
    var actionSearch by remember { mutableStateOf("") }
    var toolId by remember { mutableStateOf("") }
    var inputJson by remember { mutableStateOf("{}") }
    var statusMessage by remember { mutableStateOf("") }
    val taskTitles = remember(tasks) { tasks.associate { it.id to it.title } }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("TASK MANAGER") }) },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("New task") },
                        )
                        Button(
                            onClick = {
                                val onError: (String) -> Unit = { statusMessage = it }
                                if (toolId.isBlank()) {
                                    viewModel.createTask(
                                        title, description, expectedOutcome, priority.toInt(),
                                        selectedDependencies.toList(), onError,
                                    )
                                } else {
                                    viewModel.createToolTask(
                                        title, toolId, inputJson, description, expectedOutcome,
                                        priority.toInt(), selectedDependencies.toList(), onError,
                                    )
                                }
                                title = ""
                                description = ""
                                expectedOutcome = ""
                                priority = 0f
                                selectedDependencies.clear()
                                actionSearch = ""
                                toolId = ""
                                inputJson = "{}"
                            },
                            enabled = title.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            androidx.compose.material3.Icon(Icons.Default.AddTask, contentDescription = "Create task")
                        }
                    }
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description / execution instructions (optional)") },
                    )
                    OutlinedTextField(
                        value = expectedOutcome,
                        onValueChange = { expectedOutcome = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Expected outcome (optional)") },
                    )
                    Text("Priority: ${priority.toInt()} · higher values appear first", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Slider(value = priority, onValueChange = { priority = it }, valueRange = -10f..10f, steps = 19)
                    OutlinedTextField(
                        value = actionSearch,
                        onValueChange = { actionSearch = it; if (toolId.isNotBlank()) toolId = "" },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Find a registered action (optional)") },
                    )
                    if (actionSearch.isNotBlank() && toolId.isBlank()) {
                        val matches = viewModel.availableActions.filter {
                            it.name.contains(actionSearch, ignoreCase = true) || it.description.contains(actionSearch, ignoreCase = true)
                        }.take(30)
                        if (matches.isEmpty()) {
                            Text("No registered app action matches that search.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                        } else {
                            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                matches.forEach { action ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            toolId = action.name
                                            actionSearch = action.name
                                            inputJson = action.params.takeIf { it.isNotEmpty() }
                                                ?.joinToString(prefix = "{\n  ", postfix = "\n}", separator = ",\n  ") { "\"${it.name}\": \"\"" }
                                                ?: "{}"
                                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                                    ) {
                                        Text("${action.name} · ${action.category}", color = AppTheme.colors.textPrimary, fontSize = 13.sp)
                                        Text(action.description, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (toolId.isNotBlank()) {
                        val definition = viewModel.availableActions.firstOrNull { it.name == toolId }
                        Text(
                            "Selected registered action: $toolId · risk: ${ActionSchema.riskForAction(toolId)}",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        definition?.params?.takeIf { it.isNotEmpty() }?.let { params ->
                            Text(
                                "Input fields: " + params.joinToString {
                                    "${it.name}${if (it.required) " (required)" else ""}: ${it.description}"
                                },
                                color = AppTheme.colors.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        OutlinedTextField(
                            value = inputJson,
                            onValueChange = { inputJson = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Action inputs (JSON string values)") },
                        )
                        Text(
                            "Action inputs are saved with this task on the device. Do not put passwords, tokens, or other secrets here.",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp,
                        )
                        Text(
                            "Only a registered action can run. Creating the task does not execute it; the separate Approve & Run control does.",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp,
                        )
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
                        if (selectedDependencies.isNotEmpty()) {
                            Text(
                                "A task cannot be queued or run until every prerequisite reaches SUCCEEDED.",
                                color = AppTheme.colors.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (statusMessage.isNotBlank()) {
                        Text(statusMessage, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            if (tasks.isEmpty()) item { Text("No persistent tasks yet.", color = AppTheme.colors.textSecondary) }
            else items(tasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    dependencyIds = dependenciesByTask[task.id].orEmpty(),
                    taskTitles = taskTitles,
                    onTransition = { target -> viewModel.transition(task, target, onError = { statusMessage = it }) },
                    onCancel = { viewModel.cancelToolTask(task) },
                    onMarkManualComplete = {
                        viewModel.markManualTaskCompleted(task) { statusMessage = it }
                        statusMessage = "Completion is recorded as user-reported; no app action was claimed."
                    },
                    onRunTool = { viewModel.runToolTask(task) { result ->
                        statusMessage = when (result) {
                            is ActionResult.Success -> "Verified tool result: ${result.data ?: "Completed"}"
                            else -> result.error ?: "Action did not complete"
                        }
                    } },
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: WorkspaceTaskEntity,
    dependencyIds: List<String>,
    taskTitles: Map<String, String>,
    onTransition: (TaskState) -> Unit,
    onCancel: () -> Unit,
    onMarkManualComplete: () -> Unit,
    onRunTool: () -> Unit,
) {
    val state = TaskState.valueOf(task.state)
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(task.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("${task.state} · priority ${task.priority}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (state == TaskState.SUCCEEDED && task.toolId.isNullOrBlank()) {
                Text("Completed by user report; not verified by an app action.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            if (task.description.isNotBlank()) Text(task.description, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (task.expectedOutcome.isNotBlank()) Text("Expected: ${task.expectedOutcome}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            if (dependencyIds.isNotEmpty()) {
                Text("Depends on: ${dependencyIds.joinToString { taskTitles[it] ?: "missing task" }}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (!task.toolId.isNullOrBlank()) {
                val risk = ActionSchema.riskForAction(task.toolId)
                Text("Action: ${task.toolId} · risk: ${risk.name}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                val safeInputs = CrashLogRedactor.redact(task.inputJson)
                Text(
                    "Inputs: ${safeInputs.take(1_200)}${if (safeInputs.length > 1_200) "…" else ""}",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
                Text("Review the action and inputs before approving; approval runs it immediately.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                TaskState.DRAFT -> Button(onClick = { onTransition(TaskState.READY) }) { Text("Prepare") }
                TaskState.READY -> if (!task.toolId.isNullOrBlank()) {
                    Button(onClick = onRunTool) {
                        androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(" Approve & Run")
                    }
                } else Button(onClick = { onTransition(TaskState.QUEUED) }) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" Queue")
                }
                TaskState.QUEUED -> if (!task.toolId.isNullOrBlank()) Button(onClick = onRunTool) { Text("Approve & Run") }
                    else Button(onClick = { onTransition(TaskState.RUNNING) }) { Text("Start manual task") }
                TaskState.RUNNING -> if (task.toolId.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Manual task in progress; no app action is running.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onMarkManualComplete) { Text("MARK DONE (USER-REPORTED)") }
                            androidx.compose.material3.TextButton(onClick = onCancel) { Text("CANCEL") }
                        }
                    }
                } else Button(onClick = onCancel) { Text("CANCEL ACTION") }
                TaskState.CANCELLED -> Text(
                    "Cancelled. If an external action had already started, cancellation may not undo its effects.",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
                TaskState.FAILED -> Button(onClick = { onTransition(TaskState.READY) }) { Text("Retry") }
                TaskState.WAITING_USER -> Button(onClick = { onTransition(TaskState.READY) }) { Text("Resume") }
                else -> Unit
            }
        }
    }
}
