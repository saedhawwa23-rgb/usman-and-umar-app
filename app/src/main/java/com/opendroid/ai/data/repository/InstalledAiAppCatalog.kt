package com.opendroid.ai.data.repository

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAiApp(
    val family: String,
    val label: String,
    val packageName: String,
)

/** Discovers only launchable apps whose package/label identifies a requested AI assistant. */
@Singleton
class InstalledAiAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun discover(): List<InstalledAiApp> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                    val family = classify(label, info.packageName) ?: return@mapNotNull null
                    InstalledAiApp(family = family, label = label, packageName = info.packageName)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<InstalledAiApp> { it.family }.thenBy { it.label.lowercase() })
                .toList()
        }.getOrDefault(emptyList())
    }

    companion object {
        internal fun classify(label: String, packageName: String): String? {
            val identity = "$label $packageName".lowercase()
            return when {
                "chatgpt" in identity || "openai" in identity -> "ChatGPT"
                "gemini" in identity || "google.android.apps.bard" in identity -> "Gemini"
                "deepseek" in identity -> "DeepSeek"
                "copilot" in identity -> "Copilot"
                else -> null
            }
        }
    }
}
