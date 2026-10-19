package com.opendroid.ai.core.service

/**
 * Actions exposed to local MCP clients without a UI approval channel.
 * Keep this allowlist limited to deterministic, non-personal computations.
 */
object McpToolPolicy {
    private val safeActions = setOf("CALCULATE", "CONVERT_UNITS")

    fun availableActionIds(): List<String> = safeActions.sorted()

    fun isActionAllowed(canonicalActionId: String): Boolean = canonicalActionId in safeActions
}
