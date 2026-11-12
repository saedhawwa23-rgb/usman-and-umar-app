package com.opendroid.ai.core.connector

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KillSwitchTest {
    private val connector = DeviceControlConnector(ContextWrapper(null))
    private val credentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T = block(null)
    }

    @Before
    fun resetKillSwitch() {
        KillSwitch.disable()
    }

    @After
    fun disableKillSwitch() {
        KillSwitch.disable()
    }

    @Test
    fun `global automation is off by default`() {
        assertFalse(KillSwitch.isEnabled)
    }

    @Test
    fun `disabled global gate blocks device tools with exact message before Android access`() = runBlocking {
        val readScreen = connector.tools().single { it.name == "device.read_screen" }
        val result = connector.execute(ConnectorInvocation(tool = readScreen, argumentsJson = "{}"), credentials)
        assertFalse(result.success)
        assertEquals("Action blocked by Kill Switch", result.error)
    }

    @Test
    fun `enabled gate does not bypass missing Accessibility service`() = runBlocking {
        KillSwitch.enable()
        assertTrue(KillSwitch.isEnabled)
        val readScreen = connector.tools().single { it.name == "device.read_screen" }
        val result = connector.execute(ConnectorInvocation(tool = readScreen, argumentsJson = "{}"), credentials)
        assertFalse(result.success)
        assertEquals(DeviceControlConnector.ACCESSIBILITY_ERROR, result.error)
    }
}
