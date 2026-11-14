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

/**
 * A plugin record. [grantedCapabilities] is what the owner has actually granted; it is always a
 * subset of [declaredCapabilities]. [effectiveCapabilities] is what the plugin may actually use.
 */
data class PluginRecord(
    val id: String,
    val displayName: String,
    val publisher: String,
    val homepageUrl: String,
    val supportedLanguages: Set<String> = emptySet(),
    val supportedRegions: Set<String> = emptySet(),
    val declaredCapabilities: Set<PluginCapability> = emptySet(),
    val risk: PluginRisk = PluginRisk.LOW,
    val approvalState: PluginApprovalState = PluginApprovalState.UNAPPROVED,
    val grantedCapabilities: Set<PluginCapability> = emptySet(),
) {
    /** What the plugin may actually do: granted capabilities the plugin still declares. */
    val effectiveCapabilities: Set<PluginCapability>
        get() = if (approvalState == PluginApprovalState.APPROVED) {
            grantedCapabilities intersect declaredCapabilities
        } else {
            emptySet()
        }

    val isCallable: Boolean get() = approvalState == PluginApprovalState.APPROVED
}

/** A candidate surfaced by discovery. It is a suggestion only and carries no approval. */
data class PluginCandidate(
    val id: String,
    val displayName: String,
    val homepageUrl: String,
    val source: String,
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
    }

    /** Register a plugin. Re-registering the same id can never widen a previously granted set. */
    @Synchronized
    fun register(record: PluginRecord) {
        require(record.id.matches(ID_PATTERN)) { "Invalid plugin id" }
        val existing = plugins[record.id]
        val merged = if (existing == null) {
            record.copy(grantedCapabilities = emptySet(), approvalState = PluginApprovalState.UNAPPROVED)
        } else {
            // A manifest update may shrink what the plugin declares but never expand the grant:
            // the effective grant stays the intersection of the owner's grant and the new manifest.
            record.copy(
                approvalState = existing.approvalState,
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
}
