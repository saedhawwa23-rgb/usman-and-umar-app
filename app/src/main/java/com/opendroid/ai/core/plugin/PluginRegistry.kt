package com.opendroid.ai.core.plugin

/**
 * Stage 1 of v3.10.0: an international plugin registry with no fixed plugin count.
 *
 * The registry is the single authority on whether a plugin may be called. Discovery (FMHY)
 * may only *suggest* a candidate; it never registers, approves, or runs anything. A plugin
 * becomes callable only when the owner approves it, and its effective capabilities are the
 * intersection of what the plugin declares and what the owner grants, so a plugin can never
 * widen its own permissions by re-declaring a larger manifest.
 */

/** Risk level a plugin carries. Ordering is meaningful: CRITICAL is the most severe. */
enum class PluginRisk(val severity: Int) {
    LOW(1),
    HIGH(2),
    CRITICAL(3),
}

/** A capability a plugin may be granted. A plugin can only ever use capabilities the owner grants. */
enum class PluginCapability {
    NETWORK_FETCH,
    BROWSER_USE,
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    CODE_EXECUTION,
    CLOUD_DELEGATION,
    DEVICE_CONTROL,
}

/** The owner's decision about a plugin. There is no implicit approval. */
enum class PluginApprovalState {
    UNAPPROVED,
    APPROVED,
    REVOKED,
}

/** The install lifecycle of a plugin, separate from whether the owner has approved it. */
enum class PluginInstallState {
    /** Known to the registry but not installed on this device. */
    DISCOVERED,
    INSTALLED,
    /** Installed and its service is connected/authenticated. */
    CONNECTED,
    /** Disabled by the owner; not callable even if approved. */
    DISABLED,
    REMOVED,
}

/** The most recent health signal for a plugin, derived from its execution history. */
enum class PluginHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    FAILING,
}

/**
 * A plugin record. [grantedCapabilities] is what the owner has actually granted; it is always a
 * subset of [declaredCapabilities]. [effectiveCapabilities] is what the plugin may actually use.
 */
data class PluginRecord(
    val id: String,
    val displayName: String,
    val publisher: String,
    val homepageUrl: String,
    val version: String = "0.0.0",
    val kind: PluginKind = PluginKind.SERVICE,
    val interop: Set<PluginInterop> = emptySet(),
    val supportedLanguages: Set<String> = emptySet(),
    val supportedRegions: Set<String> = emptySet(),
    val declaredCapabilities: Set<PluginCapability> = emptySet(),
    val risk: PluginRisk = PluginRisk.LOW,
    val approvalState: PluginApprovalState = PluginApprovalState.UNAPPROVED,
    val installState: PluginInstallState = PluginInstallState.DISCOVERED,
    val grantedCapabilities: Set<PluginCapability> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val minAppVersion: String = "0.0.0",
    val requiredSemverRange: String? = null,
    val updateAvailableTo: String? = null,
) {
    /** What the plugin may actually do: granted capabilities the plugin still declares. */
    val effectiveCapabilities: Set<PluginCapability>
        get() = if (approvalState == PluginApprovalState.APPROVED && installState != PluginInstallState.DISABLED &&
            installState != PluginInstallState.REMOVED
        ) {
            grantedCapabilities intersect declaredCapabilities
        } else {
            emptySet()
        }

    val isCallable: Boolean
        get() = approvalState == PluginApprovalState.APPROVED &&
            installState != PluginInstallState.DISABLED &&
            installState != PluginInstallState.REMOVED
}

/** A candidate surfaced by discovery. It is a suggestion only and carries no approval. */
data class PluginCandidate(
    val id: String,
    val displayName: String,
    val homepageUrl: String,
    val source: String,
    val kind: PluginKind = PluginKind.SERVICE,
    val interop: Set<PluginInterop> = emptySet(),
)

/**
 * One execution-history entry for a plugin. It never carries a secret: [detail] is a short result
 * or error summary, and a failure is recorded as a failure rather than a success.
 */
