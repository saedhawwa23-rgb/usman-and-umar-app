package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opendroid.ai.data.db.entities.GoogleDriveAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoogleDriveAccountDao {
    @Query("SELECT * FROM google_drive_accounts ORDER BY connectedAtMillis ASC")
    fun observeAll(): Flow<List<GoogleDriveAccountEntity>>

    @Query("SELECT * FROM google_drive_accounts ORDER BY connectedAtMillis ASC")
    suspend fun getAll(): List<GoogleDriveAccountEntity>

    @Query("SELECT * FROM google_drive_accounts WHERE id = :accountId LIMIT 1")
    suspend fun getById(accountId: String): GoogleDriveAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: GoogleDriveAccountEntity)

    @Query("UPDATE google_drive_accounts SET quotaLimitBytes = :limitBytes, quotaUsageBytes = :usageBytes, quotaUpdatedAtMillis = :checkedAtMillis, quotaError = NULL WHERE id = :accountId")
    suspend fun updateQuota(accountId: String, limitBytes: Long?, usageBytes: Long, checkedAtMillis: Long)

    @Query("UPDATE google_drive_accounts SET quotaUpdatedAtMillis = :checkedAtMillis, quotaError = :error WHERE id = :accountId")
    suspend fun updateQuotaError(accountId: String, error: String, checkedAtMillis: Long)

    @Query("DELETE FROM google_drive_accounts WHERE id = :accountId")
    suspend fun deleteById(accountId: String)
}
