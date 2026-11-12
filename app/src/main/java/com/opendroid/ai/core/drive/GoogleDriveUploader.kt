package com.opendroid.ai.core.drive

import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import com.opendroid.ai.data.repository.GoogleDriveAccountRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class DriveUploadCandidate(
    val account: GoogleDriveAccountEntity,
    val accessToken: String,
    val quota: DriveStorageQuota,
)

/** Unlimited accounts rank above finite quotas; otherwise upload to the account with most free bytes. */
internal fun orderDriveCandidates(candidates: List<DriveUploadCandidate>): List<DriveUploadCandidate> =
    candidates.sortedWith(
        compareByDescending<DriveUploadCandidate> { it.quota.isUnlimited }
            .thenByDescending { it.quota.freeBytes ?: 0L }
    )

data class GoogleDriveUploadResult(
    val accountEmail: String,
    val fileUrl: String,
)

@Singleton
class GoogleDriveUploader @Inject constructor(
    private val accountRepository: GoogleDriveAccountRepository,
    private val tokenProvider: GoogleDriveAccessTokenProvider,
    private val driveApi: GoogleDriveApi,
) {
    /** Refreshes each connected account's live Drive quota in parallel. */
    suspend fun refreshAllQuotas(): List<GoogleDriveAccountEntity> {
        val accounts = accountRepository.getAccounts()
        return coroutineScope {
            accounts.map { account ->
                async(Dispatchers.IO) {
                    try {
                        probe(account).account
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        accountRepository.updateQuotaError(account.id, safeFailureMessage(failure))
                        account.copy(quotaUpdatedAtMillis = System.currentTimeMillis(), quotaError = safeFailureMessage(failure))
                    }
                }
            }.awaitAll()
        }
    }

    /** Every account is re-queried immediately before selection; no hard-coded pool size is used. */
    suspend fun uploadTaskOutput(filename: String, content: String): GoogleDriveUploadResult =
        withContext(Dispatchers.IO) {
            val accounts = accountRepository.getAccounts()
            require(accounts.isNotEmpty()) { "Add a Google account in Drive settings before uploading task results." }
            val candidates = coroutineScope {
                accounts.map { account ->
                    async(Dispatchers.IO) {
                        try {
                            probe(account)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            accountRepository.updateQuotaError(account.id, safeFailureMessage(failure))
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val ranked = orderDriveCandidates(candidates)
            check(ranked.isNotEmpty()) {
                "Google Drive could not verify storage for any connected account. Reconnect an account or refresh storage, then retry."
            }

            var quotaFailure: Exception? = null
            for (candidate in ranked) {
                try {
                    val url = try {
                        driveApi.uploadTaskOutput(candidate.accessToken, filename, content)
                    } catch (unauthorized: DriveApiException) {
                        if (unauthorized.statusCode != 401) throw unauthorized
                        tokenProvider.invalidate(candidate.account.email, candidate.accessToken)
                        val refreshedToken = tokenProvider.getAccessToken(candidate.account.email)
                        driveApi.uploadTaskOutput(refreshedToken, filename, content)
                    }
                    return@withContext GoogleDriveUploadResult(candidate.account.email, url)
                } catch (failure: DriveApiException) {
                    val isQuotaExceeded = failure.statusCode == 403 && failure.reason.contains("quota", ignoreCase = true)
                    if (!isQuotaExceeded) throw failure
                    quotaFailure = failure
                    accountRepository.updateQuotaError(candidate.account.id, "Drive reported that this account is out of storage.")
                }
            }
            throw quotaFailure ?: IllegalStateException("No connected Google Drive account has enough storage.")
        }

    private suspend fun probe(account: GoogleDriveAccountEntity): DriveUploadCandidate {
        var token = tokenProvider.getAccessToken(account.email)
        val quota = try {
            driveApi.getStorageQuota(token)
        } catch (unauthorized: DriveApiException) {
            if (unauthorized.statusCode != 401) throw unauthorized
            tokenProvider.invalidate(account.email, token)
            token = tokenProvider.getAccessToken(account.email)
            driveApi.getStorageQuota(token)
        }
        accountRepository.updateQuota(account.id, quota.limitBytes, quota.usageBytes)
        return DriveUploadCandidate(
            account = account.copy(
                quotaLimitBytes = quota.limitBytes,
                quotaUsageBytes = quota.usageBytes,
                quotaUpdatedAtMillis = System.currentTimeMillis(),
                quotaError = null,
            ),
            accessToken = token,
            quota = quota,
        )
    }

    private fun safeFailureMessage(failure: Exception): String = when (failure) {
        is DriveApiException -> "Google Drive returned HTTP ${failure.statusCode}."
        else -> (failure.message ?: "Storage check failed.").take(180)
    }
}
