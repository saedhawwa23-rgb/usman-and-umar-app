package com.usmanandumar.workspace.core.tool

import kotlinx.serialization.Serializable

@Serializable
enum class ToolAvailability {
    AVAILABLE,
    NEEDS_PERMISSION,
    NEEDS_CONFIGURATION,
    DEPENDENCY_MISSING,
    UNAVAILABLE,
    RUNNING,
    FAILED,
}

@Serializable
enum class ToolPermission {
    NONE,
    NETWORK,
    FILES,
    TERMUX,
    ACCESSIBILITY,
    GITHUB_WRITE,
}

@Serializable
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val version: Int = 1,
    val inputSchema: String = "{}",
    val outputSchema: String = "{}",
    val permissions: Set<ToolPermission> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val availability: ToolAvailability = ToolAvailability.AVAILABLE,
    val timeoutMs: Long = 30_000,
    val requiresApproval: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Tool IDs must be stable lowercase identifiers" }
        require(name.isNotBlank()) { "Tool name must not be blank" }
        require(version > 0) { "Tool version must be positive" }
        require(timeoutMs > 0) { "Tool timeout must be positive" }
    }
}

@Serializable
data class ToolExecutionResult(
    val toolId: String,
    val success: Boolean,
    val output: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val warnings: List<String> = emptyList(),
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val relevantFiles: List<String> = emptyList(),
) {
    init {
        require(finishedAtEpochMs >= startedAtEpochMs) { "Execution finish cannot precede start" }
        require(success || !errorCode.isNullOrBlank()) { "Failed executions need an error code" }
    }
}

class ToolRegistry(definitions: Iterable<ToolDefinition> = emptyList()) {
    private val tools = linkedMapOf<String, ToolDefinition>()

    init {
        definitions.forEach(::register)
    }

    fun register(definition: ToolDefinition) {
        require(definition.id !in tools) { "Tool already registered: ${definition.id}" }
        tools[definition.id] = definition
    }

    fun replace(definition: ToolDefinition) {
        tools[definition.id] = definition
    }

    fun get(id: String): ToolDefinition? = tools[id]

    fun all(): List<ToolDefinition> = tools.values.toList()
}
