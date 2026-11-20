package com.opendroid.ai.core.remote

/**
 * Binds [ReconnectionCoordinator] to the real paired-desktop store and a connect function.
 *
 * The coordinator knows the *policy* (who wants to be connected, how long to back off); this class
 * knows *which* computers exist and how to reach one. It holds no Android types, so a cold start, a
 * reboot, and a dropped connection are all exercised on the JVM.
 *
 * The target id is the paired desktop's `deviceId`, so a persisted desire and a paired record refer
 * to the same computer.
 */
class RemoteReconnectSupervisor(
    private val coordinator: ReconnectionCoordinator,
    private val paired: PairedDesktopStore,
    private val connect: suspend (PairedDesktop) -> Boolean,
) {

    /** The targets a cold start or a reboot must reconnect. */
    fun targetsToReconnectOnStartup(): List<ReconnectState> = coordinator.targetsToReconnectOnStartup()

    /** Record that the owner wants this computer connected; the desire survives a restart. */
    fun wantConnected(deviceId: String, reason: ReconnectReason): ReconnectState =
        coordinator.wantConnected(deviceId, reason)

    /** Record that the owner no longer wants this computer connected. */
    fun wantDisconnected(deviceId: String): ReconnectState? = coordinator.wantDisconnected(deviceId)

    fun state(deviceId: String): ReconnectState? = coordinator.state(deviceId)

    /**
     * Drive every desired target to connected or given-up. A target whose paired record is gone
     * (the owner forgot the computer) stops being wanted rather than retrying forever.
     * [sleep] is injected so a test runs the whole backoff schedule instantly.
     */
    suspend fun reconnectAll(
        reason: ReconnectReason,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Map<String, ReconnectState> {
        val results = LinkedHashMap<String, ReconnectState>()
        for (target in coordinator.targetsToReconnectOnStartup()) {
            val desktop = paired.load().firstOrNull { it.deviceId == target.targetId }
            if (desktop == null) {
                coordinator.wantDisconnected(target.targetId)
                coordinator.state(target.targetId)?.let { results[target.targetId] = it }
                continue
            }
            results[target.targetId] = coordinator.reconnect(
                targetId = target.targetId,
                reason = reason,
                connect = { connect(desktop) },
                sleep = sleep,
            )
        }
        return results
    }
}
