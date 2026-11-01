package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartLocalAiRouterTest {
    private fun app(name: String, packageName: String = name.lowercase().replace(' ', '.')) =
        InstalledAiAppDiscovery.InstalledAiApp(packageName, name, true)

    private val installed = listOf(
        app("ChatGPT"),
        app("Gemini"),
        app("Claude"),
        app("DeepSeek"),
        app("Grok"),
        app("Manus"),
    )

    @Test
    fun coding_prefers_coding_specialist() {
        assertEquals("Claude", SmartLocalAiRouter.select("Write a Python script", installed)?.displayName)
    }

    @Test
    fun research_prefers_research_assistant() {
        assertEquals("Gemini", SmartLocalAiRouter.select("Summarize the latest news with sources", installed)?.displayName)
    }

    @Test
    fun tool_task_prefers_tool_oriented_agent() {
        assertEquals("Grok", SmartLocalAiRouter.select("Browse this website and automate the tool", installed)?.displayName)
    }

    @Test
    fun unavailable_preferred_app_falls_back_to_available_candidate() {
        val available = listOf(app("Gemini"), app("Manus"))
        assertEquals("Gemini", SmartLocalAiRouter.select("Write a Python script", available)?.displayName)
    }

    @Test
    fun empty_inventory_returns_no_route() {
        assertNull(SmartLocalAiRouter.select("research this", emptyList()))
    }
}
