package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.models.DynamicAIProvider
import com.opendroid.ai.data.models.GitHubProject
import com.opendroid.ai.core.llm.AIModel
import com.opendroid.ai.core.llm.ModelFetcher
import com.opendroid.ai.core.llm.ModelFetchOutcome
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.termux.TermuxIntegration
import com.opendroid.ai.data.repository.DiscoveryRepository
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val modelFetcher: ModelFetcher,
    private val termuxIntegration: TermuxIntegration
) : ViewModel() {
    private val _githubResults = MutableStateFlow<List<GitHubProject>>(emptyList())
    val githubResults: StateFlow<List<GitHubProject>> = _githubResults.asStateFlow()
    data class ProviderModels(val provider: String, val outcome: ModelFetchOutcome)
    private val _aiResults = MutableStateFlow<List<ProviderModels>>(emptyList())
    val aiResults: StateFlow<List<ProviderModels>> = _aiResults.asStateFlow()
    private val _savedProjects = MutableStateFlow<List<GitHubProject>>(emptyList())
    val savedProjects: StateFlow<List<GitHubProject>> = _savedProjects.asStateFlow()
    private val _openedRepository = MutableStateFlow<GitHubProject?>(null)
    val openedRepository: StateFlow<GitHubProject?> = _openedRepository.asStateFlow()
    private val _savedProviders = MutableStateFlow<List<DynamicAIProvider>>(emptyList())
    val savedProviders: StateFlow<List<DynamicAIProvider>> = _savedProviders.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refreshSaved() }

    fun refreshSaved() = viewModelScope.launch {
        val config = settingsRepository.llmConfig.first()
        _savedProviders.value = config.dynamicAIProviders
        _savedProjects.value = config.supportedGitHubProjects
    }

    fun searchGitHub(query: String) = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        runCatching { discoveryRepository.searchGitHub(query) }
            .onSuccess { _githubResults.value = it }
            .onFailure { _message.value = it.message ?: "GitHub search failed" }
        _busy.value = false
    }

    /**
     * Searches real provider model endpoints. Provider-only queries discover the
     * named provider; model-name queries search providers the user has configured
     * plus OpenRouter's public catalog. No GitHub repository is treated as an AI API.
     */
    fun searchAI(query: String) = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        try {
            val config = settingsRepository.llmConfig.first()
            val cloudProviders = ProviderCatalog.providers
                .map { it.canonicalName }
                .distinct()
                .plus(config.dynamicAIProviders.map { it.id })
                .distinct()
            val term = query.trim()
            val byProviderName = term.isNotBlank() && cloudProviders.any { it.contains(term, ignoreCase = true) }
            val candidates = if (byProviderName) {
                cloudProviders.filter { it.contains(term, ignoreCase = true) }
            } else {
                cloudProviders.filter { name ->
                    name == "OpenRouter" || config.apiKeys[name].orEmpty().isNotBlank() ||
                        (name == "Ollama" && config.ollamaUrl.isNotBlank()) ||
                        (name == "Copilot API" && config.copilotUrl.isNotBlank()) ||
                        (ProviderCatalog.isDynamic(name) && config.apiKeys[name].orEmpty().isNotBlank())
                }
            }
            val results = candidates.map { provider ->
                ProviderModels(provider, modelFetcher.fetchModels(provider))
            }
            _aiResults.value = if (!byProviderName && term.isNotBlank()) {
                results.mapNotNull { result ->
                    when (val outcome = result.outcome) {
                        is ModelFetchOutcome.Success -> {
                            val matching = outcome.models.filter {
                                it.id.contains(term, true) || it.displayName.contains(term, true)
                            }
                            matching.takeIf { it.isNotEmpty() }?.let {
                                result.copy(outcome = ModelFetchOutcome.Success(it))
                            }
                        }
                        else -> null
                    }
                }
            } else results
            if (_aiResults.value.isEmpty()) {
                _message.value = if (candidates.isEmpty()) {
                    "No matching provider is configured. Add its endpoint and credentials in Settings, then retry."
                } else {
                    "No matching model was returned by the configured providers. Check credentials, endpoint availability, or search by provider name."
                }
            }
        } catch (failure: Exception) {
            _message.value = failure.message ?: "Provider model discovery failed."
        } finally {
            _busy.value = false
        }
    }

    fun selectModel(model: AIModel) = viewModelScope.launch {
        settingsRepository.updateConfig { it.withSelectedModel(model.provider, model.id).withActiveProvider(model.provider) }
        _message.value = "Selected ${model.id} from ${model.provider}. A successful catalog lookup does not itself verify chat completion or tool calling."
    }

    fun cloneRepository(url: String) = viewModelScope.launch {
        _message.value = termuxIntegration.cloneGitHubRepository(url).fold(
            onSuccess = { "Git clone started in Termux (job $it). Open the Termux screen to view its exit code and output." },
            onFailure = { it.message ?: "Could not start the GitHub clone in Termux." }
        )
    }

    fun openRepository(url: String) = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        runCatching { discoveryRepository.loadGitHubRepository(url) }
            .onSuccess {
                _openedRepository.value = it
                _message.value = "Loaded live public GitHub metadata for ${it.fullName}. Choose ADD or USE to save it; lookup alone does not change your project library."
            }
            .onFailure { _message.value = it.message ?: "Repository lookup failed" }
        _busy.value = false
    }

    fun addProject(project: GitHubProject) = viewModelScope.launch {
        settingsRepository.updateConfig { config ->
            config.copy(supportedGitHubProjects = (config.supportedGitHubProjects + project).distinctBy { it.id })
        }
        refreshSaved()
    }

    fun useProject(project: GitHubProject) = viewModelScope.launch {
        settingsRepository.updateConfig { config ->
            config.copy(
                supportedGitHubProjects = (config.supportedGitHubProjects + project).distinctBy { it.id },
                activeGitHubProjectId = project.id
            )
        }
        refreshSaved()
        _message.value = "Using ${project.fullName} as the active project context."
    }

    fun addProvider(provider: DynamicAIProvider, apiKey: String) = viewModelScope.launch {
        val storedProvider = provider.copy(
            id = if (provider.id.startsWith("discovered:")) provider.id else "discovered:${provider.id}"
        )
        settingsRepository.updateConfig { config ->
            val providers = (config.dynamicAIProviders.filterNot { it.id == storedProvider.id } + storedProvider)
            val keys = config.apiKeys + (storedProvider.id to apiKey)
            val endpoints = config.customEndpoints + (storedProvider.id to storedProvider.endpoint)
            val models = (config.selectedModels.orEmpty() + (storedProvider.id to storedProvider.model))
            config.copy(dynamicAIProviders = providers, apiKeys = keys, customEndpoints = endpoints,
                selectedModels = models, activeProvider = storedProvider.id, activeModel = storedProvider.model)
        }
        refreshSaved()
        _message.value = "Added ${storedProvider.displayName}; it is now the active provider."
    }

    fun useProvider(provider: DynamicAIProvider) = viewModelScope.launch {
        settingsRepository.updateConfig { it.copy(activeProvider = provider.id, activeModel = provider.model) }
        _message.value = "Using ${provider.displayName}."
    }
}
