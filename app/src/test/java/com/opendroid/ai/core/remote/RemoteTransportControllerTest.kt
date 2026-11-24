package com.opendroid.ai.core.remote

import com.opendroid.ai.core.agent.AgentToolEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTransportControllerTest {
    @Test
    fun `kill switch stop closes the listener and clears the published state`() {
        val environment = object : AgentToolEnvironment {
            override fun listInstalledPackages(thirdPartyOnly: Boolean) = ""
            override fun deviceProperty(command: String) = ""
            override fun batteryStatus() = ""
            override fun networkStatus() = ""
            override fun screenBrightness() = ""
            override fun displaySize() = ""
            override suspend fun runLinux(command: String) = ""
            override suspend fun openApp(target: String) = ""
            override suspend fun openUrl(url: String) = ""
            override suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String) = ""
        }
        val server = LanRemoteControlServer(
            service = RemoteControlService(keyStore = InMemoryDeviceKeyStore()),
            approvalGate = RemoteApprovalGate(),
            environment = environment,
            port = 0,
        )
        val controller = RemoteTransportController(server)

        try {
            controller.start()
            assertTrue(controller.state.value.running)
            assertTrue(server.isRunning)

            controller.stopForKillSwitch()

            assertFalse(server.isRunning)
            assertFalse(controller.state.value.running)
            assertNull(controller.state.value.port)
        } finally {
            server.stop()
        }
    }
}
