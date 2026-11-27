package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.core.plugin.AgentKind
import com.opendroid.ai.core.plugin.PluginCapability
import com.opendroid.ai.core.plugin.PluginInterop
import com.opendroid.ai.core.plugin.PluginRecord
import com.opendroid.ai.ui.components.BrandButton
import com.opendroid.ai.ui.components.BrandHeader
import com.opendroid.ai.ui.components.BrandOutlineButton
import com.opendroid.ai.ui.components.EmptyState
import com.opendroid.ai.ui.components.MetricTile
import com.opendroid.ai.ui.components.PlatformCard
import com.opendroid.ai.ui.components.PlatformStatus
import com.opendroid.ai.ui.components.PlatformTab
import com.opendroid.ai.ui.components.SectionHeading
import com.opendroid.ai.ui.components.SegmentedTabs
import com.opendroid.ai.ui.components.StatusPill
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.PluginViewModel

/**
 * The v3.10.4 Plugin platform: discover and connect integrations (AI platforms, MCP servers and
 * OpenAPI services), manage what is connected, build multi-plugin automations, and reach the
 * developer tools.
 *
 * Every action here is a request to the security core; the screen never grants a capability on its
 * own, and it never displays or logs a credential value.
 */
@Composable
fun PluginPlatformScreen(viewModel: PluginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val colors = AppTheme.colors

    Column(Modifier.fillMaxSize()) {
        BrandHeader(
            title = "Integration Platform",
            subtitle = "${state.connectedCount} connected · ${state.catalogCount} in catalog",
            icon = Icons.Filled.Hub,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(state.connectedCount.toString(), "Connected", colors.emerald, Modifier.weight(1f))
                MetricTile(state.catalogCount.toString(), "Available", colors.electricBlue, Modifier.weight(1f))
                MetricTile(state.plugins.count { it.risk.name != "LOW" }.toString(), "Elevated risk", colors.accentRed, Modifier.weight(1f))
            }

            SegmentedTabs(
                tabs = listOf(
                    PlatformTab("Discover"),
                    PlatformTab("Manage", state.connectedCount),
                    PlatformTab("Automate"),
                    PlatformTab("Developer"),
                ),
                selectedIndex = tab,
                onSelect = { tab = it },
            )

            when (tab) {
                0 -> DiscoverTab(state, viewModel)
                1 -> ManageTab(state, viewModel)
                2 -> AutomateTab(state)
                else -> DeveloperTab(state, viewModel)
            }

            state.message?.let { message ->
                StatusPill(
                    text = message,
                    status = if (state.messageIsError) PlatformStatus.ERROR else PlatformStatus.HEALTHY,
                )
            }
        }
    }
}

