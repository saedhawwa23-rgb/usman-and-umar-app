package com.opendroid.ai.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolPolicyTest {
    @Test
    fun `only local arithmetic is exposed to MCP`() {
        assertTrue(McpToolPolicy.isActionAllowed("CALCULATE"))
        assertFalse(McpToolPolicy.isActionAllowed("CONVERT_UNITS"))
        assertFalse(McpToolPolicy.isActionAllowed("WEB_SEARCH"))
        assertFalse(McpToolPolicy.isActionAllowed("GET_CLIPBOARD"))
        assertFalse(McpToolPolicy.isActionAllowed("SEND_SMS"))
        assertFalse(McpToolPolicy.isActionAllowed("DELETE_FILE"))
        assertFalse(McpToolPolicy.isActionAllowed("RUN_PRIVILEGED_COMMAND"))
        assertFalse(McpToolPolicy.isActionAllowed("TERMINAL_WRITE"))
    }

    @Test
    fun `calculator respects precedence parentheses and unary signs`() {
        assertEquals(14.0, McpToolPolicy.calculate("2 + 3 * 4"), 0.0)
        assertEquals(20.0, McpToolPolicy.calculate("(2 + 3) * 4"), 0.0)
        assertEquals(-3.0, McpToolPolicy.calculate("1 - 2 * 2"), 0.0)
        assertEquals(2.5, McpToolPolicy.calculate("10 / 4"), 0.0)
    }

    @Test
    fun `calculator rejects malformed non-finite and oversized expressions`() {
        assertFalse(runCatching { McpToolPolicy.calculate("1 / 0") }.isSuccess)
        assertFalse(runCatching { McpToolPolicy.calculate("1; rm -rf /") }.isSuccess)
        assertFalse(runCatching { McpToolPolicy.calculate("9".repeat(300)) }.isSuccess)
    }
}
