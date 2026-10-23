package com.opendroid.ai.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.git.GitWorkspaceCommit
import com.opendroid.ai.core.git.GitWorkspaceRepositoryInfo
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.GitWorkspaceViewModel

@Composable
fun GitWorkspaceScreen(viewModel: GitWorkspaceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var cloneUrl by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var showBranches by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("LOCAL GIT WORKSPACE", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::refreshRepositories) { Icon(Icons.Default.Refresh, contentDescription = "Refresh repositories") }
        }
        Text("Full-history clones are stored inside this app. Branch and commit operations remain local; this screen does not push changes.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = cloneUrl,
            onValueChange = { cloneUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Public GitHub repository URL") },
            placeholder = { Text("https://github.com/owner/repository") },
            enabled = !state.busy
        )
        Button(onClick = { viewModel.clone(cloneUrl) }, enabled = !state.busy && cloneUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("CLONE FULL HISTORY INTO APP WORKSPACE")
        }
        if (state.busy || state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Text("  ${state.progress.ifBlank { "Working…" }}", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
            }
        }
        state.error?.let { Text(it, color = AppTheme.colors.accentRed, modifier = Modifier.padding(vertical = 4.dp)) }
        state.notice?.let { Text(it, color = AppTheme.colors.accentCyan, modifier = Modifier.padding(vertical = 4.dp)) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            val selectedRepository = state.selectedRepository
            if (selectedRepository == null) {
                item { Text("APP WORKSPACE REPOSITORIES", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) }
                if (state.repositories.isEmpty()) {
                    item { Text("No repositories cloned yet. Paste a public GitHub URL above to start a full-history clone.", color = AppTheme.colors.textSecondary) }
                }
                items(state.repositories, key = { it.id }) { repository -> RepositoryCard(repository) { viewModel.selectRepository(repository) } }
            } else {
                val repository = selectedRepository
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showBranches = !showBranches }) {
                            Text("${repository.currentBranch}  ▾", color = AppTheme.colors.accentCyan)
                        }
                        Text(repository.name, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.refreshRepositories() }) { Text("REFRESH") }
                    }
                    Text(repository.remoteUrl, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                    if (showBranches) {
                        state.branches.forEach { branch ->
                            TextButton(onClick = { showBranches = false; if (!branch.isCurrent) viewModel.checkout(branch.name) }) {
                                Text("${if (branch.isCurrent) "✓ " else ""}${branch.name}${if (branch.isRemote) " (remote)" else ""}")
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(branchName, { branchName = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("New branch") }, enabled = !state.busy)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { viewModel.createBranch(branchName); branchName = "" }, enabled = branchName.isNotBlank() && !state.busy) { Text("CREATE") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("CHANGES (${state.status?.changedPaths?.size ?: 0})", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                    state.status?.changedPaths?.forEach { Text("• $it", color = AppTheme.colors.textSecondary, fontSize = 12.sp) }
                    state.status?.stagedPatch?.takeIf { it.isNotBlank() }?.let { PatchCard("STAGED DIFF", it) }
                    state.status?.workingPatch?.takeIf { it.isNotBlank() }?.let { PatchCard("WORKING TREE DIFF", it) }
                    if (state.status?.changedPaths.isNullOrEmpty()) Text("Working tree clean.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    OutlinedTextField(commitMessage, { commitMessage = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Commit message") }, enabled = !state.busy, maxLines = 2)
                    Button(onClick = { viewModel.commit(commitMessage); commitMessage = "" }, enabled = !state.busy && commitMessage.isNotBlank() && state.status?.changedPaths?.isNotEmpty() == true, modifier = Modifier.fillMaxWidth()) {
                        Text("STAGE ALL CHANGES & COMMIT LOCALLY")
                    }
                    Text("COMMIT HISTORY (${state.status?.commitCount ?: 0} total)", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary, modifier = Modifier.padding(top = 12.dp))
                }
                items(state.commits, key = { it.id }) { commit ->
                    CommitCard(commit, expanded = state.selectedCommitId == commit.id, patch = state.selectedCommitPatch, onClick = { viewModel.showCommitDiff(commit) })
                }
                if (state.hasMoreCommits) item { OutlinedButton(onClick = viewModel::loadOlderCommits, modifier = Modifier.fillMaxWidth()) { Text("LOAD OLDER COMMITS") } }
                item {
                    TextButton(onClick = { viewModel.showRepositoryList() }) { Text("BACK TO REPOSITORY LIST") }
                }
            }
        }
    }
}

@Composable
private fun RepositoryCard(repository: GitWorkspaceRepositoryInfo, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(repository.name, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            Text("${repository.currentBranch} · ${repository.headCommit?.take(10) ?: "empty"}", color = AppTheme.colors.accentCyan, fontSize = 12.sp)
            Text(repository.remoteUrl, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CommitCard(commit: GitWorkspaceCommit, expanded: Boolean, patch: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(commit.shortMessage, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
            Text("${commit.id.take(10)} · ${commit.author}", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            if (expanded) PatchCard("COMMIT DIFF", patch.orEmpty())
        }
    }
}

@Composable
private fun PatchCard(title: String, patch: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = AppTheme.colors.background)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = AppTheme.colors.accentCyan, fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp))
            SelectionContainer {
                Text(patch.ifBlank { "No diff available." }, fontFamily = FontFamily.Monospace, color = AppTheme.colors.textPrimary, fontSize = 10.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}
