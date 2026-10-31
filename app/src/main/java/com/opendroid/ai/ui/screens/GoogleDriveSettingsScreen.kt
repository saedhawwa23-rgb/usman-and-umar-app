package com.opendroid.ai.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.opendroid.ai.ui.theme.AppTheme

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val TASK_OUTPUT_FOLDER = "USMAN_AND_UMAR_Tasks"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val driveScope = remember { Scope(DRIVE_FILE_SCOPE) }
    var connectedAccount by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun updateConnection(account: GoogleSignInAccount?) {
        connectedAccount = account?.takeIf { GoogleSignIn.hasPermissions(it, driveScope) }
    }

    LaunchedEffect(Unit) {
        updateConnection(GoogleSignIn.getLastSignedInAccount(context))
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .addOnSuccessListener { account ->
                    updateConnection(account)
                    message = if (GoogleSignIn.hasPermissions(account, driveScope)) {
                        "Drive access is connected for ${account.email ?: "the selected Google account"}."
                    } else {
                        "Google sign-in completed, but Drive.file permission was not granted. Try connecting again."
                    }
                }
                .addOnFailureListener { failure ->
                    val code = (failure as? ApiException)?.statusCode?.let(GoogleSignInStatusCodes::getStatusCodeString)
                    message = "Google Drive authorization failed${code?.let { " ($it)" }.orEmpty()}. Verify that the installed APK signing SHA-1 is registered for package com.usmanandumar.app."
                }
        } else {
            message = "Google Drive connection was cancelled before consent completed. Verify the Android OAuth client package and signing SHA-1, then try again."
        }
    }

    val signInOptions = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()
    }
    val signInClient = remember(context, signInOptions) { GoogleSignIn.getClient(context, signInOptions) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GOOGLE DRIVE") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (connectedAccount != null) "DRIVE CONNECTED" else "DRIVE NOT CONNECTED",
                        color = AppTheme.colors.textPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        connectedAccount?.email ?: "Connect a Google account to save completed task outputs.",
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }

            Text(
                "Task results are uploaded directly through the Google Drive API to the app folder “$TASK_OUTPUT_FOLDER”. Only the narrow drive.file scope is requested; no system document picker is used for task output.",
                color = AppTheme.colors.textSecondary,
            )

            Button(
                onClick = { signInLauncher.launch(signInClient.signInIntent) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (connectedAccount == null) "Connect Google Drive" else "Switch Google account")
            }

            if (connectedAccount != null) {
                OutlinedButton(
                    onClick = {
                        signInClient.signOut().addOnCompleteListener {
                            connectedAccount = null
                            message = "Google account signed out from this app."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign out") }
            }

            message?.let { Text(it, color = AppTheme.colors.textSecondary) }
        }
    }
}
