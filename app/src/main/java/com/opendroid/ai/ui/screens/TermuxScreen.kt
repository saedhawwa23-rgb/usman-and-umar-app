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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandOutlineButton
import com.opendroid.ai.ui.components.BrandedBackHeader
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.StatusPill
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
            BrandedBackHeader(
                title = "Termux · Linux",
                subtitle = "On-device development environment",
                onBack = onBack,
                icon = Icons.Default.Terminal,
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PlatformCard(
                    title = "Linux environment",
                    subtitle = "Local execution · explicit permission required",
                    icon = Icons.Default.Terminal,
                    status = (if (installed) "Termux detected" else "Not installed") to
                        (if (installed) PlatformStatus.HEALTHY else PlatformStatus.WARNING),
                ) {
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
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton(onClick = { viewModel.refresh() }, modifier = Modifier.weight(1f), text = "CHECK STATUS")
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("DONE") }
                }
            }
            if (installed && !permissionGranted) {
                item {
                    PlatformCard(
                        title = "Termux permission",
                        subtitle = "Required before a command can run",
                        icon = Icons.Default.Terminal,
                        status = "Needs approval" to PlatformStatus.WARNING,
                    ) {
                    Text("Android must grant ‘Run commands in Termux environment’ before any Termux command can start.", color = AppTheme.colors.textSecondary)
                    BrandButton(onClick = { permissionLauncher.launch(TermuxIntegration.RUN_COMMAND_PERMISSION) }, modifier = Modifier.fillMaxWidth(), text = "GRANT COMMAND ACCESS")
                    }
                }
            }
            if (installed) {
                item {
                    PlatformCard(
                        title = "Local development tasks",
                        subtitle = "Run a real Termux task on this device",
                        icon = Icons.Default.Code,
                        status = (if (permissionGranted) "Permission granted" else "Permission required") to
                            (if (permissionGranted) PlatformStatus.HEALTHY else PlatformStatus.WARNING),
                    ) {
                        TermuxTask.entries.forEach { task ->
                            BrandOutlineButton(onClick = { viewModel.run(task) }, modifier = Modifier.fillMaxWidth(), enabled = permissionGranted, text = task.label, icon = Icons.Default.Terminal)
                        }
                    }
                }
                item {
                    PlatformCard(title = "Clone a public repository", subtitle = "Clone into the Termux home directory", icon = Icons.Default.Code) {
                    OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("https://github.com/owner/repository") },
                        singleLine = true
                    )
                    BrandButton(
                        onClick = { viewModel.cloneGitHubRepository(repositoryUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = repositoryUrl.isNotBlank() && permissionGranted,
                        text = "CLONE IN TERMUX",
                    )
                    }
                }
            }
            if (status != null) item { StatusPill(status!!, if (status!!.contains("error", ignoreCase = true)) PlatformStatus.ERROR else PlatformStatus.INFO) }
            if (results.isNotEmpty()) {
                item { Text("RECENT RUNS", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) }
                items(results.size) { index ->
                    val output = results[results.lastIndex - index]
                    PlatformCard(
                        title = output.taskLabel,
                        subtitle = if (output.error != null) "Termux error" else "Exit ${output.exitCode}",
                        icon = Icons.Default.Terminal,
                        status = (if (output.error != null) "Failed" else "Completed") to
                            (if (output.error != null) PlatformStatus.ERROR else PlatformStatus.HEALTHY),
                    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
}
