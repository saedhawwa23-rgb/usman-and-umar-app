package com.opendroid.ai.core.browser

import java.net.URI
import java.util.Locale

enum class OpenSwarmConnectionState { ONLINE, WAKING, OFFLINE }

internal fun validateOpenSwarmTunnelUrl(value: String): String {
    val candidate = value.trim().trimEnd('/')
    require(candidate.isNotBlank()) { "Enter the OpenSwarm Colab tunnel URL." }
    val uri = runCatching { URI(candidate) }.getOrElse {
        throw IllegalArgumentException("Enter a valid HTTPS Colab tunnel URL.")
    }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "Only HTTPS Colab tunnel URLs are accepted."
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port == -1) {
        "Tunnel URL must not contain credentials, a port, query, or fragment."
    }
    val host = uri.host?.lowercase(Locale.ROOT)
        ?: throw IllegalArgumentException("Enter a valid HTTPS Colab tunnel URL.")
    val acceptedHost = (host.endsWith(".loca.lt") && host.removeSuffix(".loca.lt").isNotBlank()) ||
        (host.endsWith(".trycloudflare.com") && host.removeSuffix(".trycloudflare.com").isNotBlank())
    require(acceptedHost) {
        "Only https://<name>.loca.lt and https://*.trycloudflare.com URLs are supported."
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "Enter only the tunnel origin, without a path."
    }
    return "https://$host"
}

/** Exact readiness contract emitted by the Colab backend /health endpoint. */
internal fun mapOpenSwarmHealth(
    httpCode: Int?,
    ready: Boolean?,
    authReady: Boolean?,
    redisReady: Boolean?,
    checkpointsReady: Boolean?,
    checkpointStorageReady: Boolean?,
    leaseReady: Boolean?,
    leaseCoordinatorReady: Boolean?,
    acceptingNewTasks: Boolean?,
    draining: Boolean?,
    attempt: Int = 1,
    timedOut: Boolean = false,
): OpenSwarmConnectionState {
    if (httpCode == 200 && ready == true && authReady == true && redisReady == true &&
        checkpointsReady == true && checkpointStorageReady == true && leaseReady == true &&
        leaseCoordinatorReady == true && acceptingNewTasks == true && draining == false
    ) return OpenSwarmConnectionState.ONLINE
    if (timedOut && attempt == 1) return OpenSwarmConnectionState.WAKING
    if (httpCode == 200 && attempt == 1) return OpenSwarmConnectionState.WAKING
    return OpenSwarmConnectionState.OFFLINE
}

internal fun requireOpenSwarmOnline(online: Boolean) {
    check(online) { "OpenSwarm server offline. Start the Colab notebook and try again." }
}
