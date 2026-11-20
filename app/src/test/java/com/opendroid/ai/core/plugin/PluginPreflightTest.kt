package com.opendroid.ai.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real preflight and masking code that the Agent screen and the task-start path use, so
 * an incompatible pairing or a leaked key is caught here rather than on a device.
 */
class PluginPreflightTest {

    @Test
    fun `claude code requires an anthropic endpoint and is refused on openai-completions`() {
        val nvidia = AgentProviders.byId("nvidia_nim")!!
        val result = AgentPreflight.check(
            agent = AgentKind.CLAUDE_CODE,
            provider = nvidia,
            apiKeyPresent = true,
            agentInstalled = true,
            agentVerified = true,
        )
        assertTrue(result is PreflightResult.Blocked)
        val blocked = result as PreflightResult.Blocked
        assertEquals(PreflightBlocker.PROTOCOL_MISMATCH, blocked.blocker)
        assertTrue(blocked.message.contains("Anthropic Messages API"))
        assertTrue(blocked.message.contains("openai-completions"))
    }

    @Test
    fun `claude code is ready on the anthropic provider when everything else passes`() {
        val anthropic = AgentProviders.byId("anthropic")!!
        val result = AgentPreflight.check(
            agent = AgentKind.CLAUDE_CODE,
            provider = anthropic,
            apiKeyPresent = true,
            agentInstalled = true,
            agentVerified = true,
        )
        assertTrue(result is PreflightResult.Ready)
    }

    @Test
    fun `antigravity requires the gemini protocol`() {
        val openai = AgentProviders.byId("openai")!!
        val result = AgentPreflight.check(AgentKind.ANTIGRAVITY, openai, true, true, true)
        assertTrue(result is PreflightResult.Blocked)
        assertEquals(PreflightBlocker.PROTOCOL_MISMATCH, (result as PreflightResult.Blocked).blocker)
    }

    @Test
    fun `a missing key is reported before the install check`() {
        val provider = AgentProviders.byId("deepseek")!!
        val result = AgentPreflight.check(AgentKind.DEEPSEEK_HARNESS, provider, apiKeyPresent = false, agentInstalled = false, agentVerified = false)
        assertEquals(PreflightBlocker.MISSING_KEY, (result as PreflightResult.Blocked).blocker)
    }

    @Test
    fun `an unverified installed agent is blocked by name`() {
        val provider = AgentProviders.byId("deepseek")!!
        val result = AgentPreflight.check(AgentKind.DEEPSEEK_HARNESS, provider, true, agentInstalled = true, agentVerified = false)
        assertEquals(PreflightBlocker.AGENT_NOT_VERIFIED, (result as PreflightResult.Blocked).blocker)
    }

    @Test
    fun `every agent has at least one compatible provider`() {
        AgentKind.entries.forEach { agent ->
            assertTrue("no provider for ${agent.displayName}", AgentProviders.compatibleWith(agent).isNotEmpty())
        }
    }

    @Test
    fun `compatible providers only ever expose the agent's own protocol`() {
        AgentKind.entries.forEach { agent ->
            AgentProviders.compatibleWith(agent).forEach { provider ->
                assertEquals(agent.requiredProtocol, provider.protocol)
            }
        }
    }

    @Test
    fun `mask keeps a recognisable prefix and suffix but never the whole key`() {
        val key = "sk-abcdef1234567890"
        val masked = SecretMask.mask(key)
        assertFalse(masked.contains(key))
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.endsWith("7890"))
    }

    @Test
    fun `mask reports an absent key rather than an empty string`() {
        assertEquals("(not set)", SecretMask.mask(null))
        assertEquals("(not set)", SecretMask.mask(""))
        assertEquals("••••", SecretMask.mask("short"))
    }

    @Test
    fun `redact removes a known secret and a bearer token from a log line`() {
        val secret = "sk-live-abcdef123456"
        val line = "calling with api_key=$secret and header Authorization: Bearer $secret"
        val redacted = SecretMask.redact(line, listOf(secret))
        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains("<redacted>"))
    }
}