data class PluginExecutionRecord(
    val pluginId: String,
    val toolName: String,
    val timestampMs: Long,
    val success: Boolean,
    val detail: String,
)

/** The outcome of an authorization attempt. Denial always carries a specific reason. */
sealed interface PluginAuthorization {
    data class Allowed(val pluginId: String, val capabilities: Set<PluginCapability>) : PluginAuthorization
    data class Denied(val pluginId: String, val reason: String) : PluginAuthorization
}

/**
 * In-memory plugin registry. Behaviour does not change as the number of plugins grows; the
 * registry holds no fixed cap and is safe to use with hundreds of entries.
 */
class PluginRegistry {

    private val plugins = LinkedHashMap<String, PluginRecord>()
    private val candidates = LinkedHashMap<String, PluginCandidate>()

    companion object {
        /** Plugin ids are stable, namespaced, and lowercase; they are never free-form model text. */
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")

        const val UNKNOWN_PLUGIN = "Plugin is not registered"
        const val UNAPPROVED_PLUGIN = "Plugin is not approved by the owner"
        const val REVOKED_PLUGIN = "Plugin approval was revoked by the owner"
        const val CAPABILITY_NOT_GRANTED = "Plugin capability is not granted by the owner"
        const val DISABLED_PLUGIN = "Plugin is disabled by the owner"

        const val MAX_HISTORY_PER_PLUGIN = 100
        const val HEALTH_WINDOW = 10
    }

    /** Register a plugin. Re-registering the same id can never widen a previously granted set. */
    @Synchronized
    fun register(record: PluginRecord) {
        require(record.id.matches(ID_PATTERN)) { "Invalid plugin id" }
        val existing = plugins[record.id]
        val merged = if (existing == null) {
            record.copy(
                grantedCapabilities = emptySet(),
                approvalState = PluginApprovalState.UNAPPROVED,
                installState = PluginInstallState.DISCOVERED,
            )
        } else {
            // A manifest update may shrink what the plugin declares but never expand the grant:
            // the effective grant stays the intersection of the owner's grant and the new manifest.
            record.copy(
                approvalState = existing.approvalState,
                installState = existing.installState,
                grantedCapabilities = existing.grantedCapabilities intersect record.declaredCapabilities,
            )
        }
        plugins[record.id] = merged
    }

    @Synchronized
    fun unregister(id: String): Boolean = plugins.remove(id) != null

    @Synchronized
    fun get(id: String): PluginRecord? = plugins[id]

    @Synchronized
    fun all(): List<PluginRecord> = plugins.values.toList()

    @Synchronized
    fun approved(): List<PluginRecord> = plugins.values.filter { it.isCallable }

