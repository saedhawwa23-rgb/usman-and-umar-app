package com.opendroid.ai.core.drive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Requests a fresh, account-specific access token; Google Play services retains refresh credentials. */
@Singleton
class GoogleDriveAccessTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenVault: GoogleDriveTokenVault,
) {
    suspend fun getAccessToken(accountEmail: String): String = withContext(Dispatchers.IO) {
        runCatching { tokenVault.readValid(accountEmail) }
            .getOrNull()
            ?.let { return@withContext it }
        @Suppress("DEPRECATION")
        val token = GoogleAuthUtil.getToken(context, accountEmail, TOKEN_SCOPE)
        tokenVault.save(accountEmail, token)
        token
    }

    suspend fun invalidate(accountEmail: String, accessToken: String) = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        GoogleAuthUtil.clearToken(context, accessToken)
        tokenVault.remove(accountEmail)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val TOKEN_SCOPE = "oauth2:$DRIVE_FILE_SCOPE"
    }
}
