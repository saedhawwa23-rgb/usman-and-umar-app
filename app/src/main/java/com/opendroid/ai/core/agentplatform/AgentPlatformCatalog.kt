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
     * OpenHands agent server. Verified against a live v1.62.0 server: conversations can be created,
     * tasks run, events streamed and the final answer read. File transfer uses the Agent Server's
     * documented workspace endpoints; the opt-in live E2E test covers upload and download when a
     * reachable server and session key are configured.
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
            PlatformCapability.UPLOAD_FILE,
            PlatformCapability.DOWNLOAD_ARTIFACT,
        ),
        documentationUrl = "https://docs.all-hands.dev/",
        limitations = listOf(
            "Requires an OpenHands agent server the user runs (self-hosted or cloud) and its session key.",
            "Cancellation stops the agent at its next safe point; it is not instantaneous.",
            "The server must be reachable from the phone; a loopback URL only works on the same host.",
            "Files can be uploaded and downloaded by exact server-side path; the Agent Server API does not currently expose file enumeration.",
        ),
    )

    /**
     * The USMAN AND UMAR OpenSwarm task backend (`cloud-backend/main.py`), the app's own service.
     *
     * Verified against a locally launched instance: `/health` needs no token, and `/agents`,
     * `/capabilities`, `/tasks/{id}` and `/tasks/{id}/logs` require the bearer token and answer
     * 401 without it and 404 for an unknown task. `/execute` refuses to run until the backend's own
     * readiness checks pass (Upstash Redis configured) and returns `BACKEND_NOT_READY`, so task
     * submission is declared but its precondition is stated in [limitations] rather than hidden.
     */
    val openSwarm = AgentPlatform(
        id = "openswarm",
        displayName = "OpenSwarm task backend",
        integrationMode = IntegrationMode.DOCUMENTED_API,
        authKind = PlatformAuthKind.API_TOKEN,
        trustLevel = TrustLevel.SELF_HOSTED,
        supported = setOf(
            PlatformCapability.CREATE_SESSION,
            PlatformCapability.SUBMIT_TASK,
            PlatformCapability.OBSERVE_EVENTS,
            PlatformCapability.CANCEL_TASK,
        ),
        documentationUrl = "https://github.com/saedhawwa23-rgb/usman-and-umar-app/tree/main/cloud-backend",
        limitations = listOf(
            "Requires your own deployed backend URL and the APP_CLIENT_TOKEN configured on it.",
            "Task execution stays disabled until the backend reports ready; it needs Upstash Redis configured.",
            "No file upload or artifact download: the backend does not expose those endpoints.",
            "Cancellation is cooperative; the agent stops at its next safe point.",
        ),
    )

    /**
     * A Model Context Protocol server the user runs or subscribes to.
     *
     * This is a real integration mode, not a link: the app performs MCP `tools/list` to verify the
     * connection and `tools/call` to run a tool, using [com.opendroid.ai.core.connector.McpToolDiscovery]
     * and [com.opendroid.ai.core.connector.McpConnector]. MCP defines no conversation, no event
     * stream and no cancellation, so none of those capabilities are declared.
     */
    val mcpServer = AgentPlatform(
        id = "mcp_server",
        displayName = "MCP tool server",
        integrationMode = IntegrationMode.MCP,
        authKind = PlatformAuthKind.API_TOKEN,
        trustLevel = TrustLevel.SELF_HOSTED,
        supported = setOf(
            PlatformCapability.SUBMIT_TASK,
        ),
        documentationUrl = "https://modelcontextprotocol.io/",
        limitations = listOf(
            "Requires the URL of an MCP server you run or are subscribed to.",
            "A call is one tool invocation: the prompt's first line is the tool name, the rest is its JSON arguments.",
            "MCP defines no conversation, event stream, cancellation or file transfer, so those are unavailable rather than simulated.",
            "The server's own tool schemas are used as-is; the app does not invent parameters.",
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

    val all: List<AgentPlatform> = listOf(openHands, openSwarm, mcpServer, browserOnlyExample)

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
