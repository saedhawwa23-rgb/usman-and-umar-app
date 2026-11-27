package com.opendroid.ai.core.plugin

import com.opendroid.ai.core.security.FakeSecretBytesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement 2 and 3: a grant is stored encrypted, survives a process restart, and a revoked,
 * expired or capability-restricted grant never restores access. The store is rebuilt from the same
 * encrypted bytes to model a fresh process, which is what a phone restart produces.
 */
class EncryptedGrantStoreTest {

    private val capabilities = setOf(PluginCapability.NETWORK_FETCH)

    private fun registry(pluginId: String): PluginRegistry {
        val registry = PluginRegistry()
        registry.register(
            PluginRecord(
                id = pluginId,
                displayName = pluginId,
                publisher = "test",
                homepageUrl = "https://example.test",
                declaredCapabilities = capabilities,
            )
        )
        registry.approve(pluginId, capabilities)
        registry.install(pluginId)
        return registry
    }

    @Test
    fun aGrantSurvivesAFreshProcessFromTheSameEncryptedBytes() {
        val secrets = FakeSecretBytesStore()
        val first = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 1_000L },
        )
        first.grant("sync", capabilities)

        // A new process reads the same encrypted store.
        val second = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 2_000L },
        )
        assertTrue(second.isAuthorized("sync").authorized)
    }

    @Test
    fun aRevokedGrantStaysRevokedAcrossARestart() {
        val secrets = FakeSecretBytesStore()
        val first = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 1_000L },
        )
        first.grant("sync", capabilities)
        first.revoke("sync")

        val second = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 2_000L },
        )
        val decision = second.isAuthorized("sync")
        assertFalse(decision.authorized)
        assertEquals(GrantRefusal.REVOKED, decision.reason)
    }

    @Test
    fun anUnreadableEncryptedStoreYieldsNoAuthorityRatherThanPlaintext() {
        val secrets = FakeSecretBytesStore()
        PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 1_000L },
        ).grant("sync", capabilities)

        // The Keystore key is lost (device reset / key invalidation): reads fail closed.
        secrets.setAvailable(false)
        val afterLoss = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(secrets),
            pluginRegistry = registry("sync"),
            clock = { 2_000L },
        )
        assertFalse(afterLoss.isAuthorized("sync").authorized)
        assertEquals(GrantRefusal.NOT_GRANTED, afterLoss.isAuthorized("sync").reason)
    }

    @Test
    fun aGrantWrittenToTheEncryptedStoreIsNotRecoverableUnderADifferentKey() {
        val secrets = FakeSecretBytesStore()
        EncryptedPluginGrantStore(secrets, key = "grant-a").save(
            PluginGrant("sync", capabilities)
        )
        // A different logical key reads as empty, so a record cannot be replayed under another key.
        assertNull(EncryptedPluginGrantStore(secrets, key = "grant-b").load("sync"))
        assertNotNull(EncryptedPluginGrantStore(secrets, key = "grant-a").load("sync"))
    }

    @Test
    fun thirtyDayPresetExpiresAtTheRightMoment() {
        var now = 0L
        val store = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(FakeSecretBytesStore()),
            pluginRegistry = registry("sync"),
            clock = { now },
        )
        val grant = store.grantWithExpiry("sync", capabilities, GrantExpiry.DAYS_30)
        assertEquals(30L * GrantExpiry.DAY_MS, grant.expiresAtMs)

        now = 30L * GrantExpiry.DAY_MS - 1
        assertTrue(store.isAuthorized("sync").authorized)
        // Exactly at the boundary the grant is expired.
        now = 30L * GrantExpiry.DAY_MS
        assertEquals(GrantRefusal.EXPIRED, store.isAuthorized("sync").reason)
    }

    @Test
    fun ninetyDayPresetAndUntilRevokedPreset() {
        var now = 0L
        val store = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(FakeSecretBytesStore()),
            pluginRegistry = registry("sync"),
            clock = { now },
        )
        store.grantWithExpiry("sync", capabilities, GrantExpiry.DAYS_90)
        now = 89L * GrantExpiry.DAY_MS
        assertTrue(store.isAuthorized("sync").authorized)
        now = 90L * GrantExpiry.DAY_MS
        assertEquals(GrantRefusal.EXPIRED, store.isAuthorized("sync").reason)

        // "Until revoked" never expires, even far in the future.
        store.grantWithExpiry("sync", capabilities, GrantExpiry.NEVER)
        assertNull(store.grantFor("sync")?.expiresAtMs)
        now = 10_000L * GrantExpiry.DAY_MS
        assertTrue(store.isAuthorized("sync").authorized)
    }

    @Test
    fun customExpiryUsesTheRequestedNumberOfDays() {
        var now = 0L
        val store = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(FakeSecretBytesStore()),
            pluginRegistry = registry("sync"),
            clock = { now },
        )
        store.grantWithExpiry("sync", capabilities, GrantExpiry.CUSTOM, customDays = 7)
        assertEquals(7L * GrantExpiry.DAY_MS, store.grantFor("sync")?.expiresAtMs)
        now = 6L * GrantExpiry.DAY_MS
        assertTrue(store.isAuthorized("sync").authorized)
        now = 7L * GrantExpiry.DAY_MS
        assertEquals(GrantRefusal.EXPIRED, store.isAuthorized("sync").reason)
    }

    @Test
    fun theExpiryInstantIsAnchoredAtGrantTimeAndDoesNotSlideWithTheClock() {
        var now = 100L * GrantExpiry.DAY_MS
        val store = PersistentAuthorizationStore(
            grants = EncryptedPluginGrantStore(FakeSecretBytesStore()),
            pluginRegistry = registry("sync"),
            clock = { now },
        )
        val grantedAt = now
        store.grantWithExpiry("sync", capabilities, GrantExpiry.DAYS_30)
        // The instant is grant time plus the ttl; it is absolute, not "30 days from whenever asked".
        assertEquals(grantedAt + 30L * GrantExpiry.DAY_MS, store.grantFor("sync")?.expiresAtMs)

        now = grantedAt + 31L * GrantExpiry.DAY_MS
        assertEquals(GrantRefusal.EXPIRED, store.isAuthorized("sync").reason)

        // Advancing the clock further never un-expires it.
        now = grantedAt + 400L * GrantExpiry.DAY_MS
        assertEquals(GrantRefusal.EXPIRED, store.isAuthorized("sync").reason)
    }
}
