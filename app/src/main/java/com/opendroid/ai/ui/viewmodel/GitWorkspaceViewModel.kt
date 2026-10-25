package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.git.GitWorkspaceBranch
import com.opendroid.ai.core.git.GitWorkspaceCommit
import com.opendroid.ai.core.git.GitWorkspaceRepository
import com.opendroid.ai.core.git.GitWorkspaceRepositoryInfo
import com.opendroid.ai.core.git.GitWorkspaceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GitWorkspaceUiState(
    val repositories: List<GitWorkspaceRepositoryInfo> = emptyList(),
    val selectedRepository: GitWorkspaceRepositoryInfo? = null,
    val branches: List<GitWorkspaceBranch> = emptyList(),
    val commits: List<GitWorkspaceCommit> = emptyList(),
    val status: GitWorkspaceStatus? = null,
    val selectedCommitId: String? = null,
    val selectedCommitPatch: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val hasMoreCommits: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val notice: String? = null
)

@HiltViewModel
class GitWorkspaceViewModel @Inject constructor(
    private val gitWorkspace: GitWorkspaceRepository
) : ViewModel() {
    private val _state = MutableStateFlow(GitWorkspaceUiState())
    val state: StateFlow<GitWorkspaceUiState> = _state.asStateFlow()

    init { refreshRepositories() }

    fun refreshRepositories() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { withContext(Dispatchers.IO) { gitWorkspace.listRepositories() } }
            .onSuccess { repos ->
                val selectedId = _state.value.selectedRepository?.id
                _state.update { it.copy(repositories = repos, loading = false) }
                repos.firstOrNull { it.id == selectedId }?.let { selectRepository(it) }
            }
            .onFailure { failure -> _state.update { it.copy(loading = false, error = failure.message ?: "Could not read local repositories.") } }
    }

    fun clone(url: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, progress = "Preparing a full-history clone…", error = null, notice = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                gitWorkspace.clone(url) { message -> _state.update { state -> state.copy(progress = message) } }
            }
        }.onSuccess { repository ->
            _state.update { it.copy(busy = false, progress = "", notice = "Cloned ${repository.name} with full Git history in app-private storage.") }
            selectRepository(repository)
        }.onFailure { failure -> _state.update { it.copy(busy = false, progress = "", error = failure.message ?: "Git clone failed.") } }
    }

    fun selectRepository(repository: GitWorkspaceRepositoryInfo) = viewModelScope.launch {
        _state.update { it.copy(selectedRepository = repository, loading = true, error = null, selectedCommitId = null, selectedCommitPatch = null) }
        loadRepository(repository.id, resetHistory = true)
    }

    fun showRepositoryList() {
        _state.update { it.copy(selectedRepository = null, selectedCommitId = null, selectedCommitPatch = null, error = null) }
    }

    fun checkout(branch: String) = mutate("Switched to $branch.") { id -> gitWorkspace.checkout(id, branch) }
    fun createBranch(name: String) = mutate("Created and checked out $name.") { id -> gitWorkspace.createBranch(id, name) }
    fun commit(message: String) = viewModelScope.launch {
        val repository = _state.value.selectedRepository ?: return@launch
        _state.update { it.copy(busy = true, error = null, notice = null) }
        runCatching { withContext(Dispatchers.IO) { gitWorkspace.commit(repository.id, message) } }
            .onSuccess { updated ->
                _state.update { it.copy(busy = false, notice = "Commit created locally. It has not been pushed to GitHub.") }
                loadRepository(updated.id, resetHistory = true)
            }.onFailure { failure -> _state.update { state -> state.copy(busy = false, error = failure.message ?: "Could not create commit.") } }
    }

    fun showCommitDiff(commit: GitWorkspaceCommit) = viewModelScope.launch {
        val repository = _state.value.selectedRepository ?: return@launch
        if (_state.value.selectedCommitId == commit.id) {
            _state.update { it.copy(selectedCommitId = null, selectedCommitPatch = null) }
            return@launch
        }
        _state.update { it.copy(loading = true, error = null) }
        runCatching { withContext(Dispatchers.IO) { gitWorkspace.commitDiff(repository.id, commit.id) } }
            .onSuccess { patch -> _state.update { it.copy(loading = false, selectedCommitId = commit.id, selectedCommitPatch = patch) } }
            .onFailure { failure -> _state.update { it.copy(loading = false, error = failure.message ?: "Could not load commit diff.") } }
    }

    fun loadOlderCommits() = viewModelScope.launch {
        val repository = _state.value.selectedRepository ?: return@launch
        val offset = _state.value.commits.size
        runCatching { withContext(Dispatchers.IO) { gitWorkspace.history(repository.id, offset, 101) } }
            .onSuccess { page -> _state.update { state -> state.copy(commits = state.commits + page.take(100), hasMoreCommits = page.size > 100) } }
            .onFailure { failure -> _state.update { it.copy(error = failure.message ?: "Could not load older commits.") } }
    }

    private fun mutate(message: String, action: (String) -> GitWorkspaceRepositoryInfo) = viewModelScope.launch {
        val repo = _state.value.selectedRepository ?: return@launch
        _state.update { it.copy(busy = true, error = null, notice = null) }
        runCatching { withContext(Dispatchers.IO) { action(repo.id) } }
            .onSuccess { updated -> _state.update { it.copy(busy = false, notice = message) }; loadRepository(updated.id, resetHistory = true) }
            .onFailure { failure -> _state.update { it.copy(busy = false, error = failure.message ?: "Git operation failed.") } }
    }

    private suspend fun loadRepository(id: String, resetHistory: Boolean) {
        runCatching {
            withContext(Dispatchers.IO) {
                val repositories = gitWorkspace.listRepositories()
                val info = repositories.firstOrNull { it.id == id } ?: error("Repository is no longer in the app workspace.")
                val branches = gitWorkspace.branches(id)
                val status = gitWorkspace.status(id)
                val commits = gitWorkspace.history(id, 0, 101)
                WorkspaceSnapshot(repositories, info, branches, status, commits.take(100), commits.size > 100)
            }
        }.onSuccess { snapshot ->
            _state.update { it.copy(repositories = snapshot.repositories, selectedRepository = snapshot.repository, branches = snapshot.branches, status = snapshot.status, loading = false, commits = if (resetHistory) snapshot.commits else it.commits, hasMoreCommits = snapshot.hasMore, selectedCommitId = null, selectedCommitPatch = null) }
        }.onFailure { failure -> _state.update { it.copy(loading = false, error = failure.message ?: "Could not load repository details.") } }
    }

    private data class WorkspaceSnapshot(
        val repositories: List<GitWorkspaceRepositoryInfo>,
        val repository: GitWorkspaceRepositoryInfo,
        val branches: List<GitWorkspaceBranch>,
        val status: GitWorkspaceStatus,
        val commits: List<GitWorkspaceCommit>,
        val hasMore: Boolean
    )
}
