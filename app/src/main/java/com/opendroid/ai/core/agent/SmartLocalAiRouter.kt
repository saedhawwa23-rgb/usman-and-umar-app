package com.opendroid.ai.core.agent

/**
 * Selects an installed AI client without depending on package IDs. The router only ranks
 * candidates returned by PackageManager, so an unavailable preferred client is skipped.
 */
object SmartLocalAiRouter {
    const val AUTO_TARGET = "auto"

    private data class Route(
        val signals: Set<String>,
        val keywords: Set<String>,
    )

    private val routes = listOf(
        Route(
            signals = setOf("claude", "deepseek", "copilot", "cursor", "codeium"),
            keywords = setOf("code", "coding", "python", "kotlin", "java", "javascript", "typescript", "script", "debug", "program", "function", "api", "compile"),
        ),
        Route(
            signals = setOf("gemini", "chatgpt", "perplexity", "you.com"),
            keywords = setOf("research", "latest", "news", "summarize", "summary", "compare", "sources", "fact", "web", "search", "article", "paper"),
        ),
        Route(
            signals = setOf("grok", "manus", "genspark"),
            keywords = setOf("x.com", "twitter", "social", "browser", "website", "browse", "tool", "automation", "agent"),
        ),
    )

    /** Returns the best available candidate, or null when PackageManager found none. */
    fun select(
        prompt: String,
        candidates: List<InstalledAiAppDiscovery.InstalledAiApp>,
    ): InstalledAiAppDiscovery.InstalledAiApp? {
        if (candidates.isEmpty()) return null
        val normalizedPrompt = prompt.lowercase()
        val ranked = candidates.map { candidate ->
            val label = "${candidate.displayName} ${candidate.packageName}".lowercase()
            val routeScore = routes.maxOfOrNull { route ->
                val keywordMatch = route.keywords.count { normalizedPrompt.contains(it) }
                val appMatch = route.signals.count { label.contains(it) }
                if (keywordMatch == 0 || appMatch == 0) 0 else 10_000 + keywordMatch * 100 + appMatch * 10
            } ?: 0
            val generalPreference = when {
                label.contains("chatgpt") -> 90
                label.contains("gemini") -> 80
                label.contains("claude") -> 70
                label.contains("deepseek") -> 60
                label.contains("perplexity") -> 50
                label.contains("grok") -> 40
                label.contains("manus") -> 30
                else -> 0
            }
            candidate to (routeScore + generalPreference)
        }
        return ranked.sortedWith(
            compareByDescending<Pair<InstalledAiAppDiscovery.InstalledAiApp, Int>> { it.second }
                .thenByDescending { it.first.isAiCandidate }
                .thenBy { it.first.displayName.lowercase() },
        ).firstOrNull()?.first
    }
}
