package com.opendroid.ai.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.NotebookViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotebookLibraryScreen(
    viewModel: NotebookViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.opendroid.ai.data.db.entities.NotebookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.opendroid.ai.data.db.entities.NotebookEntity?>(null) }
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

    Column(Modifier.fillMaxSize()) {
        BrandHeader(title = "Notebook Library", subtitle = "Import, review and safely manage Jupyter notebooks", icon = Icons.Default.Description)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton(
                text = "Upload notebook",
                onClick = { importPicker.launch(arrayOf("application/x-ipynb+json", "application/json", "text/plain", "*/*")) },
                icon = Icons.Default.UploadFile,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { sortByName = !sortByName }) {
                Icon(Icons.Default.Sort, contentDescription = if (sortByName) "Sort by recently modified" else "Sort by name")
            }
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Search notebooks") },
        )
        state.message?.let { Text(it, color = AppTheme.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        state.error?.let { Text(it, color = AppTheme.colors.accentRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                Text("Importing or saving notebook…", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { viewModel.select(notebook) }) { Text("Preview") }
                        TextButton(onClick = {
                            renameTarget = notebook; renameName = notebook.name.removeSuffix(".ipynb"); renameDescription = notebook.description
                        }) { Icon(Icons.Default.Edit, null); Text("Rename") }
                        TextButton(onClick = { viewModel.select(notebook); exportPicker.launch(notebook.name) }) { Icon(Icons.Default.Download, null); Text("Export") }
                        if (!notebook.builtIn) TextButton(onClick = { deleteTarget = notebook }) { Icon(Icons.Default.Delete, null); Text("Delete") }
                    }
                }
            }
        }
    }

    state.selected?.let { selected ->
        AlertDialog(
            onDismissRequest = { viewModel.clearMessage() },
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
                    Text("Execution requires an explicit action in the connected Colab workflow. This screen does not run notebook code.", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { exportPicker.launch(selected.name) }) { Icon(Icons.Default.Download, null); Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, COLAB_NOTEBOOK_URL.toUri())
                    context.startActivity(intent)
                }) { Icon(Icons.Default.OpenInNew, null); Text("Open Colab") }
            },
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
