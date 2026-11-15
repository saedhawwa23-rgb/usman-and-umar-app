package com.opendroid.ai.core.security

/**
 * Minimal encrypted byte storage used by the remote-control key store.
 *
 * It is a narrow interface on purpose: the security core and its JVM tests depend only on this,
 * while the Android side binds it to the Keystore-backed [KeystoreSecretRecords]. There is no
 * plaintext fallback - a caller that cannot encrypt must fail rather than write in the clear.
 */
interface SecretBytesStore {
    /** Returns null when no value exists or the value cannot be safely recovered. */
    fun read(key: String): ByteArray?

    /** Persists a value, or throws if it cannot be durably and safely stored. */
    fun write(key: String, value: ByteArray)

    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * Adapts the Keystore-backed record store to [SecretBytesStore].
 *
 * The record store authenticates each value against its own logical key as additional
 * authenticated data, so a record moved to a different key fails to decrypt rather than
 * silently resolving. A read that cannot be decrypted returns null (fail closed: the device is
 * treated as not paired), while a write that cannot be persisted throws so pairing reports
 * failure instead of appearing to succeed.
 */
internal class KeystoreSecretBytesStore(
    private val records: KeystoreSecretRecords,
) : SecretBytesStore {

    override fun read(key: String): ByteArray? = when (val result = records.read(key, aad = key)) {
        is SecretRecordResult.Success -> result.value
        SecretRecordResult.Unrecoverable -> null
        SecretRecordResult.StorageUnavailable -> null
    }

    override fun write(key: String, value: ByteArray) {
        when (records.write(key, aad = key, plaintext = value)) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable ->
                throw IllegalStateException("Encrypted record for '$key' could not be written")
            SecretRecordResult.StorageUnavailable ->
                throw IllegalStateException("Encrypted storage is unavailable for '$key'")
        }
    }

    override fun remove(key: String) {
        when (records.removeRecord(key)) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable ->
                throw IllegalStateException("Encrypted record for '$key' could not be removed")
            SecretRecordResult.StorageUnavailable ->
                throw IllegalStateException("Encrypted storage is unavailable for '$key'")
        }
    }

    override fun keys(): Set<String> = when (val result = records.keys()) {
        is SecretRecordResult.Success -> result.value
        SecretRecordResult.Unrecoverable -> emptySet()
        SecretRecordResult.StorageUnavailable -> emptySet()
    }
}
