package com.opendroid.ai.core.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's approval gate for an incoming remote request.
 *
 * The transport thread calls [requestApproval] and blocks; the UI shows [current] and calls
 * [decide]. If the owner does not answer within the timeout, the answer is Deny - the same
 * deny-on-timeout default the security core uses, applied here so a headless request can never
 * execute on silence.
 */
class RemoteApprovalGate(
    private val defaultTimeoutMs: Long = RemoteControlService.DEFAULT_APPROVAL_TIMEOUT_MS,
) {

    /** What the owner is being asked to allow. It never contains a secret. */
    data class Pending(
        val deviceId: String,
        val toolName: String,
        val risk: RemoteRisk,
        val summary: String,
    )

    private val lock = Object()
    private val mutableCurrent = MutableStateFlow<Pending?>(null)
    val current: StateFlow<Pending?> = mutableCurrent.asStateFlow()

    @Volatile
    private var decision: Boolean? = null

    /** Blocks until the owner answers or the timeout elapses. Returns true only on Approve. */
    fun requestApproval(pending: Pending, timeoutMs: Long = defaultTimeoutMs): Boolean {
        synchronized(lock) {
            decision = null
            mutableCurrent.value = pending
        }
        try {
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (decision == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
                return decision == true
            }
        } finally {
            synchronized(lock) {
                decision = null
                mutableCurrent.value = null
            }
        }
    }

    /** The owner's answer. Wakes [requestApproval] if it is waiting. */
    fun decide(approved: Boolean) {
        synchronized(lock) {
            decision = approved
            lock.notifyAll()
        }
    }
}
