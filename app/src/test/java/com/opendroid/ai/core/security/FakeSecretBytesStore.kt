package com.opendroid.ai.core.security

/**
 * A JVM stand-in for the Keystore-backed [SecretBytesStore]. It is not encryption, but it enforces
 * the same contract the Android store does: a value written under one key is only readable under
 * that key (a "move" fails to resolve), and a store marked unavailable throws on write and reads
 * back null, exactly like a lost Keystore key.
 */
class FakeSecretBytesStore(
    private var available: Boolean = true,
) : SecretBytesStore {

    private val values = LinkedHashMap<String, ByteArray>()

    fun setAvailable(value: Boolean) {
        available = value
    }

    override fun read(key: String): ByteArray? {
        if (!available) return null
        return values[key]?.copyOf()
    }

    override fun write(key: String, value: ByteArray) {
        check(available) { "Encrypted storage is unavailable for '$key'" }
        values[key] = value.copyOf()
    }

    override fun remove(key: String) {
        check(available) { "Encrypted storage is unavailable for '$key'" }
        values.remove(key)
    }

    override fun keys(): Set<String> = values.keys.toSet()
}
