package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workspace_tasks",
    indices = [Index("state"), Index("priority"), Index("updatedAt"), Index("projectId")],
)
data class WorkspaceTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val projectId: String?,
    val category: String,
    val tagsJson: String,
    val priority: Int,
    val deadlineAt: Long?,
    val toolId: String?,
    val workflowId: String?,
    val inputJson: String,
    val expectedOutcome: String,
    val notes: String,
    val retryLimit: Int,
    val timeoutMs: Long,
    val requiresApproval: Boolean,
    val schedule: String?,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workspace_task_dependencies",
    primaryKeys = ["taskId", "dependsOnTaskId"],
    indices = [Index("dependsOnTaskId")],
)
data class WorkspaceTaskDependencyEntity(
    val taskId: String,
    val dependsOnTaskId: String,
)

@Entity(
    tableName = "workspace_task_executions",
    indices = [Index("taskId"), Index("startedAt"), Index("toState")],
)
data class WorkspaceTaskExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val event: String,
    val fromState: String?,
    val toState: String,
    val attempt: Int,
    val success: Boolean?,
    val outputRedacted: String?,
    val errorCode: String?,
    val errorMessageRedacted: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)
