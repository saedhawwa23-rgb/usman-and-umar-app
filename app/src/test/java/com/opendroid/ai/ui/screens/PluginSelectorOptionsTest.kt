package com.opendroid.ai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSelectorOptionsTest {
    @Test
    fun `required cloud and system plugins remain selectable with backend identifiers`() {
        val optionsById = CLOUD_SYSTEM_PLUGIN_OPTIONS.associateBy { it.pluginId }

        assertEquals("FMHY", optionsById["fmhy_browser"]?.label)
        assertEquals("OpenSwarm Agent", optionsById["openswarm_agent"]?.label)
        assertEquals("Web Browser", optionsById["browser_use"]?.label)
        assertEquals("Code Executor", optionsById["code_executor"]?.label)
    }

    @Test
    fun `plugin options include GitHub search and use unique nonblank identifiers`() {
        assertTrue(CLOUD_SYSTEM_PLUGIN_OPTIONS.any { it.label == "GitHub Search" && it.pluginId == "github_search" })
        assertTrue(CLOUD_SYSTEM_PLUGIN_OPTIONS.all { it.pluginId.isNotBlank() })
        assertEquals(
            CLOUD_SYSTEM_PLUGIN_OPTIONS.size,
            CLOUD_SYSTEM_PLUGIN_OPTIONS.map { it.pluginId }.distinct().size,
        )
    }
}
