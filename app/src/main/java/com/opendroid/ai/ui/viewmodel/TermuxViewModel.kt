package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.core.termux.TermuxResult
import com.opendroid.ai.core.termux.TermuxTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TermuxViewModel @Inject constructor(
    private val termux: TermuxIntegration
) : ViewModel() {
    val results: StateFlow<List<TermuxResult>> = termux.results
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

    private fun dispatch(label: String, operation: () -> Result<Int>) = viewModelScope.launch {
        refresh()
        operation().fold(
            onSuccess = { id -> _status.value = "Started $label in Termux (job $id). Waiting for its exit code and output…" },
            onFailure = { failure -> _status.value = failure.message ?: "Unable to start the Termux task." }
        )
    }

}
