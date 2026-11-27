package com.opendroid.ai.core.remote

import com.opendroid.ai.core.agent.AgentToolEnvironment
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end transport test over a real loopback socket: a real [LanRemoteClient] sends to a real
 * [LanRemoteControlServer] with a real [RemoteControlService] and [RemoteApprovalGate], executing
 * a real tool against a fake (non-Android) environment. No mocks of the security core.
 */
class LanRemoteControlServerTest {

    private val environment = FakeEnvironment()
    private var server: LanRemoteControlServer? = null

    private class FakeEnvironment : AgentToolEnvironment {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun listInstalledPackages(thirdPartyOnly: Boolean) = "packages"
        override fun deviceProperty(command: String) = "Android release"
        override fun batteryStatus(): String {
            calls.add("battery_status")
            return "Battery: 42%, discharging."
        }
        override fun networkStatus(): String {
            calls.add("network_status")
            return "Active network: Wi-Fi."
        }
        override fun screenBrightness() = "brightness"
        override fun displaySize() = "size"
        override suspend fun runLinux(command: String) = "linux"
        override suspend fun openApp(target: String) = "app"
        override suspend fun openUrl(url: String) = "url"
        override suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String) = "local"
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun pairedService(): RemoteControlService {
        val service = RemoteControlService(keyStore = InMemoryDeviceKeyStore(), now = { 1_000_000L })
        val session = service.startPairing("target-1", "Phone")
        service.completePairing(session.code, "key-1")
        return service
    }

    /** Starts the server on an ephemeral port with the given gate. */
    private fun startServer(service: RemoteControlService, gate: RemoteApprovalGate): Int {
        val s = LanRemoteControlServer(service = service, approvalGate = gate, environment = environment, port = 0)
        server = s
        return s.start()
    }

    private fun approveWhenAsked(gate: RemoteApprovalGate, approve: Boolean) {
        thread(isDaemon = true) {
            while (gate.current.value == null) Thread.sleep(2)
            gate.decide(approve)
        }
    }

    private fun signed(service: RemoteControlService, tool: String = "battery_status") =
        service.sign("key-1", "target-1", RemoteAction(tool, "{}", RemoteRisk.LOW), 1_000_000L, "nonce-$tool-${System.nanoTime()}")

    @Test
    fun `an approved request executes the tool and returns its result`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        approveWhenAsked(gate, approve = true)

        val reply = LanRemoteClient().send("127.0.0.1", port, signed(service)).getOrThrow()

        assertTrue(reply.executed)
        assertEquals("battery_status", reply.toolName)
        assertTrue(reply.detail.contains("Battery: 42%"))
        assertEquals(listOf("battery_status"), environment.calls)
    }

    @Test
    fun `a denied request does not execute the tool`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        approveWhenAsked(gate, approve = false)

        val reply = LanRemoteClient().send("127.0.0.1", port, signed(service)).getOrThrow()

        assertFalse(reply.executed)
        assertTrue(reply.detail.contains("Not approved"))
        assertTrue(environment.calls.isEmpty())
    }

    @Test
    fun `an unpaired device is rejected before the owner is asked`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        // A stranger signs a well-formed request with a key this device has never paired.
        val strangerService = RemoteControlService(keyStore = InMemoryDeviceKeyStore(), now = { 1_000_000L })
        val strangerSession = strangerService.startPairing("stranger-1", "Other")
        strangerService.completePairing(strangerSession.code, "ghost-key")
        val stranger = strangerService.sign(
            "ghost-key", "target-1", RemoteAction("battery_status", "{}", RemoteRisk.LOW), 1_000_000L, "n1"
        )

        val reply = LanRemoteClient().send("127.0.0.1", port, stranger).getOrThrow()

        assertFalse(reply.executed)
        assertTrue(reply.detail.contains(RemoteControlService.UNKNOWN_DEVICE))
        assertTrue(environment.calls.isEmpty())
    }

    @Test
    fun `the kill switch rejects before the owner is asked`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        val request = signed(service)
        service.engageKillSwitch()

        val reply = LanRemoteClient().send("127.0.0.1", port, request).getOrThrow()

        assertFalse(reply.executed)
        assertTrue(reply.detail.contains(RemoteControlService.KILL_SWITCH_ACTIVE))
        assertTrue(environment.calls.isEmpty())
    }

    @Test
    fun `a replayed request is rejected the second time`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        val request = service.sign("key-1", "target-1", RemoteAction("battery_status", "{}", RemoteRisk.LOW), 1_000_000L, "fixed-nonce")
        approveWhenAsked(gate, approve = true)

        val first = LanRemoteClient().send("127.0.0.1", port, request).getOrThrow()
        val second = LanRemoteClient().send("127.0.0.1", port, request).getOrThrow()

        assertTrue(first.executed)
        assertFalse(second.executed)
        assertTrue(second.detail.contains(RemoteControlService.REPLAYED_REQUEST))
        assertEquals(listOf("battery_status"), environment.calls)
    }

    @Test
    fun `an unknown tool is rejected without asking the owner`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)

        val reply = LanRemoteClient().send("127.0.0.1", port, signed(service, tool = "delete_everything")).getOrThrow()

        assertFalse(reply.executed)
        assertTrue(reply.detail.contains("Unknown tool"))
        assertTrue(environment.calls.isEmpty())
    }

    @Test
    fun `a malformed line is rejected`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)

        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("this is not a request\n".toByteArray())
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine()
            val reply = RemoteTransportReply.decode(line)!!
            assertFalse(reply.executed)
            assertTrue(reply.detail.contains("not a complete signed action"))
        }
        assertTrue(environment.calls.isEmpty())
    }

    @Test
    fun `the listener stops accepting after stop`() {
        val service = pairedService()
        val gate = RemoteApprovalGate()
        val port = startServer(service, gate)
        server!!.stop()

        val result = LanRemoteClient().send("127.0.0.1", port, signed(service))

        assertTrue(result.isFailure)
        assertFalse(server!!.isRunning)
    }
}
