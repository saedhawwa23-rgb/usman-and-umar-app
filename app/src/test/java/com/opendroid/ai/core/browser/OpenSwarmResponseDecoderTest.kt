package com.opendroid.ai.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSwarmResponseDecoderTest {
    @Test
    fun `verified final report yields only human-readable output`() {
        val response = OpenSwarmResponseDecoder.decode(
            """{"summary":"Create a report","verified":true,"results":[{"agent_id":"research","status":"EXECUTED","verified":true,"output":"Report is ready."}]}""",
        )

        assertEquals(OpenSwarmResponse.Completed("Report is ready."), response)
    }

    @Test
    fun `control only report is planning progress and never completion`() {
        val response = OpenSwarmResponseDecoder.decode(
            """{"status":"CONTROL_ONLY","verified":false,"output":"internal plan JSON"}""",
        )

        assertEquals(
            OpenSwarmResponse.InProgress("Manager Agent is planning the task...", "CONTROL_ONLY"),
            response,
        )
        assertFalse(response is OpenSwarmResponse.Completed)
    }

    @Test
    fun `unverified report is progress even when it contains apparent output`() {
        val response = OpenSwarmResponseDecoder.decode(
            """{"status":"RUNNING","verified":false,"output":"not a final answer"}""",
        )

        assertEquals(
            OpenSwarmResponse.InProgress("Manager Agent is working on the task...", "RUNNING"),
            response,
        )
        assertFalse(response is OpenSwarmResponse.Completed)
    }

    @Test
    fun `verified manager summary without specialist output is not treated as final answer`() {
        val response = OpenSwarmResponseDecoder.decode(
            """{"status":"COMPLETED","verified":true,"summary":"Manager planned the task","results":[]}""",
        )

        assertEquals(
            OpenSwarmResponse.Failed("OpenSwarm could not provide a verified final result.", "MISSING_FINAL_OUTPUT"),
            response,
        )
    }

    @Test
    fun `malformed JSON is a clean failure rather than a raw response`() {
        val response = OpenSwarmResponseDecoder.decode("""{"status":"COMPLETED","verified":true,"output":}""")

        assertTrue(response is OpenSwarmResponse.Failed)
        val failure = response as OpenSwarmResponse.Failed
        assertEquals("MALFORMED_RESPONSE", failure.status)
        assertFalse(failure.message.contains("status"))
        assertFalse(failure.message.contains("{"))
    }

    @Test
    fun `failed JSON response exposes concise message but never the raw JSON dump`() {
        val response = OpenSwarmResponseDecoder.decode(
            """{"status":"FAILED","error":{"message":"Runtime unavailable"},"trace":{"token":"secret"}}""",
        )

        assertEquals(OpenSwarmResponse.Failed("Runtime unavailable", "FAILED"), response)
        val unsafe = OpenSwarmResponseDecoder.cleanFailureMessage("""{"error":"raw dump","token":"secret"}""")
        assertEquals("The OpenSwarm task could not be completed.", unsafe)
        assertFalse(unsafe.contains("raw dump"))
        assertFalse(unsafe.contains("secret"))
        val quotedJson = OpenSwarmResponseDecoder.decode(
            """{"status":"FAILED","error":"{\"debug\":\"private\"}"}""",
        ) as OpenSwarmResponse.Failed
        assertFalse(quotedJson.message.contains("private"))
    }

    @Test
    fun `legacy plain text remains distinguishable but is not verified completion`() {
        val response = OpenSwarmResponseDecoder.decode("Legacy browser result")

        assertEquals(OpenSwarmResponse.LegacyText("Legacy browser result"), response)
        assertFalse(response is OpenSwarmResponse.Completed)
    }
}
