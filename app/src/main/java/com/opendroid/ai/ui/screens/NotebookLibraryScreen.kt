package com.opendroid.ai.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.data.db.entities.NotebookEntity
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.NotebookColabStage
import com.opendroid.ai.ui.viewmodel.NotebookViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookLibraryScreen(
    viewModel: NotebookViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenDriveSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var colabConsentTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var renameName by remember { mutableStateOf("") }
    var renameDescription by remember { mutableStateOf("") }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.import(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ipynb+json")) { uri ->
        val selected = state.selected
        if (uri != null && selected != null) viewModel.export(selected, uri)
    }
    val filtered = state.notebooks.filter {
        query.isBlank() || it.name.lowercase(Locale.ROOT).contains(query.trim().lowercase(Locale.ROOT)) ||
            it.description.lowercase(Locale.ROOT).contains(query.trim().lowercase(Locale.ROOT))
    }.let { matches -> if (sortByName) matches.sortedBy { it.name.lowercase(Locale.ROOT) } else matches }

    LaunchedEffect(state.message, state.error) {
        if (state.message != null || state.error != null) kotlinx.coroutines.delay(3500)
        viewModel.clearMessage()
    }

    val handoff = state.colabHandoff
    LaunchedEffect(handoff?.driveFileId, handoff?.colabUrl, handoff?.stage, handoff?.contentVerified) {
        val fileId = handoff?.driveFileId ?: return@LaunchedEffect
        if (handoff.stage != NotebookColabStage.UPLOADED || !handoff.contentVerified) return@LaunchedEffect
        try {
            val url = Uri.parse(handoff.colabUrl)
            require(url.scheme == "https" && url.host == "colab.research.google.com" && url.path == "/drive/$fileId") {
                "Verified Drive file did not provide a matching Google Colab URL."
            }
            viewModel.markColabOpening(fileId)
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            // This records that Android accepted the exact verified Colab URL, not that it loaded or ran.
            viewModel.markColabOpened(fileId)
        } catch (_: ActivityNotFoundException) {
            viewModel.markColabOpenFailed(fileId)
        } catch (_: Exception) {
            viewModel.markColabOpenFailed(fileId)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BrandHeader(title = "Notebook Library", subtitle = "Import, review and safely manage Jupyter notebooks", icon = Icons.Default.Description)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton(
                text = "Import notebook",
                onClick = { importPicker.launch(arrayOf("application/x-ipynb+json", "application/json", "text/plain", "*/*")) },
                icon = Icons.Default.UploadFile,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { sortByName = !sortByName }) {
                Icon(Icons.Default.Sort, contentDescription = if (sortByName) "Sort by recently modified" else "Sort by name")
            }
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        }
        TextButton(onClick = onOpenDriveSettings, modifier = Modifier.padding(start = 12.dp)) {
            Text("Manage Drive accounts")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Search notebooks") },
        )
        state.message?.let { Text(it, color = AppTheme.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        state.error?.let { Text(it, color = AppTheme.colors.accentRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                Text(
                    if (handoff?.stage == NotebookColabStage.UPLOADING) "Uploading and verifying in Google Drive…" else "Importing or saving notebook…",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item {
                PlatformCard(title = "No notebooks yet", subtitle = "Choose a .ipynb file from Android storage, Google Drive or another provider.", icon = Icons.Default.Description) {
                    Text("The built-in OpenSwarm notebook will appear here after the library initializes.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                }
            }
            items(filtered, key = { it.id }) { notebook ->
                PlatformCard(
                    title = notebook.name,
                    subtitle = if (notebook.builtIn) "Built-in OpenSwarm setup" else "Imported · ${notebook.sizeBytes / 1024} KB · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(notebook.updatedAtMillis))}",
                    icon = Icons.Default.Description,
                ) {
                    Text(notebook.description.ifBlank { "No description" }, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        TextButton(onClick = { viewModel.select(notebook) }) { Text("Preview") }
                        TextButton(onClick = {
                            renameTarget = notebook; renameName = notebook.name.removeSuffix(".ipynb"); renameDescription = notebook.description
                        }) { Icon(Icons.Default.Edit, null); Text("Rename") }
                        TextButton(onClick = { viewModel.select(notebook); exportPicker.launch(notebook.name) }) { Icon(Icons.Default.Download, null); Text("Export") }
                        if (!notebook.builtIn) TextButton(onClick = { deleteTarget = notebook }) { Icon(Icons.Default.Delete, null); Text("Delete") }
                    }
                    TextButton(
                        onClick = { colabConsentTarget = notebook },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Text("Open in Google Colab", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    state.colabHandoff?.takeIf { it.notebookId == notebook.id }?.let { transfer ->
                        val color = if (transfer.stage == NotebookColabStage.FAILED || transfer.stage == NotebookColabStage.OPEN_FAILED) {
                            AppTheme.colors.accentRed
                        } else AppTheme.colors.textSecondary
                        Text(
                            "Drive: ${driveStatus(transfer.stage)} · Colab: ${colabStatus(transfer.stage)} · Execution: ${executionStatus(transfer.stage)}",
                            color = color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(transfer.message, color = color, fontSize = 11.sp)
                        if (transfer.stage == NotebookColabStage.FAILED || transfer.stage == NotebookColabStage.OPEN_FAILED) {
                            TextButton(onClick = onOpenDriveSettings) { Text("Manage Drive accounts") }
                        }
                    }
                }
            }
        }
    }

    colabConsentTarget?.let { notebook ->
        AlertDialog(
            onDismissRequest = { colabConsentTarget = null },
            title = { Text("Open ${notebook.name} in Google Colab?") },
            text = {
                Column {
                    Text("This uploads the selected notebook to the connected Google Drive account, verifies the uploaded file contents, then hands that exact Drive file's Colab URL to an app or browser.")
                    Spacer(Modifier.height(8.dp))
                    Text("The notebook stays on this device. OpenSwarm cannot confirm the Colab page loaded and does not execute notebook code; review it and run it yourself in Colab.", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                    TextButton(onClick = { colabConsentTarget = null; onOpenDriveSettings() }) {
                        Text("Manage Drive accounts")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.uploadAndOpenInColab(notebook); colabConsentTarget = null }) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Text("Upload & open")
                }
            },
            dismissButton = { TextButton(onClick = { colabConsentTarget = null }) { Text("Cancel") } },
        )
    }

    state.selected?.let { selected ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPreview() },
            title = { Text(selected.name) },
            text = {
                Column {
                    Text("Safe preview · cells are never executed", fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    state.preview?.let { preview ->
                        Text("${preview.cells} cells · ${preview.codeCells} code · ${preview.markdownCells} Markdown", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(preview.previewText.ifBlank { "No Markdown or code source preview available." }, fontSize = 12.sp)
                    } ?: Text("Loading preview…", fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Execution requires an explicit action in Colab. This screen does not run notebook code.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { exportPicker.launch(selected.name) }) { Icon(Icons.Default.Download, null); Text("Export") }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissPreview() }) { Text("Close") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename notebook") },
            text = {
                Column {
                    OutlinedTextField(renameName, { renameName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(renameDescription, { renameDescription = it }, label = { Text("Description") }, singleLine = false)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.rename(target, renameName, renameDescription); renameTarget = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete notebook?") },
            text = { Text("${target.name} will be removed from the library. Its original provider file will not be deleted.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(target); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun driveStatus(stage: NotebookColabStage): String = when (stage) {
    NotebookColabStage.UPLOADING -> "uploading / not verified"
    NotebookColabStage.UPLOADED, NotebookColabStage.OPENING, NotebookColabStage.OPENED, NotebookColabStage.OPEN_FAILED -> "uploaded and verified"
    NotebookColabStage.FAILED -> "failed / not confirmed"
    NotebookColabStage.INTERRUPTED -> "interrupted / not verified"
}

private fun colabStatus(stage: NotebookColabStage): String = when (stage) {
    NotebookColabStage.UPLOADING, NotebookColabStage.FAILED, NotebookColabStage.INTERRUPTED -> "not opened"
    NotebookColabStage.UPLOADED -> "opening"
    NotebookColabStage.OPENING -> "opening"
    NotebookColabStage.OPENED -> "link handed off (page load unconfirmed)"
    NotebookColabStage.OPEN_FAILED -> "failed to open"
}

private fun executionStatus(stage: NotebookColabStage): String = when (stage) {
    NotebookColabStage.UPLOADED, NotebookColabStage.OPENING, NotebookColabStage.OPENED, NotebookColabStage.OPEN_FAILED -> "not confirmed"
    NotebookColabStage.UPLOADING, NotebookColabStage.FAILED, NotebookColabStage.INTERRUPTED -> "not run by app"
}
