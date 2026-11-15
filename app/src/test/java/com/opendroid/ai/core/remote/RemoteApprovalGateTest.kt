package com.opendroid.ai.core.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApprovalGateTest {

    private val pending = RemoteApprovalGate.Pending("target-1", "battery_status", RemoteRisk.LOW, "summary")

    @Test
    fun `approval returns true only after the owner approves`() {
        val gate = RemoteApprovalGate()
        val result = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val thread = Thread {
            ready.countDown()
            result.set(gate.requestApproval(pending, timeoutMs = 5_000))
        }
        thread.start()
        ready.await()
        // Wait until the gate is actually showing the request before answering it.
        while (gate.current.value == null) Thread.sleep(5)
        gate.decide(true)
        thread.join(5_000)

        assertTrue(result.get())
        assertNull(gate.current.value)
    }

    @Test
    fun `denial returns false`() {
        val gate = RemoteApprovalGate()
        val result = AtomicBoolean(true)
        val thread = Thread { result.set(gate.requestApproval(pending, timeoutMs = 5_000)) }
        thread.start()
        while (gate.current.value == null) Thread.sleep(5)
        gate.decide(false)
        thread.join(5_000)

        assertFalse(result.get())
    }

    @Test
    fun `no answer within the timeout is a deny`() {
        val gate = RemoteApprovalGate()

        val approved = gate.requestApproval(pending, timeoutMs = 50)

        assertFalse(approved)
        assertNull(gate.current.value)
    }

    @Test
    fun `the current request is cleared after a decision`() {
        val gate = RemoteApprovalGate()
        val thread = Thread { gate.requestApproval(pending, timeoutMs = 5_000) }
        thread.start()
        while (gate.current.value == null) Thread.sleep(5)
        gate.decide(false)
        thread.join(5_000)

        assertNull(gate.current.value)
    }

    @Test
    fun `the gate survives a second sequential request`() {
        val gate = RemoteApprovalGate()
        assertFalse(gate.requestApproval(pending, timeoutMs = 20))
        assertFalse(gate.requestApproval(pending, timeoutMs = 20))
    }

    @Test
    fun `waiting callers unblock promptly when the owner decides`() {
        val gate = RemoteApprovalGate()
        val approved = AtomicBoolean(false)
        val thread = Thread { approved.set(gate.requestApproval(pending, timeoutMs = 10_000)) }
        val start = System.currentTimeMillis()
        thread.start()
        while (gate.current.value == null) Thread.sleep(5)
        gate.decide(true)
        thread.join(TimeUnit.SECONDS.toMillis(5))

        assertTrue(approved.get())
        assertTrue("decide() should unblock immediately, not after the timeout", System.currentTimeMillis() - start < 5_000)
    }
}
