package com.opendroid.ai.core.oauth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.security.SecretBytesStore

/**
 * Encrypted-at-rest store for OAuth token sets, one record per provider.
 *
 * It sits on [SecretBytesStore], which the Android build binds to the Keystore-backed store, so a
 * token is only ever persisted encrypted. There is no plaintext fallback: if the store cannot
 * encrypt, the write throws and the connection reports failure rather than appearing to succeed.
 */
class OAuthTokenVault(private val store: SecretBytesStore) {

    private fun key(providerId: String) = "oauth.token.$providerId"

    fun save(providerId: String, tokens: OAuthTokenSet) {
        require(providerId.isNotBlank()) { "Provider id is required" }
        val json = JsonObject().apply {
            addProperty("access_token", tokens.accessToken)
            tokens.refreshToken?.let { addProperty("refresh_token", it) }
            addProperty("token_type", tokens.tokenType)
            tokens.scope?.let { addProperty("scope", it) }
            addProperty("expires_at_ms", tokens.expiresAtMs)
        }
        store.write(key(providerId), json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Returns null when no token exists or the record cannot be decrypted (fail closed). */
    fun load(providerId: String): OAuthTokenSet? {
        val raw = store.read(key(providerId)) ?: return null
        val obj = runCatching { JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject }.getOrNull()
            ?: return null
        val access = obj.get("access_token")?.asString ?: return null
        return OAuthTokenSet(
            accessToken = access,
            refreshToken = obj.get("refresh_token")?.asString,
            tokenType = obj.get("token_type")?.asString ?: "Bearer",
            scope = obj.get("scope")?.asString,
            expiresAtMs = obj.get("expires_at_ms")?.asLong ?: 0L,
        )
    }

    fun remove(providerId: String) = store.remove(key(providerId))

    /** Provider ids that currently hold a token, for the connection list. Never the tokens. */
    fun connectedProviderIds(): Set<String> =
        store.keys().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()

    private companion object {
        const val PREFIX = "oauth.token."
    }
}
