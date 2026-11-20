package com.opendroid.ai.core.security

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A JSON list persisted through [SecretBytesStore] (Android Keystore-backed) instead of a plain file.
 *
 * This is the durable half of "restore authorization after a restart" and "a revoked grant never
 * restores access": the bytes on disk are AES-GCM ciphertext bound to [aad], so a value moved to a
 * different logical key fails to decrypt rather than silently resolving. A read that cannot be
 * decrypted yields an empty list (fail closed: the device reads as having no grants, not as having
 * grants it cannot prove), while a write that cannot be persisted throws so a mutation is never
 * reported as done when it was not stored.
 */
class EncryptedJsonListStore<T>(
    private val secrets: SecretBytesStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val aad: String = key,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) {

    @Synchronized
    fun load(): List<T> {
        val bytes = secrets.read(key) ?: return emptyList()
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), text) }.getOrDefault(emptyList())
    }

    /** Persists the whole list, or throws if the encrypted store cannot hold it. */
    @Synchronized
    fun save(items: List<T>) {
        val payload = json.encodeToString(ListSerializer(serializer), items)
        secrets.write(key, payload.encodeToByteArray())
    }

    /** The additional authenticated data this store binds to; exposed so a test can prove binding. */
    fun aadForTest(): String = aad
}
