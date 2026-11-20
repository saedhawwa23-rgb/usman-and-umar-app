package com.opendroid.ai.core.remote

import com.opendroid.ai.core.security.SecretBytesStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentDeviceKeyStoreTest {

    /** Simple in-memory encrypted-store stand-in for the JVM test. */
    private class FakeSecretBytesStore : SecretBytesStore {
        val map = LinkedHashMap<String, ByteArray>()
        override fun read(key: String): ByteArray? = map[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { map[key] = value.copyOf() }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private fun device(keyId: String, deviceId: String, secret: ByteArray) =
        PairedDevice(keyId = keyId, deviceId = deviceId, secret = secret)

    @Test
    fun `device and secret round trip through the encrypted store`() {
        val store = FakeSecretBytesStore()
        val keyStore = PersistentDeviceKeyStore(store)
        val secret = ByteArray(32) { it.toByte() }

        keyStore.put(device("key-1", "target-1", secret))

        val restored = keyStore.get("key-1")!!
        assertEquals("target-1", restored.deviceId)
        assertArrayEquals(secret, restored.secret)
        assertEquals(listOf("key-1"), keyStore.all().map { it.keyId })
    }

    @Test
    fun `missing key returns null rather than throwing`() {
        assertNull(PersistentDeviceKeyStore(FakeSecretBytesStore()).get("absent"))
    }

    @Test
    fun `remove deletes the key`() {
        val keyStore = PersistentDeviceKeyStore(FakeSecretBytesStore())
        keyStore.put(device("key-1", "target-1", ByteArray(32)))

        keyStore.remove("key-1")

        assertNull(keyStore.get("key-1"))
        assertTrue(keyStore.all().isEmpty())
    }

    @Test
    fun `storage keys are namespaced so unrelated records are ignored`() {
        val store = FakeSecretBytesStore()
        store.write("unrelated_record", ByteArray(4))
        val keyStore = PersistentDeviceKeyStore(store)
        keyStore.put(device("key-1", "target-1", ByteArray(32)))

        assertEquals(setOf("key-1"), keyStore.all().map { it.keyId }.toSet())
    }

    @Test
    fun `pairing survives a restart and requests still authenticate`() {
        val store = FakeSecretBytesStore()
        val first = RemoteControlService(keyStore = PersistentDeviceKeyStore(store), now = { 1_000_000L })
        val session = first.startPairing("target-1", "My Phone")
        first.completePairing(session.code, "key-1")
        val action = RemoteAction("device.open_app", "{\"package\":\"com.example\"}", RemoteRisk.HIGH)
        val signed = first.sign("key-1", "target-1", action, 1_000_000L, "nonce-restart")

        // A fresh service over the same encrypted storage still authenticates the signed request.
        val second = RemoteControlService(keyStore = PersistentDeviceKeyStore(store), now = { 1_000_000L })
        assertEquals(setOf("key-1"), second.pairedKeyIds())
        assertTrue(second.authenticate(signed) is RemoteAuthentication.Authenticated)
    }

    @Test
    fun `a truncated record is treated as absent rather than throwing`() {
        val store = FakeSecretBytesStore()
        store.write("remote_device_key-1", byteArrayOf(0, 0))
        val keyStore = PersistentDeviceKeyStore(store)
        assertNull(keyStore.get("key-1"))
    }
}
