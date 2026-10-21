package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.repository.GoogleDriveAccount
import com.opendroid.ai.data.repository.GoogleDriveItem
import com.opendroid.ai.data.repository.GoogleDriveRepository
import com.opendroid.ai.data.repository.GoogleDriveSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GoogleDriveUiState(
    val credentialEmail: String? = null,
    val connectedAccount: GoogleDriveAccount? = null,
    val files: List<GoogleDriveItem> = emptyList(),
    val currentFolderId: String = GoogleDriveRepository.ROOT_FOLDER_ID,
    val busy: Boolean = false,
    val progressPercent: Int? = null,
    val message: String? = null
)

@HiltViewModel
class GoogleDriveViewModel @Inject constructor(
    private val drive: GoogleDriveRepository,
    private val driveSession: GoogleDriveSession
) : ViewModel() {
    private val _state = MutableStateFlow(GoogleDriveUiState())
    val state: StateFlow<GoogleDriveUiState> = _state.asStateFlow()
    @Volatile private var authorizationGeneration = driveSession.currentGeneration()

    init {
        viewModelScope.launch {
            driveSession.snapshots.collect { session ->
                if (!session.isAuthorized) {
                    _state.update { current ->
                        val selectingIdentity = current.message == "Choose the Google account to connect."
                        if (current.connectedAccount != null || (current.busy && !selectingIdentity)) current.copy(
                            connectedAccount = null,
                            busy = false,
                            progressPercent = null,
                            message = "Drive authorization was cleared. Reauthorize before continuing."
                        ) else current
                    }
                }
            }
        }
    }

    @Synchronized
    fun currentAuthorizationGeneration(): Long {
        authorizationGeneration = driveSession.currentGeneration()
        return authorizationGeneration
    }

    fun beginGoogleIdentitySelection(): Long {
        authorizationGeneration = driveSession.clear()
        _state.value = GoogleDriveUiState(
            busy = true,
            message = "Choose the Google account to connect."
        )
        return authorizationGeneration
    }

    fun onGoogleIdentitySelected(email: String, expectedGeneration: Long) {
        if (expectedGeneration != authorizationGeneration) return
        _state.value = GoogleDriveUiState(
            credentialEmail = email,
            message = "Google account selected. Grant Drive permissions to verify access."
        )
    }

    fun onAuthorizationDenied(message: String, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != authorizationGeneration) return
        authorizationGeneration = driveSession.clear()
        _state.update { it.copy(connectedAccount = null, message = message, busy = false) }
    }

    fun disconnect(expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != authorizationGeneration) return
        authorizationGeneration = driveSession.clear()
        _state.value = GoogleDriveUiState(message = "Google account disconnected from this app.")
    }

    fun verifyAuthorization(token: String, expectedEmail: String, requestGeneration: Long = authorizationGeneration) {
        if (requestGeneration != authorizationGeneration || !_state.value.credentialEmail.equals(expectedEmail, ignoreCase = true)) return
        _state.update { it.copy(busy = true, message = "Verifying Google Drive access…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { drive.verifyAccount(token) }
                .onSuccess { account ->
                    if (requestGeneration != authorizationGeneration) return@onSuccess
                    if (!account.email.equals(expectedEmail, ignoreCase = true)) {
                        authorizationGeneration = driveSession.clear()
                        _state.update {
                            it.copy(
                                connectedAccount = null,
                                busy = false,
                                message = "The selected Google sign-in account did not match the Drive-authorized account. Switch accounts and retry."
                            )
                        }
                    } else {
                        if (!driveSession.connect(token, account.email, requestGeneration)) return@onSuccess
                        _state.update {
                            it.copy(
                                connectedAccount = account,
                                busy = false,
                                message = "Drive access verified for ${account.email}."
                            )
                        }
                        loadFiles()
                    }
                }
                .onFailure { failure -> if (requestGeneration == authorizationGeneration) fail(failure, requestGeneration) }
        }
    }

    fun loadFiles(searchText: String = "", folderId: String = _state.value.currentFolderId) {
        val session = driveSession.snapshots.value
        val token = session.accessToken ?: run {
            _state.update { it.copy(message = "Authorize Drive access before browsing files.") }
            return
        }
        _state.update { it.copy(busy = true, message = "Loading Google Drive files…", currentFolderId = folderId) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { drive.listFiles(token, searchText, folderId) }
                .onSuccess { files ->
                    if (session.generation != driveSession.currentGeneration()) return@onSuccess
                    _state.update {
                        it.copy(
                            files = files,
                            busy = false,
                            message = if (files.isEmpty()) "No accessible files or folders found. The current scopes do not grant unrestricted access to every Drive file." else "Loaded ${files.size} Drive items."
                        )
                    }
                }
                .onFailure { fail(it, session.generation) }
        }
    }

    fun upload(source: Uri) {
        val session = driveSession.snapshots.value
        val token = session.accessToken ?: return _state.update { it.copy(message = "Authorize Drive access before uploading.") }
        _state.update { it.copy(busy = true, progressPercent = 0, message = "Uploading selected file to My Drive root…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                drive.uploadFile(token, source) { sent, total ->
                    val percent = if (total <= 0) 0 else ((sent * 100) / total).toInt().coerceIn(0, 100)
                    _state.update { it.copy(progressPercent = percent) }
                }
            }.onSuccess { file ->
                if (session.generation != driveSession.currentGeneration()) return@onSuccess
                _state.update { it.copy(busy = false, progressPercent = null, message = "Upload verified: ${file.name} (Drive ID ${file.id}).") }
                loadFiles(folderId = GoogleDriveRepository.ROOT_FOLDER_ID)
            }.onFailure { fail(it, session.generation) }
        }
    }

    fun import(item: GoogleDriveItem) {
        val session = driveSession.snapshots.value
        val token = session.accessToken ?: return _state.update { it.copy(message = "Authorize Drive access before importing.") }
        _state.update { it.copy(busy = true, progressPercent = 0, message = "Importing ${item.name} into the local workspace…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                drive.importToWorkspace(token, item) { written, total ->
                    val percent = total?.takeIf { it > 0 }?.let { ((written * 100) / it).toInt().coerceIn(0, 100) }
                    _state.update { it.copy(progressPercent = percent) }
                }
            }.onSuccess { file ->
                if (session.generation != driveSession.currentGeneration()) return@onSuccess
                _state.update { it.copy(busy = false, progressPercent = null, message = "Import verified: ${file.name} (${file.length()} bytes) saved in the app workspace.") }
            }.onFailure { fail(it, session.generation) }
        }
    }

    fun export(item: GoogleDriveItem, destination: Uri) {
        val session = driveSession.snapshots.value
        val token = session.accessToken ?: return _state.update { it.copy(message = "Authorize Drive access before exporting.") }
        _state.update { it.copy(busy = true, progressPercent = 0, message = "Exporting ${item.name}…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                drive.exportToUri(token, item, destination) { written, total ->
                    val percent = total?.takeIf { it > 0 }?.let { ((written * 100) / it).toInt().coerceIn(0, 100) }
                    _state.update { it.copy(progressPercent = percent) }
                }
            }.onSuccess { bytes ->
                if (session.generation != driveSession.currentGeneration()) return@onSuccess
                _state.update { it.copy(busy = false, progressPercent = null, message = "Export verified: $bytes bytes written to the selected destination.") }
            }.onFailure { fail(it, session.generation) }
        }
    }

    private fun fail(failure: Throwable, requestGeneration: Long) {
        if (requestGeneration != driveSession.currentGeneration()) return
        if (failure is com.opendroid.ai.data.repository.GoogleDriveAuthorizationException) {
            authorizationGeneration = driveSession.clear()
            _state.update { it.copy(connectedAccount = null, busy = false, progressPercent = null, message = failure.message) }
        } else {
            _state.update { it.copy(busy = false, progressPercent = null, message = failure.message ?: "Google Drive operation failed. Check network, API activation, consent, and account permissions.") }
        }
    }
}
