package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.McpToolDiscovery
import com.opendroid.ai.core.connector.UniversalToolRegistry

/**
 * v3.10.4: the functional engine behind the Plugin platform.
 *
 * It is the one place that turns a user gesture in the Plugin screen into a real, enforced change:
 * seeding the catalog, importing an OpenAPI document, discovering an MCP server's tools, approving
 * a plugin's capabilities, and revoking it. It reuses the existing, already-audited
 * [PluginRegistry], [PluginInstaller], [McpToolDiscovery] and [PersistentAuthorizationStore] rather
 * than re-implementing any security rule, so the approval gate, the kill switch and the grant store
 * keep applying to every call.
 *
 * No method here logs a secret: an outcome carries a status and a message, never a token.
 */
class PluginPlatformService(
    private val registry: PluginRegistry,
    private val installer: PluginInstaller,
    private val toolRegistry: UniversalToolRegistry,
    private val authorizationStore: PersistentAuthorizationStore,
    private val mcpDiscovery: McpToolDiscovery,
    private val appVersion: String,
) {

    /** A user-facing outcome. [ok] is false for a refusal or a failure; [message] is always specific. */
    data class Outcome(val ok: Boolean, val message: String)

    /** Register the built-in catalog. Idempotent, so opening the screen twice changes nothing. */
    fun seedCatalog(): Int {
        val before = registry.all().size
        PluginCatalog.seed(registry)
        return registry.all().size - before
    }

    /**
     * Connect a catalog entry (an AI platform, MCP server or REST API described by
     * [PluginCatalog]). A catalog entry ships no connector tools of its own, so connecting it is
     * install → approve the declared capabilities → create a live grant. The registry still holds
     * the effective set at the intersection of declared and granted.
     */
    fun connectCatalogEntry(
        pluginId: String,
        requestedCapabilities: Set<PluginCapability>,
        expiry: GrantExpiry = GrantExpiry.DAYS_30,
    ): Outcome {
        val record = registry.get(pluginId) ?: return Outcome(false, "Unknown plugin '$pluginId'.")
        val capabilities = requestedCapabilities.ifEmpty { record.declaredCapabilities }
        return runCatching {
            if (record.installState == PluginInstallState.DISCOVERED) registry.install(pluginId)
        }.fold(
            onSuccess = { approveAndGrant(pluginId, capabilities, expiry) },
            onFailure = { Outcome(false, "Connect failed: ${it.message ?: "unknown error"}") },
        )
    }

    fun records(): List<PluginRecord> = registry.all().sortedWith(
        compareBy({ it.kind.ordinal }, { it.displayName.lowercase() })
    )

    fun record(pluginId: String): PluginRecord? = registry.get(pluginId)

    /** The capabilities that are granted and live right now for a plugin. */
    fun activeGrantCapabilities(pluginId: String): Set<PluginCapability> =
        authorizationStore.grantFor(pluginId)?.takeIf { it.isActive(System.currentTimeMillis()) }?.capabilities ?: emptySet()

    /**
     * Install a plugin from an OpenAPI 3.1 document: import, register, install, then approve exactly
     * the requested capabilities and create a live grant. The order fails closed at every step.
     */
    fun installFromOpenApi(
        documentJson: String,
        pluginId: String,
        displayName: String?,
        authMode: PluginAuthMode,
        credentialId: String?,
        requestedCapabilities: Set<PluginCapability>,
        expiry: GrantExpiry = GrantExpiry.DAYS_30,
    ): Outcome {
        val imported = when (val result = OpenApiImporter.import(documentJson, pluginId, displayName, "1.0.0", appVersion)) {
            is OpenApiImporter.Result.Success -> result.api
            is OpenApiImporter.Result.Failure -> return Outcome(false, "Import failed: ${result.reason}")
        }
        return when (val outcome = installer.install(
            manifest = imported.manifest,
            baseUrl = imported.baseUrl,
            tools = imported.tools,
            authMode = authMode,
            credentialId = credentialId,
        )) {
            is InstallOutcome.Refused -> Outcome(false, outcome.reason)
            is InstallOutcome.Installed -> {
                approveAndGrant(pluginId, requestedCapabilities, expiry)
                val warning = outcome.warnings.firstOrNull()?.let { " Warning: $it" } ?: ""
                Outcome(true, "Installed '${imported.manifest.displayName}' with ${outcome.toolCount} tool(s).$warning")
            }
        }
    }

    /**
     * Discover and install an MCP server. The server's tools are discovered over JSON-RPC
     * `tools/list`; nothing is registered until discovery succeeds.
     */
    suspend fun installMcpServer(
        pluginId: String,
        displayName: String,
        endpoint: String,
        requestedCapabilities: Set<PluginCapability>,
        risk: PluginRisk = PluginRisk.HIGH,
        expiry: GrantExpiry = GrantExpiry.DAYS_30,
    ): Outcome {
        val manifest = PluginManifest(
            id = pluginId,
            displayName = displayName,
            version = "1.0.0",
            publisher = "Model Context Protocol",
            homepageUrl = endpoint,
            kind = PluginKind.SERVICE.name,
            interop = listOf(PluginInterop.MCP.name),
            capabilities = requestedCapabilities.map { it.name },
            risk = risk.name,
            compatibility = PluginCompatibility(minAppVersion = appVersion),
        )
        when (val registration = registry.registerManifest(manifest, appVersion)) {
            is PluginAuthorization.Denied -> return Outcome(false, registration.reason)
            is PluginAuthorization.Allowed -> Unit
        }
        val discovered = mcpDiscovery.discoverAndRegister(toolRegistry, pluginId, displayName, endpoint)
        if (discovered.isSuccess) {
            runCatching { registry.install(pluginId) }
                .onFailure { return Outcome(false, it.message ?: "Install failed") }
            approveAndGrant(pluginId, requestedCapabilities, expiry)
            return Outcome(true, "Connected '$displayName' with ${discovered.getOrNull()?.size ?: 0} MCP tool(s).")
        }
        // A server that does not answer must not stay registered as if it worked.
        runCatching { registry.remove(pluginId) }
        return Outcome(false, "MCP discovery failed: ${discovered.exceptionOrNull()?.message ?: "unknown error"}")
    }

    /** Approve the given capabilities and create a live unattended grant with the chosen expiry. */
    fun approveAndGrant(
        pluginId: String,
        capabilities: Set<PluginCapability>,
        expiry: GrantExpiry = GrantExpiry.DAYS_30,
    ): Outcome {
        val declared = registry.get(pluginId)?.declaredCapabilities
            ?: return Outcome(false, "Unknown plugin '$pluginId'.")
        val grantable = capabilities intersect declared
        if (grantable.isEmpty()) {
            return Outcome(false, "Select at least one capability this plugin declares.")
        }
        return runCatching {
            installer.approve(pluginId, grantable)
            registry.markConnected(pluginId)
            authorizationStore.grantWithExpiry(pluginId, grantable, expiry)
        }.fold(
            onSuccess = { Outcome(true, "Approved ${grantable.size} capability(ies) for '$pluginId'.") },
            onFailure = { Outcome(false, "Approval failed: ${it.message ?: "unknown error"}") },
        )
    }

    /** Revoke a plugin completely: approval, tools, installation record and any live grant. */
    fun revoke(pluginId: String): Outcome = runCatching {
        installer.revoke(pluginId)
        authorizationStore.revoke(pluginId)
        Outcome(true, "Revoked '$pluginId'. Its tools no longer resolve.")
    }.getOrElse { Outcome(false, "Revoke failed: ${it.message ?: "unknown error"}") }

    /** Disable a plugin without revoking it, so it can be re-enabled later. */
    fun setEnabled(pluginId: String, enabled: Boolean): Outcome = runCatching {
        if (enabled) registry.enable(pluginId) else registry.disable(pluginId)
        Outcome(true, if (enabled) "Enabled '$pluginId'." else "Disabled '$pluginId'.")
    }.getOrElse { Outcome(false, "Could not change state: ${it.message ?: "unknown error"}") }

    /**
     * A compatibility preflight for a plugin the owner is about to use, so an incompatible pairing is
     * refused with a specific message rather than failing mid-task.
     */
    fun compatibility(pluginId: String): PluginCompatibilityReport {
        val record = registry.get(pluginId)
            ?: return PluginCompatibilityReport(false, "Unknown plugin '$pluginId'.")
        val check = registry.checkManifest(
            PluginManifest(
                id = record.id,
                displayName = record.displayName,
                version = record.version,
                capabilities = record.declaredCapabilities.map { it.name },
                compatibility = PluginCompatibility(
                    minAppVersion = record.minAppVersion,
                    requiredSemverRange = record.requiredSemverRange,
                ),
            ),
            appVersion,
        )
        return PluginCompatibilityReport(check.compatible, check.problems.joinToString(" ").ifBlank { "Compatible." })
    }
}

data class PluginCompatibilityReport(val compatible: Boolean, val message: String)
