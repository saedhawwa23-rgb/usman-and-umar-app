package com.opendroid.ai.core.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReconnectionCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun backoffIsBoundedAndMonotonic() {
        val backoff = ReconnectBackoff(baseDelayMs = 1_000, multiplier = 2.0, maxDelayMs = 8_000, maxAttempts = 10)
        assertEquals(1_000L, backoff.delayForAttempt(1))
        assertEquals(2_000L, backoff.delayForAttempt(2))
        assertEquals(4_000L, backoff.delayForAttempt(3))
        assertEquals(8_000L, backoff.delayForAttempt(4))
        // Capped at the maximum, never growing without bound.
        assertEquals(8_000L, backoff.delayForAttempt(9))
    }

    @Test
    fun appRestartReconnectsDesiredTarget() = runBlocking {
        val store = InMemoryReconnectStateStore()
        // First run: the owner connects, then the process dies.
        ReconnectionCoordinator(store, clock = { 0L }).wantConnected("desktop-1", ReconnectReason.MANUAL)

        // Cold start: a brand-new coordinator over the same store must still want the target.
        val afterRestart = ReconnectionCoordinator(store, clock = { 5_000L })
        val pending = afterRestart.targetsToReconnectOnStartup()
        assertEquals(1, pending.size)
        assertEquals("desktop-1", pending.single().targetId)

        var connected = 0
        val result = afterRestart.reconnect("desktop-1", ReconnectReason.APP_START, connect = { connected++; true }, sleep = {})
        assertEquals(1, connected)
        assertEquals(0, result.attempts)
        assertTrue(result.lastConnectedAtMs > 0)
    }

    @Test
    fun deviceBootReconnectsThroughFileStore() {
        val file = temporaryFolder.newFile("reconnect.json")
        ReconnectionCoordinator(FileReconnectStateStore(file), clock = { 0L })
            .wantConnected("desktop-1", ReconnectReason.MANUAL)

        val afterBoot = ReconnectionCoordinator(FileReconnectStateStore(file), clock = { 0L })
        assertEquals(listOf("desktop-1"), afterBoot.targetsToReconnectOnStartup().map { it.targetId })
        // A target the owner disconnected must NOT come back after a boot.
        afterBoot.wantDisconnected("desktop-1")
        assertTrue(ReconnectionCoordinator(FileReconnectStateStore(file), clock = { 0L }).targetsToReconnectOnStartup().isEmpty())
    }

    @Test
    fun networkInterruptionRetriesWithBackoffThenSucceeds() = runBlocking {
        val coordinator = ReconnectionCoordinator(
            backoff = ReconnectBackoff(baseDelayMs = 1_000, multiplier = 2.0, maxDelayMs = 60_000, maxAttempts = 8),
            clock = { 0L },
        )
        coordinator.wantConnected("desktop-1", ReconnectReason.NETWORK_INTERRUPTED)
        val delays = mutableListOf<Long>()
        var tries = 0
        val result = coordinator.reconnect(
            targetId = "desktop-1",
            reason = ReconnectReason.NETWORK_INTERRUPTED,
            connect = { tries++; tries >= 3 },
            sleep = { delays += it },
        )
        assertEquals(3, tries)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(0, result.attempts)
    }

    @Test
    fun givesUpAfterBoundedAttempts() = runBlocking {
        val coordinator = ReconnectionCoordinator(
            backoff = ReconnectBackoff(baseDelayMs = 1_000, multiplier = 2.0, maxDelayMs = 60_000, maxAttempts = 4),
            clock = { 0L },
        )
        coordinator.wantConnected("desktop-1", ReconnectReason.NETWORK_INTERRUPTED)
        var tries = 0
        val delays = mutableListOf<Long>()
        val result = coordinator.reconnect(
            targetId = "desktop-1",
            reason = ReconnectReason.NETWORK_INTERRUPTED,
            connect = { tries++; false },
            sleep = { delays += it },
        )
        assertTrue(result.gaveUp)
        assertEquals(4, tries)
        // Three waits precede the fourth (final) attempt.
        assertEquals(3, delays.size)
    }

    @Test
    fun disabledTargetIsNotReconnected() = runBlocking {
        val coordinator = ReconnectionCoordinator(clock = { 0L })
        coordinator.wantConnected("desktop-1", ReconnectReason.MANUAL)
        coordinator.wantDisconnected("desktop-1")
        var connected = false
        coordinator.reconnect("desktop-1", ReconnectReason.APP_START, connect = { connected = true; true }, sleep = {})
        assertFalse(connected)
        assertEquals(ReconnectDecision.Disabled, coordinator.nextDecision("desktop-1", 0))
    }

    @Test
    fun successfulConnectionResetsAttemptCounter() {
        val coordinator = ReconnectionCoordinator(clock = { 0L })
        coordinator.wantConnected("desktop-1", ReconnectReason.MANUAL)
        coordinator.recordFailure("desktop-1", ReconnectReason.NETWORK_INTERRUPTED)
        coordinator.recordFailure("desktop-1", ReconnectReason.NETWORK_INTERRUPTED)
        assertEquals(2, coordinator.state("desktop-1")?.attempts)
        val connected = coordinator.recordConnected("desktop-1", ReconnectReason.NETWORK_INTERRUPTED)
        assertEquals(0, connected.attempts)
    }

    @Test
    fun unknownTargetIsDisabled() {
        val coordinator = ReconnectionCoordinator(clock = { 0L })
        assertEquals(ReconnectDecision.Disabled, coordinator.nextDecision("never-seen", 0))
        assertEquals(null, coordinator.state("never-seen"))
    }

    @Test
    fun startupTargetsSurviveRevokeAndRegrant() {
        val store = InMemoryReconnectStateStore()
        val coordinator = ReconnectionCoordinator(store, clock = { 0L })
        coordinator.wantConnected("d", ReconnectReason.MANUAL)
        coordinator.wantDisconnected("d")
        assertTrue(coordinator.targetsToReconnectOnStartup().isEmpty())
        coordinator.wantConnected("d", ReconnectReason.MANUAL)
        assertEquals(1, coordinator.targetsToReconnectOnStartup().size)
        assertNotNull(coordinator.state("d"))
    }
}
