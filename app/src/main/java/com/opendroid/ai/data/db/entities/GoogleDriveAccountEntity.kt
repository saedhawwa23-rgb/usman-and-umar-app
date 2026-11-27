package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Account metadata and the last real quota snapshot returned by Drive's about.get endpoint. */
@Entity(tableName = "google_drive_accounts")
data class GoogleDriveAccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String,
    val connectedAtMillis: Long,
    val quotaLimitBytes: Long? = null,
    val quotaUsageBytes: Long? = null,
    val quotaUpdatedAtMillis: Long? = null,
    val quotaError: String? = null,
)
