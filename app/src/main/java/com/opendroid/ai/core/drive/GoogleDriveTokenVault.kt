package com.opendroid.ai.core.drive

import android.content.Context
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores one short-lived Google OAuth access token per account, encrypted with the Android Keystore.
 * Google Play services remains responsible for refreshing Google account credentials; refresh
 * tokens are never copied into app storage.
 */
@Singleton
class GoogleDriveTokenVault @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val delegate = GoogleDriveTokenVaultImpl(
        KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ),
            cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS),
        )
    )

    fun save(accountEmail: String, accessToken: String) = delegate.save(accountEmail, accessToken)

    fun read(accountEmail: String): String? = delegate.read(accountEmail)

    fun readValid(accountEmail: String, nowMillis: Long = System.currentTimeMillis()): String? =
        delegate.readValid(accountEmail, nowMillis)

    fun remove(accountEmail: String) = delegate.remove(accountEmail)

    private companion object {
        const val PREFERENCES_NAME = "google_drive_oauth_vault"
        const val KEY_ALIAS = "opendroid.google_drive_tokens.aes_gcm.v1"
    }
}

/** JVM-testable boundary; production injects AndroidKeyStore-backed [KeystoreSecretRecords]. */
internal class GoogleDriveTokenVaultImpl(
    private val records: KeystoreSecretRecords,
) {
    fun save(accountEmail: String, accessToken: String) {
        require(accessToken.isNotBlank()) { "Google returned an empty access token." }
        val id = normalizedAccountId(accountEmail)
        val expiresAtMillis = System.currentTimeMillis() + TOKEN_CACHE_LIFETIME_MILLIS
        val payload = "$expiresAtMillis\n$accessToken"
        when (records.write(storageKey(id), aad(id), payload.toByteArray(StandardCharsets.UTF_8))) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable -> error("Google Drive token encryption is unavailable; reconnect the account.")
            SecretRecordResult.StorageUnavailable -> error("Google Drive token could not be saved securely.")
        }
    }

    fun read(accountEmail: String): String? = readRecord(accountEmail)?.accessToken

    fun readValid(accountEmail: String, nowMillis: Long): String? = readRecord(accountEmail)
        ?.takeIf { it.expiresAtMillis > nowMillis }
        ?.accessToken

    private fun readRecord(accountEmail: String): StoredToken? {
        val id = normalizedAccountId(accountEmail)
        return when (val result = records.read(storageKey(id), aad(id))) {
            is SecretRecordResult.Success -> result.value?.let { bytes ->
                val payload = String(bytes, StandardCharsets.UTF_8)
                val separator = payload.indexOf('\n')
                val expiresAtMillis = payload.substring(0, separator.coerceAtLeast(0)).toLongOrNull()
                    ?: error("Saved Google Drive token data is invalid; reconnect the account.")
                val token = payload.substring(separator + 1)
                require(separator > 0 && token.isNotBlank()) {
                    "Saved Google Drive token data is invalid; reconnect the account."
                }
                StoredToken(token, expiresAtMillis)
            }
            SecretRecordResult.Unrecoverable -> error("Saved Google Drive token is unavailable; reconnect the account.")
            SecretRecordResult.StorageUnavailable -> error("Google Drive token storage is unavailable.")
        }
    }

    fun remove(accountEmail: String) {
        val id = normalizedAccountId(accountEmail)
        when (records.removeRecord(storageKey(id))) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable -> error("Google Drive token storage is unavailable.")
            SecretRecordResult.StorageUnavailable -> error("Google Drive token storage is unavailable.")
        }
    }

    private fun normalizedAccountId(email: String): String {
        val normalized = email.trim().lowercase(Locale.ROOT)
        require(normalized.isNotBlank()) { "Google account email cannot be blank." }
        return normalized
    }

    private fun storageKey(accountId: String): String = "google-drive-token." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(accountId.toByteArray(StandardCharsets.UTF_8))

    private fun aad(accountId: String): String = "google-drive-access-token:$accountId"

    private data class StoredToken(val accessToken: String, val expiresAtMillis: Long)

    private companion object {
        const val TOKEN_CACHE_LIFETIME_MILLIS = 50L * 60L * 1000L
    }
}
