package com.usmanandumar.workspace.core.task

/** A dependent task may enter its executable queue only after every prerequisite succeeds. */
object TaskDependencyPolicy {
    fun isReady(prerequisiteStates: Iterable<TaskState>): Boolean =
        prerequisiteStates.all { it == TaskState.SUCCEEDED }

    fun unmet(prerequisiteStates: Iterable<Pair<String, TaskState>>): List<Pair<String, TaskState>> =
        prerequisiteStates.filter { (_, state) -> state != TaskState.SUCCEEDED }
}
