package com.opendroid.ai.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.UserRecoverableAuthException
import com.opendroid.ai.core.drive.GoogleDriveAccessTokenProvider
import com.opendroid.ai.core.drive.GoogleDriveUploader
import com.opendroid.ai.core.drive.GoogleDriveUploadResult
import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import com.opendroid.ai.data.repository.GoogleDriveAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GoogleDriveAccountsViewModel @Inject constructor(
    private val accountRepository: GoogleDriveAccountRepository,
    private val tokenProvider: GoogleDriveAccessTokenProvider,
    private val uploader: GoogleDriveUploader,
) : ViewModel() {
    val accounts: StateFlow<List<GoogleDriveAccountEntity>> = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _authorizationPrompts = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val authorizationPrompts = _authorizationPrompts.asSharedFlow()

    private var pendingAuthorizationEmail: String? = null

    fun connectAccount(accountEmail: String) {
        val email = accountEmail.trim()
        if (email.isBlank()) {
            _message.value = "Google did not return an account email. Try choosing the account again."
            return
        }
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val token = tokenProvider.getAccessToken(email)
                accountRepository.connect(email, token)
                val checked = uploader.refreshAllQuotas()
                val addedAccount = checked.firstOrNull { it.email.equals(email, ignoreCase = true) }
                _message.value = if (addedAccount?.quotaError == null && addedAccount?.quotaUpdatedAtMillis != null) {
                    "Connected $email. Its actual Google Drive storage quota was checked."
                } else {
                    "Connected $email, but Google did not return its storage quota. The other saved accounts were kept; choose this account again to renew Drive permission if needed."
                }
            } catch (recoverable: UserRecoverableAuthException) {
                pendingAuthorizationEmail = email
                val resolutionIntent = recoverable.intent
                if (resolutionIntent != null) {
                    _authorizationPrompts.emit(resolutionIntent)
                } else {
                    pendingAuthorizationEmail = null
                    _message.value = "Google requires renewed Drive permission for $email. Reopen the account picker and try again."
                }
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not connect this Google Drive account."
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun onAuthorizationResult(granted: Boolean) {
        val email = pendingAuthorizationEmail
        pendingAuthorizationEmail = null
        if (email == null) return
        if (granted) {
            connectAccount(email)
        } else {
            _message.value = "Drive permission was not granted for $email. The other connected accounts remain saved."
        }
    }

    fun refreshAllQuotas() {
        viewModelScope.launch {
            _isWorking.value = true
            try {
                val checked = uploader.refreshAllQuotas()
                val successful = checked.count { it.quotaUpdatedAtMillis != null && it.quotaError == null }
                _message.value = "Storage check complete: $successful of ${checked.size} accounts returned quota data."
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not refresh Google Drive storage."
            } finally {
                _isWorking.value = false
            }
        }
    }

    fun removeAccount(account: GoogleDriveAccountEntity) {
        viewModelScope.launch {
            runCatching { accountRepository.remove(account.email) }
                .onSuccess { _message.value = "${account.email} was removed from this app's storage pool." }
                .onFailure { _message.value = it.message ?: "Could not remove this Google account." }
        }
    }

    suspend fun uploadTaskOutput(filename: String, content: String): GoogleDriveUploadResult =
        uploader.uploadTaskOutput(filename, content)
}
