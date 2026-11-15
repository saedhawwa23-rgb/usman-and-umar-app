package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end wiring of pairing to session lifecycle, using a controlled clock. This is the test
 * that proves the five-minute pairing code no longer bounds an established session.
 */
class RemoteControlCoordinatorSessionTest {

    private var clock = 5_000_000L
    private val service = RemoteControlService(now = { clock })
    private val sessions = RemoteSessionManager(nowPolicy())
    private val coordinator = RemoteControlCoordinator(
        service = service,
        sessions = sessions,
        clock = { clock },
    )

    private fun nowPolicy() = RemoteSessionPolicy()

    private fun pair(): String {
        val start = coordinator.startPairing("dev-1", "School Desktop")
        val keyId = "key-1"
        val result = coordinator.completePairing(start.code, keyId)
        assertTrue(result is RemoteControlCoordinator.StepResult.Success)
        return keyId
    }

    @Test
    fun `a completed pairing opens a connected session`() {
        pair()

        val session = coordinator.sessionFor("dev-1")
        assertEquals(RemoteSessionState.CONNECTED, session!!.state)
        assertEquals(SessionEndReason.NONE, session.endReason)
    }

    @Test
    fun `the session outlives the five-minute pairing code while heartbeats continue`() {
        pair()

        // Six minutes of live heartbeats: the pairing code would long since be dead.
        repeat(24) {
            clock += 15_000L
            coordinator.noteHeartbeat("dev-1")
            coordinator.tickSessions()
        }

        assertTrue(clock - 5_000_000L > coordinator.sessionFor("dev-1")!!.let { 5 * 60 * 1000L })
        assertEquals(RemoteSessionState.CONNECTED, coordinator.sessionFor("dev-1")!!.state)
    }

    @Test
    fun `an expired pairing code cannot open a session`() {
        val start = coordinator.startPairing("dev-1", "School Desktop")
        clock += RemoteControlService.PAIRING_CODE_TTL_MS + 1

        val result = coordinator.completePairing(start.code, "key-1")

        assertTrue(result is RemoteControlCoordinator.StepResult.Rejected)
        assertEquals(RemoteSessionState.PAIRING, coordinator.sessionFor("dev-1")!!.state)
    }

    @Test
    fun `disconnect ends the session and reports the owner reason`() {
        pair()

        val result = coordinator.disconnectSession("dev-1")

        assertTrue(result is RemoteControlCoordinator.StepResult.Success)
        assertEquals("You disconnected this session.", (result as RemoteControlCoordinator.StepResult.Success).message)
        assertEquals(SessionEndReason.OWNER_DISCONNECTED, coordinator.sessionFor("dev-1")!!.endReason)
    }

    @Test
    fun `unpairing revokes the session`() {
        val keyId = pair()

        coordinator.unpair(keyId)

        assertEquals(RemoteSessionState.REVOKED, coordinator.sessionFor("dev-1")!!.state)
        assertEquals(SessionEndReason.OWNER_REVOKED, coordinator.sessionFor("dev-1")!!.endReason)
    }

    @Test
    fun `kill switch ends every session`() {
        pair()

        coordinator.engageKillSwitch()

        val session = coordinator.sessionFor("dev-1")!!
        assertEquals(RemoteSessionState.REVOKED, session.state)
        assertEquals(SessionEndReason.KILL_SWITCH, session.endReason)
        assertFalse(session.isLive)
    }

    @Test
    fun `idle past the inactivity policy ends the session with the inactivity reason`() {
        pair()

        clock += RemoteSessionPolicy().inactivityTimeoutMs + 1
        coordinator.tickSessions()

        assertEquals(RemoteSessionState.EXPIRED, coordinator.sessionFor("dev-1")!!.state)
        assertEquals(SessionEndReason.INACTIVITY_TIMEOUT, coordinator.sessionFor("dev-1")!!.endReason)
    }
}
