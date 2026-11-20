package com.opendroid.ai.core.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area 5: a denied tool call is a deliberate owner decision, not a failure.
 *
 * It must be classified as a denial (so the loop reports "denied", not "failed"),
 * and it must not count toward the stop-after-two-consecutive-failures rule.
 * Real failures still stop the loop after two in a row.
 */
class ToolOutcomeDenialTest {

    @Test
    fun `only the exact denial string is classified as a user denial`() {
        assertTrue(ToolOutcome.isUserDenial("user denied"))
        assertTrue(ToolOutcome.isUserDenial("User Denied"))
        assertTrue(ToolOutcome.isUserDenial("  user denied  "))

        assertFalse(ToolOutcome.isUserDenial(null))
        assertFalse(ToolOutcome.isUserDenial(""))
        assertFalse(ToolOutcome.isUserDenial("User approval required"))
        assertFalse(ToolOutcome.isUserDenial("Action blocked by Kill Switch"))
        assertFalse(ToolOutcome.isUserDenial("connector failed"))
    }

    @Test
    fun `denials never count toward the consecutive-failure stop`() {
        val budget = AgentLoopBudget(maxSteps = 12, startedAtNanos = 0L, timeoutMillis = 600_000L)
        assertFalse(budget.recordToolFailure(ToolOutcome.isUserDenial("user denied")))
        assertFalse(budget.recordToolFailure(ToolOutcome.isUserDenial("user denied")))
        assertFalse(budget.recordToolFailure(ToolOutcome.isUserDenial("user denied")))
    }

    @Test
    fun `two real failures still stop the loop but a denial in between resets nothing`() {
        val budget = AgentLoopBudget(maxSteps = 12, startedAtNanos = 0L, timeoutMillis = 600_000L)
        assertFalse(budget.recordToolFailure(ToolOutcome.isUserDenial("connector failed")))
        // A denial between failures must not count as a failure.
        assertFalse(budget.recordToolFailure(ToolOutcome.isUserDenial("user denied")))
        assertTrue(budget.recordToolFailure(ToolOutcome.isUserDenial("connector failed")))
    }

    @Test
    fun `a success resets the consecutive-failure counter`() {
        val budget = AgentLoopBudget(maxSteps = 12, startedAtNanos = 0L, timeoutMillis = 600_000L)
        assertFalse(budget.recordToolFailure(userDenied = false))
        budget.recordToolSuccess()
        assertFalse(budget.recordToolFailure(userDenied = false))
    }

    @Test
    fun `the registry denial message is the one the loop classifies`() {
        assertEquals(ToolOutcome.USER_DENIAL_ERROR, "user denied")
        assertTrue(ToolOutcome.isUserDenial(ToolOutcome.USER_DENIAL_ERROR))
    }
}
