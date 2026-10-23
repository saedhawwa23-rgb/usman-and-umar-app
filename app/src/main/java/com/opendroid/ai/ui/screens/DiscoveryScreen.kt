package com.opendroid.ai.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.llm.ModelFetchOutcome
import com.opendroid.ai.data.models.GitHubProject
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.DiscoveryViewModel

@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val githubResults by viewModel.githubResults.collectAsState()
    val aiResults by viewModel.aiResults.collectAsState()
    val savedProjects by viewModel.savedProjects.collectAsState()
    val openedRepository by viewModel.openedRepository.collectAsState()
    val savedProviders by viewModel.savedProviders.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var query by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DISCOVER EXTENSIONS", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("AI PROVIDERS") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("SEARCH GITHUB") })
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (tab == 0) "Provider name or model ID" else "Search public GitHub projects") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { if (tab == 0) viewModel.searchAI(query) else viewModel.searchGitHub(query) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "SEARCHING LIVE ENDPOINTS…" else "SEARCH") }
                    if (message != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(message!!, color = AppTheme.colors.textSecondary)
                    }
                }

                if (tab == 0) {
                    item {
                        Text(
                            "Models below are returned by the provider's live catalog endpoint. This does not by itself verify chat completion or tool calling. Configure credentials/endpoints in Settings where required.",
                            color = AppTheme.colors.textSecondary
                        )
                    }
                    items(aiResults, key = { it.provider }) { providerResult ->
                        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(providerResult.provider, fontWeight = FontWeight.Bold)
                                when (val outcome = providerResult.outcome) {
                                    is ModelFetchOutcome.Success -> {
                                        if (outcome.models.isEmpty()) Text("The provider returned no chat-capable models.", color = AppTheme.colors.textSecondary)
                                        outcome.models.take(30).forEach { liveModel ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(liveModel.displayName)
                                                    Text(liveModel.id, color = AppTheme.colors.textSecondary)
                                                    liveModel.contextWindow?.let { Text("Context: $it tokens", color = AppTheme.colors.textSecondary) }
                                                }
                                                OutlinedButton(onClick = { viewModel.selectModel(liveModel) }) { Text("USE") }
                                            }
                                        }
                                    }
                                    is ModelFetchOutcome.NeedsCredentials -> Text(outcome.message, color = AppTheme.colors.textSecondary)
                                    is ModelFetchOutcome.Failed -> Text(outcome.message, color = AppTheme.colors.accentRed)
                                }
                            }
                        }
                    }
                    if (savedProviders.isNotEmpty()) {
                        item { Text("ADDED AI PROVIDERS", fontWeight = FontWeight.Bold) }
                        items(savedProviders, key = { it.id }) { provider ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) { Text(provider.displayName); Text(provider.model, color = AppTheme.colors.textSecondary) }
                                OutlinedButton(onClick = { viewModel.useProvider(provider) }) { Text("USE") }
                            }
                        }
                    }
                } else {
                    item {
                        OutlinedTextField(directUrl, { directUrl = it }, Modifier.fillMaxWidth(), label = { Text("Direct GitHub URL or owner/repository") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.openRepository(directUrl) }, enabled = directUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("LOOK UP REPOSITORY") }
                        Text("Search and lookup use the public GitHub REST API. Lookup is read-only; private repositories require an authenticated integration and are not represented as public results.", color = AppTheme.colors.textSecondary)
                    }
                    openedRepository?.let { project ->
                        item {
                            Text("LOOKUP RESULT (NOT SAVED)", fontWeight = FontWeight.Bold)
                            DiscoveryProjectCard(project, onOpen = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, project.htmlUrl.toUri()))
                            }, onAdd = { viewModel.addProject(project) }, onUse = { viewModel.useProject(project) })
                            OutlinedButton(onClick = { viewModel.cloneRepository(project.cloneUrl) }, modifier = Modifier.fillMaxWidth()) {
                                Text("CLONE FULL HISTORY TO APP")
                            }
                        }
                    }
                    items(githubResults, key = { it.id }) { project ->
                        DiscoveryProjectCard(project, onOpen = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, project.htmlUrl.toUri()))
                        }, onAdd = { viewModel.addProject(project) }, onUse = { viewModel.useProject(project) })
                    }
                    item {
                        Text("SUPPORTED PROJECTS", fontWeight = FontWeight.Bold)
                        savedProjects.forEach { project ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(project.fullName, Modifier.weight(1f))
                                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, project.htmlUrl.toUri())) }) { Icon(Icons.Default.OpenInNew, "Open") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryProjectCard(
    project: GitHubProject,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onUse: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(project.fullName, fontWeight = FontWeight.Bold)
            Text(project.description.ifBlank { "No description provided." }, color = AppTheme.colors.textSecondary)
            Text("★ ${project.stars}${project.language?.let { " • $it" } ?: ""}", color = AppTheme.colors.textSecondary)
            if (project.defaultBranch.isNotBlank()) Text("Default branch: ${project.defaultBranch} · Forks: ${project.forks} · Open issues: ${project.openIssues}", color = AppTheme.colors.textSecondary)
            if (project.rootFiles.isNotEmpty()) Text("Root files: ${project.rootFiles.joinToString()}", color = AppTheme.colors.textSecondary)
            if (project.readme.isNotBlank()) Text("README preview:\n${project.readme.take(2_500)}", color = AppTheme.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("OPEN") }
                OutlinedButton(onClick = onAdd) { Text("ADD") }
                OutlinedButton(onClick = onUse) { Text("USE") }
            }
        }
    }
}
