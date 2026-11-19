package com.opendroid.ai.core.agentplatform

import android.content.Context
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stored platform connection.
 *
 * The session key lives in the Keystore-encrypted record; only non-secret metadata is ever held in
 * plain preferences, so a backup or a debug dump of the app's prefs cannot leak a credential.
 */
@Serializable
data class PlatformConnection(
    val platformId: String,
    val displayName: String,
    val baseUrl: String,
    val connectedAtMillis: Long,
    val lastValidatedAtMillis: Long? = null,
    val lastValidationOk: Boolean = false,
    /** Never the key itself: a short recognisable label only. */
    val keyHint: String = "",
    /** Model the server reports for its agent settings, shown read-only in the UI. */
    val serverModel: String? = null,
)

interface PlatformConnectionStore {
    fun connections(): List<PlatformConnection>
    fun connection(platformId: String): PlatformConnection?
    fun save(connection: PlatformConnection, sessionKey: String): Boolean
    fun sessionKey(platformId: String): String?
    fun remove(platformId: String): Boolean
}

@Singleton
class AndroidPlatformConnectionStore @Inject constructor(
    @ApplicationContext context: Context
) : PlatformConnectionStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(prefs),
        cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS),
    )

    override fun connections(): List<PlatformConnection> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PlatformConnection>>(raw)
        } catch (_: Exception) {
            // A corrupt index must not crash the screen; the user can re-connect.
            emptyList()
        }
    }

    override fun connection(platformId: String): PlatformConnection? =
        connections().firstOrNull { it.platformId == platformId }

    // The KTX edit(commit = true) helper returns Unit, which would discard commit()'s success
    // flag; save() reports that flag to the caller.
    @Suppress("UseKtx")
    override fun save(connection: PlatformConnection, sessionKey: String): Boolean {
        val written = records.write(
            storageKey = secretKey(connection.platformId),
            aad = aad(connection.platformId),
            plaintext = sessionKey.toByteArray(StandardCharsets.UTF_8),
        )
        if (written !is SecretRecordResult.Success) return false

        val updated = connections().filterNot { it.platformId == connection.platformId } + connection
        return prefs.edit()
            .putString(KEY_INDEX, json.encodeToString(updated))
            .commit()
    }

    override fun sessionKey(platformId: String): String? =
        when (val result = records.read(secretKey(platformId), aad(platformId))) {
            is SecretRecordResult.Success ->
                result.value?.let { String(it, StandardCharsets.UTF_8) }
            else -> null
        }

    @Suppress("UseKtx")
    override fun remove(platformId: String): Boolean {
        records.removeRecord(secretKey(platformId))
        val remaining = connections().filterNot { it.platformId == platformId }
        return prefs.edit()
            .putString(KEY_INDEX, json.encodeToString(remaining))
            .commit()
    }

    private fun secretKey(platformId: String) = "agent_platform_session_$platformId"

    private fun aad(platformId: String) = "agent-platform|$platformId"

    private companion object {
        const val PREFERENCES_NAME = "agent_platform_connections"
        const val KEY_INDEX = "connections_index"
        const val KEY_ALIAS = "agent_platform_session_key"
    }
}

/**
 * A short, non-reversible label for a key so the user can tell which one is stored. Deliberately
 * shows no characters from the secret.
 */
object SessionKeyHint {
    fun of(key: String): String {
        if (key.isBlank()) return "(not set)"
        return "•••• (${key.length} chars)"
    }
}
