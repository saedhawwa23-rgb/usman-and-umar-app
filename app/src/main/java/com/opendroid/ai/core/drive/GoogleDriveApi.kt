package com.opendroid.ai.core.drive

import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class DriveStorageQuota(
    val limitBytes: Long?,
    val usageBytes: Long,
) {
    val isUnlimited: Boolean get() = limitBytes == null
    val freeBytes: Long? get() = limitBytes?.let { (it - usageBytes).coerceAtLeast(0L) }
}

internal class DriveApiException(
    val statusCode: Int,
    val reason: String,
) : java.io.IOException("Google Drive request failed (HTTP $statusCode${reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}).")

data class GoogleDriveNotebookUploadResult(
    val accountEmail: String,
    val fileId: String,
    val fileName: String,
    val driveUrl: String?,
    val colabUrl: String,
    val contentVerified: Boolean,
    val reusedExisting: Boolean,
    val sizeBytes: Long,
)

@Singleton
class GoogleDriveApi @Inject constructor() {
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val driveBaseUrl = "https://www.googleapis.com/drive/v3/files"
    private val uploadBaseUrl = "https://www.googleapis.com/upload/drive/v3/files"
    private val aboutUrl = "https://www.googleapis.com/drive/v3/about"

    /** Narrow test seam; production wiring continues to use the no-argument injected constructor. */
    internal constructor(client: OkHttpClient, driveBaseUrl: String, uploadBaseUrl: String, aboutUrl: String) : this() {
        this.testClient = client
        this.testDriveBaseUrl = driveBaseUrl
        this.testUploadBaseUrl = uploadBaseUrl
        this.testAboutUrl = aboutUrl
    }

    private var testClient: OkHttpClient? = null
    private var testDriveBaseUrl: String? = null
    private var testUploadBaseUrl: String? = null
    private var testAboutUrl: String? = null
    private val http: OkHttpClient get() = testClient ?: client
    private val driveApi: String get() = testDriveBaseUrl ?: driveBaseUrl
    private val uploadApi: String get() = testUploadBaseUrl ?: uploadBaseUrl
    private val aboutApi: String get() = testAboutUrl ?: aboutUrl

    internal fun getStorageQuota(accessToken: String): DriveStorageQuota {
        val request = Request.Builder().url("$aboutApi?fields=storageQuota%28limit%2Cusage%29")
            .header("Authorization", "Bearer $accessToken").get().build()
        val json = JSONObject(execute(request))
        val storageQuota = json.optJSONObject("storageQuota") ?: throw IllegalStateException("Google Drive did not return storage quota data.")
        val usageBytes = storageQuota.optString("usage").toLongOrNull() ?: throw IllegalStateException("Google Drive did not return a valid storage usage value.")
        val limitBytes = if (!storageQuota.has("limit") || storageQuota.isNull("limit")) null else storageQuota.optString("limit").toLongOrNull()
            ?: throw IllegalStateException("Google Drive returned an invalid storage limit.")
        require(usageBytes >= 0L && (limitBytes == null || limitBytes >= 0L)) { "Google Drive returned negative storage values." }
        return DriveStorageQuota(limitBytes, usageBytes)
    }

