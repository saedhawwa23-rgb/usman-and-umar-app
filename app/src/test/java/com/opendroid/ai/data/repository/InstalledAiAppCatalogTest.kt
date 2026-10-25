package com.opendroid.ai.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledAiAppCatalogTest {
    @Test
    fun classifiesSupportedAppsFromLabelsAndKnownPackageHints() {
        assertEquals("ChatGPT", InstalledAiAppCatalog.classify("ChatGPT", "vendor.changed.package"))
        assertEquals("Gemini", InstalledAiAppCatalog.classify("Google", "com.google.android.apps.bard"))
        assertEquals("DeepSeek", InstalledAiAppCatalog.classify("DeepSeek", "com.example.ai"))
        assertEquals("Copilot", InstalledAiAppCatalog.classify("Microsoft Copilot", "com.microsoft.example"))
    }

    @Test
    fun excludesUnrelatedApps() {
        assertNull(InstalledAiAppCatalog.classify("Calculator", "com.example.calc"))
    }
}
