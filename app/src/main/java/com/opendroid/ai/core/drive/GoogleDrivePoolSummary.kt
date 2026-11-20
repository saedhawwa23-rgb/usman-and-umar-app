package com.opendroid.ai.core.drive

import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import java.util.Locale

internal data class GoogleDrivePoolSummary(
    val accountCount: Int,
    val verifiedAccountCount: Int,
    val totalLimitBytes: Long?,
    val totalUsageBytes: Long?,
    val includesUnlimitedAccount: Boolean,
) {
    val isComplete: Boolean get() = accountCount == verifiedAccountCount
}

internal fun summarizeGoogleDrivePool(accounts: List<GoogleDriveAccountEntity>): GoogleDrivePoolSummary {
    val verified = accounts.filter {
        it.quotaUpdatedAtMillis != null && it.quotaError == null && it.quotaUsageBytes != null
    }
    val complete = verified.size == accounts.size
    val includesUnlimited = complete && verified.any { it.quotaLimitBytes == null }
    return GoogleDrivePoolSummary(
        accountCount = accounts.size,
        verifiedAccountCount = verified.size,
        totalLimitBytes = if (complete && !includesUnlimited) {
            verified.sumOf { it.quotaLimitBytes ?: 0L }
        } else {
            null
        },
        totalUsageBytes = if (complete) verified.sumOf { it.quotaUsageBytes ?: 0L } else null,
        includesUnlimitedAccount = includesUnlimited,
    )
}

internal fun formatGoogleStorage(bytes: Long): String {
    val gibibytes = bytes.coerceAtLeast(0L).toDouble() / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f GB", gibibytes)
}