    fun uploadTaskOutput(accessToken: String, filename: String, content: String): String {
        val folderId = findOrCreateFolder(accessToken, TASK_FOLDER_NAME)
        val metadata = JSONObject().apply {
            put("name", filename)
            put("mimeType", "text/plain")
            put("parents", JSONArray().also { it.put(folderId) })
        }
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(content.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
        val request = Request.Builder().url("$uploadApi?uploadType=multipart&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $accessToken").post(body).build()
        val response = JSONObject(execute(request))
        return response.optString("webViewLink").ifBlank { "Google Drive upload completed." }
    }

    /** Uploads imported notebook bytes unchanged, reusing only a verified byte-identical file. */
    fun uploadNotebook(accessToken: String, filename: String, bytes: ByteArray): GoogleDriveNotebookUploadResult {
        require(filename.endsWith(".ipynb", ignoreCase = true)) { "Notebook filename must end in .ipynb." }
        val folderId = findOrCreateFolder(accessToken, NOTEBOOK_FOLDER_NAME)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).toHex()
        val folderFiles = listFiles(accessToken, folderId)
        val existing = folderFiles.filter { it.optString("name") == filename }
        for (file in existing) {
            val verified = getVerifiedFile(accessToken, file.getString("id"))
            if (verified.name == filename && verified.contentMatches(bytes.size.toLong(), md5)) {
                return verified.asResult(reused = true, sizeBytes = bytes.size.toLong())
            }
        }
        val reservedNames = folderFiles.map { it.optString("name") }.toSet()
        val targetName = if (filename !in reservedNames) filename else nextFreeName(filename, reservedNames)
        val metadata = JSONObject().apply {
            put("name", targetName)
            put("mimeType", NOTEBOOK_MIME)
            put("parents", JSONArray().also { it.put(folderId) })
        }
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(bytes.toRequestBody(NOTEBOOK_MIME.toMediaType())).build()
        val request = Request.Builder().url("$uploadApi?uploadType=multipart&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $accessToken").post(body).build()
        val uploaded = JSONObject(execute(request))
        val id = uploaded.optString("id").takeIf(String::isNotBlank) ?: throw IllegalStateException("Google Drive did not return the uploaded notebook ID.")
        val verified = getVerifiedFile(accessToken, id)
        check(verified.name == targetName && verified.mimeType == NOTEBOOK_MIME && verified.size == bytes.size.toLong() && verified.md5 == md5) {
            "Google Drive notebook verification failed; the uploaded file metadata or content digest did not match."
        }
        return verified.asResult(reused = false, sizeBytes = bytes.size.toLong())
    }

    private data class VerifiedDriveFile(val id: String, val name: String, val mimeType: String, val size: Long, val md5: String, val link: String) {
        fun contentMatches(expectedSize: Long, expectedMd5: String) = mimeType == NOTEBOOK_MIME && size == expectedSize && md5.equals(expectedMd5, ignoreCase = true)
        fun asResult(reused: Boolean, sizeBytes: Long) = GoogleDriveNotebookUploadResult(
            accountEmail = "", fileId = id, fileName = name, driveUrl = link.takeIf(String::isNotBlank),
            colabUrl = "https://colab.research.google.com/drive/$id", contentVerified = true,
            reusedExisting = reused, sizeBytes = sizeBytes,
        )
    }

    private fun getVerifiedFile(token: String, id: String): VerifiedDriveFile {
        val request = Request.Builder().url("$driveApi/$id?fields=id,name,mimeType,size,md5Checksum,webViewLink")
            .header("Authorization", "Bearer $token").get().build()
        val json = JSONObject(execute(request))
        val size = json.optString("size").toLongOrNull() ?: throw IllegalStateException("Google Drive did not return a valid notebook size.")
        val md5 = json.optString("md5Checksum").takeIf(String::isNotBlank) ?: throw IllegalStateException("Google Drive did not return a notebook content digest.")
        return VerifiedDriveFile(json.getString("id"), json.getString("name"), json.optString("mimeType"), size, md5, json.optString("webViewLink"))
    }

    private fun listFiles(token: String, folderId: String): List<JSONObject> {
        val query = "'$folderId' in parents and trashed = false"
        val url = driveApi.toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        val files = JSONObject(execute(request)).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { files.getJSONObject(it) }
    }

    private fun nextFreeName(filename: String, occupied: Set<String>): String {
        val stem = filename.removeSuffix(".ipynb")
        var suffix = 2
        while ("$stem ($suffix).ipynb" in occupied) suffix++
        return "$stem ($suffix).ipynb"
    }

    private fun findOrCreateFolder(accessToken: String, folderName: String): String {
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val findUrl = driveApi.toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("spaces", "drive").addQueryParameter("fields", "files(id,name)").build()
        val findRequest = Request.Builder().url(findUrl).header("Authorization", "Bearer $accessToken").get().build()
        val files = JSONObject(execute(findRequest)).optJSONArray("files")
        if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        val folderBody = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
        }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val createRequest = Request.Builder().url("$driveApi?fields=id").header("Authorization", "Bearer $accessToken").post(folderBody).build()
        return JSONObject(execute(createRequest)).getString("id")
    }

    private fun execute(request: Request): String = http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val reason = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("").take(120)
            throw DriveApiException(response.code, reason)
        }
        body
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TASK_FOLDER_NAME = "USMAN_AND_UMAR_Tasks"
        const val NOTEBOOK_FOLDER_NAME = "USMAN_AND_UMAR_Notebooks"
        const val NOTEBOOK_MIME = "application/x-ipynb+json"
    }
}
