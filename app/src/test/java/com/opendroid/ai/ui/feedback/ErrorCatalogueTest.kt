package com.opendroid.ai.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCatalogueTest {

    @Test
    fun `every catalogued error names a category, a message, and one action`() {
        val entries = listOf(
            ErrorCatalogue.pluginNotApproved,
            ErrorCatalogue.remoteNotPaired,
            ErrorCatalogue.remoteKillSwitch,
            ErrorCatalogue.providerProtocolMismatch,
        )

        entries.forEach { error ->
            assertNotNull(error.category)
            assertTrue(error.titleKey.isNotBlank())
            assertTrue(error.messageKey.isNotBlank())
            assertNotNull("${error.messageKey} must offer exactly one action", error.action)
            assertTrue(error.action!!.labelKey.isNotBlank())
        }
    }

    @Test
    fun `technical detail is absent by default so it stays collapsed`() {
        assertNull(ErrorCatalogue.pluginNotApproved.technicalDetail)
        assertNull(ErrorCatalogue.remoteNotPaired.technicalDetail)
    }

    @Test
    fun `technical detail can be attached without changing the message or action`() {
        val withDetail = ErrorCatalogue.of(
            ErrorCategory.RUNTIME,
            "error_runtime_title",
            "error_runtime_failed",
            "copy_logs",
            "action_copy_logs",
            technicalDetail = "dpkg: error processing package python3.8-minimal (--configure):",
        )

        assertEquals(ErrorCategory.RUNTIME, withDetail.category)
        assertEquals("copy_logs", withDetail.action?.actionId)
        assertEquals("dpkg: error processing package python3.8-minimal (--configure):", withDetail.technicalDetail)
    }

    @Test
    fun `an error with only an id and no label has no action`() {
        val noAction = ErrorCatalogue.of(ErrorCategory.UNKNOWN, "t", "m")
        assertNull(noAction.action)
    }
}
