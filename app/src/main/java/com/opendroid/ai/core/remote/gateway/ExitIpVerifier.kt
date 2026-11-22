package com.opendroid.ai.core.remote.gateway

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks a public address service which IP this device is currently reaching the internet from.
 *
 * A WireGuard tunnel reports itself "up" as soon as the interface exists, and a handshake only
 * proves the desktop answered. Neither proves the phone's traffic actually left through the desktop,
 * which is the only thing that matters to the user. Comparing the exit IP with the tunnel down and
 * the tunnel up is the difference between those claims.
 */
class ExitIpVerifier(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val timeoutMs: Int = 8000,
) {

    /** The exit address as a plain string, or a failure the UI can show verbatim. */
    sealed interface Result {
        data class Success(val ip: String) : Result
        data class Failure(val reason: String) : Result
    }

    fun fetch(): Result {
        var lastError = "no endpoint answered"
        for (endpoint in endpoints) {
            val attempt = runCatching { fetchOne(endpoint) }
            attempt.getOrNull()?.let { return Result.Success(it) }
            lastError = attempt.exceptionOrNull()?.let { e ->
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            } ?: lastError
        }
        return Result.Failure(lastError)
    }

    private fun fetchOne(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code from ${URL(endpoint).host}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            // Only a bare address is accepted; anything else means a captive portal or an error page.
            if (!IPV4.matches(body) && !IPV6.matches(body)) {
                throw IllegalStateException("unexpected response from ${URL(endpoint).host}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Two independent services, because one being blocked by a school network is likely and a
         * single failure must not read as "the tunnel does not work".
         */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
        )

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val IPV6 = Regex("""^[0-9a-fA-F:]{3,45}$""")

        /**
         * True when the two readings differ, which is what proves the exit changed.
         *
         * Addresses are compared as text; a school uplink behind carrier-grade NAT may share a
         * prefix, so equality is the only safe conclusion from a string comparison.
         */
        fun changed(before: String?, after: String?): Boolean =
            before != null && after != null && before != after
    }
}
