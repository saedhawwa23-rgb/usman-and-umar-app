package com.opendroid.ai.core.agentplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL rule is security-relevant: it is what stops a credential reaching a log line and what
 * stops a key being sent in cleartext. Each branch is asserted directly.
 */
class PlatformUrlPolicyTest {

    private fun reject(url: String): String {
        val verdict = PlatformUrlPolicy.check(url, "blank")
        assertTrue("Expected rejection for '$url', got $verdict", verdict is PlatformUrlPolicy.Verdict.Rejected)
        return (verdict as PlatformUrlPolicy.Verdict.Rejected).message
    }

    private fun accept(url: String) {
        val verdict = PlatformUrlPolicy.check(url, "blank")
        assertTrue("Expected acceptance for '$url', got $verdict", verdict is PlatformUrlPolicy.Verdict.Accepted)
    }

    @Test
    fun `blank input uses the caller's own message`() {
        assertEquals("blank", reject("   "))
    }

    @Test
    fun `an unparseable url is rejected`() {
        assertTrue(reject("not a url").contains("valid server URL"))
    }

    @Test
    fun `embedded credentials are rejected so a key cannot leak`() {
        val message = reject("https://user:secret@example.com")
        assertTrue(message.contains("embedded credentials"))
        assertFalse("The rejection must not echo the credential", message.contains("secret"))
    }

    @Test
    fun `cleartext http is rejected for a remote host`() {
        assertTrue(reject("http://example.com").contains("HTTPS"))
    }

    @Test
    fun `cleartext http is accepted on loopback for local development`() {
        accept("http://localhost:8000")
        accept("http://127.0.0.1:8000")
        accept("http://10.0.2.2:8000")
    }

    @Test
    fun `https is accepted`() {
        accept("https://agent.example.com")
    }

    @Test
    fun `display safety refuses a url carrying credentials`() {
        assertTrue(PlatformUrlPolicy.isSafeToDisplay("https://agent.example.com"))
        assertFalse(PlatformUrlPolicy.isSafeToDisplay("https://user:secret@agent.example.com"))
        assertFalse(PlatformUrlPolicy.isSafeToDisplay("nonsense"))
    }
}
