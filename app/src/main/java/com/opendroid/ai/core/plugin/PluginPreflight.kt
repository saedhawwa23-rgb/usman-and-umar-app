package com.opendroid.ai.core.plugin

/**
 * v3.10.4: agent ↔ provider compatibility and preflight, so a task can never be started with an
 * agent that cannot speak the configured provider's protocol, and so a missing key or an
 * uninstalled agent produces a specific message instead of a silent "Task stopped".
 *
 * The pairing rule is a property of the protocol, not a preference: Claude Code drives the Anthropic
 * Messages API, Antigravity drives the Google Gemini API, and DeepSeek Harness drives an
 * OpenAI-compatible chat-completions endpoint. Pairing any of them with the wrong wire format is a
 * hard refusal, checked before a download or a task start.
 */

/** The wire format a provider speaks. */
enum class ProviderProtocol(val displayName: String, val wireFormat: String) {
    OPENAI_COMPLETIONS("OpenAI-compatible", "openai-completions"),
    ANTHROPIC_MESSAGES("Anthropic Messages API", "anthropic-messages"),
    GEMINI_GENERATE("Google Gemini API", "gemini-generate-content"),
}

/** The agents the app can drive. Each requires exactly one protocol. */
enum class AgentKind(val displayName: String, val requiredProtocol: ProviderProtocol) {
    DEEPSEEK_HARNESS("DeepSeek Harness", ProviderProtocol.OPENAI_COMPLETIONS),
    CLAUDE_CODE("Claude Code", ProviderProtocol.ANTHROPIC_MESSAGES),
    ANTIGRAVITY("Antigravity", ProviderProtocol.GEMINI_GENERATE),
}

/**
 * A selectable provider. [protocol] is the wire format the endpoint actually speaks, and it is what
 * the preflight compares against the agent's requirement.
 */
data class AgentProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val protocol: ProviderProtocol,
    /** The label the UI shows for the credential field; never a value. */
    val credentialLabel: String = "API key",
)

/** The built-in provider catalog for the Agent screen. Endpoints are the documented ones. */
object AgentProviders {
    val all: List<AgentProvider> = listOf(
        AgentProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", ProviderProtocol.OPENAI_COMPLETIONS, "DeepSeek API key"),
        AgentProvider("deepseek_anthropic", "DeepSeek (Anthropic-compatible)", "https://api.deepseek.com/anthropic", ProviderProtocol.ANTHROPIC_MESSAGES, "DeepSeek API key"),
        AgentProvider("anthropic", "Anthropic", "https://api.anthropic.com/v1", ProviderProtocol.ANTHROPIC_MESSAGES, "Anthropic API key"),
        AgentProvider("nvidia_nim", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", ProviderProtocol.OPENAI_COMPLETIONS, "NVIDIA API key"),
        AgentProvider("openai", "OpenAI", "https://api.openai.com/v1", ProviderProtocol.OPENAI_COMPLETIONS, "OpenAI API key"),
        AgentProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", ProviderProtocol.GEMINI_GENERATE, "Google AI Studio key"),
    )

    fun byId(id: String): AgentProvider? = all.firstOrNull { it.id == id }

    /** The providers whose protocol an agent can actually use. */
    fun compatibleWith(agent: AgentKind): List<AgentProvider> = all.filter { it.protocol == agent.requiredProtocol }
}

/** Why a task may not start. Every value carries a specific, user-facing message. */
enum class PreflightBlocker(val code: String) {
    MISSING_KEY("MISSING_KEY"),
    PROTOCOL_MISMATCH("PROTOCOL_MISMATCH"),
    AGENT_NOT_INSTALLED("AGENT_NOT_INSTALLED"),
    AGENT_NOT_VERIFIED("AGENT_NOT_VERIFIED"),
}

/** The outcome of a preflight. [Ready] carries the pairing that was checked. */
sealed interface PreflightResult {
    data class Ready(val agent: AgentKind, val provider: AgentProvider) : PreflightResult
    data class Blocked(val blocker: PreflightBlocker, val message: String) : PreflightResult
}

object AgentPreflight {

    /**
     * Check a task start. Order matters: the cheapest and most specific failure is reported first,
     * and no check is skipped because an earlier one passed.
     */
    fun check(
        agent: AgentKind,
        provider: AgentProvider,
        apiKeyPresent: Boolean,
        agentInstalled: Boolean,
        agentVerified: Boolean,
    ): PreflightResult {
        if (provider.protocol != agent.requiredProtocol) {
            return PreflightResult.Blocked(
                PreflightBlocker.PROTOCOL_MISMATCH,
                "${agent.displayName} requires an ${agent.requiredProtocol.displayName} endpoint. " +
                    "The current provider (${provider.displayName}) uses ${provider.protocol.wireFormat}.",
            )
        }
        if (!apiKeyPresent) {
            return PreflightResult.Blocked(
                PreflightBlocker.MISSING_KEY,
                "Add a ${provider.credentialLabel} for ${provider.displayName} before starting a task.",
            )
        }
        if (!agentInstalled) {
            return PreflightResult.Blocked(
                PreflightBlocker.AGENT_NOT_INSTALLED,
                "${agent.displayName} is not installed on this device. Install it from the Agent screen, then retry.",
            )
        }
        if (!agentVerified) {
            return PreflightResult.Blocked(
                PreflightBlocker.AGENT_NOT_VERIFIED,
                "${agent.displayName} is installed but has not been verified. Verify it before starting a task.",
            )
        }
        return PreflightResult.Ready(agent, provider)
    }
}

/**
 * Secret masking. A key is shown only as a short prefix and suffix so the owner can recognise it
 * without the full value ever reaching a log, an error message, or the screen.
 */
object SecretMask {

    fun mask(value: String?): String {
        if (value.isNullOrEmpty()) return "(not set)"
        val trimmed = value.trim()
        if (trimmed.length < 8) return "••••"
        return trimmed.take(3) + "…" + trimmed.takeLast(4)
    }

    /**
     * Redact any known secret that appears inside a free-text line (for logs and errors). A log
     * line gets a full `<redacted>` marker, not the display mask: a prefix and suffix are still a
     * partial key, and a log outlives the screen that showed it.
     */
    fun redact(line: String, secrets: Iterable<String?>): String {
        var out = line
        secrets.filterNotNull().filter { it.length >= 6 }.forEach { secret ->
            out = out.replace(secret, "<redacted>")
        }
        // Common bearer/api-key shapes, even when the exact value is not known here.
        out = out.replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}"), "$1<redacted>")
        out = out.replace(Regex("(?i)((?:api[_-]?key|token|authorization)[\"'\\s:=]+)[A-Za-z0-9._\\-]{8,}"), "$1<redacted>")
        return out
    }
}
