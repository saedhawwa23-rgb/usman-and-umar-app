package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.connector.ApprovalDecision
import com.opendroid.ai.core.connector.ConnectorApprovalGate
import com.opendroid.ai.core.connector.ConnectorCredentialRef
import com.opendroid.ai.core.connector.ConnectorCredentialResolver
import com.opendroid.ai.core.connector.ConnectorInvocation
import com.opendroid.ai.core.connector.ConnectorTool
import com.opendroid.ai.core.connector.UniversalToolRegistry
import com.opendroid.ai.core.oauth.OAuthAuthorizationClient
import com.opendroid.ai.core.oauth.OAuthProvider
import com.opendroid.ai.core.oauth.OAuthResult
import com.opendroid.ai.core.oauth.OAuthTokenParser
import com.opendroid.ai.core.oauth.OAuthTokenVault
import com.opendroid.ai.core.oauth.OAuthTokenSet

/** How a plugin authenticates. [NONE] is refused for any plugin that declares a network capability. */
enum class PluginAuthMode { NONE, API_KEY, OAUTH2_PKCE }

/**
 * A live binding between a registered plugin and the connector that actually performs its calls.
 * It holds no secret: a credential is referenced by id and resolved at call time.
 */
data class PluginInstallation(
    val pluginId: String,
    val baseUrl: String,
    val authMode: PluginAuthMode,
    val credentialId: String? = null,
    val toolNames: Set<String> = emptySet(),
    val oauthProviderId: String? = null,
)

sealed interface InstallOutcome {
    data class Installed(val pluginId: String, val toolCount: Int, val warnings: List<String>) : InstallOutcome
    data class Refused(val reason: String) : InstallOutcome
}

/**
 * Wires a plugin into the running app.
 *
 * The order is deliberate and fails closed at every step: the manifest is checked against the
 * running app, registered (never auto-approved), then the plugin's connector is registered so its
 * tools exist, and finally the plugin is installed. The plugin is *not* callable until the owner
 * approves it, and a network plugin without an authentication mode is refused rather than being
 * silently connected anonymously.
 */
class PluginInstaller(
    private val pluginRegistry: PluginRegistry,
    private val toolRegistry: UniversalToolRegistry,
    private val appVersion: String,
) {

    private val installations = LinkedHashMap<String, PluginInstallation>()

    @Synchronized
    fun install(
        manifest: PluginManifest,
        baseUrl: String,
        tools: List<ConnectorTool>,
        authMode: PluginAuthMode,
        credentialId: String? = null,
        oauthProviderId: String? = null,
    ): InstallOutcome {
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            return InstallOutcome.Refused("Plugin base URL must be http(s); found $baseUrl")
        }
        val needsNetwork = manifest.declaredCapabilities().contains(PluginCapability.NETWORK_FETCH)
        if (needsNetwork && authMode == PluginAuthMode.NONE) {
            return InstallOutcome.Refused(
                "Plugin '${manifest.id}' fetches over the network but declares no authentication. " +
                    "Choose an API key or OAuth 2.0 with PKCE."
            )
        }
        if (authMode == PluginAuthMode.OAUTH2_PKCE && oauthProviderId.isNullOrBlank()) {
            return InstallOutcome.Refused("OAuth 2.0 requires a provider registration")
        }
        if (tools.any { it.connectorId != manifest.id }) {
            return InstallOutcome.Refused("Every tool must belong to the plugin being installed")
        }

        when (val registration = pluginRegistry.registerManifest(manifest, appVersion)) {
            is PluginAuthorization.Denied -> return InstallOutcome.Refused(registration.reason)
            is PluginAuthorization.Allowed -> Unit
        }

        val credential = credentialId?.let { ConnectorCredentialRef(id = it, kind = authMode.name.lowercase()) }
        val boundTools = tools.map { it.copy(credential = credential) }
        val warnings = mutableListOf<String>()
        runCatching {
            toolRegistry.registerHttpManifest(
                id = manifest.id,
                displayName = manifest.displayName,
                kind = com.opendroid.ai.core.connector.ConnectorKind.REST,
                baseUrl = baseUrl,
                tools = boundTools,
            )
        }.onFailure { return InstallOutcome.Refused("Could not register plugin tools: ${it.message}") }

        runCatching { pluginRegistry.install(manifest.id) }
            .onFailure { return InstallOutcome.Refused(it.message ?: "Install failed") }

        installations[manifest.id] = PluginInstallation(
            pluginId = manifest.id,
            baseUrl = baseUrl,
            authMode = authMode,
            credentialId = credentialId,
            toolNames = boundTools.map { it.name }.toSet(),
            oauthProviderId = oauthProviderId,
        )
        if (baseUrl.startsWith("http://")) warnings += "Traffic to this plugin is not encrypted (plain HTTP)"
        return InstallOutcome.Installed(manifest.id, boundTools.size, warnings)
    }

    /** Mark a plugin connected once its authentication has actually succeeded. */
    @Synchronized
    fun markConnected(pluginId: String): Boolean =
        runCatching { pluginRegistry.markConnected(pluginId) }.isSuccess

    /** Approve the exact capabilities the owner chose. Nothing wider is granted. */
    @Synchronized
    fun approve(pluginId: String, capabilities: Set<PluginCapability>) {
        val declared = pluginRegistry.get(pluginId)?.declaredCapabilities ?: emptySet()
        require(capabilities.all { it in declared }) {
            "The owner cannot grant a capability the plugin did not declare"
        }
        pluginRegistry.approve(pluginId, capabilities)
    }

    /**
     * Revoke a plugin completely: its approval, its tools, and its installation record. A revoked
     * plugin's tools stop resolving, so a stale tool name cannot be called afterwards.
     */
    @Synchronized
    fun revoke(pluginId: String) {
        installations.remove(pluginId)
        toolRegistry.unregister(pluginId)
        runCatching { pluginRegistry.revoke(pluginId) }
    }

    @Synchronized
    fun installation(pluginId: String): PluginInstallation? = installations[pluginId]

    @Synchronized
    fun installedPluginIds(): Set<String> = installations.keys.toSet()
}

