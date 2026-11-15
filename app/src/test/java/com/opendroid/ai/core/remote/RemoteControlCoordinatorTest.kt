package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the coordinator against the real [RemoteControlService] and the real in-memory key
 * store - no mocks. The coordinator must not weaken any check the core performs.
 */
class RemoteControlCoordinatorTest {

    private var clock = 5_000_000L
    private val service = RemoteControlService(keyStore = InMemoryDeviceKeyStore(), now = { clock })
    private val coordinator = RemoteControlCoordinator(service, clock = { clock })

    @Test
    fun `startPairing returns a six digit code bound to the device`() {
        val start = coordinator.startPairing("target-1", "My Phone")

        assertEquals(6, start.code.length)
        assertTrue(start.code.all { it.isDigit() })
        assertEquals("target-1", start.deviceId)
        assertEquals("My Phone", start.deviceName)
        assertTrue(start.expiresAtMs > clock)
    }

    @Test
    fun `completing pairing with the shown code pairs the device`() {
        val start = coordinator.startPairing("target-1", "My Phone")

        val result = coordinator.completePairing(start.code, "key-1")

        assertTrue(result is RemoteControlCoordinator.StepResult.Success)
        assertEquals(listOf("key-1"), coordinator.pairedKeyIds())
    }

    @Test
    fun `completing pairing accepts surrounding whitespace in the code`() {
        val start = coordinator.startPairing("target-1", "My Phone")

        val result = coordinator.completePairing("  ${start.code}  ", "key-1")

        assertTrue(result is RemoteControlCoordinator.StepResult.Success)
    }

    @Test
    fun `a code that is not six digits is rejected before reaching the core`() {
        val tooShort = coordinator.completePairing("123", "key-1")
        val nonNumeric = coordinator.completePairing("12a456", "key-1")

        assertTrue(tooShort is RemoteControlCoordinator.StepResult.Rejected)
        assertTrue(nonNumeric is RemoteControlCoordinator.StepResult.Rejected)
        assertTrue(coordinator.pairedKeyIds().isEmpty())
    }

    @Test
    fun `an expired pairing code is rejected with the core's reason`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        clock += RemoteControlService.PAIRING_CODE_TTL_MS + 1

        val result = coordinator.completePairing(start.code, "key-1")

        assertEquals(
            RemoteControlService.EXPIRED_PAIRING_CODE,
            (result as RemoteControlCoordinator.StepResult.Rejected).reason,
        )
    }

    @Test
    fun `a pairing code can be used only once`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        coordinator.completePairing(start.code, "key-1")

        val second = coordinator.completePairing(start.code, "key-2")

        assertTrue(second is RemoteControlCoordinator.StepResult.Rejected)
        assertEquals(listOf("key-1"), coordinator.pairedKeyIds())
    }

    @Test
    fun `unpair deletes the key and unknown devices are reported`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        coordinator.completePairing(start.code, "key-1")

        val removed = coordinator.unpair("key-1")
        val again = coordinator.unpair("key-1")

        assertTrue(removed is RemoteControlCoordinator.StepResult.Success)
        assertTrue(again is RemoteControlCoordinator.StepResult.Rejected)
        assertTrue(coordinator.pairedKeyIds().isEmpty())
    }

    @Test
    fun `paired key ids are returned in a stable sorted order`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        coordinator.completePairing(start.code, "key-b")
        val start2 = coordinator.startPairing("target-2", "Other Phone")
        coordinator.completePairing(start2.code, "key-a")

        assertEquals(listOf("key-a", "key-b"), coordinator.pairedKeyIds())
    }

    @Test
    fun `the kill switch disables the coordinator and reports its state`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        coordinator.completePairing(start.code, "key-1")

        val result = coordinator.engageKillSwitch()

        assertTrue(result is RemoteControlCoordinator.StepResult.Success)
        assertTrue(coordinator.isKillSwitchActive())
    }

    @Test
    fun `kill switch makes an otherwise valid request unauthenticatable`() {
        val start = coordinator.startPairing("target-1", "My Phone")
        coordinator.completePairing(start.code, "key-1")
        val request = service.sign(
            keyId = "key-1",
            deviceId = "target-1",
            action = RemoteAction("device.read_screen", "{}", RemoteRisk.LOW),
            timestampMs = clock,
            nonce = "nonce-1",
        )
        coordinator.engageKillSwitch()

        val auth = service.authenticate(request)

        assertEquals(
            RemoteControlService.KILL_SWITCH_ACTIVE,
            (auth as RemoteAuthentication.Rejected).reason,
        )
    }

    @Test
    fun `seconds remaining counts down and never goes negative`() {
        val start = coordinator.startPairing("target-1", "My Phone")

        val atStart = coordinator.secondsRemaining(start.expiresAtMs)
        clock = start.expiresAtMs + 10_000
        val afterExpiry = coordinator.secondsRemaining(start.expiresAtMs)

        assertEquals(RemoteControlService.PAIRING_CODE_TTL_MS / 1000, atStart)
        assertEquals(0L, afterExpiry)
        assertFalse(atStart < 0)
    }
}
