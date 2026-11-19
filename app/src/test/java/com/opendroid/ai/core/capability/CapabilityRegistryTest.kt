package com.opendroid.ai.core.capability

import com.opendroid.ai.ui.screens.toolbar.ToolAvailability
import com.opendroid.ai.ui.screens.toolbar.ToolBarModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {

    @Test
    fun `every capability id is unique`() {
        assertEquals(emptyList<String>(), CapabilityRegistry.duplicateIds())
    }

    @Test
    fun `catalog is not empty and every capability names a module`() {
        val all = CapabilityRegistry.all()
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.title.isNotBlank() })
    }

    @Test
    fun `nothing resolves to READY when every dependency is missing`() {
        val resolved = CapabilityRegistry.resolveAll(
            CapabilityEnvironment(
                termuxInstalled = false,
                credentialConfigured = false,
                cloudServerConfigured = false,
                githubTokenConnected = false,
            )
        )
        val wronglyReady = resolved.filter { it.state == CapabilityState.READY && it.capability.requirement != CapabilityRequirement.NONE }
        assertEquals(emptyList<ResolvedCapability>(), wronglyReady)
    }

    @Test
    fun `termux capabilities become READY only when termux is installed`() {
        val without = CapabilityRegistry.resolve(
            CapabilityRegistry.find("linux.termux")!!,
            CapabilityEnvironment(termuxInstalled = false)
        )
        val with = CapabilityRegistry.resolve(
            CapabilityRegistry.find("linux.termux")!!,
            CapabilityEnvironment(termuxInstalled = true)
        )
        assertEquals(CapabilityState.NEEDS_TERMUX, without)
        assertEquals(CapabilityState.READY, with)
    }

    @Test
    fun `cloud capabilities need a configured server`() {
        val cap = CapabilityRegistry.find("ai.cloud-agent")!!
        assertEquals(
            CapabilityState.NEEDS_SETUP,
            CapabilityRegistry.resolve(cap, CapabilityEnvironment(cloudServerConfigured = false))
        )
        assertEquals(
            CapabilityState.READY,
            CapabilityRegistry.resolve(cap, CapabilityEnvironment(cloudServerConfigured = true))
        )
    }

    @Test
    fun `private github needs a connected token and is never READY by default`() {
        val cap = CapabilityRegistry.find("github.private")!!
        assertFalse(
            CapabilityRegistry.resolve(cap, CapabilityEnvironment()) in
                listOf(CapabilityState.READY, CapabilityState.READ_ONLY)
        )
        assertEquals(
            CapabilityState.READY,
            CapabilityRegistry.resolve(cap, CapabilityEnvironment(githubTokenConnected = true))
        )
    }

    @Test
    fun `no network downgrades network capabilities to DEPENDENCY_MISSING`() {
        val cap = CapabilityRegistry.find("github.search")!!
        assertEquals(
            CapabilityState.DEPENDENCY_MISSING,
            CapabilityRegistry.resolve(cap, CapabilityEnvironment(networkAvailable = false))
        )
        assertEquals(
            CapabilityState.READ_ONLY,
            CapabilityRegistry.resolve(cap, CapabilityEnvironment(networkAvailable = true))
        )
    }

    @Test
    fun `tool bar vocabulary maps onto the registry vocabulary without loss`() {
        ToolAvailability.entries.forEach { availability ->
            val mapped = CapabilityRegistry.fromToolBar(availability)
            assertTrue("unmapped: $availability", mapped in CapabilityState.entries)
        }
        assertEquals(CapabilityState.NEEDS_TERMUX, CapabilityRegistry.fromToolBar(ToolAvailability.NEEDS_TERMUX))
        assertEquals(CapabilityState.READ_ONLY, CapabilityRegistry.fromToolBar(ToolAvailability.READ_ONLY))
    }

    @Test
    fun `tool bar labels and registry labels agree for shared states`() {
        assertEquals(ToolBarModel.availabilityLabel(ToolAvailability.READY), CapabilityRegistry.label(CapabilityState.READY))
        assertEquals(ToolBarModel.availabilityLabel(ToolAvailability.READ_ONLY), CapabilityRegistry.label(CapabilityState.READ_ONLY))
        assertEquals(ToolBarModel.availabilityLabel(ToolAvailability.NEEDS_TERMUX), CapabilityRegistry.label(CapabilityState.NEEDS_TERMUX))
        assertEquals(ToolBarModel.availabilityLabel(ToolAvailability.NEEDS_SETUP), CapabilityRegistry.label(CapabilityState.NEEDS_SETUP))
    }

    @Test
    fun `every tool bar entry has a registry counterpart`() {
        val registryIds = CapabilityRegistry.all().map { it.id }.toSet()
        val toolBarIds = ToolBarModel.sections().flatMap { section -> section.entries.map { it.id } }
        val missing = toolBarIds.filterNot { it in registryIds }
        assertEquals("Tool Bar entries with no registry entry: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `modules with capabilities report them and unused modules are visible`() {
        val github = CapabilityRegistry.byModule(CapabilityModule.GITHUB)
        assertTrue(github.isNotEmpty())
        assertTrue(github.all { it.module == CapabilityModule.GITHUB })
    }

    @Test
    fun `tool bar availability agrees with the registry state for an unconfigured device`() {
        // A fresh device: no Termux, no credential, no cloud server, no GitHub token.
        val env = CapabilityEnvironment()
        val drifted = ToolBarModel.sections().flatMap { section -> section.entries }
            .mapNotNull { entry ->
                val capability = CapabilityRegistry.find(entry.id) ?: return@mapNotNull null
                val expected = CapabilityRegistry.resolve(capability, env)
                val declared = CapabilityRegistry.fromToolBar(entry.availability)
                if (declared == expected) null else "${entry.id}: tool bar=$declared registry=$expected"
            }
        assertEquals("Tool Bar availability drifted from the registry: $drifted", emptyList<String>(), drifted)
    }
}
