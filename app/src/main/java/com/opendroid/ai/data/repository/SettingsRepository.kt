package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.core.llm.ProviderCatalog
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Outcome for a configuration save that needs direct credential-store persistence. */
sealed interface ProviderCredentialPersistenceState {
    data object Ready : ProviderCredentialPersistenceState
    data object StorageUnavailable : ProviderCredentialPersistenceState
    data object CredentialsMustBeReentered : ProviderCredentialPersistenceState
}

@kotlinx.serialization.Serializable
data class CloudServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    @kotlinx.serialization.Transient val token: String = "",
)

@Singleton
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val providerCredentialStore: ProviderCredentialStore,
    private val runStartupMigration: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        providerCredentialStore: ProviderCredentialStore
    ) : this(context.dataStore, providerCredentialStore, runStartupMigration = true)

    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")
    private val openSwarmServerUrlKey = stringPreferencesKey("openswarm_backend_url")
    private val legacyOpenSwarmServerUrlKey = stringPreferencesKey("openswarm_render_url")
    private val cloudServerUrlKeys = (1..3).map { stringPreferencesKey("openswarm_server_${it}_url") }
    private val cloudServerEnabledKeys = (1..3).map { booleanPreferencesKey("openswarm_server_${it}_enabled") }
    private val cloudServersKey = stringPreferencesKey("openswarm_cloud_servers_v2")
    private val autoRunGeneratedCodeKey = booleanPreferencesKey("auto_run_generated_code")
    private val mutableOpenSwarmClientToken = MutableStateFlow("")
    val openSwarmClientToken: StateFlow<String> = mutableOpenSwarmClientToken.asStateFlow()
    private val mutableCloudServerTokens = MutableStateFlow(listOf("", "", ""))
    val cloudServerTokens: StateFlow<List<String>> = mutableCloudServerTokens.asStateFlow()

    /** Unlimited named backends. Token values are read only from encrypted storage at runtime. */
    val cloudServers: Flow<List<CloudServerConfig>> = dataStore.data.map { preferences ->
        val stored = preferences[cloudServersKey].orEmpty().let { raw ->
            runCatching { json.decodeFromString<List<CloudServerConfig>>(raw) }.getOrNull()
        } ?: legacyCloudServers(preferences)
        stored.map { server -> server.copy(token = readDynamicCloudToken(server.id)) }
    }.flowOn(Dispatchers.IO)

    private fun legacyCloudServers(preferences: Preferences): List<CloudServerConfig> =
        (0..2).mapNotNull { index ->
            val url = preferences[cloudServerUrlKeys[index]].orEmpty().ifBlank {
                if (index == 0) preferences[openSwarmServerUrlKey] ?: preferences[legacyOpenSwarmServerUrlKey] ?: "" else ""
            }
            url.takeIf(String::isNotBlank)?.let {
                CloudServerConfig("legacy-${index + 1}", "Server ${index + 1}", it, preferences[cloudServerEnabledKeys[index]] ?: (index == 0))
            }
        }

    private fun dynamicCloudCredential(id: String): ProviderCredentialId.ApiKey =
        ProviderCredentialId.ApiKey("openswarm-cloud-server-$id")

    private fun readDynamicCloudToken(id: String): String {
        val legacyIndex = id.removePrefix("legacy-").toIntOrNull()?.minus(1)
        val credential = when (legacyIndex) {
            0 -> ProviderCredentialId.OpenSwarmServer1Token
            1 -> ProviderCredentialId.OpenSwarmServer2Token
            2 -> ProviderCredentialId.OpenSwarmServer3Token
            else -> dynamicCloudCredential(id)
        }
        return (providerCredentialStore.read(credential) as? CredentialStoreResult.Success)?.value.orEmpty()
    }

    suspend fun addCloudServer(name: String, url: String, token: String = ""): CloudServerConfig {
        val server = CloudServerConfig(UUID.randomUUID().toString(), name.trim().ifBlank { "Cloud Server" }.take(80), normalizeCloudServerUrl(url))
        saveCloudServer(server, token)
        return server
    }

    suspend fun saveCloudServer(server: CloudServerConfig, token: String = server.token) {
        val normalized = server.copy(name = server.name.trim().ifBlank { "Cloud Server" }.take(80), url = normalizeCloudServerUrl(server.url), token = "")
        val current = cloudServers.first().map { it.copy(token = "") }.filterNot { it.id == normalized.id }
        dataStore.edit { it[cloudServersKey] = json.encodeToString(current + normalized) }
        saveDynamicCloudToken(normalized.id, token)
    }

    suspend fun removeCloudServer(id: String) {
        val remaining = cloudServers.first().filterNot { it.id == id }.map { it.copy(token = "") }
        dataStore.edit { it[cloudServersKey] = json.encodeToString(remaining) }
        providerCredentialStore.remove(dynamicCloudCredential(id))
    }

    suspend fun setCloudServerEnabled(id: String, enabled: Boolean) {
        val updated = cloudServers.first().map { if (it.id == id) it.copy(enabled = enabled, token = "") else it.copy(token = "") }
        dataStore.edit { it[cloudServersKey] = json.encodeToString(updated) }
    }

    private suspend fun saveDynamicCloudToken(id: String, value: String) = withContext(Dispatchers.IO) {
        val result = if (value.trim().isBlank()) providerCredentialStore.remove(dynamicCloudCredential(id)) else providerCredentialStore.write(dynamicCloudCredential(id), value.trim())
        when (result) {
            is CredentialStoreResult.Success -> Unit
            CredentialStoreResult.CredentialsMustBeReentered -> error("Credential storage needs recovery before saving a cloud token.")
            CredentialStoreResult.StorageUnavailable -> error("Android secure storage is unavailable; the cloud token was not saved.")
        }
    }

    private fun normalizeCloudServerUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Enter the HTTPS URL of your cloud backend." }
        val uri = java.net.URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "Enter the HTTPS URL of your cloud backend." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "The server URL must not contain credentials, a query, or a fragment." }
        return normalized
    }

    /** A UI-safe recovery signal; it never contains credential or ciphertext data. */
    val providerCredentialRecoveryState = providerCredentialStore.recoveryState

    private val mutableProviderCredentialPersistenceState =
        MutableStateFlow<ProviderCredentialPersistenceState>(ProviderCredentialPersistenceState.Ready)
    /** Observable save result for callers that need to retry a transient storage failure. */
    val providerCredentialPersistenceState: StateFlow<ProviderCredentialPersistenceState> =
        mutableProviderCredentialPersistenceState.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            if (runStartupMigration) {
                // Legacy encrypted-preference credentials are imported before DataStore
                // secrets are stripped. If either store is unavailable, updateConfig still strips
                // plaintext rather than using it as a recovery fallback.
                try {
                    providerCredentialStore.migrateLegacyCredentials()
                    updateConfig { it }
                } catch (_: Exception) {
                    // The credential store has no plaintext fallback; a later update retries.
                }
            }
            readOpenSwarmClientToken()
        }
    }

    /**
     * Reads only authenticated direct-store values. Persisted JSON credentials are migration
     * input, never a runtime credential fallback.
     */
    private fun mergeSecretsForRead(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey.orEmpty()
        )
    }

    /**
     * Supplies legacy DataStore values to a write only when direct credential reads are healthy.
     * This gives the one-time DataStore migration a source without exposing it to callers.
     */
    private fun mergeSecretsForUpdate(persisted: LLMConfig): LLMConfig {
        val snapshot = (readCredentialSnapshot() as? CredentialSnapshotResult.Success)?.snapshot
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = persisted.apiKeys + snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey ?: persisted.elevenLabsApiKey
        )
    }

    /**
     * Commits direct credentials before returning a stripped DataStore configuration.
     * Any failed direct-store mutation rolls back successful earlier mutations and aborts the
     * DataStore transaction, preserving the previously persisted configuration as retry input.
     */
    private fun storeSecretsAndStrip(config: LLMConfig): CredentialStripResult {
        val snapshot = when (val snapshotResult = readCredentialSnapshot()) {
            is CredentialSnapshotResult.Success -> snapshotResult.snapshot
            is CredentialSnapshotResult.Failure -> return CredentialStripResult.Failure(snapshotResult.state)
        }
        val desiredProviderApiKeys = linkedMapOf<String, String>()
        config.apiKeys.forEach { (provider, key) ->
            // Invalid historical JSON keys are deliberately stripped, never materialized as
            // direct records, and cannot block a safe migration.
            val credential = runCatching { ProviderCredentialId.ApiKey(provider) }.getOrNull()
                ?: return@forEach
            if (key.isNotBlank()) desiredProviderApiKeys[credential.providerName] = key
        }
        val attemptedCredentials = linkedSetOf<ProviderCredentialId>()
        val providerNames = linkedSetOf<String>().apply {
            addAll(snapshot.providerApiKeys.keys)
            addAll(desiredProviderApiKeys.keys)
        }

        for (providerName in providerNames) {
            val previousValue = snapshot.providerApiKeys[providerName]
            val desiredValue = desiredProviderApiKeys[providerName]
            if (previousValue == desiredValue) continue
            val credential = ProviderCredentialId.ApiKey(providerName)
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredValue)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        val desiredElevenLabsKey = config.elevenLabsApiKey.takeUnless(String::isBlank)
        if (snapshot.elevenLabsApiKey != desiredElevenLabsKey) {
            val credential = ProviderCredentialId.ElevenLabsApiKey
            attemptedCredentials += credential
            val result = persistCredential(credential, desiredElevenLabsKey)
            if (result !is CredentialStoreResult.Success) {
                return CredentialStripResult.Failure(
                    restoreSnapshot(snapshot, attemptedCredentials) ?: result.toPersistenceState()
                )
            }
        }

        return CredentialStripResult.Success(config.copy(apiKeys = emptyMap(), elevenLabsApiKey = ""))
    }

    private fun persistCredential(
        credential: ProviderCredentialId,
        value: String?
    ): CredentialStoreResult<Unit> = if (value == null) {
        providerCredentialStore.remove(credential)
    } else {
        providerCredentialStore.write(credential, value)
    }

    /** Restores the pre-update semantic credential snapshot after a partial direct-store write. */
    private fun restoreSnapshot(
        snapshot: CredentialSnapshot,
        attemptedCredentials: Set<ProviderCredentialId>
    ): ProviderCredentialPersistenceState? {
        for (credential in attemptedCredentials) {
            val previousValue = when (credential) {
                is ProviderCredentialId.ApiKey -> snapshot.providerApiKeys[credential.providerName]
                ProviderCredentialId.ElevenLabsApiKey -> snapshot.elevenLabsApiKey
                ProviderCredentialId.HuggingFaceToken, ProviderCredentialId.OpenSwarmClientToken,
                ProviderCredentialId.OpenSwarmServer1Token, ProviderCredentialId.OpenSwarmServer2Token,
                ProviderCredentialId.OpenSwarmServer3Token -> null
            }
            val result = persistCredential(credential, previousValue)
            if (result !is CredentialStoreResult.Success) return result.toPersistenceState()
        }
        return null
    }

    private fun CredentialStoreResult<*>.toPersistenceState(): ProviderCredentialPersistenceState = when (this) {
        CredentialStoreResult.CredentialsMustBeReentered ->
            ProviderCredentialPersistenceState.CredentialsMustBeReentered
        CredentialStoreResult.StorageUnavailable -> ProviderCredentialPersistenceState.StorageUnavailable
        is CredentialStoreResult.Success -> ProviderCredentialPersistenceState.Ready
    }

    private fun readCredentialSnapshot(): CredentialSnapshotResult {
        if (providerCredentialStore.recoveryState.value ==
            ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return CredentialSnapshotResult.Failure(
                ProviderCredentialPersistenceState.CredentialsMustBeReentered
            )
        }
        val providerApiKeys = providerCredentialStore.readProviderApiKeys()
        val elevenLabsApiKey = providerCredentialStore.read(ProviderCredentialId.ElevenLabsApiKey)
        if (providerApiKeys !is CredentialStoreResult.Success ||
            elevenLabsApiKey !is CredentialStoreResult.Success ||
            providerCredentialStore.recoveryState.value ==
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            val failure = when {
                providerCredentialStore.recoveryState.value ==
                    ProviderCredentialRecoveryState.CredentialsMustBeReentered ->
                    ProviderCredentialPersistenceState.CredentialsMustBeReentered
                providerApiKeys !is CredentialStoreResult.Success ->
                    providerApiKeys.toPersistenceState()
                else -> elevenLabsApiKey.toPersistenceState()
            }
            return CredentialSnapshotResult.Failure(failure)
        }
        return CredentialSnapshotResult.Success(
            CredentialSnapshot(providerApiKeys.value, elevenLabsApiKey.value)
        )
    }

    suspend fun resetProviderCredentialsForReentry(): CredentialStoreResult<Unit> =
        withContext(Dispatchers.IO) {
            providerCredentialStore.resetForReentry().also { result ->
                mutableProviderCredentialPersistenceState.value = result.toPersistenceState()
            }
        }

    private data class CredentialSnapshot(
        val providerApiKeys: Map<String, String>,
        val elevenLabsApiKey: String?
    )

    private sealed interface CredentialSnapshotResult {
        data class Success(val snapshot: CredentialSnapshot) : CredentialSnapshotResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialSnapshotResult
    }

    private sealed interface CredentialStripResult {
        data class Success(val strippedConfig: LLMConfig) : CredentialStripResult
        data class Failure(val state: ProviderCredentialPersistenceState) : CredentialStripResult
    }

    private class CredentialPersistenceAborted(
        val state: ProviderCredentialPersistenceState
    ) : RuntimeException(null, null, false, false)

    // Auto-reply preference keys
    private val autoReplyGlobalKey = booleanPreferencesKey("auto_reply_global")
    private val autoReplyWhatsAppKey = booleanPreferencesKey("auto_reply_whatsapp")
    private val autoReplySmsKey = booleanPreferencesKey("auto_reply_sms")
    private val autoReplyEmailKey = booleanPreferencesKey("auto_reply_email")
    private val autoReplyDelayKey = intPreferencesKey("auto_reply_delay_minutes")
    private val autoReplyBlacklistKey = stringSetPreferencesKey("auto_reply_blacklist")
    private val autoReplyWhitelistKey = stringSetPreferencesKey("auto_reply_whitelist")
    private val autoReplyCustomPromptKey = stringPreferencesKey("auto_reply_custom_prompt")
    private val autoReplyMaxPerHourKey = intPreferencesKey("auto_reply_max_per_hour")

    val llmConfig: Flow<LLMConfig> = dataStore.data
        .map { preferences -> mergeSecretsForRead(decodeConfig(preferences[llmConfigKey])) }
        .flowOn(Dispatchers.IO)

    val autoReplyConfig: Flow<AutoReplyConfig> = dataStore.data.map { preferences ->
        AutoReplyConfig(
            // Auto-reply is opt-in (see AutoReplyConfig): default OFF until the
            // user explicitly enables each channel.
            globalEnabled = preferences[autoReplyGlobalKey] ?: false,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: false,
            smsEnabled = preferences[autoReplySmsKey] ?: false,
            emailEnabled = preferences[autoReplyEmailKey] ?: false,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    /** Base URL for the user's own cloud-hosted OpenSwarm bridge; blank means not configured. */
    val openSwarmServerUrl: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[openSwarmServerUrlKey]
                ?: preferences[legacyOpenSwarmServerUrlKey]
                ?: ""
        }
        .flowOn(Dispatchers.IO)

    val cloudServerUrls: Flow<List<String>> = dataStore.data.map { preferences ->
        (0..2).map { index ->
            preferences[cloudServerUrlKeys[index]].orEmpty().ifBlank {
                if (index == 0) preferences[openSwarmServerUrlKey] ?: preferences[legacyOpenSwarmServerUrlKey] ?: "" else ""
            }
        }
    }.flowOn(Dispatchers.IO)

    val cloudServerEnabled: Flow<List<Boolean>> = dataStore.data.map { preferences ->
        (0..2).map { index -> preferences[cloudServerEnabledKeys[index]] ?: (index == 0) }
    }.flowOn(Dispatchers.IO)

    suspend fun saveOpenSwarmServerUrl(value: String) {
        saveCloudServerUrl(0, value)
    }

    suspend fun saveCloudServerUrl(index: Int, value: String) {
        require(index in 0..2) { "Server index must be between 0 and 2." }
        val normalized = value.trim().trimEnd('/')
        if (normalized.isNotEmpty()) {
            val uri = java.net.URI(normalized)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "Enter the HTTPS URL of your cloud backend."
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "The server URL must not contain credentials, a query, or a fragment."
            }
        }
        dataStore.edit { preferences ->
            if (normalized.isBlank()) preferences.remove(cloudServerUrlKeys[index])
            else preferences[cloudServerUrlKeys[index]] = normalized
            if (index == 0) {
                if (normalized.isBlank()) preferences.remove(openSwarmServerUrlKey)
                else preferences[openSwarmServerUrlKey] = normalized
                preferences.remove(legacyOpenSwarmServerUrlKey)
            }
        }
    }

    suspend fun setCloudServerEnabled(index: Int, enabled: Boolean) {
        require(index in 0..2) { "Server index must be between 0 and 2." }
        dataStore.edit { it[cloudServerEnabledKeys[index]] = enabled }
    }

    private fun readOpenSwarmClientToken() {
        val ids = listOf(ProviderCredentialId.OpenSwarmServer1Token, ProviderCredentialId.OpenSwarmServer2Token, ProviderCredentialId.OpenSwarmServer3Token)
        val values = ids.map { (providerCredentialStore.read(it) as? CredentialStoreResult.Success)?.value.orEmpty() }
        mutableCloudServerTokens.value = values
        mutableOpenSwarmClientToken.value = values.firstOrNull().orEmpty()
    }

    suspend fun saveOpenSwarmClientToken(value: String) = withContext(Dispatchers.IO) {
        saveCloudServerToken(0, value)
    }

    suspend fun saveCloudServerToken(index: Int, value: String) = withContext(Dispatchers.IO) {
        require(index in 0..2) { "Server index must be between 0 and 2." }
        val normalized = value.trim()
        require(normalized.length <= 512) { "The bridge token is too long." }
        val credential = listOf(ProviderCredentialId.OpenSwarmServer1Token, ProviderCredentialId.OpenSwarmServer2Token, ProviderCredentialId.OpenSwarmServer3Token)[index]
        val result = if (normalized.isBlank()) {
            providerCredentialStore.remove(credential)
        } else {
            providerCredentialStore.write(credential, normalized)
        }
        when (result) {
            is CredentialStoreResult.Success -> {
                mutableCloudServerTokens.value = mutableCloudServerTokens.value.toMutableList().also { it[index] = normalized }
                if (index == 0) mutableOpenSwarmClientToken.value = normalized
            }
            CredentialStoreResult.CredentialsMustBeReentered -> error("Credential storage needs recovery before the cloud token can be saved.")
            CredentialStoreResult.StorageUnavailable -> error("Android secure storage is unavailable; the cloud token was not saved.")
        }
    }

    val autoRunGeneratedCode: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[autoRunGeneratedCodeKey] ?: false }
        .flowOn(Dispatchers.IO)

    suspend fun setAutoRunGeneratedCode(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[autoRunGeneratedCodeKey] = enabled }
    }

    suspend fun updateConfig(
        update: (LLMConfig) -> LLMConfig
    ): ProviderCredentialPersistenceState = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { preferences ->
                val currentConfig = decodeConfig(preferences[llmConfigKey])
                val newConfig = update(mergeSecretsForUpdate(currentConfig))
                when (val result = storeSecretsAndStrip(newConfig)) {
                    is CredentialStripResult.Success -> {
                        preferences[llmConfigKey] = json.encodeToString(result.strippedConfig)
                    }
                    is CredentialStripResult.Failure -> throw CredentialPersistenceAborted(result.state)
                }
            }
            mutableProviderCredentialPersistenceState.value = ProviderCredentialPersistenceState.Ready
            ProviderCredentialPersistenceState.Ready
        } catch (aborted: CredentialPersistenceAborted) {
            mutableProviderCredentialPersistenceState.value = aborted.state
            aborted.state
        } catch (_: IOException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        } catch (_: SecurityException) {
            mutableProviderCredentialPersistenceState.value =
                ProviderCredentialPersistenceState.StorageUnavailable
            ProviderCredentialPersistenceState.StorageUnavailable
        }
    }

    private fun decodeConfig(configStr: String?): LLMConfig {
        val decoded = if (configStr != null) {
            try {
                json.decodeFromString<LLMConfig>(configStr)
            } catch (_: Exception) {
                LLMConfig()
            }
        } else LLMConfig()
        val legacyLocalProvider = ProviderCatalog.isRetiredLocalProvider(decoded.activeProvider)
        val provider = if (legacyLocalProvider) "OpenRouter" else ProviderCatalog.canonicalName(decoded.activeProvider)
        val activeModel = if (legacyLocalProvider) ProviderCatalog.defaultModel(provider) else decoded.activeModel
        val selectedModels = decoded.selectedModels?.entries?.associate { (key, model) ->
            ProviderCatalog.canonicalName(key) to model
        }
        return decoded.copy(activeProvider = provider, activeModel = activeModel, selectedModels = selectedModels)
    }

    suspend fun saveModelCache(provider: String, models: List<com.opendroid.ai.core.llm.AIModel>) {
        updateConfig { current ->
            val cache = current.modelCache.toMutableMap()
            cache[provider] = models
            val fetchMap = current.lastModelFetch.toMutableMap()
            fetchMap[provider] = System.currentTimeMillis()
            current.copy(modelCache = cache, lastModelFetch = fetchMap)
        }
    }

    suspend fun updateAutoReplyConfig(config: AutoReplyConfig) {
        dataStore.edit { preferences ->
            preferences[autoReplyGlobalKey] = config.globalEnabled
            preferences[autoReplyWhatsAppKey] = config.whatsappEnabled
            preferences[autoReplySmsKey] = config.smsEnabled
            preferences[autoReplyEmailKey] = config.emailEnabled
            preferences[autoReplyDelayKey] = config.replyDelayMinutes
            preferences[autoReplyBlacklistKey] = config.blacklistedContacts
            preferences[autoReplyWhitelistKey] = config.whitelistedContacts
            if (config.customPrompt != null) {
                preferences[autoReplyCustomPromptKey] = config.customPrompt
            } else {
                preferences.remove(autoReplyCustomPromptKey)
            }
            preferences[autoReplyMaxPerHourKey] = config.maxRepliesPerContactPerHour
        }
    }
}
