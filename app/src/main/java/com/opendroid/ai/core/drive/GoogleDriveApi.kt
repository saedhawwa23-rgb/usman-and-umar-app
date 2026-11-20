package com.opendroid.ai.core.drive

import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
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

@Singleton
class GoogleDriveApi @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    internal fun getStorageQuota(accessToken: String): DriveStorageQuota {
        val request = Request.Builder()
            .url("$ABOUT_API?fields=storageQuota%28limit%2Cusage%29")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val json = JSONObject(execute(request))
        val storageQuota = json.optJSONObject("storageQuota")
            ?: throw IllegalStateException("Google Drive did not return storage quota data.")
        val usageBytes = storageQuota.optString("usage").toLongOrNull()
            ?: throw IllegalStateException("Google Drive did not return a valid storage usage value.")
        val limitBytes = if (!storageQuota.has("limit") || storageQuota.isNull("limit")) {
            null
        } else {
            storageQuota.optString("limit").toLongOrNull()
                ?: throw IllegalStateException("Google Drive returned an invalid storage limit.")
        }
        require(usageBytes >= 0L && (limitBytes == null || limitBytes >= 0L)) {
            "Google Drive returned negative storage values."
        }
        return DriveStorageQuota(limitBytes, usageBytes)
    }

    fun uploadTaskOutput(accessToken: String, filename: String, content: String): String {
        val folderId = findOrCreateFolder(accessToken)
        val metadata = JSONObject()
            .put("name", filename)
            .put("mimeType", "text/plain")
            .put("parents", JSONArray().put(folderId))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_API?uploadType=multipart&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        val response = JSONObject(execute(request))
        return response.optString("webViewLink").ifBlank { "Google Drive upload completed." }
    }

    private fun findOrCreateFolder(accessToken: String): String {
        val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val findRequest = Request.Builder()
            .url("$DRIVE_API?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&spaces=drive&fields=files(id,name)")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val files = JSONObject(execute(findRequest)).optJSONArray("files")
        if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")

        val folderBody = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val createRequest = Request.Builder()
            .url("$DRIVE_API?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(folderBody)
            .build()
        return JSONObject(execute(createRequest)).getString("id")
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val reason = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("").take(120)
            throw DriveApiException(response.code, reason)
        }
        body
    }

    private companion object {
        const val FOLDER_NAME = "USMAN_AND_UMAR_Tasks"
        const val DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        const val ABOUT_API = "https://www.googleapis.com/drive/v3/about"
        const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    }
}
