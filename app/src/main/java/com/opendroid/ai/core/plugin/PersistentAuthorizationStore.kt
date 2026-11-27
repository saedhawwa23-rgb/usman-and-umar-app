package com.opendroid.ai.core.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.opendroid.ai.core.security.EncryptedJsonListStore
import com.opendroid.ai.core.security.SecretBytesStore
import java.io.File

/**
 * Persistent, unattended authorization for plugins.
 *
 * [PluginRegistry] holds the owner's decision in memory and [PluginRegistryPersistence] round-trips
 * it, but neither expresses *time*: an unattended automation needs a grant that can expire, a
 * per-capability scope, and a revocation that takes effect immediately and survives a restart.
 *
 * This store is that record. It is deliberately separate from the registry so a grant can be
 * audited and listed on its own, and every mutation is persisted through a [PluginGrantStore] before
 * it is reported as done. [isAuthorized] is the only thing a caller needs: it returns a decision
 * with a specific reason, so an unattended run can explain a refusal instead of stopping silently.
 */
enum class GrantScope {
    /** The plugin may be used only while the owner is interacting (foreground). */
    FOREGROUND,

    /** The plugin may be used unattended, including with the screen off. */
    UNATTENDED,
}

/** Why a grant is not currently usable. [NONE] means it is usable. */
enum class GrantRefusal(val message: String) {
    NONE("Grant is active."),
    NOT_GRANTED("No unattended grant exists for this plugin."),
    REVOKED("The owner revoked this plugin's grant."),
    EXPIRED("The plugin's grant has expired."),
    CAPABILITY_NOT_GRANTED("The requested capability is outside this grant's scope."),
    BUDGET_EXHAUSTED("The plugin's grant has reached its maximum number of uses."),
}

/**
 * One persisted grant. [expiresAtMs] null means "no expiry"; [maxUses] null means "unlimited".
 * [uses] counts authorized calls so a bounded grant can be exhausted.
 */
@Serializable
data class PluginGrant(
    val pluginId: String,
    val capabilities: Set<PluginCapability>,
    val scope: GrantScope = GrantScope.FOREGROUND,
    val grantedAtMs: Long = 0L,
    val expiresAtMs: Long? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
    val revoked: Boolean = false,
    @SerialName("revoked_at_ms") val revokedAtMs: Long? = null,
) {
    /** Whether this grant would allow [capability] at [atMs], ignoring the use budget. */
    fun covers(capability: PluginCapability?, atMs: Long): GrantRefusal {
        if (revoked) return GrantRefusal.REVOKED
        if (expiresAtMs != null && atMs >= expiresAtMs) return GrantRefusal.EXPIRED
        if (capability != null && capability !in capabilities) return GrantRefusal.CAPABILITY_NOT_GRANTED
        return GrantRefusal.NONE
    }

    fun isActive(atMs: Long): Boolean = covers(null, atMs) == GrantRefusal.NONE

    /** Whole seconds until expiry, or null when the grant never expires. Floored at zero. */
    fun secondsUntilExpiry(atMs: Long): Long? =
        expiresAtMs?.let { ((it - atMs).coerceAtLeast(0L)) / 1000L }
}

/**
 * The expiry choices the owner can pick from when granting unattended access. [NEVER] is a
 * deliberate "until I revoke it" option; [DAYS_30] and [DAYS_90] are the common cases; [CUSTOM] is
 * any other period. The preset is turned into a ttl at the moment of the grant.
 */
enum class GrantExpiry(val ttlMs: Long?) {
    NEVER(null),
    DAYS_30(30L * 24 * 60 * 60 * 1000),
    DAYS_90(90L * 24 * 60 * 60 * 1000),
    CUSTOM(null);

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** A custom period in whole days, or null for "until revoked". */
        fun customDays(days: Long?): GrantExpiry = if (days == null) NEVER else CUSTOM

        fun ttlForCustomDays(days: Long): Long {
            require(days > 0) { "A custom expiry must be at least one day" }
            return days * DAY_MS
        }
    }
}

/** Where grants live. The file store makes a grant survive a restart. */
interface PluginGrantStore {
    fun load(pluginId: String): PluginGrant?
    fun save(grant: PluginGrant)
    fun all(): List<PluginGrant>
    fun remove(pluginId: String): Boolean
}

class InMemoryPluginGrantStore : PluginGrantStore {
    private val grants = LinkedHashMap<String, PluginGrant>()

    @Synchronized
    override fun load(pluginId: String): PluginGrant? = grants[pluginId]

    @Synchronized
    override fun save(grant: PluginGrant) {
        grants[grant.pluginId] = grant
    }

    @Synchronized
    override fun all(): List<PluginGrant> = grants.values.toList()

