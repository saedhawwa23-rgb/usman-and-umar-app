package com.opendroid.ai.core.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUseRemoteProtocolTest {
    @Test
    fun fmhyRequestUsesPluginAndTrimsPrompt() {
        val request = BrowserUseRemoteProtocol.buildRequestJson(
            taskId = "browser-123",
            prompt = "  Find an image generator on FMHY.  ",
            useFmhy = true,
        )

        assertEquals("browser-123", request.getString("task_id"))
        assertEquals("Find an image generator on FMHY.", request.getString("prompt"))
        assertEquals(BrowserUseRemoteProtocol.FMHY_PLUGIN_ID, request.getString("plugin_id"))
        assertEquals(15, request.getInt("browser_max_steps"))
    }

    @Test
    fun genericRequestUsesBrowserPluginWhenFmhyIsDisabled() {
        val request = BrowserUseRemoteProtocol.buildRequestJson(
            taskId = "browser-456",
            prompt = "Read https://example.com",
            useFmhy = false,
            maxSteps = 4,
        )

        assertEquals(BrowserUseRemoteProtocol.GENERIC_PLUGIN_ID, request.getString("plugin_id"))
        assertEquals(4, request.getInt("browser_max_steps"))
    }

    @Test
    fun requestedStepBudgetIsClampedToTheBackendMaximum() {
        val tooLarge = BrowserUseRemoteProtocol.buildRequestJson("task-a", "task", false, 99)
        val tooSmall = BrowserUseRemoteProtocol.buildRequestJson("task-b", "task", false, 0)

        assertEquals(15, tooLarge.getInt("browser_max_steps"))
        assertEquals(1, tooSmall.getInt("browser_max_steps"))
    }

    @Test
    fun successResponsePrefersResultAndReadsStepCount() {
        val parsed = BrowserUseRemoteProtocol.parseSuccess(
            JSONObject()
                .put("result", "Generated output")
                .put("steps", 7)
                .toString(),
        )

        assertEquals("Generated output", parsed.result)
        assertEquals(7, parsed.steps)
    }

    @Test
    fun successResponseFallsBackToOutputAndRawText() {
        val output = BrowserUseRemoteProtocol.parseSuccess("{\"output\":\"Fallback output\"}")
        val raw = BrowserUseRemoteProtocol.parseSuccess("plain backend result")

        assertEquals("Fallback output", output.result)
        assertEquals("plain backend result", raw.result)
        assertNull(output.steps)
    }

    @Test(expected = IllegalStateException::class)
    fun successfulHttpWithErrorPayloadIsNotAcceptedAsTaskOutput() {
        BrowserUseRemoteProtocol.parseSuccess("{\"error\":\"tool unavailable\"}")
    }

    @Test
    fun errorResponseIncludesServerDiagnosticWithoutInventingSuccess() {
        val error = BrowserUseRemoteProtocol.httpError(422, "{\"error\":\"FMHY tool was unavailable\"}")

        assertTrue(error.contains("HTTP 422"))
        assertTrue(error.contains("FMHY tool was unavailable"))
        assertTrue(!error.contains("completed"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankPromptIsRejectedBeforeNetworkRequest() {
        BrowserUseRemoteProtocol.buildRequestJson("task", "   ", useFmhy = true)
    }
}
