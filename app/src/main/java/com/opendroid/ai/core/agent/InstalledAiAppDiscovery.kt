package com.opendroid.ai.core.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Discovers likely AI clients from Android's PackageManager instead of maintaining a
 * package-name allowlist. Classification is based on user-visible label/package-name
 * signals, so newer clients (including Manus and Genspark) remain discoverable without
 * adding package IDs to the app.
 */
object InstalledAiAppDiscovery {
    data class InstalledAiApp(
        val packageName: String,
        val displayName: String,
        val isAiCandidate: Boolean,
    )

    private val aiNamePattern = Regex(
        "(?i)(\\bai\\b|artificial intelligence|\\bgpt\\b|\\bchatgpt\\b|\\bllm\\b|\\bchat\\b|\\bchatbot\\b|\\bassistant\\b|\\bcopilot\\b|\\bbot\\b|\\bmanus\\b|\\bgenspark\\b|\\bgemini\\b|\\bclaude\\b|\\bgrok\\b|\\bdeepseek\\b|\\bperplexity\\b|\\bmistral\\b|\\bqwen\\b|\\bkimi\\b|\\bpoe\\b)"
    )

    fun scan(packageManager: PackageManager): List<InstalledAiApp> {
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return applications.mapNotNull { application ->
            val launchIntent = runCatching {
                packageManager.getLaunchIntentForPackage(application.packageName)
            }.getOrNull() ?: return@mapNotNull null
            if (launchIntent.component == null) return@mapNotNull null

            val label = runCatching { packageManager.getApplicationLabel(application).toString() }
                .getOrDefault(application.packageName)
                .ifBlank { application.packageName }
            val candidate = aiNamePattern.containsMatchIn(label) ||
                aiNamePattern.containsMatchIn(application.packageName.replace('.', ' '))
            if (!candidate) return@mapNotNull null
            InstalledAiApp(
                packageName = application.packageName,
                displayName = label,
                isAiCandidate = candidate,
            )
        }.distinctBy { it.packageName }
            .sortedWith(compareByDescending<InstalledAiApp> { it.isAiCandidate }.thenBy { it.displayName.lowercase() })
    }
}

/** Persists the user's default locally selected AI client without storing credentials. */
object InstalledAiAppPreferences {
    private const val PREFS = "installed_ai_app_preferences"
    private const val KEY_PACKAGE = "selected_package"

    fun selectedPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PACKAGE, null)

    fun setSelectedPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, packageName)
            .apply()
    }
}
