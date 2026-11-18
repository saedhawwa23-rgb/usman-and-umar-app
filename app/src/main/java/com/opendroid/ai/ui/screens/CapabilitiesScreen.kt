package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.PlatformTab
import com.opendroid.ai.ui.components.SectionHeading
import com.opendroid.ai.ui.components.SegmentedTabs
import com.opendroid.ai.ui.screens.toolbar.ToolAvailability
import com.opendroid.ai.ui.screens.toolbar.ToolBarContext
import com.opendroid.ai.ui.screens.toolbar.ToolBarModel
import com.opendroid.ai.ui.screens.toolbar.ToolDestination
import com.opendroid.ai.ui.screens.toolbar.ToolEntry
import com.opendroid.ai.ui.screens.toolbar.ToolSection
import com.opendroid.ai.ui.screens.toolbar.ToolSectionId
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.TermuxViewModel
import com.opendroid.ai.ui.viewmodel.WorkspaceTaskViewModel

/**
 * v3.10.5: the Tool Bar, restructured into three clearly labelled sections — AI providers and
 * web-based agents, GitHub project management, and the Termux / Linux environment.
 *
 * Every entry routes to an existing, fully scoped feature screen; nothing here opens a website and
 * calls it integration. Each entry shows an availability pill computed from the real runtime
 * context (Termux installed, a cloud server configured), so the Tool Bar cannot label something
 * "ready" that the user cannot yet run.
 */
@Composable
fun CapabilitiesScreen(
    onSearchAI: () -> Unit,
    onSearchGitHub: () -> Unit,
    onTermux: () -> Unit,
    onUniversalAgent: () -> Unit,
    onOpenPluginPlatform: () -> Unit = {},
) {
    val termuxViewModel: TermuxViewModel = hiltViewModel()
    val workspaceTaskViewModel: WorkspaceTaskViewModel = hiltViewModel()
    val termuxInstalled by termuxViewModel.installed.collectAsState()
    val cloudServers by workspaceTaskViewModel.cloudServers.collectAsState()

    val context = remember(termuxInstalled, cloudServers) {
        ToolBarContext(
            termuxInstalled = termuxInstalled,
            cloudServerConfigured = cloudServers.any { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() },
            githubTokenConnected = false,
        )
    }
    CapabilitiesScreenContent(
        context = context,
        onSearchAI = onSearchAI,
        onSearchGitHub = onSearchGitHub,
        onTermux = onTermux,
        onUniversalAgent = onUniversalAgent,
        onOpenPluginPlatform = onOpenPluginPlatform,
    )
}

/**
 * The Tool Bar's presentation, split out from [CapabilitiesScreen] so it can be rendered directly
 * with a fixed [context] and asserted in a Robolectric Compose test — the navigation callbacks are
 * plain lambdas, so a test can prove each entry routes to the right screen without a device.
 */
@Composable
internal fun CapabilitiesScreenContent(
    context: ToolBarContext,
    onSearchAI: () -> Unit,
    onSearchGitHub: () -> Unit,
    onTermux: () -> Unit,
    onUniversalAgent: () -> Unit,
    onOpenPluginPlatform: () -> Unit,
) {
    val sections = remember(context) { ToolBarModel.resolvedSections(context) }
    var selected by remember { mutableIntStateOf(0) }

    val open: (ToolDestination) -> Unit = { destination ->
        when (destination) {
            ToolDestination.PROVIDER_DISCOVERY -> onSearchAI()
            ToolDestination.GITHUB_DISCOVERY -> onSearchGitHub()
            ToolDestination.TERMUX -> onTermux()
            ToolDestination.CLOUD_AGENT -> onUniversalAgent()
            ToolDestination.INTEGRATION_PLATFORM -> onOpenPluginPlatform()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader(
            title = "Tool Bar",
            subtitle = "AI agents, GitHub and the Linux environment",
            icon = Icons.Default.Build,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SegmentedTabs(
                    tabs = sections.map { PlatformTab(it.shortLabel()) },
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )
            }
            val section = sections.getOrElse(selected) { sections.first() }
            item {
                SectionHeading(title = section.title, subtitle = section.subtitle)
            }
            items(section.entries, key = { it.id }) { entry ->
                ToolEntryCard(entry = entry, onOpen = { open(entry.destination) })
            }
            item {
                Spacer(Modifier.height(4.dp))
                PlatformCard(
                    title = "Integration platform",
                    subtitle = "MCP servers, OpenAPI services and grants",
                    icon = Icons.Default.Extension,
                ) {
                    Text(
                        "Discover and connect AI platforms, MCP servers and OpenAPI services, manage grants " +
                            "and chain them into automations.",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    BrandButton(
                        text = "Open integrations",
                        onClick = onOpenPluginPlatform,
                        icon = Icons.Default.Extension,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolEntryCard(entry: ToolEntry, onOpen: () -> Unit) {
    PlatformCard(
        title = entry.title,
        icon = entry.destination.icon(),
        status = ToolBarModel.availabilityLabel(entry.availability) to entry.availability.status(),
    ) {
        Text(entry.description, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BrandButton(text = entry.actionLabel(), onClick = onOpen, icon = entry.destination.icon())
        }
    }
}

private fun ToolSection.shortLabel(): String = when (id) {
    ToolSectionId.AI_AGENTS -> "AI"
    ToolSectionId.GITHUB -> "GitHub"
    ToolSectionId.LINUX -> "Linux"
}

private fun ToolAvailability.status(): PlatformStatus = when (this) {
    ToolAvailability.READY -> PlatformStatus.HEALTHY
    ToolAvailability.READ_ONLY -> PlatformStatus.INFO
    ToolAvailability.NEEDS_TERMUX -> PlatformStatus.WARNING
    ToolAvailability.NEEDS_SETUP -> PlatformStatus.IDLE
}

private fun ToolDestination.icon(): ImageVector = when (this) {
    ToolDestination.PROVIDER_DISCOVERY -> Icons.Default.SmartToy
    ToolDestination.GITHUB_DISCOVERY -> Icons.Default.Code
    ToolDestination.TERMUX -> Icons.Default.Terminal
    ToolDestination.CLOUD_AGENT -> Icons.Default.SmartToy
    ToolDestination.INTEGRATION_PLATFORM -> Icons.Default.Extension
}

private fun ToolEntry.actionLabel(): String = when (destination) {
    ToolDestination.PROVIDER_DISCOVERY -> "Search providers"
    ToolDestination.GITHUB_DISCOVERY -> "Open GitHub"
    ToolDestination.TERMUX -> "Open terminal"
    ToolDestination.CLOUD_AGENT -> "Open agent"
    ToolDestination.INTEGRATION_PLATFORM -> "Open"
}
