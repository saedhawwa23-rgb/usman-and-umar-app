package com.opendroid.ai.core.remote

import com.opendroid.ai.core.agent.AgentToolEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the path that was missing in v3.10.0: an authenticated remote request
 * becoming an executed action, and every rejection stopping before execution.
 */
class RemoteRequestHandlerTest {

    private class FakeEnvironment : AgentToolEnvironment {
        val calls = mutableListOf<String>()
        override fun listInstalledPackages(thirdPartyOnly: Boolean) = "packages".also { calls += "list" }
        override fun deviceProperty(command: String) = "device".also { calls += "deviceProperty" }
        override fun batteryStatus() = "battery".also { calls += "battery" }
        override fun networkStatus() = "network".also { calls += "network" }
        override fun screenBrightness() = "brightness".also { calls += "brightness" }
        override fun displaySize() = "size".also { calls += "size" }
        override suspend fun runLinux(command: String) = "linux".also { calls += "runLinux($command)" }
        override suspend fun openApp(target: String) = "app".also { calls += "openApp($target)" }
        override suspend fun openUrl(url: String) = "url".also { calls += "openUrl($url)" }
        override suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String) =
            "local".also { calls += "useLocalApp" }
    }

    private var clock = 1_000_000L

    private fun pairedService(): Pair<String, RemoteControlService> {
        val service = RemoteControlService(now = { clock })
        val session = service.startPairing(deviceId = "desktop-1", deviceName = "Owner PC")
        val keyId = "key-1"
        assertEquals(RemoteAuthentication.Authenticated, service.completePairing(session.code, keyId))
        return keyId to service
    }

    private fun signedRequest(
        service: RemoteControlService,
        keyId: String,
        toolName: String,
        argumentsJson: String = "{}",
        risk: RemoteRisk = RemoteRisk.LOW,
        nonce: String = "nonce-1",
    ): SignedRemoteRequest = service.sign(
        keyId = keyId,
        deviceId = "desktop-1",
        action = RemoteAction(toolName = toolName, argumentsJson = argumentsJson, risk = risk),
        timestampMs = clock,
        nonce = nonce,
    )

    @Test
    fun `an approved authenticated request is actually executed`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val outcome = RemoteRequestHandler(service).handle(
            signedRequest(service, keyId, "battery_status"),
            env,
            ownerDecision = RemoteApprovalDecision.APPROVED,
        )
        assertTrue(outcome.executed)
        assertEquals("battery_status", outcome.toolName)
        assertEquals(listOf("battery"), env.calls)
    }

    @Test
    fun `owner denial stops before execution`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val outcome = RemoteRequestHandler(service).handle(
            signedRequest(service, keyId, "battery_status"),
            env,
            ownerDecision = RemoteApprovalDecision.DENIED,
        )
        assertFalse(outcome.executed)
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `a missing owner decision times out and does not execute`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val outcome = RemoteRequestHandler(service).handle(
            signedRequest(service, keyId, "battery_status"),
            env,
            ownerDecision = null,
            approvalTimeoutMs = 0,
        )
        assertFalse(outcome.executed)
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `an unpaired key is rejected before approval and execution`() = runBlocking {
        val (_, service) = pairedService()
        val env = FakeEnvironment()
        val forged = SignedRemoteRequest(
            keyId = "not-paired",
            deviceId = "desktop-1",
            action = RemoteAction("battery_status", "{}", RemoteRisk.LOW),
            timestampMs = clock,
            nonce = "n1",
            signatureHex = "00",
        )
        val outcome = RemoteRequestHandler(service).handle(forged, env, RemoteApprovalDecision.APPROVED)
        assertFalse(outcome.executed)
        assertTrue(outcome.detail.contains("Rejected"))
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `a replayed nonce is rejected and executes nothing`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val request = signedRequest(service, keyId, "battery_status", nonce = "replay-me")
        val first = RemoteRequestHandler(service).handle(request, env, RemoteApprovalDecision.APPROVED)
        val second = RemoteRequestHandler(service).handle(request, env, RemoteApprovalDecision.APPROVED)
        assertTrue(first.executed)
        assertFalse(second.executed)
        assertTrue(second.detail.contains("Rejected"))
        assertEquals(listOf("battery"), env.calls)
    }

    @Test
    fun `the kill switch blocks remote execution`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val request = signedRequest(service, keyId, "battery_status")
        service.engageKillSwitch()
        val outcome = RemoteRequestHandler(service).handle(request, env, RemoteApprovalDecision.APPROVED)
        assertFalse(outcome.executed)
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `an unknown tool is refused before the owner is asked`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val outcome = RemoteRequestHandler(service).handle(
            signedRequest(service, keyId, "format_the_disk"),
            env,
            ownerDecision = RemoteApprovalDecision.APPROVED,
        )
        assertFalse(outcome.executed)
        assertTrue(outcome.detail.contains("Unknown tool"))
        assertTrue(env.calls.isEmpty())
    }

    @Test
    fun `a tampered signature is rejected`() = runBlocking {
        val (keyId, service) = pairedService()
        val env = FakeEnvironment()
        val request = signedRequest(service, keyId, "battery_status")
        val tampered = request.copy(action = request.action.copy(toolName = "screen_brightness"))
        val outcome = RemoteRequestHandler(service).handle(tampered, env, RemoteApprovalDecision.APPROVED)
        assertFalse(outcome.executed)
        assertTrue(env.calls.isEmpty())
    }
}
