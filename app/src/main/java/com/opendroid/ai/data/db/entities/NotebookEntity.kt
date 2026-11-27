package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notebooks",
    indices = [Index(value = ["updatedAtMillis"]), Index(value = ["contentSha256"], unique = true)]
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val sourceUri: String?,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val builtIn: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastOpenedAtMillis: Long? = null,
)
