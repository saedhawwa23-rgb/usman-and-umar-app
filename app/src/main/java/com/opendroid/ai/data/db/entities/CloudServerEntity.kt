package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Non-secret cluster metadata. Bearer tokens remain in the encrypted credential store. */
@Entity(tableName = "cloud_servers")
data class CloudServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val role: String = "OPENSWARM",
)
