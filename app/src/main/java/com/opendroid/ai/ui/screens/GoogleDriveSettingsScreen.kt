package com.opendroid.ai.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.drive.formatGoogleStorage
import com.opendroid.ai.core.drive.summarizeGoogleDrivePool
import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.GoogleDriveAccountsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveSettingsScreen(
    viewModel: GoogleDriveAccountsViewModel,
    onBack: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()
    val message by viewModel.message.collectAsState()
    val pool = summarizeGoogleDrivePool(accounts)

    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (email.isNullOrBlank()) {
                // The account picker can also be dismissed without selecting an account.
            } else if (type.isNullOrBlank() || type == GOOGLE_ACCOUNT_TYPE) {
                viewModel.connectAccount(email)
            }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onAuthorizationResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel) {
        viewModel.authorizationPrompts.collect { resolutionIntent ->
            consentLauncher.launch(resolutionIntent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GOOGLE DRIVE STORAGE POOL") },
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
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when {
                            pool.accountCount == 0 -> "Total Pool: No accounts connected"
                            !pool.isComplete -> "Total Pool: Checking storage (${pool.verifiedAccountCount}/${pool.accountCount} accounts verified)"
                            pool.includesUnlimitedAccount -> "Total Pool: Unlimited"
                            else -> "Total Pool: ${formatGoogleStorage(pool.totalLimitBytes ?: 0L)}"
                        },
                        color = AppTheme.colors.textPrimary,
                    )
                    if (pool.isComplete && pool.totalUsageBytes != null) {
                        Text(
                            text = "Used across Google services: ${formatGoogleStorage(pool.totalUsageBytes)}",
                            color = AppTheme.colors.textSecondary,
                        )
                        if (!pool.includesUnlimitedAccount && pool.totalLimitBytes != null) {
                            Text(
                                text = "Free: ${formatGoogleStorage((pool.totalLimitBytes - pool.totalUsageBytes).coerceAtLeast(0L))}",
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    }
                    Text(
                        text = "Pool totals use live Google Drive quota responses. Google Workspace accounts may share one organization quota, so their reported totals can overlap; this screen does not claim that Drive itself is physically merged.",
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val chooser = AccountManager.newChooseAccountIntent(
                            null,
                            null,
                            arrayOf(GOOGLE_ACCOUNT_TYPE),
                            "Choose a Google account to add to the storage pool",
                            null,
                            null,
                            null,
                        )
                        chooserLauncher.launch(chooser)
                    },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("+ Add Google Account")
                }
                OutlinedButton(
                    onClick = viewModel::refreshAllQuotas,
                    enabled = !isWorking && accounts.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Refresh storage")
                    }
                }
            }

            message?.let { Text(it, color = AppTheme.colors.textSecondary) }

            Text(
                text = "Connected accounts (${accounts.size})",
                color = AppTheme.colors.textPrimary,
            )

            if (accounts.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("No Google accounts added", color = AppTheme.colors.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add one or more accounts. The app checks each account's actual quota and chooses the account with the most free storage for task uploads.",
                            color = AppTheme.colors.textSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(accounts, key = { it.id }) { account ->
                        GoogleDriveAccountCard(account, onRemove = { viewModel.removeAccount(account) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleDriveAccountCard(
    account: GoogleDriveAccountEntity,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.email, color = AppTheme.colors.textPrimary)
                Text(accountQuotaDescription(account), color = AppTheme.colors.textSecondary)
                account.quotaError?.let { error ->
                    Text("Last check failed: $error", color = AppTheme.colors.textSecondary)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${account.email}")
            }
        }
    }
}

private fun accountQuotaDescription(account: GoogleDriveAccountEntity): String {
    val usage = account.quotaUsageBytes
    if (account.quotaUpdatedAtMillis == null || usage == null) return "Storage not checked yet"
    val limit = account.quotaLimitBytes
        ?: return "Unlimited quota · ${formatGoogleStorage(usage)} used across Google services"
    val free = (limit - usage).coerceAtLeast(0L)
    return "${formatGoogleStorage(free)} free of ${formatGoogleStorage(limit)} · ${formatGoogleStorage(usage)} used"
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
