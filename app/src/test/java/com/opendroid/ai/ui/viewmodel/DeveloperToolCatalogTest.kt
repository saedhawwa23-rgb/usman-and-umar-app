package com.opendroid.ai.ui.viewmodel

import com.opendroid.ai.core.connector.ConnectorRisk
import com.opendroid.ai.core.connector.ConnectorTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperToolCatalogTest {
    @Test
    fun `groups compatible registry tools alphabetically and retains empty categories`() {
        val tools = listOf(
            ConnectorTool("local_android_actions", "zebra", "Zebra action", "{}", category = "Android & Phone"),
            ConnectorTool("local_android_actions", "alpha", "Alpha action", "{}", category = "Android & Phone"),
            ConnectorTool("disabled", "not_for_device", "Unavailable", "{}", category = "Files & Storage", compatible = false),
            ConnectorTool("cloud", "list", "List projects", "{}", risk = ConnectorRisk.READ_ONLY, category = "Cloud & APIs")
        )

        val catalog = DeveloperToolCatalog.group(tools)
        assertEquals(DeveloperToolCatalog.categories.sortedBy { it.lowercase() }, catalog.map { it.name })
        val phoneTools = catalog.single { it.name == "Android & Phone" }.tools
        assertEquals(listOf("alpha", "zebra"), phoneTools.map { it.name })
        assertEquals(1, catalog.single { it.name == "Cloud & APIs" }.tools.size)
        assertTrue(catalog.single { it.name == "Files & Storage" }.tools.isEmpty())
        assertTrue(catalog.any { it.name == "Git & Version Control" && it.tools.isEmpty() })
    }
}
