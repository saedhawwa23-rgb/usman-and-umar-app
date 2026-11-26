package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.db.entities.NotebookEntity
import com.opendroid.ai.data.repository.NotebookPreview
import com.opendroid.ai.data.repository.NotebookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotebookUiState(
    val notebooks: List<NotebookEntity> = emptyList(),
    val selected: NotebookEntity? = null,
    val preview: NotebookPreview? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val repository: NotebookRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotebookUiState())
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()
    private val notebooks = repository.notebooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            notebooks.collect { list -> _state.value = _state.value.copy(notebooks = list) }
        }
        viewModelScope.launch { repository.ensureBuiltInNotebook() }
    }

    fun import(uri: Uri) {
        runBusy {
            repository.import(uri).fold(
                onSuccess = { result ->
                    val message = when {
                        result.duplicate -> "This notebook is already in your library; the existing copy was kept."
                        result.notebook.builtIn -> "Notebook already exists."
                        else -> "Notebook imported and saved privately on this device."
                    }
                    _state.value = _state.value.copy(message = message, error = null)
                },
                onFailure = { _state.value = _state.value.copy(error = it.message ?: "Notebook import failed.") },
            )
        }
    }

    fun select(notebook: NotebookEntity) {
        _state.value = _state.value.copy(selected = notebook, preview = null, error = null)
        viewModelScope.launch {
            repository.markOpened(notebook.id)
            repository.getPreview(notebook).fold(
                onSuccess = { _state.value = _state.value.copy(preview = it) },
                onFailure = { _state.value = _state.value.copy(error = it.message ?: "Notebook preview failed.") },
            )
        }
    }

    fun rename(notebook: NotebookEntity, name: String, description: String) = runBusy {
        repository.rename(notebook.id, name, description)
        _state.value = _state.value.copy(message = "Notebook renamed.", error = null)
    }

    fun delete(notebook: NotebookEntity) = runBusy {
        repository.delete(notebook)
        if (_state.value.selected?.id == notebook.id) _state.value = _state.value.copy(selected = null, preview = null)
        _state.value = _state.value.copy(message = "Notebook deleted.", error = null)
    }

    fun export(notebook: NotebookEntity, destination: Uri) = runBusy {
        repository.export(notebook, destination).fold(
            onSuccess = { _state.value = _state.value.copy(message = "Notebook exported.", error = null) },
            onFailure = { _state.value = _state.value.copy(error = it.message ?: "Notebook export failed.") },
        )
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, error = null)
            try { block() } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
