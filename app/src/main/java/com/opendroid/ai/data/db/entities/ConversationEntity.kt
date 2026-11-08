package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["sessionId"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val text: String,
    val sender: String, // "USER" or "AGENT"
    val timestamp: Long,
    val modelBadge: String? = null,
    val contactPickerData: String? = null,
    // Which chat history this message belongs to. See ChatSessionEntity.
    val sessionId: String,
    val deliveryState: String? = null,
    val deliveryTarget: String? = null,
    val deliveryTaskId: String? = null,
    val deliveryMetadata: String? = null,
    val deliveryError: String? = null,
    val deliveryRetryable: Boolean? = null,
    val imageBase64: String? = null,
)
