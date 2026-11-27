package com.opendroid.ai.core.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stage 1 persistence for the plugin registry.
 *
 * Only the owner's decisions and the plugin metadata are stored. On restore, each record is put
 * back through [PluginRegistry.register] followed by the owner's approval, so the same
 * capability-intersection rule applies after a restart as it did when the plugin was first added.
 * A record whose manifest no longer declares a previously granted capability comes back with the
 * smaller effective set - restoring never widens a grant.
 */
@Serializable
private data class PersistedPlugin(
    val id: String,
    @SerialName("displayName") val displayName: String,
    val publisher: String,
    val homepageUrl: String,
    val supportedLanguages: List<String> = emptyList(),
    val supportedRegions: List<String> = emptyList(),
    val declaredCapabilities: List<String> = emptyList(),
    val risk: String = "LOW",
    val approvalState: String = "UNAPPROVED",
    val grantedCapabilities: List<String> = emptyList(),
)

class PluginRegistryPersistence(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) {

    fun serialize(registry: PluginRegistry): String {
        val records = registry.all().map { record ->
            PersistedPlugin(
                id = record.id,
                displayName = record.displayName,
                publisher = record.publisher,
                homepageUrl = record.homepageUrl,
                supportedLanguages = record.supportedLanguages.sorted(),
                supportedRegions = record.supportedRegions.sorted(),
                declaredCapabilities = record.declaredCapabilities.map { it.name }.sorted(),
                risk = record.risk.name,
                approvalState = record.approvalState.name,
                grantedCapabilities = record.grantedCapabilities.map { it.name }.sorted(),
            )
        }
        return json.encodeToString(records)
    }

    /** Restores into [registry]. Malformed entries are skipped rather than failing the whole load. */
    fun restore(registry: PluginRegistry, payload: String?) {
        if (payload.isNullOrBlank()) return
        val records = runCatching { json.decodeFromString<List<PersistedPlugin>>(payload) }.getOrNull() ?: return
        records.forEach { persisted ->
            val record = runCatching {
                PluginRecord(
                    id = persisted.id,
                    displayName = persisted.displayName,
                    publisher = persisted.publisher,
                    homepageUrl = persisted.homepageUrl,
                    supportedLanguages = persisted.supportedLanguages.toSet(),
                    supportedRegions = persisted.supportedRegions.toSet(),
                    declaredCapabilities = persisted.declaredCapabilities.mapNotNull {
                        runCatching { PluginCapability.valueOf(it) }.getOrNull()
                    }.toSet(),
                    risk = runCatching { PluginRisk.valueOf(persisted.risk) }.getOrDefault(PluginRisk.LOW),
                )
            }.getOrNull() ?: return@forEach

            runCatching { registry.register(record) }.onFailure { return@forEach }

            val granted = persisted.grantedCapabilities.mapNotNull {
                runCatching { PluginCapability.valueOf(it) }.getOrNull()
            }.toSet()

            when (persisted.approvalState) {
                PluginApprovalState.APPROVED.name ->
                    runCatching { registry.approve(persisted.id, granted) }
                PluginApprovalState.REVOKED.name ->
                    runCatching { registry.revoke(persisted.id) }
                else -> Unit
            }
        }
    }
}
