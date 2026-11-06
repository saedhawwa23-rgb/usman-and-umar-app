package com.opendroid.ai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSelectorOptionsTest {
    @Test
    fun `required cloud and system plugins remain selectable with backend identifiers`() {
        val optionsById = SYSTEM_PLUGIN_OPTIONS.associateBy { it.pluginId }

        assertEquals("FMHY", optionsById["fmhy_browser"]?.label)
        assertEquals("OpenSwarm Agent", optionsById["openswarm_agent"]?.label)
        assertEquals("Web Browser", optionsById["browser_use"]?.label)
        assertEquals("Code Executor", optionsById["code_executor"]?.label)
    }

    @Test
    fun `system plugin options are limited to the four requested tools`() {
        assertEquals(
            listOf("fmhy_browser", "openswarm_agent", "browser_use", "code_executor"),
            SYSTEM_PLUGIN_OPTIONS.map { it.pluginId },
        )
        assertTrue(SYSTEM_PLUGIN_OPTIONS.all { it.pluginId.isNotBlank() })
        assertEquals(
            SYSTEM_PLUGIN_OPTIONS.size,
            SYSTEM_PLUGIN_OPTIONS.map { it.pluginId }.distinct().size,
        )
    }
}
