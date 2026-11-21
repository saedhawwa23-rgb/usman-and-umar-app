package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RECORD_SCREEN used to report success while doing nothing, which let the agent
 * claim a recording that never happened. These tests pin the honest behaviour.
 */
class RecordScreenActionTest {

    private val context: Context = ContextWrapper(null)

    private fun recordScreen() =
        SystemActions(unusedLazy(), unusedLazy(), unusedLazy())
            .getActions()
            .first { it.name == "RECORD_SCREEN" }

    private fun <T> unusedLazy(): Lazy<T> = Lazy {
        error("This dependency is not used by RECORD_SCREEN")
    }

    @Test
    fun `record screen reports failure because no capture service is bundled`() = runBlocking {
        val result = recordScreen().execute(emptyMap(), context)

        assertFalse("RECORD_SCREEN must not claim success without capturing", result.success)
        assertNull(result.data)
        assertNotNull(result.error)
        assertTrue(
            "Error should name the missing capability, was: ${result.error}",
            result.error!!.contains("not available", ignoreCase = true)
        )
    }

    @Test
    fun `record screen does not claim success when asked to stop`() = runBlocking {
        val result = recordScreen().execute(mapOf("start" to "false"), context)

        assertFalse(result.success)
    }
}
