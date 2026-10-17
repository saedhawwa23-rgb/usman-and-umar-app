package com.opendroid.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.core.termux.TermuxTask
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.TermuxViewModel

@Composable
fun TermuxScreen(viewModel: TermuxViewModel, onBack: () -> Unit) {
    val installed by viewModel.installed.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()
    val status by viewModel.status.collectAsState()
    val results by viewModel.results.collectAsState()
    var repositoryUrl by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TERMUX INTEGRATION", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    if (installed) "Termux detected on this device." else "Termux is not installed. Normal app operation does not require it.",
                    color = if (installed) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Termux must also have allow-external-apps=true in ~/.termux/termux.properties. Commands run only after you tap a task; arbitrary shell input is not accepted.",
                    color = AppTheme.colors.textSecondary
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f)) { Text("CHECK") }
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("DONE") }
                }
            }
            if (installed && !permissionGranted) {
                item {
                    Text("Android must grant ‘Run commands in Termux environment’ before any Termux command can start.", color = AppTheme.colors.textSecondary)
                    Button(onClick = { permissionLauncher.launch(TermuxIntegration.RUN_COMMAND_PERMISSION) }) {
                        Text("GRANT TERMUX COMMAND ACCESS")
                    }
                }
            }
            if (installed) {
                item { Text("LOCAL TASKS", fontWeight = FontWeight.Bold) }
                items(TermuxTask.entries.size) { index ->
                    val task = TermuxTask.entries[index]
                    OutlinedButton(onClick = { viewModel.run(task) }, modifier = Modifier.fillMaxWidth(), enabled = permissionGranted) { Text(task.label) }
                }
                item {
                    Text("CLONE A PUBLIC GITHUB REPOSITORY", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("https://github.com/owner/repository") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { viewModel.cloneGitHubRepository(repositoryUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = repositoryUrl.isNotBlank() && permissionGranted
                    ) { Text("CLONE IN TERMUX HOME") }
                }
            }
            if (status != null) item { Text(status!!, color = AppTheme.colors.textSecondary) }
            if (results.isNotEmpty()) {
                item { Text("RECENT TERMUX JOBS", fontWeight = FontWeight.Bold) }
                items(results.size) { index ->
                    val output = results[results.lastIndex - index]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${output.taskLabel} · ${if (output.error != null) "Termux error" else "exit ${output.exitCode}"}", fontWeight = FontWeight.Bold)
                        if (output.error != null) Text(output.error, color = AppTheme.colors.accentRed)
                        if (output.stdout.isNotBlank()) Text(output.stdout, color = AppTheme.colors.textSecondary)
                        if (output.stderr.isNotBlank()) Text("stderr: ${output.stderr}", color = AppTheme.colors.accentRed)
                        if (output.stdout.isBlank() && output.stderr.isBlank() && output.error == null) Text("Command completed without output.", color = AppTheme.colors.textSecondary)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
