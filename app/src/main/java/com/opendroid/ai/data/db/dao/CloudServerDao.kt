package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opendroid.ai.data.db.entities.CloudServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudServerDao {
    @Query("SELECT * FROM cloud_servers ORDER BY position ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CloudServerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: CloudServerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<CloudServerEntity>)

    @Query("UPDATE cloud_servers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM cloud_servers WHERE id = :id")
    suspend fun delete(id: String)
}
