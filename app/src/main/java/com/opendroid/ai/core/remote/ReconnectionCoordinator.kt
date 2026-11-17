package com.opendroid.ai.core.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Automatic reconnection for a paired desktop, across the three ways a session actually dies:
 * a phone restart, an app restart, and a network interruption.
 *
 * The rule that makes this work is that "the owner wants this connection" is *persisted*, while
 * "we are connected right now" is not. On a cold start the coordinator reads the persisted desire
 * and reconnects; when a heartbeat is lost it backs off and retries instead of giving up. The
 * backoff is a pure function of the attempt number, so the whole schedule is tested on the JVM
 * without a socket, a device, or a wall clock.
 */
@Serializable
enum class ReconnectReason {
    /** The app started and a connection was previously wanted. */
    APP_START,

    /** The phone rebooted and a connection was previously wanted. */
    DEVICE_BOOT,

    /** A live connection dropped (heartbeat lost, socket closed, network changed). */
    NETWORK_INTERRUPTED,

    /** The owner asked to reconnect. */
    MANUAL,
}

@Serializable
data class ReconnectState(
    val targetId: String,
    val desired: Boolean,
    val attempts: Int = 0,
    val lastAttemptAtMs: Long = 0L,
    val lastConnectedAtMs: Long = 0L,
    val lastReason: ReconnectReason? = null,
    val gaveUp: Boolean = false,
)

/** Deterministic, bounded backoff. No jitter by default so the schedule is reproducible in tests. */
data class ReconnectBackoff(
    val baseDelayMs: Long = 2_000L,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 120_000L,
    val maxAttempts: Int = 8,
) {
    init {
        require(baseDelayMs > 0) { "Base delay must be positive" }
        require(multiplier >= 1.0) { "Multiplier must be at least 1" }
        require(maxDelayMs >= baseDelayMs) { "Max delay must not be below the base delay" }
        require(maxAttempts >= 1) { "There must be at least one attempt" }
    }

    /** Delay before the given attempt, where attempt 1 is the first retry after a failure. */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var delay = baseDelayMs.toDouble()
        repeat(attempt - 1) { delay *= multiplier }
        return delay.coerceAtMost(maxDelayMs.toDouble()).toLong()
    }
}

/** Persistence boundary so the reconnect desire survives a restart. */
interface ReconnectStateStore {
    fun load(): List<ReconnectState>
    fun save(state: ReconnectState)
    fun remove(targetId: String): Boolean
}

class InMemoryReconnectStateStore : ReconnectStateStore {
    private val states = LinkedHashMap<String, ReconnectState>()

    @Synchronized
    override fun load(): List<ReconnectState> = states.values.toList()

    @Synchronized
    override fun save(state: ReconnectState) {
        states[state.targetId] = state
    }

    @Synchronized
    override fun remove(targetId: String): Boolean = states.remove(targetId) != null
}

class FileReconnectStateStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : ReconnectStateStore {
    private val cache = LinkedHashMap<String, ReconnectState>()

    init {
        loadFromDisk().forEach { cache[it.targetId] = it }
    }

    @Synchronized
    override fun load(): List<ReconnectState> = cache.values.toList()

    @Synchronized
    override fun save(state: ReconnectState) {
        cache[state.targetId] = state
        flush()
    }

    @Synchronized
    override fun remove(targetId: String): Boolean {
        val removed = cache.remove(targetId) != null
        if (removed) flush()
        return removed
    }

    private fun loadFromDisk(): List<ReconnectState> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<ReconnectState>>(text) }.getOrDefault(emptyList())
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

/** What to do next for a target. */
sealed interface ReconnectDecision {
    /** Connect now; no wait. */
    data object AttemptNow : ReconnectDecision

    /** Wait [delayMs], then attempt. */
    data class WaitAndRetry(val delayMs: Long, val attempt: Int) : ReconnectDecision

    /** Stop trying and tell the owner why. */
    data class GiveUp(val reason: String) : ReconnectDecision

    /** The owner no longer wants this connection. */
    data object Disabled : ReconnectDecision
}

/**
 * Tracks reconnection for paired targets. It holds no socket: the caller supplies the connect
 * function, so the coordinator can be driven to every state in a unit test.
 */
