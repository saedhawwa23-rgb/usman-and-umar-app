package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.plugin.AgentKind
import com.opendroid.ai.core.plugin.AgentPreflight
import com.opendroid.ai.core.plugin.AgentProvider
import com.opendroid.ai.core.plugin.AgentProviders
import com.opendroid.ai.core.plugin.PluginCapability
import com.opendroid.ai.core.plugin.PluginPlatformService
import com.opendroid.ai.core.plugin.PluginRecord
import com.opendroid.ai.core.plugin.PreflightResult
import com.opendroid.ai.core.plugin.SecretMask
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Plugin platform screen. It holds presentation state and forwards every action to
 * [PluginPlatformService]; no approval, grant or capability decision is made here, so the security
 * core stays the only place that can widen what a plugin may do.
 *
 * The screen state carries the selected agent and provider so the header, the composer and the
 * Agent screen can all be driven from one source of truth, and the preflight is run against exactly
 * what is displayed.
 */
@HiltViewModel
class PluginViewModel @Inject constructor(
    private val platform: PluginPlatformService,
) : ViewModel() {

    data class UiState(
        val plugins: List<PluginRecord> = emptyList(),
        val selectedAgent: AgentKind = AgentKind.DEEPSEEK_HARNESS,
        val selectedProviderId: String = AgentProviders.all.first().id,
        val apiKeyMasked: String = "(not set)",
        val agentInstalled: Boolean = false,
        val agentVerified: Boolean = false,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val busy: Boolean = false,
    ) {
        val selectedProvider: AgentProvider get() = AgentProviders.byId(selectedProviderId) ?: AgentProviders.all.first()
        val connectedCount: Int get() = plugins.count { it.isCallable }
        val catalogCount: Int get() = plugins.size
        val compatibleProviders: List<AgentProvider> get() = AgentProviders.compatibleWith(selectedAgent)
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    /** Seed the catalog (idempotent) and reload the registry snapshot. */
    fun refresh() {
        mutableState.value = mutableState.value.copy(busy = true)
        runCatching { platform.seedCatalog() }
        mutableState.value = mutableState.value.copy(plugins = platform.records(), busy = false)
    }

    fun selectAgent(agent: AgentKind) {
        val current = mutableState.value
        // Keep the provider valid: if the current one cannot speak this agent's protocol, switch to
        // the first compatible provider rather than leaving an impossible pairing on screen.
        val providerStillValid = current.selectedProvider.protocol == agent.requiredProtocol
        val nextProviderId = if (providerStillValid) current.selectedProviderId
        else AgentProviders.compatibleWith(agent).firstOrNull()?.id ?: current.selectedProviderId
        mutableState.value = current.copy(selectedAgent = agent, selectedProviderId = nextProviderId, message = null)
    }

    fun selectProvider(providerId: String) {
        val provider = AgentProviders.byId(providerId) ?: return
        val agent = mutableState.value.selectedAgent
        if (provider.protocol != agent.requiredProtocol) {
            setMessage(
                "${agent.displayName} requires an ${agent.requiredProtocol.displayName} endpoint. " +
                    "'${provider.displayName}' uses ${provider.protocol.wireFormat}.",
                isError = true,
            )
            return
        }
        mutableState.value = mutableState.value.copy(selectedProviderId = providerId, message = null)
    }

    /**
     * Record that a key exists for the selected provider, storing only a mask. The raw value never
     * enters this state object, so it cannot reach the screen or a log through it.
     */
    fun noteApiKeyPresent(rawValue: String?) {
        mutableState.value = mutableState.value.copy(apiKeyMasked = SecretMask.mask(rawValue))
    }

    fun setAgentInstalled(installed: Boolean, verified: Boolean) {
        mutableState.value = mutableState.value.copy(agentInstalled = installed, agentVerified = verified)
    }

    /** Run the pairing preflight and show the specific blocker, if any. */
    fun preflight(): PreflightResult {
        val current = mutableState.value
        val result = AgentPreflight.check(
            agent = current.selectedAgent,
            provider = current.selectedProvider,
            apiKeyPresent = current.apiKeyMasked != "(not set)",
            agentInstalled = current.agentInstalled,
            agentVerified = current.agentVerified,
        )
        when (result) {
            is PreflightResult.Ready ->
                setMessage("Ready: ${result.agent.displayName} on ${result.provider.displayName}.", isError = false)
            is PreflightResult.Blocked ->
                setMessage("${result.blocker.code}: ${result.message}", isError = true)
        }
        return result
    }

    fun connect(pluginId: String, capabilities: Set<PluginCapability>) {
        val outcome = platform.connectCatalogEntry(pluginId, capabilities)
        applyOutcome(outcome)
    }

    /** Import an OpenAPI document and install it as a plugin. */
    fun importOpenApi(
        documentJson: String,
        pluginId: String,
        displayName: String?,
        capabilities: Set<PluginCapability>,
    ) {
        val outcome = platform.installFromOpenApi(
            documentJson = documentJson,
            pluginId = pluginId,
            displayName = displayName,
            authMode = com.opendroid.ai.core.plugin.PluginAuthMode.API_KEY,
            credentialId = pluginId,
            requestedCapabilities = capabilities,
        )
        applyOutcome(outcome)
    }

    /** Discover and connect an MCP server over JSON-RPC `tools/list`. */
    fun connectMcpServer(pluginId: String, displayName: String, endpoint: String) {
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            val outcome = platform.installMcpServer(
                pluginId = pluginId,
                displayName = displayName,
                endpoint = endpoint,
                requestedCapabilities = setOf(PluginCapability.NETWORK_FETCH),
            )
            applyOutcome(outcome)
        }
    }

    fun revoke(pluginId: String) = applyOutcome(platform.revoke(pluginId))

    fun setEnabled(pluginId: String, enabled: Boolean) = applyOutcome(platform.setEnabled(pluginId, enabled))

    fun approve(pluginId: String, capabilities: Set<PluginCapability>) =
        applyOutcome(platform.approveAndGrant(pluginId, capabilities))

    private fun applyOutcome(outcome: PluginPlatformService.Outcome) {
        mutableState.value = mutableState.value.copy(
            plugins = platform.records(),
            message = outcome.message,
            messageIsError = !outcome.ok,
            busy = false,
        )
    }

    private fun setMessage(text: String, isError: Boolean) {
        mutableState.value = mutableState.value.copy(message = text, messageIsError = isError)
    }
}
