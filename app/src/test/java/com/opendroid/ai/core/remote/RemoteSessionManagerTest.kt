package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the two things that must never be conflated: a five-minute pairing code, and an
 * established session that must outlive it.
 */
class RemoteSessionManagerTest {

    private var clock = 1_000_000L
    private val policy = RemoteSessionPolicy()
    private val audit = mutableListOf<String>()
    private fun manager() = RemoteSessionManager(policy = policy, auditSink = { audit.add(it) })

    @Test
    fun `pairing code ttl is five minutes and is the only pairing bound`() {
        assertEquals(5 * 60 * 1000L, policy.pairingCodeTtlMs)
    }

    @Test
    fun `established session is still connected after the pairing code would have expired`() {
        val manager = manager()
        manager.beginPairing("dev-1", atMs = clock)
        manager.markConnected("dev-1", atMs = clock)

        // Advance past the five-minute pairing-code window and past several heartbeats, while the
        // session keeps heartbeating as a live connection would.
        repeat(25) {
            clock += policy.heartbeatIntervalMs
            manager.recordHeartbeat("dev-1", atMs = clock)
            manager.tick(atMs = clock)
        }

        assertTrue("session must survive beyond 5 minutes", clock - 1_000_000L > policy.pairingCodeTtlMs)
        val session = manager.session("dev-1")!!
        assertEquals(RemoteSessionState.CONNECTED, session.state)
        assertEquals(SessionEndReason.NONE, session.endReason)
    }

    @Test
    fun `session ends only on inactivity timeout and reports why`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        manager.tick(atMs = clock + policy.inactivityTimeoutMs + 1)

        val session = manager.session("dev-1")!!
        assertEquals(RemoteSessionState.EXPIRED, session.state)
        assertEquals(SessionEndReason.INACTIVITY_TIMEOUT, session.endReason)
    }

    @Test
    fun `a missed heartbeat moves the session to reconnecting, not ended`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        manager.tick(atMs = clock + policy.heartbeatTimeoutMs + 1)

        assertEquals(RemoteSessionState.RECONNECTING, manager.session("dev-1")!!.state)
        assertTrue(manager.session("dev-1")!!.isLive)
    }

    @Test
    fun `a recovered heartbeat returns a reconnecting session to connected`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)
        manager.tick(atMs = clock + policy.heartbeatTimeoutMs + 1)

        manager.recordHeartbeat("dev-1", atMs = clock + policy.heartbeatTimeoutMs + 5)

        assertEquals(RemoteSessionState.CONNECTED, manager.session("dev-1")!!.state)
    }

    @Test
    fun `heartbeat lost beyond the grace window disconnects with a clear reason`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)
        manager.tick(atMs = clock + policy.heartbeatTimeoutMs + 1)

        manager.tick(atMs = clock + policy.heartbeatTimeoutMs + policy.reconnectGraceMs + 1)

        val session = manager.session("dev-1")!!
        assertEquals(RemoteSessionState.DISCONNECTED, session.state)
        assertEquals(SessionEndReason.HEARTBEAT_LOST, session.endReason)
        assertFalse(session.isLive)
    }

    @Test
    fun `owner can disconnect a live session immediately`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        val ended = manager.disconnect("dev-1", atMs = clock + 1000)

        assertEquals(RemoteSessionState.DISCONNECTED, ended!!.state)
        assertEquals(SessionEndReason.OWNER_DISCONNECTED, ended.endReason)
        assertFalse(manager.session("dev-1")!!.isLive)
    }

    @Test
    fun `owner can revoke access and the session cannot be resumed by a heartbeat`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        val revoked = manager.revoke("dev-1")!!
        assertEquals(RemoteSessionState.REVOKED, revoked.state)
        assertEquals(SessionEndReason.OWNER_REVOKED, revoked.endReason)

        // A late heartbeat must not revive a revoked session.
        val after = manager.recordHeartbeat("dev-1", atMs = clock + 1000)!!
        assertEquals(RemoteSessionState.REVOKED, after.state)
        assertFalse(after.isLive)
    }

    @Test
    fun `kill switch ends every live session`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)
        manager.markConnected("dev-2", atMs = clock)

        val affected = manager.onKillSwitch()

        assertEquals(2, affected.size)
        assertTrue(affected.all { it.state == RemoteSessionState.REVOKED && it.endReason == SessionEndReason.KILL_SWITCH })
        assertTrue(manager.live().isEmpty())
    }

    @Test
    fun `maximum duration ends a session even while it is active`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        var t = clock
        while (t - clock < policy.maxSessionDurationMs) {
            t += policy.heartbeatIntervalMs
            manager.recordActivity("dev-1", atMs = t)
            manager.tick(atMs = t)
        }
        manager.tick(atMs = t + 1)

        val session = manager.session("dev-1")!!
        assertEquals(RemoteSessionState.EXPIRED, session.state)
        assertEquals(SessionEndReason.MAX_DURATION_REACHED, session.endReason)
    }

    @Test
    fun `idle seconds remaining counts down toward the inactivity policy`() {
        val manager = manager()
        manager.markConnected("dev-1", atMs = clock)

        assertEquals(policy.inactivityTimeoutMs / 1000, manager.idleSecondsRemaining("dev-1", atMs = clock))
        assertEquals(0L, manager.idleSecondsRemaining("dev-1", atMs = clock + policy.inactivityTimeoutMs))
        assertEquals(0L, manager.idleSecondsRemaining("never", atMs = clock))
    }

    @Test
    fun `an unknown device has no session`() {
        assertNull(manager().session("ghost"))
    }
}
