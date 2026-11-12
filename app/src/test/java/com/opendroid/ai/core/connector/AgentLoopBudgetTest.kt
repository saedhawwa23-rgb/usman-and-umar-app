package com.opendroid.ai.core.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopBudgetTest {
    @Test
    fun `the twelve-step cap counts model iterations rather than individual tool calls`() {
        val budget = AgentLoopBudget(maxSteps = 99, startedAtNanos = 0L, timeoutMillis = 600_000L)
        repeat(12) { iteration ->
            assertNull(budget.beginStep(iteration * 1_000_000L))
            // Any tool calls emitted by this model iteration do not consume another step.
        }
        assertEquals(12, budget.stepsUsed)
        assertEquals(AgentLoopStopReason.STEP_LIMIT, budget.beginStep(13_000_000L))
    }

    @Test
    fun `the ten-minute deadline is monotonic and checked at model-step boundaries`() {
        val start = 8_000_000_000L
        val budget = AgentLoopBudget(maxSteps = 12, startedAtNanos = start, timeoutMillis = 600_000L)
        assertNull(budget.beginStep(start))
        assertFalse(budget.isTimedOut(start + 599_999_999_999L))
        assertEquals(AgentLoopStopReason.TIME_LIMIT, budget.beginStep(start + 600_000_000_000L))
        assertTrue(budget.isTimedOut(start + 600_000_000_000L))
    }

    @Test
    fun `user denials do not count and two consecutive other failures stop the loop`() {
        val budget = AgentLoopBudget(maxSteps = 12, startedAtNanos = 0L, timeoutMillis = 600_000L)
        assertFalse(budget.recordToolFailure(userDenied = true))
        assertFalse(budget.recordToolFailure(userDenied = false))
        assertFalse(budget.recordToolFailure(userDenied = true))
        assertTrue(budget.recordToolFailure(userDenied = false))
        budget.recordToolSuccess()
        assertFalse(budget.recordToolFailure(userDenied = false))
    }
}
