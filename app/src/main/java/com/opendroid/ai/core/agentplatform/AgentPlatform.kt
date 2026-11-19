package com.opendroid.ai.core.agentplatform

/**
 * Domain model for web-based AI agent platforms (OpenHands, Browser Use, ...), kept strictly
 * separate from the LLM model-provider catalog in `com.opendroid.ai.core.plugin.AgentProviders`.
 *
 * The distinction matters: a model provider answers completions, while an agent platform runs
 * tasks. A website URL alone is never an integration, so [IntegrationMode] forces every entry to
 * declare how it is actually driven, and an adapter may only offer the operations its platform
 * documents. Unsupported operations return [PlatformResult.Unsupported]; nothing is simulated.
 */

/** How an agent platform is actually driven. A plain website link is none of these. */
enum class IntegrationMode(val displayName: String) {
    /** A documented HTTP API the app calls directly. */
    DOCUMENTED_API("Documented API"),

    /** A vendor SDK wrapped by a thin adapter. */
    SDK("Vendor SDK"),

    /** A Model Context Protocol server exposing tools. */
    MCP("MCP server"),

    /** A worker process the app enqueues work to and polls. */
    REMOTE_WORKER("Remote worker"),

    /** Browser automation against an approved host. Requires explicit per-run approval. */
    APPROVED_BROWSER_AUTOMATION("Approved browser automation"),
}

/** What a platform can do. Declared per platform so the UI can show honest limits. */
enum class PlatformCapability {
    CREATE_SESSION,
    SUBMIT_TASK,
    OBSERVE_EVENTS,
    CANCEL_TASK,
    LIST_ARTIFACTS,
    DOWNLOAD_ARTIFACT,
}

/** Authentication a platform requires. */
enum class PlatformAuthKind {
    OAUTH_PKCE,
    API_TOKEN,
    NONE,
}

/** Trust classification shown to the user next to the platform. */
enum class TrustLevel {
    /** Documented, supported integration owned by the vendor. */
    VENDOR_SUPPORTED,

    /** Community or self-hosted; the user runs the server. */
    SELF_HOSTED,

    /** Browser automation, which is inherently fragile and requires approval. */
    BROWSER_ONLY,
}

/**
 * A platform the app can integrate with.
 *
 * [supported] is the authoritative list: an adapter must refuse anything not in it. [limitations]
 * is shown verbatim so the UI cannot overstate what works.
 */
data class AgentPlatform(
    val id: String,
    val displayName: String,
    val integrationMode: IntegrationMode,
    val authKind: PlatformAuthKind,
    val trustLevel: TrustLevel,
    val supported: Set<PlatformCapability>,
    val documentationUrl: String,
    val limitations: List<String> = emptyList(),
)

/** Lifecycle of one task on a platform. Mirrors the states the UI is required to show. */
enum class AgentTaskState {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    COMPLETE,
    FAILED,
    CANCELLED,

    /** The platform does not report state for this operation. */
    UNKNOWN,
}

/** A single event in a task's timeline. [sequence] makes ordering explicit across reconnects. */
data class AgentTaskEvent(
    val sequence: Long,
    val state: AgentTaskState,
    val message: String,
    val timestampMillis: Long,
)

/** A produced file. [sizeBytes] and [sha256] let the caller verify integrity before opening. */
data class AgentArtifact(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/** Result of an adapter call. Failure carries a specific, user-facing message. */
sealed interface PlatformResult<out T> {
    data class Success<T>(val value: T) : PlatformResult<T>

    /** The platform does not provide this operation. Never treated as success. */
    data class Unsupported(val operation: String, val platformId: String) : PlatformResult<Nothing>

    /** A real failure. [message] is safe to show; it must never contain a credential. */
    data class Failure(val message: String, val cause: Throwable? = null) : PlatformResult<Nothing>
}

/**
 * The contract every platform adapter implements.
 *
 * Defaults return [PlatformResult.Unsupported] so an adapter declares only what its platform
 * really supports, and a caller can never mistake an unimplemented method for a working one.
 */
interface AgentPlatformAdapter {
    val platform: AgentPlatform

    suspend fun validateConnection(): PlatformResult<Unit> =
        PlatformResult.Unsupported("validateConnection", platform.id)

    suspend fun listCapabilities(): PlatformResult<Set<PlatformCapability>> =
        PlatformResult.Success(platform.supported)

    suspend fun createSession(): PlatformResult<String> =
        PlatformResult.Unsupported("createSession", platform.id)

    suspend fun submitTask(sessionId: String, prompt: String): PlatformResult<String> =
        PlatformResult.Unsupported("submitTask", platform.id)

    suspend fun getTask(taskId: String): PlatformResult<AgentTaskState> =
        PlatformResult.Unsupported("getTask", platform.id)

    suspend fun observeTaskEvents(taskId: String, sinceSequence: Long): PlatformResult<List<AgentTaskEvent>> =
        PlatformResult.Unsupported("observeTaskEvents", platform.id)

    suspend fun cancelTask(taskId: String): PlatformResult<Unit> =
        PlatformResult.Unsupported("cancelTask", platform.id)

    suspend fun listArtifacts(taskId: String): PlatformResult<List<AgentArtifact>> =
        PlatformResult.Unsupported("listArtifacts", platform.id)

    suspend fun downloadArtifact(taskId: String, artifactId: String): PlatformResult<ByteArray> =
        PlatformResult.Unsupported("downloadArtifact", platform.id)
}
