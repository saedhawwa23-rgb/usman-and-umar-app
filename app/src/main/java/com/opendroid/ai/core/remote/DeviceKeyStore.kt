package com.opendroid.ai.core.remote

import com.opendroid.ai.core.security.SecretBytesStore
import java.nio.ByteBuffer

/**
 * A device the owner has paired. The secret is the per-device HMAC key; [deviceId] is stored
 * alongside it so the binding survives a restart without a separate in-memory map.
 */
data class PairedDevice(
    val keyId: String,
    val deviceId: String,
    val secret: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PairedDevice && other.keyId == keyId && other.deviceId == deviceId &&
            other.secret.contentEquals(secret)

    override fun hashCode(): Int = 31 * (31 * keyId.hashCode() + deviceId.hashCode()) + secret.contentHashCode()
}

/** Encrypted-storage boundary for paired devices. The core never persists keys itself. */
interface DeviceKeyStore {
    fun put(device: PairedDevice)
    fun get(keyId: String): PairedDevice?
    fun remove(keyId: String)
    fun all(): List<PairedDevice>
}

/** In-memory key store for tests and for callers that supply their own encrypted storage. */
class InMemoryDeviceKeyStore : DeviceKeyStore {
    private val devices = LinkedHashMap<String, PairedDevice>()
    @Synchronized override fun put(device: PairedDevice) {
        devices[device.keyId] = device.copy(secret = device.secret.copyOf())
    }
    @Synchronized override fun get(keyId: String): PairedDevice? = devices[keyId]?.let {
        it.copy(secret = it.secret.copyOf())
    }
    @Synchronized override fun remove(keyId: String) { devices.remove(keyId) }
    @Synchronized override fun all(): List<PairedDevice> = devices.values.map { it.copy(secret = it.secret.copyOf()) }
}

/**
 * Persists paired devices through an encrypted [SecretBytesStore] so the pairing survives a
 * restart. The value is a framed record (device id length, device id bytes, secret bytes); it is
 * never written as readable text, and a value that cannot be decrypted is treated as absent
 * (fail closed) rather than falling back to a weaker source.
 */
class PersistentDeviceKeyStore(
    private val secretBytes: SecretBytesStore,
    private val keyPrefix: String = "remote_device_",
) : DeviceKeyStore {

    private fun storageKey(keyId: String): String = keyPrefix + keyId

    override fun put(device: PairedDevice) {
        val idBytes = device.deviceId.toByteArray(Charsets.UTF_8)
        val framed = ByteBuffer.allocate(4 + idBytes.size + device.secret.size)
            .putInt(idBytes.size)
            .put(idBytes)
            .put(device.secret)
            .array()
        secretBytes.write(storageKey(device.keyId), framed)
    }

    override fun get(keyId: String): PairedDevice? {
        val framed = secretBytes.read(storageKey(keyId)) ?: return null
        if (framed.size < 4) return null
        val buffer = ByteBuffer.wrap(framed)
        val idLength = buffer.int
        if (idLength < 0 || idLength > framed.size - 4) return null
        val idBytes = ByteArray(idLength).also { buffer.get(it) }
        val secret = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (secret.isEmpty()) return null
        return PairedDevice(keyId, String(idBytes, Charsets.UTF_8), secret)
    }

    override fun remove(keyId: String) {
        secretBytes.remove(storageKey(keyId))
    }

    override fun all(): List<PairedDevice> =
        secretBytes.keys()
            .filter { it.startsWith(keyPrefix) }
            .mapNotNull { get(it.removePrefix(keyPrefix)) }
}
