package com.opendroid.ai.core.agentplatform

/**
 * The reviewed platform catalog and the rule that keeps adapters honest.
 *
 * Entries describe only platforms whose integration mode is real. A browser-only site is recorded
 * as [IntegrationMode.APPROVED_BROWSER_AUTOMATION] with a reduced capability set and a trust level
 * that says so, never as a documented API.
 */
object AgentPlatformCatalog {

    /**
     * OpenHands agent server. Verified against a live v1.60.1 server: conversations can be created,
     * tasks run, events streamed and the final answer read. Every capability below was exercised.
     */
    val openHands = AgentPlatform(
        id = "openhands",
        displayName = "OpenHands",
        integrationMode = IntegrationMode.DOCUMENTED_API,
        authKind = PlatformAuthKind.API_TOKEN,
        trustLevel = TrustLevel.SELF_HOSTED,
        supported = setOf(
            PlatformCapability.CREATE_SESSION,
            PlatformCapability.SUBMIT_TASK,
            PlatformCapability.OBSERVE_EVENTS,
            PlatformCapability.CANCEL_TASK,
        ),
        documentationUrl = "https://docs.all-hands.dev/",
        limitations = listOf(
            "Requires an OpenHands agent server the user runs (self-hosted or cloud) and its session key.",
            "Cancellation stops the agent at its next safe point; it is not instantaneous.",
            "The server must be reachable from the phone; a loopback URL only works on the same host.",
        ),
    )

    /**
     * Browser-only platforms are recorded honestly: no API, no unattended automation, no capability
     * that would imply either. This keeps the catalog from promising an integration it does not have.
     */
    val browserOnlyExample = AgentPlatform(
        id = "browser_only",
        displayName = "Browser-only platform",
        integrationMode = IntegrationMode.APPROVED_BROWSER_AUTOMATION,
        authKind = PlatformAuthKind.NONE,
        trustLevel = TrustLevel.BROWSER_ONLY,
        supported = emptySet(),
        documentationUrl = "",
        limitations = listOf(
            "No documented API. Not an integration; browser automation requires per-run approval.",
        ),
    )

    val all: List<AgentPlatform> = listOf(openHands, browserOnlyExample)

    fun byId(id: String): AgentPlatform? = all.firstOrNull { it.id == id }

    /** Case-insensitive search over the name, mode and limitations shown in the UI. */
    fun search(query: String): List<AgentPlatform> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { platform ->
            platform.displayName.lowercase().contains(q) ||
                platform.integrationMode.displayName.lowercase().contains(q) ||
                platform.trustLevel.name.lowercase().contains(q) ||
                platform.limitations.any { it.lowercase().contains(q) }
        }
    }

    /** Platforms that expose a documented API or SDK and can therefore be integrated unattended. */
    fun apiIntegrated(): List<AgentPlatform> = all.filter {
        it.integrationMode == IntegrationMode.DOCUMENTED_API ||
            it.integrationMode == IntegrationMode.SDK ||
            it.integrationMode == IntegrationMode.MCP ||
            it.integrationMode == IntegrationMode.REMOTE_WORKER
    }

    /**
     * Refuses an operation the platform did not declare, so an adapter cannot quietly grow
     * behaviour its catalog entry does not claim. Returns null when the call may proceed.
     */
    fun guard(platform: AgentPlatform, capability: PlatformCapability): PlatformResult<Unit>? =
        if (capability in platform.supported) null
        else PlatformResult.Unsupported(capability.name.lowercase(), platform.id)
}
