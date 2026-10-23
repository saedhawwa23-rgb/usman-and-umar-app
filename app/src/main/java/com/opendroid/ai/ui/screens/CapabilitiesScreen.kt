package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.DeveloperToolCategory
import com.opendroid.ai.ui.viewmodel.DeveloperToolEntry
import com.opendroid.ai.ui.viewmodel.DeveloperToolsViewModel

/** Developer catalog backed by the same registry used for cloud-agent tool execution. */
@Composable
fun CapabilitiesScreen(
    viewModel: DeveloperToolsViewModel,
    onSearchAI: () -> Unit,
    onSearchGitHub: () -> Unit,
    onTermux: () -> Unit,
    onUniversalAgent: () -> Unit,
    onGitWorkspace: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("DEVELOPER TOOLS A–Z", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Live entries from the shared on-device connector registry. Tool names are sorted alphabetically inside each category.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
        }
        items(categories, key = { it.name }) { category -> ToolCategorySection(category) }
        item {
            Text("OPEN WORKSPACES & DISCOVERY", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        }
        item { CapabilityCard("LOCAL GIT WORKSPACE", "Full-history clone into app-private storage, with local branches, commits, and diffs.", Icons.Default.Code, onGitWorkspace) }
        item { CapabilityCard("SEARCH AI PROVIDERS", "Discover provider/model catalogs; lookup does not itself verify chat or tool support.", Icons.Default.Search, onSearchAI) }
        item { CapabilityCard("SEARCH GITHUB PROJECTS", "Search public repositories, preview files, save projects, or clone to the local workspace.", Icons.Default.Code, onSearchGitHub) }
        item { CapabilityCard("TERMUX INTEGRATION", "Check Termux and run supported allowlisted tasks with visible output.", Icons.Default.Terminal, onTermux) }
        item { CapabilityCard("CLOUD AI TOOL AGENT", "Use configured cloud-provider tool calls with the shared registry and approval controls.", Icons.Default.Extension, onUniversalAgent) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ToolCategorySection(category: DeveloperToolCategory) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(category.name, color = AppTheme.colors.accentCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (category.tools.isEmpty()) {
                Text("No compatible tools currently available.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            } else {
                category.tools.forEach { ToolRow(it) }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: DeveloperToolEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(tool.name, color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("${tool.connectorName} · ${tool.risk.name.lowercase()}", color = AppTheme.colors.accentCyan, fontSize = 10.sp)
        Text(tool.description, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun CapabilityCard(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AppTheme.colors.accentCyan)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(3.dp))
                Text(description, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            }
            TextButton(onClick = onClick) { Text("OPEN") }
        }
    }
}
