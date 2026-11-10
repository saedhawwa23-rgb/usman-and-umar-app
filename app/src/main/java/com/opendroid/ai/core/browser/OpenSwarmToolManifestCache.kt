package com.opendroid.ai.core.browser

/** Process-local tool manifest cache only; it never contains credentials or payloads. */
object OpenSwarmToolManifestCache {
    @Volatile private var manifest: List<OpenSwarmRemoteTool> = emptyList()
    @Volatile var refreshedAtEpochMs: Long = 0
        private set

    fun update(tools: List<OpenSwarmRemoteTool>) {
        manifest = tools.toList()
        refreshedAtEpochMs = System.currentTimeMillis()
    }

    fun snapshot(): List<OpenSwarmRemoteTool> = manifest.toList()
}
