package com.opendroid.ai.core.connector

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class KillSwitchTest {
    private val connector = DeviceControlConnector(ContextWrapper(null))
    private val credentials = object : ConnectorCredentialResolver {
        override suspend fun <T> withSecret(ref: ConnectorCredentialRef?, block: suspend (String?) -> T): T =
            block(null)
    }

    @Before
    fun resetKillSwitch() {
        KillSwitch.release()
    }

    @After
    fun releaseKillSwitch() {
        KillSwitch.release()
    }

    @Test
    fun `engaged kill switch blocks device tools before touching Android services`() = runBlocking {
        KillSwitch.engage()
        val readScreen = connector.tools().single { it.name == "device.read_screen" }

        val result = connector.execute(
            ConnectorInvocation(tool = readScreen, argumentsJson = "{}"),
            credentials,
        )

        assertFalse(result.success)
        assertEquals(DeviceControlConnector.KILL_SWITCH_ERROR, result.error)
    }
}
