package com.opendroid.ai.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTargetRoutingTest {
    @Test
    fun `OpenSwarm plugin bypasses API manual target`() {
        assertEquals(
            ChatTarget.CLOUD_OPENSWARM,
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
            ChatTarget.CLOUD_OPENSWARM,
            resolveChatTarget(ChatTarget.CLOUD_OPENSWARM, emptyList()),
        )
    }
}
