package com.opendroid.ai.ui.screens

import java.net.URI
import java.util.Locale

internal const val COLAB_NOTEBOOK_URL =
    "https://colab.research.google.com/github/saedhawwa23-rgb/usman-and-umar-app/blob/feature/hybrid-task-orchestrator/USMAN_OPENSWARM_NODE.ipynb"

/** Extract only HTTPS Colab tunnel origins from clipboard text; ignore unrelated or unsafe URLs. */
internal fun findColabTunnelUrl(clipboardText: String?): String? {
    val text = clipboardText?.trim().orEmpty()
    if (text.isEmpty()) return null
    val candidate = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
        .find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val isColabTunnel = host == "loca.lt" || host.endsWith(".loca.lt") ||
        host == "trycloudflare.com" || host.endsWith(".trycloudflare.com")
    if (!isColabTunnel) return null
    val port = if (uri.port in 1..65535) ":${uri.port}" else ""
    return "https://$host$port"
}
