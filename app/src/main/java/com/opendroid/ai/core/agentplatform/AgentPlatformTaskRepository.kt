package com.opendroid.ai.core.agentplatform

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One submitted task and its outcome, kept so history and results survive a process restart.
 *
 * The record stores the agent's final answer and the last error, never a credential.
 */
@Entity(tableName = "agent_platform_tasks")
data class AgentPlatformTaskEntity(
    @PrimaryKey val id: String,
    val platformId: String,
    val conversationId: String,
    val prompt: String,
    val workingDir: String,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val finalResponse: String? = null,
    val lastError: String? = null,
    val eventCount: Int = 0,
)

@Dao
interface AgentPlatformTaskDao {
    @Query("SELECT * FROM agent_platform_tasks ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AgentPlatformTaskEntity>>

    @Query("SELECT * FROM agent_platform_tasks WHERE id = :id")
    suspend fun find(id: String): AgentPlatformTaskEntity?

    @Query("SELECT * FROM agent_platform_tasks WHERE platformId = :platformId ORDER BY createdAtMillis DESC")
    suspend fun forPlatform(platformId: String): List<AgentPlatformTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentPlatformTaskEntity)

    @Query("DELETE FROM agent_platform_tasks WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * A separate database file, so adding this feature cannot disturb the existing
 * `opendroid_database` schema or its migration chain.
 */
@Database(entities = [AgentPlatformTaskEntity::class], version = 1, exportSchema = false)
abstract class AgentPlatformDatabase : RoomDatabase() {
    abstract fun taskDao(): AgentPlatformTaskDao
}

@Singleton
class AgentPlatformTaskRepository @Inject constructor(
    private val dao: AgentPlatformTaskDao,
) {
    fun observeAll(): Flow<List<AgentPlatformTaskEntity>> = dao.observeAll()

    suspend fun find(id: String): AgentPlatformTaskEntity? = dao.find(id)

    suspend fun record(
        id: String,
        platformId: String,
        conversationId: String,
        prompt: String,
        workingDir: String,
        state: String,
        finalResponse: String? = null,
        lastError: String? = null,
        eventCount: Int = 0,
    ) {
        val existing = dao.find(id)
        val now = System.currentTimeMillis()
        dao.upsert(
            AgentPlatformTaskEntity(
                id = id,
                platformId = platformId,
                conversationId = conversationId,
                prompt = prompt,
                workingDir = workingDir,
                state = state,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
                finalResponse = finalResponse ?: existing?.finalResponse,
                lastError = lastError,
                eventCount = eventCount,
            )
        )
    }

    suspend fun delete(id: String) = dao.delete(id)
}

object AgentPlatformDatabaseFactory {
    fun create(context: Context): AgentPlatformDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AgentPlatformDatabase::class.java,
            "agent_platform_database",
        ).fallbackToDestructiveMigration().build()
}
