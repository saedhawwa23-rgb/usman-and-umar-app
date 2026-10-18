package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.opendroid.ai.data.db.entities.WorkspaceTaskDependencyEntity
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.db.entities.WorkspaceTaskExecutionEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WorkspaceTaskDao {
    @Query("SELECT * FROM workspace_tasks ORDER BY priority DESC, updatedAt DESC")
    abstract fun observeAll(): Flow<List<WorkspaceTaskEntity>>

    @Query("SELECT * FROM workspace_tasks WHERE state IN ('RUNNING', 'QUEUED') ORDER BY priority DESC, updatedAt ASC")
    abstract suspend fun findInterruptedOrQueued(): List<WorkspaceTaskEntity>

    @Query("SELECT * FROM workspace_tasks WHERE id = :taskId LIMIT 1")
    abstract suspend fun get(taskId: String): WorkspaceTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(task: WorkspaceTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun update(task: WorkspaceTaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addDependency(dependency: WorkspaceTaskDependencyEntity)

    @Query("SELECT dependsOnTaskId FROM workspace_task_dependencies WHERE taskId = :taskId")
    abstract suspend fun dependenciesOf(taskId: String): List<String>

    @Query("SELECT taskId FROM workspace_task_dependencies WHERE dependsOnTaskId = :dependencyId")
    abstract suspend fun dependentsOf(dependencyId: String): List<String>

    @Insert
    abstract suspend fun recordExecution(execution: WorkspaceTaskExecutionEntity): Long

    @Query("SELECT * FROM workspace_task_executions WHERE taskId = :taskId ORDER BY startedAt DESC, id DESC")
    abstract suspend fun executionsOf(taskId: String): List<WorkspaceTaskExecutionEntity>

    @Transaction
    open suspend fun insertWithDependencies(
        task: WorkspaceTaskEntity,
        dependencies: List<WorkspaceTaskDependencyEntity>,
    ) {
        insert(task)
        dependencies.forEach { dependency -> addDependency(dependency) }
    }
}
