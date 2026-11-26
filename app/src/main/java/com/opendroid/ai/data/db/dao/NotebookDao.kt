package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opendroid.ai.data.db.entities.NotebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY builtIn DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE contentSha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): NotebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET name = :name, description = :description, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updateDetails(id: String, name: String, description: String, updatedAtMillis: Long)

    @Query("UPDATE notebooks SET lastOpenedAtMillis = :openedAtMillis WHERE id = :id")
    suspend fun markOpened(id: String, openedAtMillis: Long)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteById(id: String)
}
