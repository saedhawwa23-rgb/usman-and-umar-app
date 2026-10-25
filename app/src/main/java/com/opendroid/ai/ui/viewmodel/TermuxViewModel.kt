package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.repository.GoogleDriveAuthorizationException
import com.opendroid.ai.data.repository.GoogleDriveRepository
import com.opendroid.ai.data.repository.GoogleDriveSession
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.core.termux.TermuxActiveJob
import com.opendroid.ai.core.termux.TermuxResult
import com.opendroid.ai.core.termux.TermuxTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TermuxViewModel @Inject constructor(
    private val termux: TermuxIntegration,
    private val drive: GoogleDriveRepository,
    private val driveSession: GoogleDriveSession
) : ViewModel() {
    val results: StateFlow<List<TermuxResult>> = termux.results
    val activeJobs: StateFlow<List<TermuxActiveJob>> = termux.activeJobs
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _installed = MutableStateFlow(termux.isInstalled())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()
    private val _permissionGranted = MutableStateFlow(termux.hasRunCommandPermission())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    fun refresh() {
        _installed.value = termux.isInstalled()
        _permissionGranted.value = termux.hasRunCommandPermission()
    }

    fun run(task: TermuxTask) = dispatch(task.label) { termux.run(task) }

    fun cloneGitHubRepository(url: String) = dispatch("GitHub repository clone") {
        termux.cloneGitHubRepository(url)
    }

    fun installAgentRuntimes() = dispatch("OpenManus/OpenSwarm setup") {
        termux.installAgentRuntimes()
    }

    fun installPatchedProot() = dispatch("Patched PRoot runtime and loader installation") {
        termux.installPatchedProot()
    }

    fun abort(executionId: Int) = dispatch("SIGINT for job $executionId") {
        termux.abort(executionId)
    }

    fun saveTextToDrive(name: String, text: String) = saveToDrive(name, text)

    fun saveResultToDrive(result: TermuxResult) {
        saveToDrive(resultDriveFileName(result), resultDriveText(result))
    }

    fun resultDriveFileName(result: TermuxResult): String = "termux-job-${result.executionId}-output.txt"

    fun resultDriveText(result: TermuxResult): String = buildString {
        appendLine("Task: ${result.taskLabel}")
        appendLine("Job: ${result.executionId}")
        appendLine("Completed: ${result.completedAtMs}")
        appendLine("Exit code: ${result.exitCode ?: "unknown"}")
        result.error?.let { appendLine("Error: $it") }
        if (result.stdout.isNotBlank()) {
            appendLine()
            appendLine("stdout:")
            appendLine(result.stdout)
        }
        if (result.stderr.isNotBlank()) {
            appendLine()
            appendLine("stderr:")
            appendLine(result.stderr)
        }
    }

    private fun saveToDrive(name: String, text: String) = viewModelScope.launch(Dispatchers.IO) {
        val session = driveSession.snapshots.value
        val token = session.accessToken
        val email = session.accountEmail
        if (token.isNullOrBlank() || email.isNullOrBlank()) {
            _status.value = "Connect and authorize Google Drive first; the access token is kept in memory only."
            return@launch
        }
        _status.value = "Uploading $name to the connected Drive account $email…"
        runCatching { drive.uploadText(token, name, text) }
            .onSuccess { item ->
                _status.value = if (driveSession.currentGeneration() == session.generation) {
                    "Drive upload verified for $email: ${item.name} · ID ${item.id}."
                } else {
                    "Upload completed to the previously connected account $email before the session changed: ${item.name} · ID ${item.id}."
                }
            }
            .onFailure { failure ->
                if (failure is GoogleDriveAuthorizationException && driveSession.currentGeneration() == session.generation) driveSession.clear()
                _status.value = failure.message ?: "Drive upload failed; the local output remains available."
            }
    }

    private fun dispatch(label: String, operation: () -> Result<Int>) = viewModelScope.launch {
        refresh()
        operation().fold(
            onSuccess = { id -> _status.value = "Started $label in Termux (job $id). Waiting for its exit code and output…" },
            onFailure = { failure -> _status.value = failure.message ?: "Unable to start the Termux task." }
        )
    }

}