    /**
     * Owner approval. The granted set is intersected with what the plugin declares, so an owner
     * can grant no more than the manifest asks for and the plugin can never widen its own grant.
     */
    @Synchronized
    fun approve(id: String, capabilities: Set<PluginCapability>): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val granted = capabilities intersect record.declaredCapabilities
        val updated = record.copy(
            approvalState = PluginApprovalState.APPROVED,
            grantedCapabilities = granted,
        )
        plugins[id] = updated
        return updated
    }

    /** Revoke a plugin. Its granted set is cleared so nothing remains callable. */
    @Synchronized
    fun revoke(id: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val updated = record.copy(
            approvalState = PluginApprovalState.REVOKED,
            grantedCapabilities = emptySet(),
        )
        plugins[id] = updated
        return updated
    }

    /**
     * Authorize a call before any request is made. Unknown, unapproved, and revoked ids are
     * refused here, and a call for a capability the owner did not grant is refused too.
     */
    @Synchronized
    fun authorize(id: String, capability: PluginCapability? = null): PluginAuthorization {
        val record = plugins[id] ?: return PluginAuthorization.Denied(id, UNKNOWN_PLUGIN)
        if (record.installState == PluginInstallState.DISABLED || record.installState == PluginInstallState.REMOVED) {
            return PluginAuthorization.Denied(id, DISABLED_PLUGIN)
        }
        return when (record.approvalState) {
            PluginApprovalState.UNAPPROVED -> PluginAuthorization.Denied(id, UNAPPROVED_PLUGIN)
            PluginApprovalState.REVOKED -> PluginAuthorization.Denied(id, REVOKED_PLUGIN)
            PluginApprovalState.APPROVED -> {
                if (capability != null && capability !in record.effectiveCapabilities) {
                    PluginAuthorization.Denied(id, CAPABILITY_NOT_GRANTED)
                } else {
                    PluginAuthorization.Allowed(id, record.effectiveCapabilities)
                }
            }
        }
    }

    /**
     * Record a discovery suggestion. Suggestions are never registered or approved here; the owner
     * must still add and approve the plugin explicitly.
     */
    @Synchronized
    fun suggest(candidate: PluginCandidate) {
        candidates[candidate.id] = candidate
    }

    @Synchronized
    fun suggestions(): List<PluginCandidate> = candidates.values.toList()

    /** Promote a suggestion into a registered (still unapproved) plugin. */
    @Synchronized
    fun acceptSuggestion(id: String, record: PluginRecord) {
        require(candidates.containsKey(id)) { "Suggestion is not present" }
        register(record)
        candidates.remove(id)
    }

    // ---- Manifest registration, version compatibility and dependencies ----

    /**
     * Check a manifest before registering it. Fails closed on an unsupported protocol, an app
     * version that is too low, a semver range that is not satisfied, or a missing dependency.
     */
    @Synchronized
    fun checkManifest(manifest: PluginManifest, appVersion: String): ManifestCheck {
        val problems = mutableListOf<String>()
        val missing = manifest.dependencies.filter { !plugins.containsKey(it) }.toSet()
        if (missing.isNotEmpty()) problems += "Missing dependencies: ${missing.sorted().joinToString(", ")}"
        if (compareSemver(appVersion, manifest.compatibility.minAppVersion) < 0) {
            problems += "Requires app version ${manifest.compatibility.minAppVersion} or newer (this app is $appVersion)."
        }
        if (!satisfiesRange(manifest.version, manifest.compatibility.requiredSemverRange)) {
            problems += "Version ${manifest.version} does not satisfy ${manifest.compatibility.requiredSemverRange}."
        }
        if (manifest.interop.isNotEmpty() && manifest.interopProtocols().isEmpty()) {
            problems += "Declares no recognized interoperability protocol."
        }
        return ManifestCheck(compatible = problems.isEmpty(), missingDependencies = missing, problems = problems)
    }

    /**
     * Register a plugin from a manifest, after [checkManifest] passes. Registration never approves:
     * the plugin is registered as DISCOVERED and UNAPPROVED regardless of what the manifest says.
     */
    @Synchronized
    fun registerManifest(manifest: PluginManifest, appVersion: String): PluginAuthorization {
        val check = checkManifest(manifest, appVersion)
        if (!check.compatible) {
            return PluginAuthorization.Denied(manifest.id, check.problems.joinToString(" "))
        }
        register(manifest.toRecord())
        return PluginAuthorization.Allowed(manifest.id, emptySet())
    }

    // ---- Install / connect / enable / disable / update / remove lifecycle ----

    /** Install a registered plugin. It is still not callable until the owner approves it. */
    @Synchronized
    fun install(id: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val missing = record.dependencies.filter { plugins[it]?.installState != PluginInstallState.INSTALLED &&
            plugins[it]?.installState != PluginInstallState.CONNECTED }
        require(missing.isEmpty()) { "Install dependencies first: ${missing.sorted().joinToString(", ")}" }
        val updated = record.copy(installState = PluginInstallState.INSTALLED)
        plugins[id] = updated
        return updated
    }

    /** Mark an installed plugin's service as connected/authenticated. */
    @Synchronized
    fun markConnected(id: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        require(record.installState == PluginInstallState.INSTALLED || record.installState == PluginInstallState.CONNECTED) {
            "Install the plugin before connecting it"
        }
        val updated = record.copy(installState = PluginInstallState.CONNECTED)
        plugins[id] = updated
        return updated
    }

    /** Disable a plugin. It stays installed and approved but is not callable. */
    @Synchronized
    fun disable(id: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val updated = record.copy(installState = PluginInstallState.DISABLED)
        plugins[id] = updated
        return updated
    }

    /** Re-enable a disabled plugin, returning it to its prior installed/connected state. */
    @Synchronized
    fun enable(id: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val updated = record.copy(installState = PluginInstallState.INSTALLED)
        plugins[id] = updated
        return updated
    }

    /**
     * Record that a newer version is available. It does not apply the update; the owner must call
     * [applyUpdate] so an update cannot silently change what a plugin declares.
     */
    @Synchronized
    fun noteUpdate(id: String, newVersion: String): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val updated = record.copy(updateAvailableTo = newVersion)
        plugins[id] = updated
        return updated
    }

    /**
     * Apply a pending update. The new manifest's declared capabilities are intersected with the
     * existing grant, so an update can never widen the owner's grant.
     */
    @Synchronized
    fun applyUpdate(id: String, newManifest: PluginManifest): PluginRecord {
        val record = plugins[id] ?: throw IllegalArgumentException(UNKNOWN_PLUGIN)
        val updated = record.copy(
            version = newManifest.version,
            declaredCapabilities = newManifest.declaredCapabilities(),
            interop = newManifest.interopProtocols().toSet(),
            grantedCapabilities = record.grantedCapabilities intersect newManifest.declaredCapabilities(),
            updateAvailableTo = null,
        )
        plugins[id] = updated
        return updated
    }

    /** Remove a plugin: it is unregistered and its grant is cleared. */
    @Synchronized
    fun remove(id: String): Boolean {
        val removed = plugins.remove(id) != null
        executionHistory.remove(id)
        healthSignals.remove(id)
        return removed
    }

    // ---- Health monitoring and execution history ----

    /** Record one execution outcome. A failure is recorded as a failure, never a success. */
    @Synchronized
    fun recordExecution(record: PluginExecutionRecord) {
        val history = executionHistory.getOrPut(record.pluginId) { ArrayDeque() }
        history.addFirst(record)
        while (history.size > MAX_HISTORY_PER_PLUGIN) history.removeLast()
        healthSignals[record.pluginId] = deriveHealth(record.pluginId)
    }

    @Synchronized
    fun executionHistory(id: String): List<PluginExecutionRecord> = executionHistory[id]?.toList() ?: emptyList()

    /**
     * Health is derived from the recent execution history: no runs is UNKNOWN, all recent runs
     * failed is FAILING, some failed is DEGRADED, otherwise HEALTHY.
     */
    @Synchronized
    fun health(id: String): PluginHealth = healthSignals[id] ?: PluginHealth.UNKNOWN

    private fun deriveHealth(id: String): PluginHealth {
        val recent = executionHistory[id]?.take(HEALTH_WINDOW)?.toList().orEmpty()
        if (recent.isEmpty()) return PluginHealth.UNKNOWN
        val failures = recent.count { !it.success }
        return when {
            failures == 0 -> PluginHealth.HEALTHY
            failures == recent.size -> PluginHealth.FAILING
            else -> PluginHealth.DEGRADED
        }
    }

    /** Plugins by kind, for the dashboard's Discover / AI / Browser / Local categories. */
    @Synchronized
    fun byKind(kind: PluginKind): List<PluginRecord> = plugins.values.filter { it.kind == kind }

    @Synchronized
    fun installed(): List<PluginRecord> =
        plugins.values.filter { it.installState == PluginInstallState.INSTALLED || it.installState == PluginInstallState.CONNECTED }

    @Synchronized
    fun connectedServices(): List<PluginRecord> = plugins.values.filter { it.installState == PluginInstallState.CONNECTED }

    private val executionHistory = LinkedHashMap<String, ArrayDeque<PluginExecutionRecord>>()
    private val healthSignals = LinkedHashMap<String, PluginHealth>()
}
