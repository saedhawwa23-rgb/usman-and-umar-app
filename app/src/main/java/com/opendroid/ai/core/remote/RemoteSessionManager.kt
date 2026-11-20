package com.opendroid.ai.core.remote

/**
 * Session lifecycle for an authenticated remote-control connection.
 *
 * This exists to separate two things that were previously conflated:
 *
 *  - the pairing code, which is deliberately short-lived (about five minutes, single use), and
 *  - the established session, which must NOT end merely because five minutes have passed.
 *
 * A session ends only for an explicit reason: the owner disconnected it, the owner revoked the
 * device, the session was idle past the inactivity policy, it reached the maximum duration, its
 * heartbeat was lost for longer than the reconnect grace, or the kill switch fired. None of those
 * is a five-minute timer, and none of them weakens authentication: every action on the session is
 * still a fresh signed request that passes [RemoteControlService.authenticate].
 *
 * The manager is pure and clock-injected, so the whole lifecycle is exercised on the JVM without
 * a device or a socket.
 */
enum class RemoteSessionState {
    /** A pairing code is live; no authenticated session exists yet. */
    PAIRING,

    /** Authenticated and healthy. */
    CONNECTED,

    /** The heartbeat was missed; the transport is trying to recover within the grace window. */
    RECONNECTING,

    /** The connection dropped and did not recover inside the grace window. */
    DISCONNECTED,

    /** Ended by policy (inactivity or maximum duration). */
    EXPIRED,

    /** Ended by the owner revoking the device. */
    REVOKED,
}

/** Why a session is no longer connected. Shown to the owner so the end is never unexplained. */
enum class SessionEndReason(val message: String) {
    NONE("Session is active."),
    OWNER_DISCONNECTED("You disconnected this session."),
    OWNER_REVOKED("You revoked this device's access."),
    INACTIVITY_TIMEOUT("The session ended after a period of inactivity."),
    MAX_DURATION_REACHED("The session reached its maximum duration and ended."),
    HEARTBEAT_LOST("The connection was lost and did not recover in time."),
    KILL_SWITCH("Remote control was disabled by the kill switch."),
}

/**
 * The rules that govern how long a session may live and when it must end. Every value is a policy
 * decision, not a hard-coded pairing-code value; [pairingCodeTtlMs] is the only one tied to
 * pairing, and it never applies to an established session.
 */
data class RemoteSessionPolicy(
    val pairingCodeTtlMs: Long = RemoteControlService.PAIRING_CODE_TTL_MS,
    val inactivityTimeoutMs: Long = 15 * 60 * 1000L,
    val maxSessionDurationMs: Long = 12 * 60 * 60 * 1000L,
    val heartbeatIntervalMs: Long = 15 * 1000L,
    val heartbeatTimeoutMs: Long = 45 * 1000L,
    val reconnectGraceMs: Long = 60 * 1000L,
)

/**
 * One session for one paired device. [startedAtMs] is set when the device becomes CONNECTED, not
 * when pairing starts, so pairing time never counts against the session budget.
 */
data class RemoteSession(
    val deviceId: String,
    val state: RemoteSessionState,
    val startedAtMs: Long,
    val lastHeartbeatMs: Long,
    val lastActivityMs: Long,
    val endReason: SessionEndReason = SessionEndReason.NONE,
) {
    val isLive: Boolean
        get() = state == RemoteSessionState.CONNECTED || state == RemoteSessionState.RECONNECTING
}

/**
 * Tracks the lifecycle of every authenticated session. Pure: it holds no sockets and no keys, and
 * every method takes an explicit timestamp so behaviour is deterministic under test.
 */
