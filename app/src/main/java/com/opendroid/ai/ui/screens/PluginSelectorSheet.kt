package com.opendroid.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.agent.InstalledAiAppDiscovery
import com.opendroid.ai.core.browser.BrowserUseRemoteProtocol
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentPurple
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

internal data class CloudSystemPluginOption(
    val label: String,
    val pluginId: String,
    val description: String,
)

internal val CLOUD_SYSTEM_PLUGIN_OPTIONS = listOf(
    CloudSystemPluginOption("FMHY", BrowserUseRemoteProtocol.FMHY_PLUGIN_ID, "Discover free and open-source tools."),
    CloudSystemPluginOption("GitHub Search", "github_search", "Search public repositories and source."),
    CloudSystemPluginOption("OpenSwarm Agent", "openswarm_agent", "Delegate work to your configured OpenSwarm server."),
    CloudSystemPluginOption("Web Browser", BrowserUseRemoteProtocol.GENERIC_PLUGIN_ID, "Browse websites using the remote browser."),
    CloudSystemPluginOption("Code Executor", "code_executor", "Run code through the configured cloud backend."),
)

@Composable
internal fun PluginSelectorSheetContent(
    installedApps: List<InstalledAiAppDiscovery.InstalledAiApp>,
    smartAiSelection: Boolean,
    onSmartAiSelectionChange: (Boolean) -> Unit,
    selectedLocalAppPackages: Set<String>,
    onLocalAppCheckedChange: (String, Boolean) -> Unit,
    selectedPluginIds: Set<String>,
    onPluginCheckedChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val selectedCount = selectedLocalAppPackages.size + selectedPluginIds.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Plugins & AI apps", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Choose local AI clients and cloud tools for this chat.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.18f))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "local-category") {
                PluginCategoryHeading(
                    title = "Local AI Apps",
                    subtitle = "ChatGPT, Claude, Gemini, Manus, and other installed apps.",
                )
            }
            item(key = "smart-selection") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSmartAiSelectionChange(!smartAiSelection) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Smart AI Selection", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Automatically rank installed apps for each task",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Switch(checked = smartAiSelection, onCheckedChange = onSmartAiSelectionChange)
                    }
                }
            }

            if (installedApps.isEmpty()) {
                item(key = "no-local-apps") {
                    Text(
                        "No launchable local AI apps were found. Cloud and system plugins are still available below.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(installedApps, key = { "local-${it.packageName}" }) { app ->
                    ListItem(
                        headlineContent = { Text(app.displayName, color = TextPrimary, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(app.packageName, color = TextSecondary, fontSize = 11.sp) },
                        leadingContent = {
                            Icon(Icons.Default.Extension, contentDescription = null, tint = AccentPurple)
                        },
                        trailingContent = {
                            Checkbox(
                                checked = app.packageName in selectedLocalAppPackages,
                                enabled = !smartAiSelection,
                                onCheckedChange = { checked -> onLocalAppCheckedChange(app.packageName, checked) },
                                colors = CheckboxDefaults.colors(checkedColor = AccentPurple),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !smartAiSelection) {
                                onLocalAppCheckedChange(app.packageName, app.packageName !in selectedLocalAppPackages)
                            },
                        colors = ListItemDefaults.colors(containerColor = CardBackground.copy(alpha = 0.44f)),
                    )
                }
            }

            item(key = "plugin-divider") {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.24f))
                Spacer(Modifier.height(4.dp))
            }
            item(key = "cloud-category") {
                PluginCategoryHeading(
                    title = "Cloud & System Plugins",
                    subtitle = "Select one or more tools. Cloud plugins use the configured server.",
                )
            }
            items(CLOUD_SYSTEM_PLUGIN_OPTIONS, key = { "plugin-${it.pluginId}" }) { option ->
                ListItem(
                    headlineContent = { Text(option.label, color = TextPrimary, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(option.description, color = TextSecondary, fontSize = 11.sp) },
                    leadingContent = { Icon(Icons.Default.Extension, contentDescription = null, tint = AccentCyan) },
                    trailingContent = {
                        Checkbox(
                            checked = option.pluginId in selectedPluginIds,
                            onCheckedChange = { checked -> onPluginCheckedChange(option.pluginId, checked) },
                            colors = CheckboxDefaults.colors(checkedColor = AccentCyan),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPluginCheckedChange(option.pluginId, option.pluginId !in selectedPluginIds)
                        },
                    colors = ListItemDefaults.colors(containerColor = CardBackground.copy(alpha = 0.44f)),
                )
            }
        }

        Surface(color = DarkSurface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("Back", color = TextSecondary)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(2f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                ) {
                    Text(
                        if (selectedCount == 0) "Confirm Selection" else "Confirm · $selectedCount",
                        color = DarkSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCategoryHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(title, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
