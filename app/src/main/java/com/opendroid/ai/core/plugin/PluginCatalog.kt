package com.opendroid.ai.core.plugin

/**
 * v3.10.4: the built-in universal-integration catalog.
 *
 * The catalog is a *seed*: it registers well-known AI platform endpoints, MCP servers, and
 * OpenAPI/REST/OIDC integrations into the [PluginRegistry] as DISCOVERED and UNAPPROVED records.
 * Nothing here is callable until the owner approves it, and no network call is made to build the
 * catalog — every entry is a static description of a real, documented endpoint.
 *
 * The catalog never invents a capability: an entry declares only the capabilities its protocol
 * actually needs, and the registry keeps the effective set at the intersection of declared and
 * granted, so a catalog entry cannot widen the owner's grant.
 */
object PluginCatalog {

    private const val MIN_APP_VERSION = "3.10.4"

    /** An AI platform reachable over an OpenAI-compatible or vendor REST API. */
    val aiPlatforms: List<PluginRecord> = listOf(
        aiPlatform("ai_openai", "OpenAI", "OpenAI", "https://platform.openai.com/docs/api-reference", "https://api.openai.com/v1"),
        aiPlatform("ai_anthropic", "Anthropic Claude", "Anthropic", "https://docs.anthropic.com/en/api", "https://api.anthropic.com/v1"),
        aiPlatform("ai_gemini", "Google Gemini", "Google", "https://ai.google.dev/api", "https://generativelanguage.googleapis.com/v1beta"),
        aiPlatform("ai_deepseek", "DeepSeek", "DeepSeek", "https://api-docs.deepseek.com", "https://api.deepseek.com/v1"),
        aiPlatform("ai_groq", "Groq", "Groq", "https://console.groq.com/docs", "https://api.groq.com/openai/v1"),
        aiPlatform("ai_mistral", "Mistral AI", "Mistral AI", "https://docs.mistral.ai", "https://api.mistral.ai/v1"),
        aiPlatform("ai_openrouter", "OpenRouter", "OpenRouter", "https://openrouter.ai/docs", "https://openrouter.ai/api/v1"),
        aiPlatform("ai_nvidia_nim", "NVIDIA NIM", "NVIDIA", "https://docs.nvidia.com/nim", "https://integrate.api.nvidia.com/v1"),
        aiPlatform("ai_ollama", "Ollama (local)", "Ollama", "https://github.com/ollama/ollama/blob/main/docs/api.md", "http://127.0.0.1:11434"),
    )

    /**
     * MCP servers. These follow the Model Context Protocol (JSON-RPC 2.0): tools are discovered
     * with `tools/list` and invoked with `tools/call` (see `core/connector/McpToolDiscovery.kt`).
     * A server is a *description*; discovery runs only after the owner approves it.
     */
    val mcpServers: List<PluginRecord> = listOf(
        mcp("mcp_filesystem", "Filesystem MCP", "Model Context Protocol", "https://github.com/modelcontextprotocol/servers",
            setOf(PluginCapability.FILESYSTEM_READ, PluginCapability.FILESYSTEM_WRITE), PluginRisk.HIGH, PluginKind.LOCAL_DEVICE),
        mcp("mcp_git", "Git MCP", "Model Context Protocol", "https://github.com/modelcontextprotocol/servers",
            setOf(PluginCapability.FILESYSTEM_READ, PluginCapability.CODE_EXECUTION), PluginRisk.HIGH, PluginKind.LOCAL_DEVICE),
        mcp("mcp_fetch", "Fetch MCP", "Model Context Protocol", "https://github.com/modelcontextprotocol/servers",
            setOf(PluginCapability.NETWORK_FETCH), PluginRisk.LOW, PluginKind.SERVICE),
        mcp("mcp_memory", "Memory MCP", "Model Context Protocol", "https://github.com/modelcontextprotocol/servers",
            setOf(PluginCapability.FILESYSTEM_READ, PluginCapability.FILESYSTEM_WRITE), PluginRisk.LOW, PluginKind.LOCAL_DEVICE),
        mcp("mcp_github", "GitHub MCP", "GitHub", "https://github.com/github/github-mcp-server",
            setOf(PluginCapability.NETWORK_FETCH), PluginRisk.HIGH, PluginKind.SERVICE),
    )

    /** OpenAPI 3.1 / REST / OIDC API integrations. */
    val apiIntegrations: List<PluginRecord> = listOf(
        api("api_github", "GitHub REST API", "GitHub", "https://docs.github.com/rest",
            setOf(PluginInterop.OPENAPI_31, PluginInterop.REST_JSON, PluginInterop.OAUTH2_OIDC),
            setOf(PluginCapability.NETWORK_FETCH), PluginRisk.HIGH),
        api("api_openapi_petstore", "OpenAPI Petstore (demo)", "OpenAPI Initiative", "https://github.com/OAI/OpenAPI-Specification",
            setOf(PluginInterop.OPENAPI_31, PluginInterop.REST_JSON),
            setOf(PluginCapability.NETWORK_FETCH), PluginRisk.LOW),
        api("api_google_oidc", "Google APIs (OIDC)", "Google", "https://developers.google.com/identity/protocols/oauth2",
            setOf(PluginInterop.OAUTH2_OIDC, PluginInterop.REST_JSON),
            setOf(PluginCapability.NETWORK_FETCH), PluginRisk.LOW),
    )

    /** Every catalog entry, in the order it should be shown. */
    fun all(): List<PluginRecord> = aiPlatforms + mcpServers + apiIntegrations

    /** Register every catalog entry. Idempotent: an existing id keeps its current state. */
    fun seed(registry: PluginRegistry) {
        all().forEach { record ->
            if (registry.get(record.id) == null) registry.register(record)
        }
    }

    private fun aiPlatform(id: String, name: String, publisher: String, homepage: String, @Suppress("UNUSED_PARAMETER") baseUrl: String) =
        PluginRecord(
            id = id,
            displayName = name,
            publisher = publisher,
            homepageUrl = homepage,
            version = "1.0.0",
            kind = PluginKind.AI_TOOL,
            interop = setOf(PluginInterop.REST_JSON, PluginInterop.OPENAPI_31),
            declaredCapabilities = setOf(PluginCapability.NETWORK_FETCH),
            risk = PluginRisk.LOW,
            minAppVersion = MIN_APP_VERSION,
        )

    private fun mcp(
        id: String,
        name: String,
        publisher: String,
        homepage: String,
        capabilities: Set<PluginCapability>,
        risk: PluginRisk,
        kind: PluginKind,
    ) = PluginRecord(
        id = id,
        displayName = name,
        publisher = publisher,
        homepageUrl = homepage,
        version = "1.0.0",
        kind = kind,
        interop = setOf(PluginInterop.MCP),
        declaredCapabilities = capabilities,
        risk = risk,
        minAppVersion = MIN_APP_VERSION,
    )

    private fun api(
        id: String,
        name: String,
        publisher: String,
        homepage: String,
        interop: Set<PluginInterop>,
        capabilities: Set<PluginCapability>,
        risk: PluginRisk,
    ) = PluginRecord(
        id = id,
        displayName = name,
        publisher = publisher,
        homepageUrl = homepage,
        version = "1.0.0",
        kind = PluginKind.SERVICE,
        interop = interop,
        declaredCapabilities = capabilities,
        risk = risk,
        minAppVersion = MIN_APP_VERSION,
    )
}
