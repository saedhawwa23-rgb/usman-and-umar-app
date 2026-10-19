package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.crash.CrashLogRedactor
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.WorkspaceTaskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTaskScreen(
    viewModel: WorkspaceTaskViewModel,
    modifier: Modifier = Modifier,
) {
    val tasks by viewModel.tasks.collectAsState()
    var title by remember { mutableStateOf("") }
    var toolId by remember { mutableStateOf("") }
    var inputJson by remember { mutableStateOf("{}") }
    var executionMessage by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("TASK MANAGER") }) },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        if (toolId.isBlank()) viewModel.createTask(title)
                        else viewModel.createToolTask(title, toolId, inputJson)
                        title = ""
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
                value = toolId,
                onValueChange = { toolId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Registered action ID (optional)") },
            )
            if (toolId.isNotBlank()) {
                OutlinedTextField(
                    value = inputJson,
                    onValueChange = { inputJson = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Action inputs (JSON string values)") },
                )
                Text("Action IDs must be registered in the app. Creating a task does not execute it.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            if (executionMessage.isNotBlank()) {
                Text(executionMessage, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (tasks.isEmpty()) {
                Text("No persistent tasks yet.", color = AppTheme.colors.textSecondary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onTransition = { viewModel.transition(task, it) },
                            onRunTool = { viewModel.runToolTask(task) { result ->
                                executionMessage = when (result) {
                                    is ActionResult.Success -> "Verified tool result: ${result.data ?: "Completed"}"
                                    else -> result.error ?: "Action did not complete"
                                }
                            } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: WorkspaceTaskEntity, onTransition: (TaskState) -> Unit, onRunTool: () -> Unit) {
    val state = TaskState.valueOf(task.state)
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(task.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("${task.state} · priority ${task.priority}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
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
                TaskState.QUEUED -> Button(onClick = { onTransition(TaskState.RUNNING) }) { Text("Start") }
                TaskState.RUNNING -> Button(onClick = { onTransition(TaskState.WAITING_USER) }) { Text("Pause for review") }
                TaskState.FAILED -> Button(onClick = { onTransition(TaskState.READY) }) { Text("Retry") }
                TaskState.WAITING_USER -> Button(onClick = { onTransition(TaskState.READY) }) { Text("Resume") }
                else -> Unit
            }
        }
    }
}