class ReconnectionCoordinator(
    private val store: ReconnectStateStore = InMemoryReconnectStateStore(),
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Record that the owner wants this target connected. Survives a restart. */
    @Synchronized
    fun wantConnected(targetId: String, reason: ReconnectReason): ReconnectState {
        val existing = store.load().firstOrNull { it.targetId == targetId }
        val state = ReconnectState(
            targetId = targetId,
            desired = true,
            attempts = 0,
            lastAttemptAtMs = existing?.lastAttemptAtMs ?: 0L,
            lastConnectedAtMs = existing?.lastConnectedAtMs ?: 0L,
            lastReason = reason,
            gaveUp = false,
        )
        store.save(state)
        return state
    }

    /** Record that the owner no longer wants this target connected. */
    @Synchronized
    fun wantDisconnected(targetId: String): ReconnectState? {
        val existing = store.load().firstOrNull { it.targetId == targetId } ?: return null
        val state = existing.copy(desired = false, attempts = 0, gaveUp = false)
        store.save(state)
        return state
    }

    @Synchronized
    fun state(targetId: String): ReconnectState? = store.load().firstOrNull { it.targetId == targetId }

    @Synchronized
    fun all(): List<ReconnectState> = store.load()

    /**
     * The targets a cold start must reconnect: desired, and not permanently given up. This is what
     * the app and the boot receiver consult, and it is why a restart reconnects.
     */
    @Synchronized
    fun targetsToReconnectOnStartup(): List<ReconnectState> =
        store.load().filter { it.desired && !it.gaveUp }

    /**
     * Decide what to do for a target that is not currently connected. [attempt] is the number of
     * failures so far; the coordinator does not sleep, it returns the delay so the caller controls
     * the clock (and tests can assert the schedule exactly).
     */
    @Synchronized
    fun nextDecision(targetId: String, attempt: Int): ReconnectDecision {
        val state = store.load().firstOrNull { it.targetId == targetId }
            ?: return ReconnectDecision.Disabled
        if (!state.desired) return ReconnectDecision.Disabled
        if (attempt <= 0) return ReconnectDecision.AttemptNow
        if (attempt >= backoff.maxAttempts) {
            return ReconnectDecision.GiveUp(
                "Gave up after ${backoff.maxAttempts} attempts. Open Remote control to try again."
            )
        }
        return ReconnectDecision.WaitAndRetry(backoff.delayForAttempt(attempt), attempt)
    }

    /** Record a successful connection: the attempt counter resets so the next drop starts fresh. */
    @Synchronized
    fun recordConnected(targetId: String, reason: ReconnectReason): ReconnectState {
        val existing = store.load().firstOrNull { it.targetId == targetId }
        val state = ReconnectState(
            targetId = targetId,
            desired = true,
            attempts = 0,
            lastAttemptAtMs = clock(),
            lastConnectedAtMs = clock(),
            lastReason = reason,
            gaveUp = false,
        )
        store.save(state)
        return state
    }

    /** Record a failed attempt and return the new state (its attempts drive the next decision). */
    @Synchronized
    fun recordFailure(targetId: String, reason: ReconnectReason): ReconnectState {
        val existing = store.load().firstOrNull { it.targetId == targetId }
            ?: ReconnectState(targetId = targetId, desired = true)
        val attempts = existing.attempts + 1
        val state = existing.copy(
            attempts = attempts,
            lastAttemptAtMs = clock(),
            lastReason = reason,
            gaveUp = attempts >= backoff.maxAttempts,
        )
        store.save(state)
        return state
    }

    /**
     * Drive reconnection to a single outcome: connect, or give up after the bounded schedule.
     * [connect] returns true on success. [sleep] is injected so a test can run the whole schedule
     * instantly and assert the exact delays. Not synchronized as a whole: it suspends, so it mutates
     * state only through the individually synchronized methods above.
     */
    suspend fun reconnect(
        targetId: String,
        reason: ReconnectReason,
        connect: suspend () -> Boolean,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): ReconnectState {
        var attempt = state(targetId)?.attempts ?: 0
        while (true) {
            when (val decision = nextDecision(targetId, attempt)) {
                ReconnectDecision.Disabled -> return state(targetId)
                    ?: ReconnectState(targetId, desired = false)
                is ReconnectDecision.GiveUp -> {
                    store.save(
                        (state(targetId) ?: ReconnectState(targetId, desired = true))
                            .copy(gaveUp = true, lastReason = reason)
                    )
                    return state(targetId)!!
                }
                is ReconnectDecision.WaitAndRetry -> sleep(decision.delayMs)
                ReconnectDecision.AttemptNow -> Unit
            }
            if (connect()) return recordConnected(targetId, reason)
            recordFailure(targetId, reason)
            attempt += 1
        }
    }
}
