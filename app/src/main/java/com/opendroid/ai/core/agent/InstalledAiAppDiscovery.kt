package com.opendroid.ai.core.agent

import android.content.pm.PackageManager
import android.os.Build
import com.usmanandumar.workspace.core.task.LocalAiProvider

/** Discovers the supported accessibility-driven AI clients installed on this device. */
object InstalledAiAppDiscovery {
    data class InstalledAiApp(val provider: LocalAiProvider, val displayName: String)

    fun scan(packageManager: PackageManager): List<InstalledAiApp> =
        LocalAiProvider.entries.mapNotNull { provider ->
            val application = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        provider.packageName,
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(provider.packageName, 0)
                }
            }.getOrNull() ?: return@mapNotNull null
            val label = runCatching { packageManager.getApplicationLabel(application).toString() }
                .getOrDefault(provider.label)
            InstalledAiApp(provider, label.ifBlank { provider.label })
        }
}