@Composable
private fun DiscoverTab(state: PluginViewModel.UiState, viewModel: PluginViewModel) {
    val colors = AppTheme.colors
    val aiPlatforms = state.plugins.filter { it.kind == com.opendroid.ai.core.plugin.PluginKind.AI_TOOL }
    val mcpServers = state.plugins.filter { PluginInterop.MCP in it.interop }
    val apis = state.plugins.filter {
        it.kind == com.opendroid.ai.core.plugin.PluginKind.SERVICE && PluginInterop.MCP !in it.interop
    }

    CatalogGroup(
        title = "AI platforms",
        subtitle = "Model and tool endpoints over REST / OpenAPI 3.1",
        records = aiPlatforms,
        viewModel = viewModel,
    )
    CatalogGroup(
        title = "MCP servers",
        subtitle = "Model Context Protocol: tools are discovered with tools/list",
        records = mcpServers,
        viewModel = viewModel,
    )
    CatalogGroup(
        title = "OpenAPI & OIDC services",
        subtitle = "REST/JSON APIs, including delegated OAuth 2.0 sign-in",
        records = apis,
        viewModel = viewModel,
    )

    PlatformCard(
        title = "Connect an MCP server",
        subtitle = "Enter the JSON-RPC endpoint; tools are discovered, never assumed",
        icon = Icons.Filled.Hub,
    ) {
        var name by remember { mutableStateOf("") }
        var endpoint by remember { mutableStateOf("") }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it },
            label = { Text("Endpoint (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        BrandButton(
            text = "DISCOVER & CONNECT",
            onClick = {
                val id = name.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(64).ifBlank { "mcp_server" }
                viewModel.connectMcpServer(id, name.trim().ifBlank { "MCP server" }, endpoint.trim())
            },
            enabled = endpoint.trim().startsWith("http") && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Nothing is registered unless the server answers tools/list, and the server's tools stay " +
                "blocked until you approve the network capability.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }
}

@Composable
private fun CatalogGroup(
    title: String,
    subtitle: String,
    records: List<PluginRecord>,
    viewModel: PluginViewModel,
) {
    if (records.isEmpty()) return
    val colors = AppTheme.colors
    SectionHeading(title, subtitle = subtitle)
    records.forEach { record ->
        PlatformCard(
            title = record.displayName,
            subtitle = record.publisher,
            status = when (record.installState) {
                com.opendroid.ai.core.plugin.PluginInstallState.CONNECTED -> "Connected" to PlatformStatus.HEALTHY
                com.opendroid.ai.core.plugin.PluginInstallState.INSTALLED -> "Installed" to PlatformStatus.INFO
                com.opendroid.ai.core.plugin.PluginInstallState.DISABLED -> "Disabled" to PlatformStatus.WARNING
                else -> record.risk.name.lowercase().replaceFirstChar { it.uppercase() } + " risk" to
                    if (record.risk.name == "LOW") PlatformStatus.IDLE else PlatformStatus.WARNING
            },
        ) {
            Text(
                record.declaredCapabilities.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                    .ifBlank { "No capabilities declared" },
                color = colors.textSecondary, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton(
                    text = if (record.isCallable) "RECONNECT" else "CONNECT",
                    onClick = { viewModel.connect(record.id, record.declaredCapabilities) },
                    modifier = Modifier.weight(1f),
                )
                if (record.isCallable) {
                    BrandOutlineButton(
                        text = "Revoke",
                        onClick = { viewModel.revoke(record.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageTab(state: PluginViewModel.UiState, viewModel: PluginViewModel) {
    val colors = AppTheme.colors
    val connected = state.plugins.filter { it.installState != com.opendroid.ai.core.plugin.PluginInstallState.DISCOVERED }
    if (connected.isEmpty()) {
        PlatformCard(title = "Connected plugins") {
            EmptyState(Icons.Filled.Extension, "Nothing connected yet", "Connect an integration from the Discover tab.")
        }
        return
    }
    connected.forEach { record ->
        PlatformCard(
            title = record.displayName,
            subtitle = "${record.version} · ${record.publisher}",
            status = when {
                record.isCallable -> "Live" to PlatformStatus.HEALTHY
                record.installState == com.opendroid.ai.core.plugin.PluginInstallState.DISABLED -> "Disabled" to PlatformStatus.WARNING
                else -> "Not approved" to PlatformStatus.INFO
            },
        ) {
            Text(
                "Granted: " + record.grantedCapabilities.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                    .ifBlank { "none" },
                color = colors.textSecondary, fontSize = 12.sp,
            )
            Text(
                "Declared: " + record.declaredCapabilities.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                    .ifBlank { "none" },
                color = colors.textSecondary, fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.installState == com.opendroid.ai.core.plugin.PluginInstallState.DISABLED) {
                    BrandOutlineButton("Enable", { viewModel.setEnabled(record.id, true) }, modifier = Modifier.weight(1f))
                } else {
                    BrandOutlineButton("Disable", { viewModel.setEnabled(record.id, false) }, modifier = Modifier.weight(1f))
                }
                BrandOutlineButton("Revoke", { viewModel.revoke(record.id) }, modifier = Modifier.weight(1f))
            }
            Text(
                "Revoking clears the grant immediately: the plugin's tools stop resolving on the next call.",
                color = colors.textSecondary, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun AutomateTab(state: PluginViewModel.UiState) {
    val colors = AppTheme.colors
    val live = state.plugins.filter { it.isCallable }
    PlatformCard(
        title = "Multi-plugin automations",
        subtitle = "Chain the tools of connected plugins into one run",
        icon = Icons.Filled.Build,
    ) {
        Text(
            "A workflow step is executed through the same connector path as a tool the agent calls " +
                "directly, so the kill switch, the argument limit, the approval gate and the execution " +
                "log all still apply. A workflow gets no shortcut around the security core.",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        if (live.isEmpty()) {
            EmptyState(Icons.Filled.Extension, "No live plugin tools", "Connect and approve a plugin first.")
        } else {
            Text("Available plugin tools", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            live.forEach { record ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(record.displayName, color = colors.textPrimary, fontSize = 13.sp)
                    StatusPill(
                        record.effectiveCapabilities.size.toString() + " capability(ies)",
                        PlatformStatus.HEALTHY,
                    )
                }
            }
            Text(
                "Add these as steps in a routine. A step that needs approval waits for you rather than " +
                    "running unattended.",
                color = colors.textSecondary, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DeveloperTab(state: PluginViewModel.UiState, viewModel: PluginViewModel) {
    val colors = AppTheme.colors
    var document by remember { mutableStateOf("") }
    var pluginId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    PlatformCard(
        title = "Agent & provider wiring",
        subtitle = "The agent must match the provider's protocol",
        icon = Icons.Filled.Public,
    ) {
        Text("Agent", color = colors.textSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentKind.entries.forEach { agent ->
                BrandOutlineButton(
                    text = agent.displayName.substringBefore(' '),
                    onClick = { viewModel.selectAgent(agent) },
                    enabled = state.selectedAgent != agent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text("Provider", color = colors.textSecondary, fontSize = 12.sp)
        state.compatibleProviders.forEach { provider ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(provider.displayName, color = colors.textPrimary, fontSize = 13.sp)
                    Text(provider.protocol.wireFormat, color = colors.textSecondary, fontSize = 11.sp)
                }
                TextButton(onClick = { viewModel.selectProvider(provider.id) }) {
                    Text(if (provider.id == state.selectedProviderId) "SELECTED" else "SELECT")
                }
            }
        }
        Text(
            "Active pairing: ${state.selectedAgent.displayName} · ${state.selectedProvider.displayName} " +
                "(${state.selectedProvider.protocol.wireFormat})",
            color = colors.electricBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        Text("Credential: ${state.apiKeyMasked}", color = colors.textSecondary, fontSize = 11.sp)
        BrandButton(
            text = "RUN PREFLIGHT",
            onClick = { viewModel.preflight() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Preflight checks the key, the protocol pairing, and whether the agent is installed and " +
                "verified, and names the first blocker specifically.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }

    PlatformCard(
        title = "Import an OpenAPI 3.1 document",
        subtitle = "Each operation becomes a plugin tool with a risk level",
        icon = Icons.Filled.Build,
    ) {
        OutlinedTextField(
            value = pluginId, onValueChange = { pluginId = it },
            label = { Text("Plugin id (lowercase, e.g. my_api)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName, onValueChange = { displayName = it },
            label = { Text("Display name (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = document, onValueChange = { document = it },
            label = { Text("Paste the OpenAPI JSON") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        BrandButton(
            text = "IMPORT & INSTALL",
            onClick = { viewModel.importOpenApi(document, pluginId.trim(), displayName.trim().ifBlank { null }, setOf(PluginCapability.NETWORK_FETCH)) },
            enabled = document.isNotBlank() && pluginId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Only http(s) server URLs are accepted, and an operation that mutates data is imported at " +
                "a higher risk level.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }
}
