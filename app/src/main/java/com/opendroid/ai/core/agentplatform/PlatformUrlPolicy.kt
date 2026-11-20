package com.opendroid.ai.core.agentplatform

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The single URL rule every agent-platform connection must pass.
 *
 * Each platform used to repeat this check inline, which meant a new platform could quietly ship a
 * weaker version. The rules are security-relevant, so they live in one place and are unit-tested:
 *
 * - the URL must parse;
 * - it must not carry embedded credentials, which would leak into logs and crash reports;
 * - it must be HTTPS, except on loopback where a developer runs a server locally.
 */
object PlatformUrlPolicy {

    /** Hosts where cleartext HTTP is acceptable because the traffic never leaves the device. */
    val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

    sealed interface Verdict {
        data class Accepted(val url: HttpUrl) : Verdict

        /** [message] is user-facing and never echoes the credential. */
        data class Rejected(val message: String) : Verdict
    }

    fun check(rawUrl: String, blankMessage: String): Verdict {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Verdict.Rejected(blankMessage)

        val parsed = url.toHttpUrlOrNull()
            ?: return Verdict.Rejected("Enter a valid server URL")

        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return Verdict.Rejected("Enter a valid server URL without embedded credentials")
        }

        if (parsed.scheme != "https" && parsed.host !in LOOPBACK_HOSTS) {
            return Verdict.Rejected(
                "Use HTTPS for remote servers; cleartext HTTP is allowed only on loopback",
            )
        }

        return Verdict.Accepted(parsed)
    }

    /**
     * True when the URL is safe to show in an error or a log line. A URL with a user-info section
     * is refused even after parsing, so a credential can never be echoed back to the screen.
     */
    fun isSafeToDisplay(rawUrl: String): Boolean {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return false
        return parsed.username.isEmpty() && parsed.password.isEmpty()
    }
}
