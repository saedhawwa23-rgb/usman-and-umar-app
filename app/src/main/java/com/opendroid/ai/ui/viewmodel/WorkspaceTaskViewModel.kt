package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usmanandumar.workspace.core.task.TaskState
import com.opendroid.ai.data.db.entities.WorkspaceTaskEntity
import com.opendroid.ai.data.repository.WorkspaceTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WorkspaceTaskViewModel @Inject constructor(
    private val repository: WorkspaceTaskRepository,
) : ViewModel() {
    val tasks: StateFlow<List<WorkspaceTaskEntity>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.create(title = title.trim())
        }
    }

    fun transition(task: WorkspaceTaskEntity, target: TaskState) {
        viewModelScope.launch {
            runCatching { repository.transition(task.id, target) }
        }
    }
}
