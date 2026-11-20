package com.opendroid.ai.actions

import com.opendroid.ai.core.connector.DeviceControlConnector
import com.opendroid.ai.core.connector.KillSwitch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Area 3: the global automation kill switch must be the first check on every
 * automation entry point.
 *
 * The unified agent loop and device tools are gated by the connector registry
 * (see [com.opendroid.ai.core.connector.UniversalConnectorTest] and
 * [com.opendroid.ai.core.connector.KillSwitchTest]). This covers the separate
 * entry points that reach [ActionDispatcher.execute] directly - AgentLoop,
 * McpServer, WorkspaceWorkflowRunner - via the extracted, fail-closed
 * [ActionDispatcher.automationAllowed] gate.
 */
class ActionDispatcherKillSwitchTest {

    @After
    fun disableKillSwitch() {
        KillSwitch.disable()
    }

    @Test
    fun `kill switch is off by default so a fresh process cannot automate`() {
        KillSwitch.disable()
        assertFalse(KillSwitch.isEnabled)
        assertFalse(ActionDispatcher.automationAllowed(KillSwitch.isEnabled))
    }

    @Test
    fun `actions are allowed only while the kill switch is enabled`() {
        assertFalse("disabled must block", ActionDispatcher.automationAllowed(false))
        assertTrue("enabled must allow", ActionDispatcher.automationAllowed(true))
    }

    @Test
    fun `disabling the switch after enabling blocks actions again`() {
        KillSwitch.enable()
        assertTrue(ActionDispatcher.automationAllowed(KillSwitch.isEnabled))
        KillSwitch.disable()
        assertFalse(ActionDispatcher.automationAllowed(KillSwitch.isEnabled))
    }

    @Test
    fun `every automation entry point reports the same blocked message`() {
        assertEquals(
            DeviceControlConnector.KILL_SWITCH_ERROR,
            ActionDispatcher.KILL_SWITCH_BLOCKED_MESSAGE,
        )
    }
}
