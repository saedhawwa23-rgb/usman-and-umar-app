package com.opendroid.ai.ui.screens.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBarModelTest {

    @Test
    fun `there are exactly three sections in the documented order`() {
        val ids = ToolBarModel.sections().map { it.id }
        assertEquals(listOf(ToolSectionId.AI_AGENTS, ToolSectionId.GITHUB, ToolSectionId.LINUX), ids)
    }

    @Test
    fun `every section has a title, a subtitle and at least one entry`() {
        ToolBarModel.sections().forEach { section ->
            assertTrue(section.title.isNotBlank())
            assertTrue(section.subtitle.isNotBlank())
            assertTrue("${section.id} has no entries", section.entries.isNotEmpty())
        }
    }

    @Test
    fun `entry ids are unique so navigation keys cannot collide`() {
        val ids = ToolBarModel.sections().flatMap { it.entries }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `no entry claims to be ready while it needs Termux`() {
        val resolved = ToolBarModel.resolvedSections(ToolBarContext(termuxInstalled = false))
        val termuxEntries = resolved
            .first { it.id == ToolSectionId.LINUX }
            .entries
        termuxEntries.forEach {
            assertEquals("${it.id} wrongly reads ready", ToolAvailability.NEEDS_TERMUX, it.availability)
        }
    }

    @Test
    fun `cloud entries stay needs-setup until a server is configured`() {
        val without = ToolBarModel.resolvedSections(ToolBarContext(cloudServerConfigured = false))
        val cloudEntry = without.flatMap { it.entries }.first { it.id == "ai.cloud-agent" }
        assertEquals(ToolAvailability.NEEDS_SETUP, cloudEntry.availability)

        val with = ToolBarModel.resolvedSections(ToolBarContext(cloudServerConfigured = true))
        val cloudReady = with.flatMap { it.entries }.first { it.id == "ai.cloud-agent" }
        assertEquals(ToolAvailability.READY, cloudReady.availability)
    }

    @Test
    fun `termux entries become ready once Termux is present`() {
        val resolved = ToolBarModel.resolvedSections(ToolBarContext(termuxInstalled = true))
        val clone = resolved.flatMap { it.entries }.first { it.id == "github.clone" }
        assertEquals(ToolAvailability.READY, clone.availability)
    }

    @Test
    fun `read only entries are never upgraded to a mutating claim`() {
        val resolved = ToolBarModel.resolvedSections(
            ToolBarContext(termuxInstalled = true, cloudServerConfigured = true, githubTokenConnected = true)
        )
        val search = resolved.flatMap { it.entries }.first { it.id == "github.search" }
        assertEquals(ToolAvailability.READ_ONLY, search.availability)
    }

    @Test
    fun `every availability has a non-blank label`() {
        ToolAvailability.entries.forEach {
            assertTrue(ToolBarModel.availabilityLabel(it).isNotBlank())
        }
    }

    @Test
    fun `no entry description mentions a capability the app does not perform`() {
        // Guard against the "opens a website and calls it integration" failure mode.
        val banned = listOf("opens in browser", "opens the website", "just opens", "simulated")
        ToolBarModel.sections().flatMap { it.entries }.forEach { entry ->
            banned.forEach { phrase ->
                assertFalse(
                    "${entry.id} contains a misleading phrase: $phrase",
                    entry.description.lowercase().contains(phrase),
                )
            }
        }
    }

    @Test
    fun `section lookup by id returns the same object as the list`() {
        ToolSectionId.entries.forEach { id ->
            assertEquals(ToolBarModel.sections().first { it.id == id }, ToolBarModel.section(id))
        }
    }
}
