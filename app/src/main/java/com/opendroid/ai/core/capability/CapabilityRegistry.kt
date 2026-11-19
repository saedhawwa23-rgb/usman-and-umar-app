package com.opendroid.ai.core.capability

import com.opendroid.ai.ui.screens.toolbar.ToolAvailability

/**
 * The single place that answers "what can this build actually do right now, and which module owns it".
 *
 * Before this file the app carried two unrelated availability vocabularies —
 * [ToolAvailability] in the Tool Bar UI model and `ToolAvailability` in
 * `com.usmanandumar.workspace.core.tool.ToolContracts` — with no shared mapping and no owner
 * recorded per capability. That made it possible for two modules to describe the same feature
 * differently. This registry reconciles both into one vocabulary and names the owning module, so a
 * capability has exactly one definition.
 *
 * Pure Kotlin: no Compose, no Android, no I/O. Availability is *derived* from facts the caller
 * passes in, never asserted, so a capability cannot report READY unless its requirement is met.
 */
enum class CapabilityState {
    /** Usable now with what is already configured. */
    READY,

    /** Performs a real but non-mutating request (search, catalog listing, metadata lookup). */
    READ_ONLY,

    /** Requires the Termux app plus its RUN_COMMAND grant. */
    NEEDS_TERMUX,

    /** Requires a credential, endpoint or server the user has not configured. */
    NEEDS_SETUP,

    /** A hard dependency of the owning module is absent. */
    DEPENDENCY_MISSING,

    /** Not shipped in this build. Never used to hide a capability that exists. */
    UNAVAILABLE,
}

/** What a capability needs before it can run. Drives [CapabilityRegistry.resolve]. */
enum class CapabilityRequirement { NONE, TERMUX, CREDENTIAL, CLOUD_SERVER, GITHUB_TOKEN, NETWORK }

/** The module that owns a capability. One owner per capability — no duplicated responsibility. */
enum class CapabilityModule {
    AI_PROVIDERS,
    CLOUD_AGENT,
    PLUGINS,
    REMOTE,
    TASKS,
    TOOL_BAR,
    TERMUX,
    BROWSER,
    FILES,
    GITHUB,
    MEMORY,
    OPENSWARM,
    SECURITY,
    MONITORING,
}

/**
 * One capability: a stable id, the module that owns it, what it needs, and where its UI lives.
 *
 * [navigationRoute] is the real route the Tool Bar opens for it, or null when the capability has no
 * dedicated screen. It is recorded so the registry and the navigation graph cannot drift apart.
 */
data class Capability(
    val id: String,
    val title: String,
    val module: CapabilityModule,
    val requirement: CapabilityRequirement,
    val navigationRoute: String? = null,
    val mutating: Boolean = false,
)

/** The observed environment. Passed in so resolution stays pure and unit-testable. */
data class CapabilityEnvironment(
    val termuxInstalled: Boolean = false,
    val credentialConfigured: Boolean = false,
    val cloudServerConfigured: Boolean = false,
    val githubTokenConnected: Boolean = false,
    val networkAvailable: Boolean = true,
)

/** A capability with its resolved state. */
data class ResolvedCapability(val capability: Capability, val state: CapabilityState) {
    val isUsable: Boolean get() = state == CapabilityState.READY || state == CapabilityState.READ_ONLY
}

object CapabilityRegistry {

