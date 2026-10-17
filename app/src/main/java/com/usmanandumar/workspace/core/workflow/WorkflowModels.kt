package com.usmanandumar.workspace.core.workflow

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowStep(
    val id: String,
    val toolId: String,
    val dependsOn: List<String> = emptyList(),
    val condition: String? = null,
    val requiresApproval: Boolean = false,
    val timeoutMs: Long = 30_000,
    val maxRetries: Int = 0,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Workflow step IDs must be stable lowercase identifiers" }
        require(toolId.isNotBlank()) { "Workflow steps need a tool ID" }
        require(timeoutMs > 0) { "Workflow step timeout must be positive" }
        require(maxRetries in 0..5) { "Workflow retries must be bounded from 0 to 5" }
    }
}

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val steps: List<WorkflowStep>,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (!id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) errors += "Workflow ID is invalid"
        if (name.isBlank()) errors += "Workflow name must not be blank"
        val ids = steps.map { it.id }
        if (ids.size != ids.toSet().size) errors += "Workflow step IDs must be unique"
        val known = ids.toSet()
        steps.forEach { step ->
            step.dependsOn.filterNot(known::contains).forEach { missing ->
                errors += "Step ${step.id} depends on missing step $missing"
            }
        }
        if (hasCycle()) errors += "Workflow dependencies contain a cycle"
        return errors
    }

    private fun hasCycle(): Boolean {
        val byId = steps.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (!visited.add(id)) return false
            visiting += id
            val cycle = byId[id]?.dependsOn?.any(::visit) == true
            visiting -= id
            return cycle
        }
        return steps.any { visit(it.id) }
    }
}
