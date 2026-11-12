package com.opendroid.ai.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTargetRoutingTest {
    @Test
    fun `chat only never routes a Colab request even when a cloud plugin id is selected`() {
        assertEquals(
            ChatTarget.API_MODEL,
            resolveChatTarget(ChatTarget.API_MODEL, listOf("openswarm_agent")),
        )
    }

    @Test
    fun `other plugin selections preserve the chosen target`() {
        assertEquals(
            ChatTarget.API_MODEL,
            resolveChatTarget(ChatTarget.API_MODEL, listOf("github_search")),
        )
        assertEquals(
            ChatTarget.UNIFIED_AGENT,
            resolveChatTarget(ChatTarget.UNIFIED_AGENT, listOf("openswarm_agent")),
        )
    }
}
