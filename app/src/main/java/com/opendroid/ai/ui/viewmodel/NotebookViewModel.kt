package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.UserRecoverableAuthException
import com.opendroid.ai.core.drive.DriveApiException
import com.opendroid.ai.core.drive.NotebookDriveUploader
import com.opendroid.ai.data.db.entities.NotebookEntity
import com.opendroid.ai.data.repository.NotebookPreview
import com.opendroid.ai.data.repository.NotebookLibraryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject

enum class NotebookColabStage {
    UPLOADING,
    UPLOADED,
    OPENING,
    OPENED,
    OPEN_FAILED,
    FAILED,
    INTERRUPTED,
}

data class NotebookColabHandoff(
    val notebookId: String,
    val notebookName: String,
    val stage: NotebookColabStage,
    val message: String,
    val driveFileId: String? = null,
    val driveUrl: String? = null,
    val colabUrl: String? = null,
    val contentVerified: Boolean = false,
    val reusedExisting: Boolean = false,
)

data class NotebookUiState(
    val notebooks: List<NotebookEntity> = emptyList(),
    val selected: NotebookEntity? = null,
    val preview: NotebookPreview? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val colabHandoff: NotebookColabHandoff? = null,
)

@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val repository: NotebookLibraryStore,
    private val driveUploader: NotebookDriveUploader,
) : ViewModel() {
    private val _state = MutableStateFlow(NotebookUiState())
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()
    private val notebooks = repository.notebooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val operationMutex = Mutex()
    // runBusy is launched on viewModelScope's main dispatcher, so this count is main-thread confined.
    private var pendingOperations = 0

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

    fun dismissPreview() {
        _state.update { it.copy(selected = null, preview = null) }
    }

    /** Upload the exact local notebook bytes, verify them in Drive, then let the UI open that file. */
    fun uploadAndOpenInColab(notebook: NotebookEntity) {
        _state.update {
            it.copy(
                message = null,
                error = null,
                colabHandoff = NotebookColabHandoff(
                    notebookId = notebook.id,
                    notebookName = notebook.name,
                    stage = NotebookColabStage.UPLOADING,
                    message = "Uploading the selected notebook to Google Drive and verifying its contents…",
                ),
            )
        }
        runBusy {
            try {
                val bytes = repository.readNotebookBytes(notebook)
                val result = driveUploader.uploadNotebook(notebook.name, bytes)
                check(result.contentVerified) { "Google Drive did not confirm the uploaded notebook contents." }
                _state.update {
                    it.copy(
                        error = null,
                        colabHandoff = NotebookColabHandoff(
                            notebookId = notebook.id,
                            notebookName = notebook.name,
                            stage = NotebookColabStage.UPLOADED,
                            message = if (result.reusedExisting) {
                                "Drive already contains this exact notebook. Its contents were verified; opening that file in Colab."
                            } else {
                                "Notebook uploaded to Drive as ${result.fileName} using ${result.accountEmail}; its bytes and file metadata were verified. Opening that exact file in Colab."
                            },
                            driveFileId = result.fileId,
                            driveUrl = result.driveUrl,
                            colabUrl = result.colabUrl,
                            contentVerified = true,
                            reusedExisting = result.reusedExisting,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        colabHandoff = it.colabHandoff?.copy(
                            stage = NotebookColabStage.INTERRUPTED,
                            message = "Upload was interrupted before Drive verification completed. The notebook remains local; retry to safely verify or reuse any completed Drive copy.",
                        ),
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                val message = transferFailureMessage(failure)
                _state.update {
                    it.copy(
                        error = message,
                        colabHandoff = it.colabHandoff?.copy(stage = NotebookColabStage.FAILED, message = message),
                    )
                }
            }
        }
    }

    fun markColabOpening(driveFileId: String) {
        _state.update { state ->
            val handoff = state.colabHandoff
            if (handoff?.driveFileId == driveFileId && handoff.stage == NotebookColabStage.UPLOADED && handoff.contentVerified) {
                state.copy(colabHandoff = handoff.copy(stage = NotebookColabStage.OPENING))
            } else state
        }
    }

    fun markColabOpened(driveFileId: String) {
        _state.update { state ->
            val handoff = state.colabHandoff
            if (handoff?.driveFileId == driveFileId && handoff.stage == NotebookColabStage.OPENING) {
                state.copy(colabHandoff = handoff.copy(
                    stage = NotebookColabStage.OPENED,
                    message = "Drive contents were verified and Android accepted the exact Colab link. The Colab page load and notebook execution are not confirmed; review and run it yourself in Colab.",
                ))
            } else state
        }
    }

    fun markColabOpenFailed(driveFileId: String) {
        _state.update { state ->
            val handoff = state.colabHandoff
            if (handoff?.driveFileId == driveFileId && handoff.stage == NotebookColabStage.OPENING) {
                state.copy(colabHandoff = handoff.copy(
                    stage = NotebookColabStage.OPEN_FAILED,
                    message = "The upload is verified in Google Drive, but this device could not open Colab. Open the verified Drive link manually; execution is not confirmed.",
                ))
            } else state
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

    private fun transferFailureMessage(failure: Exception): String {
        if (failure is UserRecoverableAuthException ||
            (failure is DriveApiException && failure.statusCode in setOf(401, 403))
        ) {
            return "Google Drive permission needs attention. Open Manage Drive accounts, reconnect or re-authorize the account, then retry. No successful upload was confirmed."
        }
        if (failure is DriveApiException) {
            return "Google Drive returned HTTP ${failure.statusCode}. Upload and content verification were not confirmed; check Drive access and retry."
        }
        if (failure is IOException) {
            return "Network interruption: Drive did not confirm this upload. The notebook remains local; check the connection and retry. A completed duplicate is verified and reused on retry."
        }
        val detail = failure.message.orEmpty()
        if (detail.contains("no Google Drive account", ignoreCase = true) ||
            detail.contains("add a Google account", ignoreCase = true) ||
            detail.contains("account is connected", ignoreCase = true)
        ) {
            return "No Google Drive account is connected. Open Manage Drive accounts, authorize a test account, then retry."
        }
        return detail.takeIf(String::isNotBlank)?.take(200)
            ?: "Drive upload or verification failed. The notebook remains local; check Drive access and retry."
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            pendingOperations++
            _state.update { it.copy(busy = true, message = null, error = null) }
            try {
                operationMutex.withLock { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update { it.copy(error = failure.message ?: "Notebook operation failed.") }
            } finally {
                pendingOperations--
                _state.update { it.copy(busy = pendingOperations > 0) }
            }
        }
    }
}
