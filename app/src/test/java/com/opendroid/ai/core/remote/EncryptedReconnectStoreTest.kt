package com.opendroid.ai.core.remote

import com.opendroid.ai.core.security.FakeSecretBytesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement 2: the reconnect desire (which computers the owner wants connected) survives a
 * restart through the encrypted store, and a record that cannot be decrypted reads as absent so a
 * restored process never resumes a connection it cannot prove the owner wanted.
 */
class EncryptedReconnectStoreTest {

    private fun state(targetId: String, desired: Boolean = true) = ReconnectState(
        targetId = targetId,
        desired = desired,
    )

    @Test
    fun aDesiredTargetSurvivesAFreshProcess() {
        val secrets = FakeSecretBytesStore()
        EncryptedReconnectStateStore(secrets).save(state("desktop-1"))

        val restored = EncryptedReconnectStateStore(secrets).load()
        assertEquals(1, restored.size)
        assertEquals("desktop-1", restored.first().targetId)
        assertTrue(restored.first().desired)
    }

    @Test
    fun anUnreadableStoreReadsAsNoDesiredTargets() {
        val secrets = FakeSecretBytesStore()
        EncryptedReconnectStateStore(secrets).save(state("desktop-1"))
        secrets.setAvailable(false)

        assertTrue(EncryptedReconnectStateStore(secrets).load().isEmpty())
    }

    @Test
    fun removeForgetsATargetSoItIsNotReconnected() {
        val secrets = FakeSecretBytesStore()
        val store = EncryptedReconnectStateStore(secrets)
        store.save(state("desktop-1"))
        store.save(state("desktop-2"))

        assertTrue(store.remove("desktop-1"))
        assertFalse(store.remove("desktop-1"))
        assertEquals(listOf("desktop-2"), store.load().map { it.targetId })
    }

    @Test
    fun savingTheSameTargetTwiceDoesNotDuplicateIt() {
        val secrets = FakeSecretBytesStore()
        val store = EncryptedReconnectStateStore(secrets)
        store.save(state("desktop-1", desired = true))
        store.save(state("desktop-1", desired = false))

        assertEquals(1, store.load().size)
        assertFalse(store.load().first().desired)
    }
}
