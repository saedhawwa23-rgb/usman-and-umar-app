package com.opendroid.ai.core.remote.gateway

import com.opendroid.ai.core.security.FakeSecretBytesStore
import com.opendroid.ai.remote.gateway.GatewayPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayKeyStoreTest {

    private val peer = GatewayPeer(
        privateKey = "cGhvbmUtcHJpdmF0ZS1rZXktZm9yLXRlc3RzLW9ubHktMDAwMDA=",
        desktopPublicKey = "ZGVza3RvcC1wdWJsaWMta2V5LWZvci10ZXN0cy0wMDAwMDAwMDA=",
        endpoint = "192.0.2.10:51820",
    )

    @Test
    fun `a saved peer loads back unchanged`() {
        val store = GatewayKeyStore(FakeSecretBytesStore())
        store.save(peer)
        val loaded = store.load()
        assertNotNull("saved peer did not load", loaded)
        assertEquals(peer.privateKey, loaded!!.privateKey)
        assertEquals(peer.desktopPublicKey, loaded.desktopPublicKey)
        assertEquals(peer.endpoint, loaded.endpoint)
    }

    @Test
    fun `nothing stored reads as absent rather than throwing`() {
        assertNull(GatewayKeyStore(FakeSecretBytesStore()).load())
    }

    @Test
    fun `a cleared store reads as absent`() {
        val store = GatewayKeyStore(FakeSecretBytesStore())
        store.save(peer)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `unavailable encrypted storage reads as absent`() {
        val secrets = FakeSecretBytesStore()
        val store = GatewayKeyStore(secrets)
        store.save(peer)
        secrets.setAvailable(false)
        assertNull("a locked keystore must not yield a peer", store.load())
    }

    @Test
    fun `a truncated record is rejected instead of half-parsed`() {
        val secrets = FakeSecretBytesStore()
        val store = GatewayKeyStore(secrets)
        store.save(peer)
        val raw = secrets.read("gateway_peer")!!
        secrets.write("gateway_peer", raw.copyOf(raw.size / 2))
        assertNull("a truncated record must not produce a peer", store.load())
    }

    @Test
    fun `peer rendering never contains the private key`() {
        val rendered = peer.toString()
        assertFalse("private key leaked in toString", rendered.contains(peer.privateKey))
        assertEquals(true, rendered.contains("<redacted>"))
    }
}
