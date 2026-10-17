package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.UniversalAgentViewModel

@Composable
fun UniversalAgentScreen(viewModel: UniversalAgentViewModel = hiltViewModel()) {
    val events by viewModel.events.collectAsState()
    val running by viewModel.running.collectAsState()
    val cloudflare by viewModel.cloudflareState.collectAsState()
    var goal by remember { mutableStateOf("") }
    var cloudflareToken by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CLOUD AI TOOL AGENT", color = AppTheme.colors.textPrimary, fontSize = 22.sp)
        Text(
            "Uses the selected cloud provider's native tool-call protocol. Read-only and reversible tools may run; sensitive and destructive actions are denied until a real approval flow is implemented.",
            color = AppTheme.colors.textSecondary
        )
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe a task") },
            placeholder = { Text("Inspect this public GitHub repository") }
        )
        Button(onClick = { viewModel.run(goal) }, enabled = goal.isNotBlank() && !running) {
            Text(if (running) "RUNNING WITH CLOUD PROVIDER…" else "RUN WITH SELECTED PROVIDER")
        }
        Text("CLOUDFLARE API TOKEN (NO WORKER TARGET CONFIGURED)", color = AppTheme.colors.textPrimary, fontSize = 16.sp)
        Text("Status: ${cloudflare.status}", color = AppTheme.colors.textSecondary)
        Text(cloudflare.message, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        Text("This verifies the Cloudflare API token only. A deployed Worker URL and Worker health/deployment API have not been configured or tested.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        if (cloudflare.status.name == "CONNECTED") {
            TextButton(onClick = viewModel::disconnectCloudflare) { Text("DISCONNECT CLOUDFLARE") }
        } else {
            OutlinedTextField(
                value = cloudflareToken,
                onValueChange = { cloudflareToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cloudflare API token") },
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = { viewModel.connectCloudflare(cloudflareToken); cloudflareToken = "" },
                enabled = cloudflareToken.isNotBlank()
            ) { Text("CONNECT CLOUDFLARE") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                Text("${event.type.uppercase()}: ${event.message}", color = AppTheme.colors.textSecondary, fontSize = 13.sp)
            }
        }
    }
}
