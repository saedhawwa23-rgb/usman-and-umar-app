package com.opendroid.ai.core.connector

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ServiceStatus { NOT_INSTALLED, AVAILABLE, NOT_CONNECTED, CONNECTED, ERROR }

data class CloudflareServiceState(
    val status: ServiceStatus,
    val message: String
)

/** Verifies the Cloudflare API token; no Worker endpoint or deployment is assumed. */
@Singleton
class CloudflareConnectorManager @Inject constructor(
    private val registry: UniversalToolRegistry,
    private val credentials: ProviderCredentialStore,
    private val client: OkHttpClient
) {
    private val mutableState = MutableStateFlow(CloudflareServiceState(ServiceStatus.NOT_CONNECTED, "Not connected"))
    val state: StateFlow<CloudflareServiceState> = mutableState.asStateFlow()
    private val gson = Gson()

    /** Only saves a token after the official Cloudflare verification endpoint confirms it active. */
    suspend fun connect(apiToken: String): Boolean {
        val token = apiToken.trim()
        if (token.isBlank()) {
            mutableState.value = CloudflareServiceState(ServiceStatus.ERROR, "Cloudflare API token is required")
            return false
        }
        mutableState.value = CloudflareServiceState(ServiceStatus.AVAILABLE, "Verifying Cloudflare API token…")
        val verification = verify(token)
        if (verification !is TokenVerification.Valid) {
            registry.unregister(CONNECTOR_ID)
            val error = (verification as? TokenVerification.Invalid)?.message
                ?: "Could not verify the Cloudflare API token."
            mutableState.value = CloudflareServiceState(ServiceStatus.ERROR, error)
            return false
        }
        when (credentials.write(ProviderCredentialId.ApiKey(CREDENTIAL_ID), token)) {
            is CredentialStoreResult.Success -> {
                registerManifestIfConnected()
                mutableState.value = CloudflareServiceState(
                    ServiceStatus.CONNECTED,
                    "Cloudflare API token verified. This build exposes a Cloudflare Pages deployment-list endpoint; no Worker URL, deployment target, or Worker health has been configured or verified."
                )
                return true
            }
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> {
                registry.unregister(CONNECTOR_ID)
                mutableState.value = CloudflareServiceState(ServiceStatus.ERROR, "Secure credential storage is unavailable")
                return false
            }
        }
    }

    suspend fun refresh() {
        val token = when (val result = credentials.read(ProviderCredentialId.ApiKey(CREDENTIAL_ID))) {
            is CredentialStoreResult.Success -> result.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> null
        }
        if (token.isNullOrBlank()) {
            registry.unregister(CONNECTOR_ID)
            mutableState.value = CloudflareServiceState(ServiceStatus.NOT_CONNECTED, "Not connected")
            return
        }
        mutableState.value = CloudflareServiceState(ServiceStatus.AVAILABLE, "Rechecking Cloudflare token…")
        when (val verification = verify(token)) {
            TokenVerification.Valid -> {
                registerManifestIfConnected()
                mutableState.value = CloudflareServiceState(
                    ServiceStatus.CONNECTED,
                    "Cloudflare API token verified. No Worker URL or Worker deployment has been configured or verified."
                )
            }
            is TokenVerification.Invalid -> {
                registry.unregister(CONNECTOR_ID)
                mutableState.value = CloudflareServiceState(ServiceStatus.ERROR, verification.message)
            }
        }
    }

    fun disconnect() {
        credentials.remove(ProviderCredentialId.ApiKey(CREDENTIAL_ID))
        registry.unregister(CONNECTOR_ID)
        mutableState.value = CloudflareServiceState(ServiceStatus.NOT_CONNECTED, "Not connected")
    }

    private suspend fun verify(token: String): TokenVerification = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/user/tokens/verify")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) return@withContext TokenVerification.Invalid("Cloudflare rejected this token (401). It was not stored.")
                if (!response.isSuccessful) return@withContext TokenVerification.Invalid("Cloudflare token verification failed (HTTP ${response.code}); it was not stored.")
                val body = response.body.string()
                val parsed = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                val active = parsed?.get("success")?.asBoolean == true &&
                    parsed.getAsJsonObject("result")?.get("status")?.asString.equals("active", ignoreCase = true)
                if (active) TokenVerification.Valid
                else TokenVerification.Invalid("Cloudflare did not report an active token. It was not stored.")
            }
        } catch (_: IOException) {
            TokenVerification.Invalid("Could not reach Cloudflare to verify the token. It was not stored.")
        }
    }

    private fun registerManifestIfConnected() {
        registry.registerHttpManifest(
            id = CONNECTOR_ID,
            displayName = "Cloudflare Pages",
            kind = ConnectorKind.CLOUD,
            baseUrl = "https://api.cloudflare.com/client/v4",
            tools = CLOUDFLARE_TOOLS,
            methodByTool = mapOf("list_deployments" to "GET"),
            pathByTool = mapOf("list_deployments" to "/accounts/{accountId}/pages/projects/{projectName}/deployments")
        )
    }

    private sealed interface TokenVerification {
        data object Valid : TokenVerification
        data class Invalid(val message: String) : TokenVerification
    }

    companion object {
        const val CONNECTOR_ID = "cloudflare"
        const val CREDENTIAL_ID = "cloudflare-api-token"
        private val CLOUDFLARE_TOOLS = listOf(
            ConnectorTool(
                CONNECTOR_ID, "list_deployments", "List Cloudflare Pages deployments for a project. Read-only.",
                "{\"type\":\"object\",\"required\":[\"accountId\",\"projectName\"],\"properties\":{\"accountId\":{\"type\":\"string\"},\"projectName\":{\"type\":\"string\"}}}",
                ConnectorRisk.READ_ONLY, ConnectorCredentialRef(CREDENTIAL_ID, "api_token"),
                category = "Cloud & APIs"
            )
        )
    }
}
