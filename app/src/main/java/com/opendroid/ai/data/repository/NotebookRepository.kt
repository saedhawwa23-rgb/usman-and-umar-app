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

@Singleton
class NotebookRepository @Inject constructor(
    private val dao: NotebookDao,
    @ApplicationContext private val context: Context,
) {
    val notebooks: Flow<List<NotebookEntity>> = dao.observeAll()

    suspend fun ensureBuiltInNotebook() = withContext(Dispatchers.IO) {
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

    suspend fun import(uri: Uri): Result<NotebookEntity> = withContext(Dispatchers.IO) {
        runCatching {
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
                input.readBytes().also { require(it.size <= MAX_NOTEBOOK_BYTES) { "Notebook is larger than 25 MB." } }
            } ?: error("The selected notebook could not be read.")
            val preview = parsePreview(bytes)
            val sha = sha256(bytes)
            dao.getBySha256(sha)?.let { return@runCatching it }
            val id = UUID.randomUUID().toString()
            val target = notebookFile(id)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            val now = System.currentTimeMillis()
            NotebookEntity(id, name, preview.previewText.lineSequence().firstOrNull().orEmpty().take(160), uri.toString(), target.path,
                resolver.getType(uri) ?: "application/x-ipynb+json", bytes.size.toLong(), sha, false, now, now).also { dao.upsert(it) }
        }
    }

    suspend fun getPreview(entity: NotebookEntity): Result<NotebookPreview> = withContext(Dispatchers.IO) {
        runCatching { parsePreview(File(entity.localPath).readBytes()) }
    }

    suspend fun rename(id: String, name: String, description: String) {
        val safeName = name.trim().removeSuffix(".ipynb").take(120).ifBlank { "Notebook" } + ".ipynb"
        dao.updateDetails(id, safeName, description.trim().take(500), System.currentTimeMillis())
    }

    suspend fun delete(entity: NotebookEntity) = withContext(Dispatchers.IO) {
        if (!entity.builtIn) File(entity.localPath).delete()
        dao.deleteById(entity.id)
    }

    suspend fun markOpened(id: String) = dao.markOpened(id, System.currentTimeMillis())

    suspend fun export(entity: NotebookEntity, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = File(entity.localPath).readBytes()
            context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                ?: error("Could not open the export destination.")
        }
    }

    private fun notebookFile(id: String) = File(File(context.filesDir, "notebooks"), "$id.ipynb")

    private fun parsePreview(bytes: ByteArray): NotebookPreview {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val cells = root.optJSONArray("cells") ?: error("This file is not a valid Jupyter notebook: cells are missing.")
        var code = 0
        var markdown = 0
        val snippets = mutableListOf<String>()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            when (cell.optString("cell_type")) {
                "code" -> code++
                "markdown" -> markdown++
                else -> Unit
            }
            if (snippets.size < 3) {
                val source = cell.opt("source")
                val text = when (source) {
                    is JSONArray -> (0 until source.length()).joinToString("") { source.optString(it) }
                    else -> source?.toString().orEmpty()
                }.trim()
                if (text.isNotBlank()) snippets += text.take(400)
            }
        }
        return NotebookPreview(cells.length(), code, markdown, snippets.joinToString("\n\n"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
