package com.opendroid.ai.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.opendroid.ai.data.db.dao.NotebookDao
import com.opendroid.ai.data.db.entities.NotebookEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val MAX_NOTEBOOK_BYTES = 25L * 1024L * 1024L

data class NotebookPreview(
    val cells: Int,
    val codeCells: Int,
    val markdownCells: Int,
    val previewText: String,
)

data class NotebookRecord(
    val entity: NotebookEntity,
    val preview: NotebookPreview,
)

data class NotebookImportResult(val notebook: NotebookEntity, val duplicate: Boolean)

interface NotebookLibraryStore {
    val notebooks: Flow<List<NotebookEntity>>
    suspend fun ensureBuiltInNotebook()
    suspend fun import(uri: Uri): Result<NotebookImportResult>
    suspend fun getPreview(entity: NotebookEntity): Result<NotebookPreview>
    suspend fun readNotebookBytes(entity: NotebookEntity): ByteArray
    suspend fun rename(id: String, name: String, description: String)
    suspend fun delete(entity: NotebookEntity)
    suspend fun markOpened(id: String)
    suspend fun export(entity: NotebookEntity, destination: Uri): Result<Unit>
}

@Singleton
class NotebookRepository @Inject constructor(
    private val dao: NotebookDao,
    @ApplicationContext private val context: Context,
) : NotebookLibraryStore {
    override val notebooks: Flow<List<NotebookEntity>> = dao.observeAll()

    override suspend fun ensureBuiltInNotebook() = withContext(Dispatchers.IO) {
        val assetName = "USMAN_OPENSWARM_NODE.ipynb"
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val sha = sha256(bytes)
        if (dao.getBySha256(sha) != null) return@withContext
        val id = "builtin-openswarm"
        val target = notebookFile(id)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        val now = System.currentTimeMillis()
        dao.upsert(NotebookEntity(id, assetName, "Built-in OpenSwarm Colab node setup.", null, target.path,
            "application/x-ipynb+json", bytes.size.toLong(), sha, true, now, now))
    }

    override suspend fun import(uri: Uri): Result<NotebookImportResult> = withContext(Dispatchers.IO) {
        try {
            Result.success(run {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        name to if (!cursor.isNull(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))) cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) else -1L
                    } else null
                } ?: ("notebook.ipynb" to -1L)
            val name = displayName.first.substringAfterLast('/').substringAfterLast('\\').ifBlank { "notebook.ipynb" }
            require(name.lowercase().endsWith(".ipynb")) { "Choose a Jupyter Notebook (.ipynb) file." }
            require(displayName.second <= MAX_NOTEBOOK_BYTES || displayName.second < 0) { "Notebook is larger than 25 MB." }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_NOTEBOOK_BYTES) { "Notebook is larger than 25 MB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("The selected notebook could not be read. Check that the file provider is available and try again.")
            val preview = parsePreview(bytes)
            val sha = sha256(bytes)
            val existing = dao.getBySha256(sha)
            if (existing != null) {
                NotebookImportResult(existing, duplicate = true)
            } else {
                val id = UUID.randomUUID().toString()
                val target = notebookFile(id)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                val now = System.currentTimeMillis()
                val notebook = NotebookEntity(id, name, preview.previewText.lineSequence().firstOrNull().orEmpty().take(160), uri.toString(), target.path,
                    resolver.getType(uri) ?: "application/x-ipynb+json", bytes.size.toLong(), sha, false, now, now)
                dao.upsert(notebook)
                NotebookImportResult(notebook, duplicate = false)
            }
            })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    override suspend fun getPreview(entity: NotebookEntity): Result<NotebookPreview> = withContext(Dispatchers.IO) {
        runCatching { parsePreview(File(entity.localPath).readBytes()) }
    }

    override suspend fun readNotebookBytes(entity: NotebookEntity): ByteArray = withContext(Dispatchers.IO) {
        require(entity.name.lowercase().endsWith(".ipynb")) { "Choose a Jupyter Notebook (.ipynb) file." }
        val file = File(entity.localPath)
        require(file.isFile) { "The saved notebook file is missing. Re-import it before uploading." }
        require(file.length() in 1..MAX_NOTEBOOK_BYTES) { "Notebook must be between 1 byte and 25 MB to upload." }
        val bytes = file.readBytes()
        require(bytes.size.toLong() <= MAX_NOTEBOOK_BYTES) { "Notebook is larger than 25 MB." }
        parsePreview(bytes)
        bytes
    }

    override suspend fun rename(id: String, name: String, description: String) {
        val safeName = name.trim().removeSuffix(".ipynb").take(120).ifBlank { "Notebook" } + ".ipynb"
        dao.updateDetails(id, safeName, description.trim().take(500), System.currentTimeMillis())
    }

    override suspend fun delete(entity: NotebookEntity) = withContext(Dispatchers.IO) {
        if (!entity.builtIn) File(entity.localPath).delete()
        dao.deleteById(entity.id)
    }

    override suspend fun markOpened(id: String) = dao.markOpened(id, System.currentTimeMillis())

    override suspend fun export(entity: NotebookEntity, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = File(entity.localPath).readBytes()
            context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                ?: error("Could not open the export destination.")
        }
    }

    private fun notebookFile(id: String) = File(File(context.filesDir, "notebooks"), "$id.ipynb")

    private fun parsePreview(bytes: ByteArray): NotebookPreview {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optInt("nbformat", 0) > 0) { "This file is not a valid Jupyter notebook: nbformat is missing." }
        val cells = root.optJSONArray("cells") ?: error("This file is not a valid Jupyter notebook: cells are missing.")
        var code = 0
        var markdown = 0
        val snippets = mutableListOf<String>()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: error("This file contains an invalid Jupyter cell at position ${i + 1}.")
            val cellType = cell.optString("cell_type")
            require(cellType == "code" || cellType == "markdown" || cellType == "raw") {
                "This file contains an unsupported Jupyter cell at position ${i + 1}."
            }
            val source = cell.opt("source")
            require(source is JSONArray || source is String) {
                "This file contains an invalid source field in cell ${i + 1}."
            }
            when (cellType) { "code" -> code++; "markdown" -> markdown++; else -> Unit }
            if (snippets.size < 3) {
                val text = when (source) {
                    is JSONArray -> (0 until source.length()).joinToString("") { source.optString(it) }
                    else -> source?.toString().orEmpty()
                }.trim()
                if (text.isNotBlank()) {
                    val label = when (cellType) {
                        "code" -> "Code"
                        "markdown" -> "Markdown"
                        else -> "Raw"
                    }
                    snippets += "$label cell ${i + 1}\n${redactSecrets(text).take(400)}"
                }
            }
        }
        return NotebookPreview(cells.length(), code, markdown, snippets.joinToString("\n\n"))
    }

    /** Keep the preview useful without rendering common inline credentials from executable source. */
    private fun redactSecrets(source: String): String {
        val assignment = Regex("(?i)(\\b(?:[A-Z0-9_]*(?:API[_-]?KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL)[A-Z0-9_]*)\\b\\s*[:=]\\s*)(['\"])(.*?)(\\2)")
        return assignment.replace(source) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]${match.groupValues[4]}" }
            .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]{8,}")) { match ->
                "${match.groupValues[1]}[REDACTED]"
            }
            .replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}"), "[REDACTED KEY]")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
