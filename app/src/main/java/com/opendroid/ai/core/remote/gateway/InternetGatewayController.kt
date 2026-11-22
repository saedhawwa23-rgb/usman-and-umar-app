package com.opendroid.ai.core.remote.gateway

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.opendroid.ai.remote.gateway.GatewayConfig
import com.opendroid.ai.remote.gateway.GatewayPeer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the phone side of the desktop internet gateway.
 *
 * The tunnel itself lives in [GatewayTunnelService]; this class holds the state the UI reads and
 * decides whether starting is allowed. It never stores keys: callers hand in a [GatewayPeer] whose
 * private key was read from encrypted storage.
 */
class InternetGatewayController(private val context: Context) {

    enum class Mode { FULL, SPLIT }

    data class State(
        val running: Boolean = false,
        val mode: Mode = Mode.FULL,
        val splitRoutes: List<String> = emptyList(),
        val endpoint: String? = null,
        val desktopName: String? = null,
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        val connectedSinceMs: Long = 0,
        val lastHandshakeMs: Long = 0,
        val error: String? = null,
        val needsPermission: Boolean = false,
    ) {
        val durationMs: Long
            get() = if (running && connectedSinceMs > 0) System.currentTimeMillis() - connectedSinceMs else 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether the OS has already granted VPN consent.
     *
     * Android asks once per app; after that [VpnService.prepare] returns null.
     */
    fun hasPermission(): Boolean = VpnService.prepare(context) == null

    /** The consent dialog to launch from an Activity, or null when permission already exists. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    fun setMode(mode: Mode, splitRoutes: List<String> = emptyList()) {
        _state.value = _state.value.copy(mode = mode, splitRoutes = splitRoutes)
    }

    /**
     * Start the tunnel.
     *
     * Returns false when consent is missing; the caller launches [consentIntent] and retries. Nothing
     * is started half-way: either the service is asked to come up, or the state says what is missing.
     */
    fun start(peer: GatewayPeer, desktopName: String? = null): Boolean {
        if (peer.privateKey.isBlank() || peer.desktopPublicKey.isBlank()) {
            _state.value = _state.value.copy(
                error = "This computer's gateway keys are missing. Pair the computer again.",
            )
            return false
        }
        if (!hasPermission()) {
            _state.value = _state.value.copy(
                needsPermission = true,
                error = "Android needs your permission before a VPN can be created.",
            )
            return false
        }

        val current = _state.value
        val allowed = GatewayConfig.allowedIps(current.mode == Mode.FULL, current.splitRoutes)
        Log.i(TAG, "starting gateway endpoint=${peer.endpoint} mode=${current.mode} routes=${allowed.size}")

        _state.value = current.copy(
            running = true,
            endpoint = peer.endpoint,
            desktopName = desktopName,
            connectedSinceMs = System.currentTimeMillis(),
            error = null,
            needsPermission = false,
            bytesIn = 0,
            bytesOut = 0,
        )
        GatewayTunnelService.start(context, peer, allowed, current.mode == Mode.FULL)
        return true
    }

    /**
     * Stop the tunnel and hand routing back to the phone.
     *
     * State is cleared even if the service was already gone, so the UI can never show a stale
     * "connected" badge over a dead tunnel.
     */
    fun stop() {
        GatewayTunnelService.stop(context)
        _state.value = _state.value.copy(
            running = false,
            connectedSinceMs = 0,
            lastHandshakeMs = 0,
            bytesIn = 0,
            bytesOut = 0,
            error = null,
        )
    }

    fun onStats(bytesIn: Long, bytesOut: Long, lastHandshakeMs: Long) {
        _state.value = _state.value.copy(
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            lastHandshakeMs = lastHandshakeMs,
        )
    }

    fun onServiceStopped(reason: String?) {
        _state.value = _state.value.copy(running = false, connectedSinceMs = 0, error = reason)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, needsPermission = false)
    }

    private companion object {
        const val TAG = "InternetGateway"
    }
}
