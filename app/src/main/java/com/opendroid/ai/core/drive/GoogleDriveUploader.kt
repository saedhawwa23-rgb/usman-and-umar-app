package com.opendroid.ai.core.drive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** OAuth-backed Drive file upload. The Android app only requests the narrow drive.file scope. */
object GoogleDriveUploader {
    private const val FOLDER_NAME = "USMAN_AND_UMAR_Tasks"
    private const val DRIVE_API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    private val client = OkHttpClient()

    suspend fun uploadTaskOutput(
        context: Context,
        account: GoogleSignInAccount,
        filename: String,
        content: String,
    ): String = withContext(Dispatchers.IO) {
        val accountName = account.account?.name ?: error("Google account is missing")
        val token = GoogleAuthUtil.getToken(context, accountName, "oauth2:${DriveScopes.DRIVE_FILE}")
        val folderId = findOrCreateFolder(token)
        val metadata = JSONObject()
            .put("name", filename)
            .put("mimeType", "text/plain")
            .put("parents", JSONArray().put(folderId))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                "application/json; charset=utf-8".toMediaType()
                    .toRequestBody(metadata.toString().toByteArray())
            )
            .addPart("text/plain; charset=utf-8".toMediaType().toRequestBody(content.toByteArray()))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_API?uploadType=multipart&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Google Drive upload failed (HTTP ${response.code})." }
            JSONObject(raw).optString("webViewLink").ifBlank { "Google Drive upload completed." }
        }
    }

    private fun findOrCreateFolder(token: String): String {
        val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val findRequest = Request.Builder()
            .url("$DRIVE_API?q=${java.net.URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=files(id,name)")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(findRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Google Drive folder lookup failed (HTTP ${response.code})." }
            val files = JSONObject(raw).optJSONArray("files")
            if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        }
        val folderBody = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val createRequest = Request.Builder()
            .url("$DRIVE_API?fields=id")
            .header("Authorization", "Bearer $token")
            .post(folderBody)
            .build()
        client.newCall(createRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Google Drive folder creation failed (HTTP ${response.code})." }
            return JSONObject(raw).getString("id")
        }
    }
}