class RemoteSessionManager(
    private val policy: RemoteSessionPolicy = RemoteSessionPolicy(),
    private val auditSink: (String) -> Unit = {},
) {

    private val sessions = LinkedHashMap<String, RemoteSession>()

    @Synchronized
    fun policy(): RemoteSessionPolicy = policy

    @Synchronized
    fun session(deviceId: String): RemoteSession? = sessions[deviceId]

    @Synchronized
    fun all(): List<RemoteSession> = sessions.values.toList()

    @Synchronized
    fun live(): List<RemoteSession> = sessions.values.filter { it.isLive }

    /** A pairing code has been shown. No session budget is consumed yet. */
    @Synchronized
    fun beginPairing(deviceId: String, atMs: Long): RemoteSession {
        val session = RemoteSession(
            deviceId = deviceId,
            state = RemoteSessionState.PAIRING,
            startedAtMs = 0L,
            lastHeartbeatMs = atMs,
            lastActivityMs = atMs,
        )
        sessions[deviceId] = session
        audit("pairing started for $deviceId")
        return session
    }

    /** Pairing succeeded and the device is authenticated. The session clock starts here. */
    @Synchronized
    fun markConnected(deviceId: String, atMs: Long): RemoteSession {
        val session = RemoteSession(
            deviceId = deviceId,
            state = RemoteSessionState.CONNECTED,
            startedAtMs = atMs,
            lastHeartbeatMs = atMs,
            lastActivityMs = atMs,
        )
        sessions[deviceId] = session
        audit("session connected for $deviceId")
        return session
    }

    /** A heartbeat arrived. A reconnecting session recovers; a healthy one just refreshes. */
    @Synchronized
    fun recordHeartbeat(deviceId: String, atMs: Long): RemoteSession? {
        val session = sessions[deviceId] ?: return null
        val recovered = session.state == RemoteSessionState.RECONNECTING
        val updated = session.copy(
            state = if (recovered) RemoteSessionState.CONNECTED else session.state,
            lastHeartbeatMs = atMs,
        )
        sessions[deviceId] = updated
        if (recovered) audit("session reconnected for $deviceId")
        return updated
    }

    /** An authenticated action arrived; it counts as activity and as a heartbeat. */
    @Synchronized
    fun recordActivity(deviceId: String, atMs: Long): RemoteSession? {
        val session = sessions[deviceId] ?: return null
        val updated = session.copy(
            state = if (session.state == RemoteSessionState.RECONNECTING) RemoteSessionState.CONNECTED else session.state,
            lastHeartbeatMs = atMs,
            lastActivityMs = atMs,
        )
        sessions[deviceId] = updated
        return updated
    }

    /**
     * Evaluate every live session against the policy. Called on a timer. This is the only place a
     * session is ended by time, and the only reasons are the explicit policy ones.
     */
    @Synchronized
    fun tick(atMs: Long): List<RemoteSession> {
        val ended = mutableListOf<RemoteSession>()
        for ((deviceId, session) in sessions.toList()) {
            if (!session.isLive) continue
            val updated = when {
                session.startedAtMs > 0 && atMs - session.startedAtMs >= policy.maxSessionDurationMs ->
                    session.copy(state = RemoteSessionState.EXPIRED, endReason = SessionEndReason.MAX_DURATION_REACHED)

                atMs - session.lastActivityMs >= policy.inactivityTimeoutMs ->
                    session.copy(state = RemoteSessionState.EXPIRED, endReason = SessionEndReason.INACTIVITY_TIMEOUT)

                session.state == RemoteSessionState.CONNECTED &&
                    atMs - session.lastHeartbeatMs >= policy.heartbeatTimeoutMs ->
                    session.copy(state = RemoteSessionState.RECONNECTING)

                session.state == RemoteSessionState.RECONNECTING &&
                    atMs - session.lastHeartbeatMs >= policy.heartbeatTimeoutMs + policy.reconnectGraceMs ->
                    session.copy(state = RemoteSessionState.DISCONNECTED, endReason = SessionEndReason.HEARTBEAT_LOST)

                else -> null
            }
            if (updated != null) {
                sessions[deviceId] = updated
                if (!updated.isLive) {
                    audit("session ended for $deviceId: ${updated.endReason.name}")
                    ended.add(updated)
                }
            }
        }
        return ended
    }

    /** The owner disconnects the session immediately. */
    @Synchronized
    fun disconnect(deviceId: String, atMs: Long): RemoteSession? {
        val session = sessions[deviceId] ?: return null
        val updated = session.copy(state = RemoteSessionState.DISCONNECTED, endReason = SessionEndReason.OWNER_DISCONNECTED)
        sessions[deviceId] = updated
        audit("owner disconnected $deviceId")
        return updated
    }

    /** The owner revokes the device. The session ends and cannot resume. */
    @Synchronized
    fun revoke(deviceId: String): RemoteSession? {
        val session = sessions[deviceId] ?: return null
        val updated = session.copy(state = RemoteSessionState.REVOKED, endReason = SessionEndReason.OWNER_REVOKED)
        sessions[deviceId] = updated
        audit("owner revoked $deviceId")
        return updated
    }

    /** The kill switch ends and revokes every session at once. */
    @Synchronized
    fun onKillSwitch(): List<RemoteSession> {
        val affected = mutableListOf<RemoteSession>()
        for ((deviceId, session) in sessions.toList()) {
            if (!session.isLive) continue
            val updated = session.copy(state = RemoteSessionState.REVOKED, endReason = SessionEndReason.KILL_SWITCH)
            sessions[deviceId] = updated
            affected.add(updated)
        }
        if (affected.isNotEmpty()) audit("kill switch ended ${affected.size} session(s)")
        return affected
    }

    /** Whole seconds left before the inactivity policy would end a live session, floored at zero. */
    @Synchronized
    fun idleSecondsRemaining(deviceId: String, atMs: Long): Long {
        val session = sessions[deviceId] ?: return 0L
        if (!session.isLive) return 0L
        return ((session.lastActivityMs + policy.inactivityTimeoutMs - atMs).coerceAtLeast(0L)) / 1000L
    }

    private fun audit(message: String) = auditSink(message)
}
