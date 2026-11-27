package com.opendroid.ai.core.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Interoperability protocol a plugin speaks. A plugin may declare more than one; the registry does
 * not assume a plugin supports a protocol it did not declare. Nothing here claims that a plugin is
 * certified against a standard - it records which protocol the plugin actually uses so the owner
 * can see it and the runtime can refuse an incompatible pairing.
 */
enum class PluginInterop(val displayName: String) {
    /** OpenAPI 3.1 description of an HTTP API. */
    OPENAPI_31("OpenAPI 3.1"),
    /** Model Context Protocol server, for AI tool interoperability. */
    MCP("Model Context Protocol"),
    /** OAuth 2.0 / OpenID Connect delegated authentication. */
    OAUTH2_OIDC("OAuth 2.0 / OIDC"),
    /** Plain REST/JSON over TLS. */
    REST_JSON("REST / JSON over TLS"),
    /** A browser-driven tool (reads pages the user could read). */
    BROWSER("Browser tool"),
    /** A local Android app or device capability. */
    LOCAL_DEVICE("Local device"),
}

/** How a plugin is delivered and invoked. */
enum class PluginKind {
    /** An HTTP/web service reached over the network. */
    SERVICE,

    /** A browser-based tool. */
    BROWSER,

    /** A local Android app or device capability. */
    LOCAL_DEVICE,

    /** An AI model or model-backed tool. */
    AI_TOOL,
}

/**
 * A version compatibility requirement: the plugin needs an app version at least this high, and its
 * declared semver must satisfy [requiredSemverRange] (simple `>=x.y.z` form).
 */
@Serializable
data class PluginCompatibility(
    @SerialName("min_app_version") val minAppVersion: String = "0.0.0",
    @SerialName("required_semver_range") val requiredSemverRange: String? = null,
)

/**
 * A plugin manifest, in a documented, machine-readable shape. It is deliberately small and
 * explicit: a plugin states its id, version, kind, the protocols it speaks, what it needs, and
 * what it declares it can do. Capabilities are still only usable when the owner grants them.
 */
@Serializable
data class PluginManifest(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val version: String,
    val publisher: String = "",
    @SerialName("homepage_url") val homepageUrl: String = "",
    val kind: String = "SERVICE",
    val interop: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    @SerialName("supported_languages") val supportedLanguages: List<String> = emptyList(),
    @SerialName("supported_regions") val supportedRegions: List<String> = emptyList(),
    val risk: String = "LOW",
    val dependencies: List<String> = emptyList(),
    val compatibility: PluginCompatibility = PluginCompatibility(),
) {
    fun kindOrDefault(): PluginKind = runCatching { PluginKind.valueOf(kind.uppercase()) }.getOrDefault(PluginKind.SERVICE)

    fun interopProtocols(): List<PluginInterop> = interop.mapNotNull { name ->
        PluginInterop.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
    }

    fun declaredCapabilities(): Set<PluginCapability> = capabilities.mapNotNull { name ->
        PluginCapability.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }.toSet()

    fun declaredRisk(): PluginRisk = runCatching { PluginRisk.valueOf(risk.uppercase()) }.getOrDefault(PluginRisk.LOW)

    fun toRecord(): PluginRecord = PluginRecord(
        id = id,
        displayName = displayName,
        publisher = publisher,
        homepageUrl = homepageUrl,
        version = version,
        kind = kindOrDefault(),
        interop = interopProtocols().toSet(),
        supportedLanguages = supportedLanguages.toSet(),
        supportedRegions = supportedRegions.toSet(),
        declaredCapabilities = declaredCapabilities(),
        risk = declaredRisk(),
        dependencies = dependencies.toSet(),
        minAppVersion = compatibility.minAppVersion,
        requiredSemverRange = compatibility.requiredSemverRange,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a manifest from JSON. Returns null for anything malformed. */
        fun parse(text: String): PluginManifest? = runCatching {
            json.decodeFromString<PluginManifest>(text)
        }.getOrNull()
    }
}

/**
 * The result of checking a manifest against the running app and the registry. [problems] is empty
 * only when the plugin may be registered and installed.
 */
data class ManifestCheck(
    val compatible: Boolean,
    val missingDependencies: Set<String>,
    val problems: List<String>,
)

/** Compares two `x.y.z` version strings. Returns a negative, zero, or positive value. */
fun compareSemver(a: String, b: String): Int {
    fun parts(v: String) = v.trim().substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** True when [version] satisfies a simple `>=x.y.z` range. Unknown range syntax fails closed. */
fun satisfiesRange(version: String, range: String?): Boolean {
    if (range.isNullOrBlank()) return true
    val trimmed = range.trim()
    if (trimmed.startsWith(">=")) return compareSemver(version, trimmed.removePrefix(">=").trim()) >= 0
    if (trimmed.startsWith(">")) return compareSemver(version, trimmed.removePrefix(">").trim()) > 0
    if (trimmed.startsWith("<=")) return compareSemver(version, trimmed.removePrefix("<=").trim()) <= 0
    if (trimmed.startsWith("<")) return compareSemver(version, trimmed.removePrefix("<").trim()) < 0
    return false
}
