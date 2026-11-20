package com.opendroid.ai.data.repository

import com.opendroid.ai.core.drive.GoogleDriveTokenVault
import com.opendroid.ai.data.db.dao.GoogleDriveAccountDao
import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class GoogleDriveAccountRepository @Inject constructor(
    private val dao: GoogleDriveAccountDao,
    private val tokenVault: GoogleDriveTokenVault,
) {
    val accounts: Flow<List<GoogleDriveAccountEntity>> = dao.observeAll()

    suspend fun getAccounts(): List<GoogleDriveAccountEntity> = dao.getAll()

    suspend fun connect(email: String, accessToken: String) {
        val normalizedId = accountId(email)
        val existing = dao.getById(normalizedId)
        tokenVault.save(normalizedId, accessToken)
        try {
            dao.upsert(
                GoogleDriveAccountEntity(
                    id = normalizedId,
                    email = email.trim(),
                    displayName = email.trim().substringBefore('@').ifBlank { email.trim() },
                    connectedAtMillis = existing?.connectedAtMillis ?: System.currentTimeMillis(),
                    quotaLimitBytes = existing?.quotaLimitBytes,
                    quotaUsageBytes = existing?.quotaUsageBytes,
                    quotaUpdatedAtMillis = existing?.quotaUpdatedAtMillis,
                    quotaError = null,
                )
            )
        } catch (failure: Exception) {
            runCatching { tokenVault.remove(normalizedId) }
            throw failure
        }
    }

    suspend fun saveAccessToken(email: String, accessToken: String) {
        tokenVault.save(accountId(email), accessToken)
    }

    suspend fun updateQuota(
        accountId: String,
        limitBytes: Long?,
        usageBytes: Long,
        checkedAtMillis: Long = System.currentTimeMillis(),
    ) {
        dao.updateQuota(accountId(accountId), limitBytes, usageBytes, checkedAtMillis)
    }

    suspend fun updateQuotaError(
        accountId: String,
        error: String,
        checkedAtMillis: Long = System.currentTimeMillis(),
    ) {
        dao.updateQuotaError(accountId(accountId), error.take(180), checkedAtMillis)
    }

    suspend fun remove(accountEmail: String) {
        val id = accountId(accountEmail)
        tokenVault.remove(id)
        dao.deleteById(id)
    }

    companion object {
        fun accountId(email: String): String = email.trim().lowercase(Locale.ROOT).also {
            require(it.isNotBlank()) { "Google account email cannot be blank." }
        }
    }
}
