package com.opendroid.ai.ui.screens.toolbar

/**
 * v3.10.5: the Tool Bar's information architecture as data.
 *
 * The three sections and their entries live here, each with the real destination it opens and an
 * honest availability that is computed from the runtime context. The screen renders whatever this
 * reports, so a section cannot claim to be "connected" when the underlying integration is not.
 * Free of Compose and Android so the mapping is asserted directly in JVM tests.
 */

/** The three Tool Bar sections, in display order. */
enum class ToolSectionId { AI_AGENTS, GITHUB, LINUX }

/** Where an entry navigates. The screen maps each to its existing, fully scoped feature screen. */
enum class ToolDestination {
    /** Live AI-provider model-catalog discovery. */
    PROVIDER_DISCOVERY,

    /** Public GitHub repository search, lookup and clone. */
    GITHUB_DISCOVERY,

    /** The Termux RUN_COMMAND integration. */
    TERMUX,

    /** The cloud provider tool-call agent. */
    CLOUD_AGENT,

    /** The integration platform (MCP servers, OpenAPI services, grants). */
    INTEGRATION_PLATFORM,
}

/**
 * How available an entry really is right now. The wording is chosen so the screen can be honest:
 * [READ_ONLY] means the action performs a real but non-mutating request; [NEEDS_TERMUX] means it
 * requires the Termux app; [NEEDS_SETUP] means it needs a credential or endpoint the user has not
 * configured yet; [READY] means the action works with what is already configured.
 */
enum class ToolAvailability { READY, READ_ONLY, NEEDS_TERMUX, NEEDS_SETUP }

/** One tool entry. */
data class ToolEntry(
    val id: String,
    val title: String,
    val description: String,
    val availability: ToolAvailability,
    val destination: ToolDestination,
)

/** One section: a title, a subtitle and its entries. */
data class ToolSection(
    val id: ToolSectionId,
    val title: String,
    val subtitle: String,
    val entries: List<ToolEntry>,
)

/** The runtime facts that decide availability. Passed in so the model stays pure and testable. */
data class ToolBarContext(
    val termuxInstalled: Boolean = false,
    val aiProviderConfigured: Boolean = false,
    val cloudServerConfigured: Boolean = false,
    val githubTokenConnected: Boolean = false,
)

object ToolBarModel {

