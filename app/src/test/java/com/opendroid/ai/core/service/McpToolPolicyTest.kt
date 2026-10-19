package com.opendroid.ai.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolPolicyTest {
    @Test
    fun `only deterministic local computations are exposed`() {
        assertTrue(McpToolPolicy.isActionAllowed("CALCULATE"))
        assertTrue(McpToolPolicy.isActionAllowed("CONVERT_UNITS"))
        assertFalse(McpToolPolicy.isActionAllowed("WEB_SEARCH"))
        assertFalse(McpToolPolicy.isActionAllowed("GET_CLIPBOARD"))
        assertFalse(McpToolPolicy.isActionAllowed("SEND_SMS"))
        assertFalse(McpToolPolicy.isActionAllowed("DELETE_FILE"))
    }

    @Test
    fun `shell execution tools are not part of the exposed action allowlist`() {
        assertFalse(McpToolPolicy.isActionAllowed("RUN_PRIVILEGED_COMMAND"))
        assertFalse(McpToolPolicy.isActionAllowed("TERMINAL_WRITE"))
    }
}