    /**
     * The catalog. Every entry names a module that already exists in this build; nothing here
     * describes work that has not been implemented.
     */
    private val catalog: List<Capability> = listOf(
        Capability("ai.providers", "AI providers & models", CapabilityModule.AI_PROVIDERS, CapabilityRequirement.NETWORK, "discovery"),
        Capability("ai.cloud-agent", "Cloud AI tool agent", CapabilityModule.CLOUD_AGENT, CapabilityRequirement.CLOUD_SERVER, "universal_agent", mutating = true),
        Capability("ai.integrations", "Integrations, MCP & OpenAPI", CapabilityModule.PLUGINS, CapabilityRequirement.NONE, "plugin_platform", mutating = true),
        Capability("ai.remote", "Universal remote control", CapabilityModule.REMOTE, CapabilityRequirement.CLOUD_SERVER, "remote_control", mutating = true),
        Capability("tasks.manager", "Task Bar", CapabilityModule.TASKS, CapabilityRequirement.NONE, "task_manager"),
        Capability("github.search", "Search public repositories", CapabilityModule.GITHUB, CapabilityRequirement.NETWORK, "github_discovery"),
        Capability("github.inspect", "Inspect a repository", CapabilityModule.GITHUB, CapabilityRequirement.NETWORK, "github_discovery"),
        Capability("github.clone", "Clone a repository", CapabilityModule.GITHUB, CapabilityRequirement.TERMUX, "termux", mutating = true),
        Capability("github.projects", "Manage projects", CapabilityModule.GITHUB, CapabilityRequirement.NONE, "github_discovery", mutating = true),
        Capability("github.private", "Private repository access", CapabilityModule.GITHUB, CapabilityRequirement.GITHUB_TOKEN, null),
        Capability("linux.termux", "Termux terminal", CapabilityModule.TERMUX, CapabilityRequirement.TERMUX, "termux", mutating = true),
        Capability("linux.tools", "Development tools", CapabilityModule.TERMUX, CapabilityRequirement.TERMUX, "termux", mutating = true),
        Capability("linux.ai-assisted", "AI-assisted commands", CapabilityModule.TERMUX, CapabilityRequirement.TERMUX, "termux", mutating = true),
        Capability("browser.automation", "Browser automation", CapabilityModule.BROWSER, CapabilityRequirement.CLOUD_SERVER, null, mutating = true),
        Capability("files.storage", "Files & workspace storage", CapabilityModule.FILES, CapabilityRequirement.NONE, null, mutating = true),
        Capability("memory.store", "Memory", CapabilityModule.MEMORY, CapabilityRequirement.NONE, "memory", mutating = true),
        Capability("security.credentials", "Credential storage", CapabilityModule.SECURITY, CapabilityRequirement.NONE, null),
        Capability("monitoring.crash", "Crash & log diagnostics", CapabilityModule.MONITORING, CapabilityRequirement.NONE, null),
    )

    fun all(): List<Capability> = catalog

    fun byModule(module: CapabilityModule): List<Capability> = catalog.filter { it.module == module }

    fun find(id: String): Capability? = catalog.firstOrNull { it.id == id }

    /** Resolves one capability against the environment. Never returns READY when a need is unmet. */
    fun resolve(capability: Capability, env: CapabilityEnvironment): CapabilityState {
        if (capability.requirement == CapabilityRequirement.NETWORK && !env.networkAvailable) {
            return CapabilityState.DEPENDENCY_MISSING
        }
        return when (capability.requirement) {
            CapabilityRequirement.NONE -> if (capability.mutating) CapabilityState.READY else CapabilityState.READ_ONLY
            CapabilityRequirement.NETWORK -> CapabilityState.READ_ONLY
            CapabilityRequirement.TERMUX -> if (env.termuxInstalled) CapabilityState.READY else CapabilityState.NEEDS_TERMUX
            CapabilityRequirement.CREDENTIAL -> if (env.credentialConfigured) CapabilityState.READY else CapabilityState.NEEDS_SETUP
            CapabilityRequirement.CLOUD_SERVER -> if (env.cloudServerConfigured) CapabilityState.READY else CapabilityState.NEEDS_SETUP
            CapabilityRequirement.GITHUB_TOKEN -> if (env.githubTokenConnected) CapabilityState.READY else CapabilityState.NEEDS_SETUP
        }
    }

    fun resolveAll(env: CapabilityEnvironment): List<ResolvedCapability> =
        catalog.map { ResolvedCapability(it, resolve(it, env)) }

    /**
     * Bridges the Tool Bar's vocabulary into the registry's. Kept explicit (no `when` on `name`)
     * so adding a [ToolAvailability] case is a compile error rather than a silent fallthrough.
     */
    fun fromToolBar(availability: ToolAvailability): CapabilityState = when (availability) {
        ToolAvailability.READY -> CapabilityState.READY
        ToolAvailability.READ_ONLY -> CapabilityState.READ_ONLY
        ToolAvailability.NEEDS_TERMUX -> CapabilityState.NEEDS_TERMUX
        ToolAvailability.NEEDS_SETUP -> CapabilityState.NEEDS_SETUP
    }

    /** A short, honest label. Mirrors the Tool Bar wording so both surfaces read identically. */
    fun label(state: CapabilityState): String = when (state) {
        CapabilityState.READY -> "Ready"
        CapabilityState.READ_ONLY -> "Read-only"
        CapabilityState.NEEDS_TERMUX -> "Needs Termux"
        CapabilityState.NEEDS_SETUP -> "Needs setup"
        CapabilityState.DEPENDENCY_MISSING -> "Dependency missing"
        CapabilityState.UNAVAILABLE -> "Unavailable"
    }

    /** Ids that more than one module claims. Empty in a healthy build; asserted in tests. */
    fun duplicateIds(): List<String> =
        catalog.groupBy { it.id }.filterValues { it.size > 1 }.keys.toList()
}