    @Synchronized
    override fun remove(pluginId: String): Boolean = grants.remove(pluginId) != null
}

/** One JSON file, written atomically via a temp file and a rename. */
class FilePluginGrantStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : PluginGrantStore {

    private val cache = LinkedHashMap<String, PluginGrant>()

    init {
        loadFromDisk().forEach { cache[it.pluginId] = it }
    }

    @Synchronized
    override fun load(pluginId: String): PluginGrant? = cache[pluginId]

    @Synchronized
    override fun save(grant: PluginGrant) {
        cache[grant.pluginId] = grant
        flush()
    }

    @Synchronized
    override fun all(): List<PluginGrant> = cache.values.toList()

    @Synchronized
    override fun remove(pluginId: String): Boolean {
        val removed = cache.remove(pluginId) != null
        if (removed) flush()
        return removed
    }

    private fun loadFromDisk(): List<PluginGrant> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<PluginGrant>>(text) }.getOrDefault(emptyList())
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        val payload = json.encodeToString(cache.values.toList())
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }
}

/**
 * The same contract as [FilePluginGrantStore], but the bytes on disk are Keystore-encrypted.
 *
 * A grant records the authority to run a plugin unattended, so it is stored the way pairing secrets
 * are: through [SecretBytesStore], which has no plaintext fallback. A grant that cannot be decrypted
 * is treated as absent, so a restored process never resumes with authority it cannot prove, and a
 * revoked grant stays revoked because the revoked record is what was persisted.
 */
class EncryptedPluginGrantStore(
    secrets: SecretBytesStore,
    key: String = "plugin-grants",
) : PluginGrantStore {

    private val delegate = EncryptedJsonListStore(
        secrets = secrets,
        key = key,
        serializer = PluginGrant.serializer(),
    )

    @Synchronized
    override fun load(pluginId: String): PluginGrant? = delegate.load().firstOrNull { it.pluginId == pluginId }

    @Synchronized
    override fun save(grant: PluginGrant) {
        val merged = delegate.load().filterNot { it.pluginId == grant.pluginId } + grant
        delegate.save(merged)
    }

    @Synchronized
    override fun all(): List<PluginGrant> = delegate.load()

    @Synchronized
    override fun remove(pluginId: String): Boolean {
        val current = delegate.load()
        val next = current.filterNot { it.pluginId == pluginId }
        if (next.size == current.size) return false
        delegate.save(next)
        return true
    }
}

data class GrantDecision(
    val authorized: Boolean,
    val reason: GrantRefusal,
    val capabilities: Set<PluginCapability> = emptySet(),
) {
    val message: String get() = reason.message
}

/**
 * The authority for unattended plugin use.
 *
 * A grant is created only by [grant], which intersects the requested capabilities with what the
 * plugin declares, exactly like the registry, so this store can never widen a grant. [revoke] is
 * immediate: the record is marked revoked and persisted before the call returns, and a revoked
 * grant is never re-activated by a later read. [isAuthorized] also consults the plugin registry so a
 * plugin that the owner disabled or removed is refused here too.
 */