/**
 * Approval gate that gives plugin lifecycle real authority inside the existing execution path.
 *
 * A tool that belongs to a registered plugin is refused unless that plugin is approved and enabled;
 * every other connector keeps the default read-only/reversible rule, so direct tool execution that
 * does not go through a plugin is unaffected.
 *
 * When an [authorizationStore] is supplied, a plugin tool also has to hold a live unattended grant.
 * That is what makes revocation immediate: [PersistentAuthorizationStore.revoke] persists the
 * revoked record, and the very next call is refused here without any restart.
 */
class PluginExecutionGate(
    private val pluginRegistry: PluginRegistry,
    private val authorizationStore: PersistentAuthorizationStore? = null,
    private val delegate: ConnectorApprovalGate = com.opendroid.ai.core.connector.DefaultConnectorApprovalGate,
) : ConnectorApprovalGate {

    override suspend fun decide(invocation: ConnectorInvocation): ApprovalDecision {
        val plugin = pluginRegistry.get(invocation.tool.connectorId)
        if (plugin != null) {
            return when (val authorization = pluginRegistry.authorize(plugin.id)) {
                is PluginAuthorization.Denied -> ApprovalDecision.DENY
                is PluginAuthorization.Allowed -> {
                    val store = authorizationStore
                    if (store != null && !store.isAuthorized(plugin.id).authorized) ApprovalDecision.DENY
                    else delegate.decide(invocation)
                }
            }
        }
        return delegate.decide(invocation)
    }

    /** A copy of this gate that forwards non-plugin tools to [delegate]. */
    fun withDelegate(delegate: ConnectorApprovalGate): PluginExecutionGate =
        PluginExecutionGate(pluginRegistry, authorizationStore, delegate)

    /** The specific refusal reason, for a UI or a log line. Null when the call may proceed. */
    fun refusalReason(invocation: ConnectorInvocation): String? {
        val plugin = pluginRegistry.get(invocation.tool.connectorId) ?: return null
        (pluginRegistry.authorize(plugin.id) as? PluginAuthorization.Denied)?.let { return it.reason }
        val decision = authorizationStore?.check(plugin.id)
        if (decision != null && !decision.authorized) return decision.reason.message
        return null
    }
}

/**
 * Resolves a plugin credential at call time. For OAuth it returns a live access token, refreshing
 * when the stored one is expired. The token is handed only to the transport callback and is never
 * returned to a caller or written to a log.
 */
class OAuthConnectorCredentialResolver(
    private val vault: OAuthTokenVault,
    private val client: OAuthAuthorizationClient = OAuthAuthorizationClient(),
    private val refreshTransport: suspend (url: String, form: String) -> Pair<Int, String>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectorCredentialResolver {

    /** Provider registrations by plugin id, so a refresh knows where to post. */
    private val providers = LinkedHashMap<String, OAuthProvider>()

    fun registerProvider(pluginId: String, provider: OAuthProvider) {
        providers[pluginId] = provider
    }

    override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T {
        if (ref == null) return block(null)
        val pluginId = ref.id
        val provider = providers[pluginId] ?: return block(vault.load(pluginId)?.accessToken)
        var tokens = vault.load(pluginId) ?: return block(null)
        if (tokens.isExpired(clock())) {
            val refresh = tokens.refreshToken ?: return block(null)
            val form = client.refreshForm(provider, refresh)
            val (status, body) = refreshTransport(provider.tokenEndpoint, form)
            when (val parsed = OAuthTokenParser.parse(body, status, clock(), previousRefreshToken = refresh)) {
                is OAuthResult.Success -> {
                    vault.save(pluginId, parsed.tokens)
                    tokens = parsed.tokens
                }
                // A failed refresh must not send the stale token: the call fails closed instead.
                is OAuthResult.Failure -> return block(null)
            }
        }
        return block(tokens.accessToken)
    }
}
