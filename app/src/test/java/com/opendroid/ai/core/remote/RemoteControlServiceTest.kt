package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlServiceTest {

    private var clock = 1_000_000L
    private val audit = mutableListOf<RemoteAuditRecord>()

    private fun service(keyStore: DeviceKeyStore = InMemoryDeviceKeyStore()) =
        RemoteControlService(keyStore = keyStore, now = { clock }, auditSink = { audit.add(it) })

    private val action = RemoteAction("device.open_app", "{\"package\":\"com.example\"}", RemoteRisk.HIGH)

    /** Pairs a device and returns (service, keyId, deviceId). */
    private fun paired(): Triple<RemoteControlService, String, String> {
        val svc = service()
        val session = svc.startPairing(deviceId = "target-1", deviceName = "My Phone")
        val result = svc.completePairing(session.code, keyId = "key-1")
        assertTrue(result is RemoteAuthentication.Authenticated)
        return Triple(svc, "key-1", "target-1")
    }

    @Test
    fun `unpaired device is rejected`() {
        val svc = service()
        val request = SignedRemoteRequest("ghost-key", "target-1", action, clock, "nonce-1", "deadbeef")

        val result = svc.authenticate(request)

        assertEquals(RemoteControlService.UNKNOWN_DEVICE, (result as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `expired pairing code is rejected`() {
        val svc = service()
        val session = svc.startPairing("target-1", "My Phone")
        clock += RemoteControlService.PAIRING_CODE_TTL_MS + 1

        val result = svc.completePairing(session.code, "key-1")

        assertEquals(RemoteControlService.EXPIRED_PAIRING_CODE, (result as RemoteAuthentication.Rejected).reason)
        assertTrue(svc.pairedKeyIds().isEmpty())
    }

    @Test
    fun `pairing code is single use`() {
        val (svc, _, _) = paired()
        // The code was consumed; replaying it must fail even within the window.
        val session = svc.startPairing("target-1", "My Phone")
        svc.completePairing(session.code, "key-2")
        val replay = svc.completePairing(session.code, "key-3")
        assertEquals(RemoteControlService.EXPIRED_PAIRING_CODE, (replay as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `replayed request is rejected`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-replay")

        assertTrue(svc.authenticate(signed) is RemoteAuthentication.Authenticated)
        val second = svc.authenticate(signed)

        assertEquals(RemoteControlService.REPLAYED_REQUEST, (second as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `bad signature is rejected`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-bad").copy(signatureHex = "00".repeat(32))

        val result = svc.authenticate(signed)

        assertEquals(RemoteControlService.BAD_SIGNATURE, (result as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `tampered action is rejected because the signature covers it`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-tamper")
        val tampered = signed.copy(action = action.copy(argumentsJson = "{\"package\":\"com.evil\"}"))

        val result = svc.authenticate(tampered)

        assertEquals(RemoteControlService.BAD_SIGNATURE, (result as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `expired timestamp is rejected`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-old")
        clock += RemoteControlService.MAX_CLOCK_SKEW_MS + 1

        val result = svc.authenticate(signed)

        assertEquals(RemoteControlService.EXPIRED_REQUEST, (result as RemoteAuthentication.Rejected).reason)
    }

    @Test
    fun `deny after timeout is applied`() {
        val (svc, _, deviceId) = paired()

        val noDecision = svc.requestApproval(deviceId, action, ownerDecision = null)
        val explicitTimeout = svc.requestApproval(deviceId, action, RemoteApprovalDecision.TIMED_OUT)

        assertEquals(RemoteApprovalDecision.TIMED_OUT, noDecision)
        assertEquals(RemoteApprovalDecision.TIMED_OUT, explicitTimeout)
    }

    @Test
    fun `owner approval is honoured`() {
        val (svc, _, deviceId) = paired()

        assertEquals(RemoteApprovalDecision.APPROVED, svc.requestApproval(deviceId, action, RemoteApprovalDecision.APPROVED))
        assertEquals(RemoteApprovalDecision.DENIED, svc.requestApproval(deviceId, action, RemoteApprovalDecision.DENIED))
    }

    @Test
    fun `kill switch blocks requests and closes sessions`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-kill")

        svc.engageKillSwitch()
        val result = svc.authenticate(signed)

        assertEquals(RemoteControlService.KILL_SWITCH_ACTIVE, (result as RemoteAuthentication.Rejected).reason)
        assertTrue(svc.isKillSwitchActive())
    }

    @Test
    fun `unpair removes keys on both sides`() {
        val keyStore = InMemoryDeviceKeyStore()
        val svc = service(keyStore)
        val session = svc.startPairing("target-1", "My Phone")
        svc.completePairing(session.code, "key-1")
        assertTrue(keyStore.get("key-1") != null)

        assertTrue(svc.unpair("key-1"))

        assertTrue(keyStore.get("key-1") == null)
        assertTrue(svc.pairedKeyIds().isEmpty())
        val signed = SignedRemoteRequest("key-1", "target-1", action, clock, "nonce-x", "00")
        assertEquals(
            RemoteControlService.UNKNOWN_DEVICE,
            (svc.authenticate(signed) as RemoteAuthentication.Rejected).reason,
        )
    }

    @Test
    fun `audit records approval outcome without secrets`() {
        val (svc, keyId, deviceId) = paired()
        val signed = svc.sign(keyId, deviceId, action, clock, "nonce-audit")
        svc.authenticate(signed)
        svc.requestApproval(deviceId, action, RemoteApprovalDecision.DENIED)

        val denial = audit.last { it.action == action.toolName }
        assertEquals(deviceId, denial.deviceId)
        assertEquals("denied", denial.approval)
        assertEquals("not executed", denial.outcome)
        assertFalse(audit.any { it.toString().contains("com.example") })
    }
}
