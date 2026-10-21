package com.opendroid.ai.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.opendroid.ai.core.storage.StorageWorkspaceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal Drive v3 metadata required by the workspace browser and transfer UI. */
data class GoogleDriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedTime: String?,
    val webViewLink: String?,
    val parents: List<String>,
    val canDownload: Boolean
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE
    val isGoogleWorkspaceDocument: Boolean get() = mimeType in EXPORTABLE_WORKSPACE_MIME_TYPES

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val EXPORTABLE_WORKSPACE_MIME_TYPES = setOf(
            "application/vnd.google-apps.document",
            "application/vnd.google-apps.spreadsheet",
            "application/vnd.google-apps.presentation",
            "application/vnd.google-apps.drawing"
        )
    }
}

data class GoogleDriveAccount(val email: String, val displayName: String?)

internal object GoogleDriveQuery {
    fun list(searchText: String, folderId: String): String {
        val conditions = mutableListOf("trashed = false")
        val cleanSearch = searchText.trim()
        if (cleanSearch.isNotEmpty()) {
            conditions += "name contains '${escape(cleanSearch)}'"
        } else {
            val parent = if (folderId == GoogleDriveRepository.ROOT_FOLDER_ID) "root" else folderId
            conditions += "'${escape(parent)}' in parents"
        }
        return conditions.joinToString(" and ")
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}

class GoogleDriveAuthorizationException(message: String) : IOException(message)
class GoogleDriveApiException(val httpStatus: Int, message: String) : IOException(message)

/**
 * Thin Drive v3 REST adapter. Tokens are supplied per call and are never persisted or logged.
 * Uploads are deliberately capped at 5 MiB (Drive multipart-upload guidance); larger uploads
 * need a separate resumable-upload implementation rather than an unsafe one-shot request.
 */
@Singleton
class GoogleDriveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        const val MAX_MULTIPART_UPLOAD_BYTES = 5L * 1024L * 1024L
        const val ROOT_FOLDER_ID = "root"
        private const val WORKSPACE_EXPORT_MIME = "application/pdf"
        private const val FIELDS = "files(id,name,mimeType,size,modifiedTime,webViewLink,parents,capabilities(canDownload)),nextPageToken"
    }

    fun verifyAccount(accessToken: String): GoogleDriveAccount {
        val url = HttpUrl.Builder()
            .scheme("https").host("www.googleapis.com")
            .addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user(displayName,emailAddress)")
            .build()
        val json = getJson(url, accessToken)
        val user = json.optJSONObject("user") ?: throw IOException("Google Drive did not return an account profile.")
        val email = user.optString("emailAddress").trim()
        if (email.isBlank()) throw IOException("Google Drive did not return a verified account email.")
        return GoogleDriveAccount(email, user.optString("displayName").takeIf(String::isNotBlank))
    }

    fun listFiles(accessToken: String, searchText: String, folderId: String): List<GoogleDriveItem> {
        val url = HttpUrl.Builder()
            .scheme("https").host("www.googleapis.com")
            .addPathSegments("drive/v3/files")
            .addQueryParameter("q", GoogleDriveQuery.list(searchText, folderId))
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("orderBy", "folder, name")
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("fields", FIELDS)
            .build()
        val files = getJson(url, accessToken).optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).mapNotNull { index -> files.optJSONObject(index)?.toDriveItem() }
    }

    /** Uploads a user-selected local document to Drive root, then verifies returned metadata. */
    fun uploadFile(
        accessToken: String,
        source: Uri,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): GoogleDriveItem {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, source)
        val mimeType = resolver.getType(source)?.takeIf(String::isNotBlank) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(source)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_MULTIPART_UPLOAD_BYTES) {
                    throw IOException("This first Drive upload flow supports files up to 5 MiB. Resumable uploads are not implemented yet.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("The selected local file could not be opened.")

        return uploadBytes(accessToken, displayName, mimeType, bytes, onProgress)
    }

    /** Uploads a small generated local-tool output and verifies its Drive file ID and metadata. */
    fun uploadText(
        accessToken: String,
        displayName: String,
        text: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): GoogleDriveItem {
        val safeName = safeFileName(displayName).let { if (it.contains('.')) it else "$it.txt" }
        return uploadBytes(accessToken, safeName, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), onProgress)
    }

    private fun uploadBytes(
        accessToken: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ): GoogleDriveItem {
        if (bytes.size > MAX_MULTIPART_UPLOAD_BYTES) {
            throw IOException("This first Drive upload flow supports files up to 5 MiB. Resumable uploads are not implemented yet.")
        }
        val metadata = JSONObject().put("name", displayName)
        val metadataPart = metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val contentPart = bytes.toRequestBody(mimeType.toMediaType())
        val multipart = okhttp3.MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadataPart)
            .addPart(contentPart)
            .build()
        val progressBody = ProgressRequestBody(multipart, onProgress)
        val uploadUrl = HttpUrl.Builder()
            .scheme("https").host("www.googleapis.com")
            .addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,webViewLink,parents,capabilities(canDownload)")
            .build()
        val uploaded = executeJson(
            Request.Builder().url(uploadUrl).header("Authorization", "Bearer $accessToken")
                .post(progressBody).build(),
            "Drive upload failed"
        ).toDriveItem()
        if (uploaded.id.isBlank()) throw IOException("Drive did not return an uploaded file ID; upload could not be verified.")

        val verified = getFile(accessToken, uploaded.id)
        if (verified.id != uploaded.id || verified.name != displayName) {
            throw IOException("Drive upload metadata did not match the uploaded file.")
        }
        return verified
    }

    /** Download a binary file or export a native Google document as PDF into the app workspace. */
    fun importToWorkspace(accessToken: String, item: GoogleDriveItem, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File {
        if (!item.canDownload) throw IOException("Google Drive reports that this file cannot be downloaded.")
        val outputDir = File(StorageWorkspaceProvider.getDefaultWorkspaceDir(context), "GoogleDrive")
        if (!outputDir.exists() && !outputDir.mkdirs()) throw IOException("Could not create the local GoogleDrive workspace folder.")
        val safeName = safeFileName(item.name) + if (item.isGoogleWorkspaceDocument) ".pdf" else ""
        val target = uniqueFile(outputDir, safeName)
        val temporary = File(outputDir, ".${target.name}.part")
        try {
            downloadToStream(accessToken, item, temporary.outputStream(), onProgress)
            if (!temporary.isFile) throw IOException("Downloaded file was not created.")
            if (item.sizeBytes != null && !item.isGoogleWorkspaceDocument && temporary.length() != item.sizeBytes) {
                throw IOException("Downloaded file size did not match Google Drive metadata.")
            }
            if (!temporary.renameTo(target)) throw IOException("Downloaded bytes were received, but the file could not be finalized in the workspace.")
            return target
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    /** Export/download the selected file to a user-chosen SAF destination and verify byte counts. */
    fun exportToUri(
        accessToken: String,
        item: GoogleDriveItem,
        destination: Uri,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ): Long {
        if (!item.canDownload) throw IOException("Google Drive reports that this file cannot be downloaded.")
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("The selected destination could not be opened for writing.")
        return downloadToStream(accessToken, item, output, onProgress)
    }

    private fun downloadToStream(
        accessToken: String,
        item: GoogleDriveItem,
        output: java.io.OutputStream,
        onProgress: (Long, Long?) -> Unit
    ): Long {
        val url = if (item.isGoogleWorkspaceDocument) {
            HttpUrl.Builder().scheme("https").host("www.googleapis.com")
                .addPathSegments("drive/v3/files").addPathSegment(item.id).addPathSegment("export")
                .addQueryParameter("mimeType", WORKSPACE_EXPORT_MIME).build()
        } else {
            HttpUrl.Builder().scheme("https").host("www.googleapis.com")
                .addPathSegments("drive/v3/files").addPathSegment(item.id)
                .addQueryParameter("alt", "media").build()
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        httpClient.newCall(request).execute().use { response ->
            requireSuccess(response, "Drive download/export failed")
            val body = response.body ?: throw IOException("Google Drive returned an empty download response.")
            val expected = body.contentLength().takeIf { it >= 0 }
            var written = 0L
            body.byteStream().use { input -> output.use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    written += count
                    onProgress(written, expected)
                }
                target.flush()
            } }
            if (expected != null && written != expected) throw IOException("Google Drive download was incomplete.")
            return written
        }
    }

    private fun getFile(accessToken: String, id: String): GoogleDriveItem {
        val url = HttpUrl.Builder().scheme("https").host("www.googleapis.com")
            .addPathSegments("drive/v3/files").addPathSegment(id)
            .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,webViewLink,parents,capabilities(canDownload)")
            .build()
        return getJson(url, accessToken).toDriveItem()
    }

    private fun getJson(url: HttpUrl, token: String): JSONObject = executeJson(
        Request.Builder().url(url).header("Authorization", "Bearer $token").get().build(),
        "Google Drive request failed"
    )

    private fun executeJson(request: Request, failurePrefix: String): JSONObject = httpClient.newCall(request).execute().use { response ->
        requireSuccess(response, failurePrefix)
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) throw IOException("Google Drive returned an empty response.")
        try { JSONObject(text) } catch (_: Exception) { throw IOException("Google Drive returned an unreadable response.") }
    }

    private fun requireSuccess(response: Response, prefix: String) {
        if (response.isSuccessful) return
        when (response.code) {
            401 -> throw GoogleDriveAuthorizationException("Google Drive authorization expired or was revoked. Reauthorize Drive access.")
            403 -> throw GoogleDriveApiException(403, "Google Drive denied this operation. Check that the Drive API is enabled, the requested scopes were approved, and this file is accessible to the connected account.")
            404 -> throw GoogleDriveApiException(404, "The Drive file or folder was not found or is no longer accessible.")
            429 -> throw GoogleDriveApiException(429, "Google Drive rate limit reached. Wait before trying again.")
            in 500..599 -> throw GoogleDriveApiException(response.code, "Google Drive is temporarily unavailable. Try again later.")
            else -> throw GoogleDriveApiException(response.code, "$prefix (HTTP ${response.code}).")
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return safeFileName(name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "upload")
    }

    private fun JSONObject.toDriveItem(): GoogleDriveItem {
        val capability = optJSONObject("capabilities")
        val parentsJson = optJSONArray("parents") ?: JSONArray()
        return GoogleDriveItem(
            id = optString("id"),
            name = optString("name", "Unnamed file"),
            mimeType = optString("mimeType", "application/octet-stream"),
            sizeBytes = optString("size").toLongOrNull(),
            modifiedTime = optString("modifiedTime").takeIf(String::isNotBlank),
            webViewLink = optString("webViewLink").takeIf(String::isNotBlank),
            parents = (0 until parentsJson.length()).map { parentsJson.optString(it) },
            canDownload = capability?.optBoolean("canDownload", false) ?: false
        )
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\x00-\\x1F/\\\\:*?\"<>|]"), "_").trim().trim('.').take(160)
        return cleaned.ifBlank { "drive-file" }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var target = File(directory, name)
        if (!target.exists()) return target
        val extension = name.substringAfterLast('.', "").takeIf { '.' in name }.orEmpty()
        val stem = if (extension.isEmpty()) name else name.removeSuffix(".$extension")
        var index = 1
        while (target.exists()) {
            target = File(directory, if (extension.isEmpty()) "$stem ($index)" else "$stem ($index).$extension")
            index++
        }
        return target
    }

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength().coerceAtLeast(0)
            var sent = 0L
            val countingSink = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    onProgress(sent, total)
                }
            }
            val buffered = countingSink.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }
}
