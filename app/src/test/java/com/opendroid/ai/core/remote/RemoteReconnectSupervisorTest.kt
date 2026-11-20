package com.opendroid.ai.core.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives the real [RemoteReconnectSupervisor] over the real [ReconnectionCoordinator] and a real
 * file-backed store, so the cold-start, reboot, and drop paths are all exercised without a socket.
 */
class RemoteReconnectSupervisorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun paired(vararg deviceIds: String): PairedDesktopStore {
        val store = object : PairedDesktopStore {
            private val desktops = LinkedHashMap<String, PairedDesktop>()
            override fun load() = desktops.values.toList()
            override fun save(desktop: PairedDesktop) { desktops[desktop.deviceId] = desktop }
            override fun remove(deviceId: String) { desktops.remove(deviceId) }
        }
        deviceIds.forEach { id ->
            store.save(PairedDesktop(host = "10.0.0.5", port = 45871, deviceId = id, serverName = id, secret = ByteArray(32)))
        }
        return store
    }

    private fun coordinator(): ReconnectionCoordinator =
        ReconnectionCoordinator(
            store = FileReconnectStateStore(temporaryFolder.newFile("reconnect.json")),
            backoff = ReconnectBackoff(baseDelayMs = 1L, multiplier = 2.0, maxDelayMs = 8L, maxAttempts = 3),
        )

    @Test
    fun aDesireRecordedBeforeRestartIsReconnectedOnColdStart() = runBlocking {
        val store = paired("desk-1")
        val coordinator = coordinator()
        // The owner wanted this computer connected, then the app was killed.
        coordinator.wantConnected("desk-1", ReconnectReason.MANUAL)

        var attempts = 0
        val supervisor = RemoteReconnectSupervisor(coordinator, store) { attempts++; true }

        val results = supervisor.reconnectAll(ReconnectReason.APP_START) { /* no wait */ }

        assertEquals(1, attempts)
        assertTrue(results.getValue("desk-1").desired)
        assertFalse(results.getValue("desk-1").gaveUp)
        assertEquals(ReconnectReason.APP_START, coordinator.state("desk-1")?.lastReason)
    }

    @Test
    fun aDropRetriesThenSucceeds() = runBlocking {
        val store = paired("desk-1")
        val coordinator = coordinator()
        coordinator.wantConnected("desk-1", ReconnectReason.MANUAL)

        var attempts = 0
        val supervisor = RemoteReconnectSupervisor(coordinator, store) {
            attempts++
            attempts >= 3 // fails twice, then connects
        }

        val results = supervisor.reconnectAll(ReconnectReason.NETWORK_INTERRUPTED) { /* no wait */ }

        assertEquals(3, attempts)
        assertFalse(results.getValue("desk-1").gaveUp)
    }

    @Test
    fun aTargetThatNeverConnectsGivesUpAfterTheBoundedSchedule() = runBlocking {
        val store = paired("desk-1")
        val coordinator = coordinator()
        coordinator.wantConnected("desk-1", ReconnectReason.MANUAL)

        val supervisor = RemoteReconnectSupervisor(coordinator, store) { false }

        val results = supervisor.reconnectAll(ReconnectReason.DEVICE_BOOT) { /* no wait */ }

        assertTrue(results.getValue("desk-1").gaveUp)
        // Once given up it is no longer a startup target, so a restart does not hammer the network.
        assertTrue(supervisor.targetsToReconnectOnStartup().isEmpty())
    }

    @Test
    fun aForgottenComputerStopsBeingWanted() = runBlocking {
        val store = paired("desk-1")
        val coordinator = coordinator()
        coordinator.wantConnected("desk-1", ReconnectReason.MANUAL)
        store.remove("desk-1") // the owner forgot the computer

        var attempts = 0
        val supervisor = RemoteReconnectSupervisor(coordinator, store) { attempts++; true }

        supervisor.reconnectAll(ReconnectReason.APP_START) { /* no wait */ }

        assertEquals(0, attempts)
        assertFalse(coordinator.state("desk-1")!!.desired)
    }

    @Test
    fun onlyDesiredTargetsAreReconnected() = runBlocking {
        val store = paired("desk-1", "desk-2")
        val coordinator = coordinator()
        coordinator.wantConnected("desk-1", ReconnectReason.MANUAL)
        coordinator.wantConnected("desk-2", ReconnectReason.MANUAL)
        coordinator.wantDisconnected("desk-2")

        val connected = mutableListOf<String>()
        val supervisor = RemoteReconnectSupervisor(coordinator, store) { connected += it.deviceId; true }

        supervisor.reconnectAll(ReconnectReason.APP_START) { /* no wait */ }

        assertEquals(listOf("desk-1"), connected)
        assertNotNull(coordinator.state("desk-2"))
    }
}