class PersistentAuthorizationStore(
    private val grants: PluginGrantStore = InMemoryPluginGrantStore(),
    private val pluginRegistry: PluginRegistry? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val auditSink: (String) -> Unit = {},
) {

    /** Create or replace a grant. [capabilities] is intersected with the plugin's declared set. */
    @Synchronized
    fun grant(
        pluginId: String,
        capabilities: Set<PluginCapability>,
        scope: GrantScope = GrantScope.UNATTENDED,
        ttlMs: Long? = null,
        maxUses: Int? = null,
    ): PluginGrant {
        require(pluginId.isNotBlank()) { "Plugin id must not be blank" }
        require(maxUses == null || maxUses > 0) { "Max uses must be positive" }
        require(ttlMs == null || ttlMs > 0) { "Expiry must be in the future" }
        val declared = pluginRegistry?.get(pluginId)?.declaredCapabilities
        val effective = if (declared != null) capabilities intersect declared else capabilities
        val now = clock()
        val existing = grants.load(pluginId)
        val grant = PluginGrant(
            pluginId = pluginId,
            capabilities = effective,
            scope = scope,
            grantedAtMs = now,
            expiresAtMs = ttlMs?.let { now + it },
            maxUses = maxUses,
            // A re-grant after a revoke starts the use counter fresh but keeps it explicit.
            uses = if (existing?.revoked == false) existing.uses else 0,
            revoked = false,
            revokedAtMs = null,
        )
        grants.save(grant)
        audit("granted $pluginId scope=$scope capabilities=${effective.map { it.name }.sorted()} expiresAt=${grant.expiresAtMs}")
        return grant
    }

    /**
     * Grant with one of the owner's expiry presets, so the UI's 30-day, 90-day, custom and "until
     * revoked" choices all funnel through the same, already-audited [grant] path.
     */
    @Synchronized
    fun grantWithExpiry(
        pluginId: String,
        capabilities: Set<PluginCapability>,
        expiry: GrantExpiry,
        scope: GrantScope = GrantScope.UNATTENDED,
        customDays: Long? = null,
        maxUses: Int? = null,
    ): PluginGrant {
        val ttl = when (expiry) {
            GrantExpiry.NEVER -> null
            GrantExpiry.DAYS_30, GrantExpiry.DAYS_90 -> expiry.ttlMs
            GrantExpiry.CUSTOM -> ttlForCustom(customDays)
        }
        return grant(pluginId, capabilities, scope, ttlMs = ttl, maxUses = maxUses)
    }

    private fun ttlForCustom(days: Long?): Long {
        require(days != null) { "A custom expiry needs a number of days" }
        return GrantExpiry.ttlForCustomDays(days)
    }

    /** Extend or shorten an existing grant's expiry without changing its capabilities. */
    @Synchronized
    fun setExpiry(pluginId: String, expiresAtMs: Long?): PluginGrant? {
        val existing = grants.load(pluginId) ?: return null
        val updated = existing.copy(expiresAtMs = expiresAtMs)
        grants.save(updated)
        audit("expiry for $pluginId set to $expiresAtMs")
        return updated
    }

    /**
     * Revoke immediately. The record is marked and persisted before this returns, so a call that
     * races with the revocation sees the revoked record. Returns false when there is no grant.
     */
    @Synchronized
    fun revoke(pluginId: String): Boolean {
        val existing = grants.load(pluginId) ?: return false
        val revoked = existing.copy(revoked = true, revokedAtMs = clock())
        grants.save(revoked)
        audit("revoked $pluginId")
        return true
    }

    @Synchronized
    fun revokeAll(): Int {
        var count = 0
        grants.all().forEach { grant ->
            if (!grant.revoked) {
                grants.save(grant.copy(revoked = true, revokedAtMs = clock()))
                count++
            }
        }
        if (count > 0) audit("revoked all grants ($count)")
        return count
    }

    @Synchronized
    fun grantFor(pluginId: String): PluginGrant? = grants.load(pluginId)

    @Synchronized
    fun all(): List<PluginGrant> = grants.all()

    /** Grants that are active right now: not revoked, not expired, and not out of uses. */
    @Synchronized
    fun active(): List<PluginGrant> {
        val now = clock()
        return grants.all().filter { it.isActive(now) && (it.maxUses == null || it.uses < it.maxUses) }
    }

    /**
     * The check an unattended run makes before every call. It refuses with a specific reason:
     * a missing, revoked, expired, over-budget or out-of-scope grant, or a plugin the registry
     * refuses. On success the use counter is incremented and persisted.
     */
    @Synchronized
    fun isAuthorized(pluginId: String, capability: PluginCapability? = null): GrantDecision {
        val decision = check(pluginId, capability)
        if (!decision.authorized) return decision
        val grant = grants.load(pluginId) ?: return GrantDecision(false, GrantRefusal.NOT_GRANTED, emptySet())
        grants.save(grant.copy(uses = grant.uses + 1))
        return decision
    }

    /**
     * The same decision as [isAuthorized] without consuming a use. A UI that asks "may this run?"
     * must not spend the budget, so the gate consumes and a status query does not.
     */
    @Synchronized
    fun check(pluginId: String, capability: PluginCapability? = null): GrantDecision {
        val now = clock()
        pluginRegistry?.let { registry ->
            when (val authorization = registry.authorize(pluginId, capability)) {
                is PluginAuthorization.Denied ->
                    return GrantDecision(false, GrantRefusal.NOT_GRANTED, emptySet()).also {
                        audit("refused $pluginId: registry=${authorization.reason}")
                    }
                is PluginAuthorization.Allowed -> Unit
            }
        }
        val grant = grants.load(pluginId)
            ?: return GrantDecision(false, GrantRefusal.NOT_GRANTED, emptySet())
        val refusal = grant.covers(capability, now)
        if (refusal != GrantRefusal.NONE) {
            audit("refused $pluginId: $refusal")
            return GrantDecision(false, refusal, emptySet())
        }
        if (grant.maxUses != null && grant.uses >= grant.maxUses) {
            audit("refused $pluginId: budget exhausted (${grant.uses}/${grant.maxUses})")
            return GrantDecision(false, GrantRefusal.BUDGET_EXHAUSTED, emptySet())
        }
        return GrantDecision(true, GrantRefusal.NONE, grant.capabilities)
    }

    private fun audit(message: String) = auditSink(message)
}
