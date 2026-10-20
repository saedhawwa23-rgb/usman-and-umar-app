package com.opendroid.ai.ui.screens

import android.app.Activity
import android.accounts.Account
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.data.repository.GoogleDriveItem
import com.opendroid.ai.data.repository.GoogleDriveRepository
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.GoogleDriveViewModel
import kotlinx.coroutines.launch

private const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Composable
fun GoogleDriveScreen(viewModel: GoogleDriveViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val coroutineScope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<GoogleDriveItem?>(null) }
    var folderStack by remember { mutableStateOf(listOf("root" to "My Drive")) }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching {
                val data = result.data ?: error("Google authorization returned no data.")
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            }.onSuccess { authResult ->
                acceptAuthorization(authResult, state.credentialEmail, viewModel)
            }.onFailure {
                viewModel.onAuthorizationDenied("Google Drive authorization could not be completed. Try granting access again.")
            }
        } else {
            viewModel.onAuthorizationDenied("Google Drive access was not granted.")
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.upload(uri)
    }
    val binaryExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) viewModel.export(item, uri)
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) viewModel.export(item, uri)
    }

    fun requestDriveAuthorization() {
        val selectedEmail = state.credentialEmail
        if (activity == null || selectedEmail.isNullOrBlank()) {
            viewModel.onAuthorizationDenied("Sign in with Google before requesting Drive access.")
            return
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE), Scope(DRIVE_FILE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) {
                        viewModel.onAuthorizationDenied("Google requested additional permission but did not provide an approval screen.")
                    } else {
                        authorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                } else {
                    acceptAuthorization(result, selectedEmail, viewModel)
                }
            }
            .addOnFailureListener {
                viewModel.onAuthorizationDenied("Google Drive authorization failed. Check account selection and OAuth configuration.")
            }
    }

    fun revokeDriveAccess() {
        val email = state.connectedAccount?.email ?: return
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(listOf(Scope(DRIVE_READONLY_SCOPE), Scope(DRIVE_FILE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(context).revokeAccess(request)
            .addOnSuccessListener {
                viewModel.disconnect()
                folderStack = listOf("root" to "My Drive")
                coroutineScope.launch {
                    runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
                }
            }
            .addOnFailureListener {
                viewModel.onAuthorizationDenied("Google could not revoke Drive access. The app cleared its in-memory token; retry revocation or remove this app in your Google Account security settings.")
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GOOGLE DRIVE", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Google account identity uses Credential Manager and the configured Web client ID. Drive access is separately requested for the actions below. No client secret or access token is stored on disk.",
                    color = AppTheme.colors.textSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "drive.readonly can expose all file metadata and content and is a restricted Google scope. drive.file limits writes to app-created or user-selected files. This screen browses existing files but uploads only to My Drive root; uploading into arbitrary existing folders is not enabled.",
                    color = AppTheme.colors.textSecondary
                )
            }

            if (state.connectedAccount == null) {
                item {
                    Button(
                        onClick = {
                            if (activity == null) {
                                viewModel.onAuthorizationDenied("Google sign-in requires an Android Activity context.")
                            } else {
                                coroutineScope.launch {
                                    try {
                                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_OAUTH_CLIENT_ID).build()
                                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                        val result = credentialManager.getCredential(activity, request)
                                        val credential = result.credential as? CustomCredential
                                        if (credential == null || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                            viewModel.onAuthorizationDenied("Google sign-in returned an unsupported credential.")
                                        } else {
                                            val googleId = GoogleIdTokenCredential.createFrom(credential.data)
                                            viewModel.onGoogleIdentitySelected(googleId.id)
                                        }
                                    } catch (_: GetCredentialException) {
                                        viewModel.onAuthorizationDenied("Google sign-in was cancelled or unavailable. Check that Google Play services and OAuth configuration are available.")
                                    } catch (_: Exception) {
                                        viewModel.onAuthorizationDenied("Google sign-in could not be completed. Check the Web OAuth client configuration.")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy
                    ) { Text(if (state.credentialEmail == null) "SIGN IN WITH GOOGLE" else "CHANGE GOOGLE ACCOUNT") }
                }
                if (state.credentialEmail != null) {
                    item {
                        Text("Selected account: ${state.credentialEmail}", color = AppTheme.colors.textPrimary)
                        Button(onClick = ::requestDriveAuthorization, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                            Text("AUTHORIZE AND VERIFY DRIVE ACCESS")
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Drive verified: ${state.connectedAccount?.displayName?.let { "$it · " }.orEmpty()}${state.connectedAccount?.email}",
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::requestDriveAuthorization, enabled = !state.busy) { Text("REFRESH ACCESS") }
                        OutlinedButton(
                            onClick = {
                                viewModel.disconnect()
                                folderStack = listOf("root" to "My Drive")
                                coroutineScope.launch {
                                    runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
                                }
                            },
                            enabled = !state.busy
                        ) { Text("SWITCH ACCOUNT") }
                    }
                    OutlinedButton(onClick = ::revokeDriveAccess, enabled = !state.busy) { Text("REVOKE GOOGLE DRIVE ACCESS") }
                }
                item {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search accessible Drive file names") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.loadFiles(searchText, folderStack.last().first) }, enabled = !state.busy) { Text("SEARCH / REFRESH") }
                        OutlinedButton(
                            onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.busy && folderStack.last().first == GoogleDriveRepository.ROOT_FOLDER_ID
                        ) { Text("UPLOAD TO MY DRIVE ROOT") }
                    }
                }
                if (folderStack.size > 1) {
                    item {
                        OutlinedButton(onClick = {
                            folderStack = folderStack.dropLast(1)
                            viewModel.loadFiles(searchText, folderStack.last().first)
                        }, enabled = !state.busy) { Text("UP: ${folderStack[folderStack.lastIndex - 1].second}") }
                    }
                }
                items(state.files, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth().then(if (item.isFolder) Modifier.clickable(enabled = !state.busy) {
                                    folderStack = folderStack + (item.id to item.name)
                                    searchText = ""
                                    viewModel.loadFiles("", item.id)
                                } else Modifier),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text(item.mimeType, color = AppTheme.colors.textSecondary)
                                }
                            }
                            if (!item.isFolder) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.import(item) }, enabled = !state.busy && item.canDownload) { Text("IMPORT TO WORKSPACE") }
                                    OutlinedButton(
                                        onClick = {
                                            pendingExport = item
                                            val title = if (item.isGoogleWorkspaceDocument && !item.name.endsWith(".pdf", true)) "${item.name}.pdf" else item.name
                                            if (item.isGoogleWorkspaceDocument) pdfExportLauncher.launch(title)
                                            else binaryExportLauncher.launch(title)
                                        },
                                        enabled = !state.busy && item.canDownload
                                    ) { Text(if (item.isGoogleWorkspaceDocument) "EXPORT PDF" else "SAVE AS") }
                                }
                            }
                        }
                    }
                }
            }

            if (state.busy) {
                item {
                    if (state.progressPercent != null) LinearProgressIndicator(progress = state.progressPercent!! / 100f, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            state.message?.let { message ->
                item { Text(message, color = AppTheme.colors.textSecondary) }
            }
        }
    }
}

private fun acceptAuthorization(
    result: AuthorizationResult,
    expectedEmail: String?,
    viewModel: GoogleDriveViewModel
) {
    val token = result.accessToken
    if (token.isNullOrBlank() || expectedEmail.isNullOrBlank()) {
        viewModel.onAuthorizationDenied("Google did not return a usable Drive access grant. Please authorize again.")
    } else {
        viewModel.verifyAuthorization(token, expectedEmail)
    }
}
import android.accounts.Account
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