    /** The catalog of entries, before runtime availability is applied. */
    private val baseSections: List<ToolSection> = listOf(
        ToolSection(
            id = ToolSectionId.AI_AGENTS,
            title = "AI providers & web agents",
            subtitle = "Discover live model catalogs and run real agent tasks.",
            entries = listOf(
                ToolEntry(
                    id = "ai.providers",
                    title = "AI providers & models",
                    description = "Query the configured providers' model-list endpoints and pick a model. " +
                        "Results are catalog metadata, not a verified chat capability.",
                    availability = ToolAvailability.READ_ONLY,
                    destination = ToolDestination.PROVIDER_DISCOVERY,
                ),
                ToolEntry(
                    id = "ai.cloud-agent",
                    title = "Cloud AI tool agent",
                    description = "Run a task through the configured cloud provider's native tool-call " +
                        "protocol with allowlisted local connectors. Sensitive actions stay behind approval.",
                    availability = ToolAvailability.NEEDS_SETUP,
                    destination = ToolDestination.CLOUD_AGENT,
                ),
                ToolEntry(
                    id = "ai.integrations",
                    title = "Integrations, MCP & OpenAPI",
                    description = "Discover and connect AI platforms, MCP servers and OpenAPI services; " +
                        "manage grants and chain them into automations.",
                    availability = ToolAvailability.READY,
                    destination = ToolDestination.INTEGRATION_PLATFORM,
                ),
            ),
        ),
        ToolSection(
            id = ToolSectionId.GITHUB,
            title = "GitHub project management",
            subtitle = "Search, inspect, clone and manage repositories.",
            entries = listOf(
                ToolEntry(
                    id = "github.search",
                    title = "Search public repositories",
                    description = "Search the public GitHub REST API and see description, language, " +
                        "stars and topics for each result.",
                    availability = ToolAvailability.READ_ONLY,
                    destination = ToolDestination.GITHUB_DISCOVERY,
                ),
                ToolEntry(
                    id = "github.inspect",
                    title = "Inspect a repository",
                    description = "Look up a repository's live metadata, README and root file names " +
                        "before deciding to add, use or clone it.",
                    availability = ToolAvailability.READ_ONLY,
                    destination = ToolDestination.GITHUB_DISCOVERY,
                ),
                ToolEntry(
                    id = "github.clone",
                    title = "Clone a repository",
                    description = "Clone a public repository into the Termux home directory and see " +
                        "the clone progress and errors.",
                    availability = ToolAvailability.NEEDS_TERMUX,
                    destination = ToolDestination.TERMUX,
                ),
                ToolEntry(
                    id = "github.projects",
                    title = "Manage projects",
                    description = "Add an inspected repository to your project library, mark one active, " +
                        "and open it from the Task Bar.",
                    availability = ToolAvailability.READY,
                    destination = ToolDestination.GITHUB_DISCOVERY,
                ),
            ),
        ),
        ToolSection(
            id = ToolSectionId.LINUX,
            title = "Termux & Linux environment",
            subtitle = "A real shell on the device, plus AI-assisted commands.",
            entries = listOf(
                ToolEntry(
                    id = "linux.termux",
                    title = "Termux terminal",
                    description = "Run allowlisted Linux-compatible commands through Termux's real " +
                        "RUN_COMMAND service and read stdout, stderr and the exit code.",
                    availability = ToolAvailability.NEEDS_TERMUX,
                    destination = ToolDestination.TERMUX,
                ),
                ToolEntry(
                    id = "linux.tools",
                    title = "Development tools",
                    description = "Install Git, Python, Node.js and build tools into the Termux " +
                        "userspace from the Termux screen.",
                    availability = ToolAvailability.NEEDS_TERMUX,
                    destination = ToolDestination.TERMUX,
                ),
                ToolEntry(
                    id = "linux.ai-assisted",
                    title = "AI-assisted commands",
                    description = "Run an explicitly entered bash or Python script through Termux, and " +
                        "route task prose to an AI agent instead of the shell.",
                    availability = ToolAvailability.NEEDS_TERMUX,
                    destination = ToolDestination.TERMUX,
                ),
            ),
        ),
    )

    fun sections(): List<ToolSection> = baseSections

    fun section(id: ToolSectionId): ToolSection = baseSections.first { it.id == id }

    /**
     * Applies the runtime context to an entry's availability. An entry that needs Termux reports
     * [ToolAvailability.NEEDS_SETUP] until Termux is present, and a cloud entry reports
     * [ToolAvailability.NEEDS_SETUP] until a server is configured — so the pill never reads "ready"
     * for something the user cannot yet run.
     */
    fun availabilityOf(entry: ToolEntry, context: ToolBarContext): ToolAvailability = when (entry.availability) {
        ToolAvailability.NEEDS_TERMUX ->
            if (context.termuxInstalled) ToolAvailability.READY else ToolAvailability.NEEDS_TERMUX
        ToolAvailability.NEEDS_SETUP -> when (entry.id) {
            "ai.cloud-agent" ->
                if (context.cloudServerConfigured) ToolAvailability.READY else ToolAvailability.NEEDS_SETUP
            else -> ToolAvailability.NEEDS_SETUP
        }
        else -> entry.availability
    }

    /** The sections with availability resolved against the context. */
    fun resolvedSections(context: ToolBarContext): List<ToolSection> = baseSections.map { section ->
        section.copy(entries = section.entries.map { it.copy(availability = availabilityOf(it, context)) })
    }

    /** A short, honest label for an availability value. */
    fun availabilityLabel(availability: ToolAvailability): String = when (availability) {
        ToolAvailability.READY -> "Ready"
        ToolAvailability.READ_ONLY -> "Read-only"
        ToolAvailability.NEEDS_TERMUX -> "Needs Termux"
        ToolAvailability.NEEDS_SETUP -> "Needs setup"
    }
}
