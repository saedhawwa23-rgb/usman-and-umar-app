package com.opendroid.ai.core.remote

import com.opendroid.ai.remote.socks.HttpProxyBridge
import com.opendroid.ai.remote.socks.Socks5
import com.opendroid.ai.remote.socks.Socks5Client
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Internet sharing: sends this phone's traffic out through a paired desktop's connection.
 *
 * The desktop runs a SOCKS5 proxy ([com.opendroid.ai.remote.socks.Socks5Server]); this side opens
 * tunnels through it and exposes them on loopback as an HTTP proxy, which is the form Android and
 * most browsers accept.
 *
 * This controller never starts on its own. Sharing is off until the owner turns it on, because
 * while it is on every request the proxy carries is leaving from the desktop's network.
 */
@Singleton
class InternetSharingController @Inject constructor() {

    data class State(
        val running: Boolean = false,
        /** Loopback address to enter in the browser or Wi-Fi proxy settings, e.g. 127.0.0.1:41234. */
        val proxyAddress: String? = null,
        val desktopName: String? = null,
        val requestsServed: Long = 0,
        val bytesRelayed: Long = 0,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    @Volatile private var bridge: HttpProxyBridge? = null
    @Volatile private var tunnelHost: String? = null
    @Volatile private var tunnelPort: Int = Socks5.DEFAULT_PORT
    @Volatile private var tunnelSecret: ByteArray? = null
    @Volatile private var tunnelDeviceId: String? = null
    @Volatile private var desktopLabel: String? = null

    /**
     * Starts the loopback proxy for [desktop].
     *
     * The paired secret is held in memory only, and only while sharing is on: it is what
     * authenticates this phone to the desktop's proxy, so it is never written to a log or a file
     * here.
     */
    fun start(desktop: PairedDesktop, port: Int = 0): String {
        stop()
        if (desktop.secret.isEmpty()) {
            val message = "This desktop has no pairing secret. Pair with it again before sharing internet."
            mutableState.value = State(error = message)
            return message
        }
        tunnelHost = desktop.host
        tunnelPort = Socks5.DEFAULT_PORT
        tunnelSecret = desktop.secret
        tunnelDeviceId = desktop.deviceId
        desktopLabel = desktop.serverName

        val created = HttpProxyBridge(
            tunnel = { target ->
                Socks5Client(
                    host = requireNotNull(tunnelHost) { "No desktop host" },
                    port = tunnelPort,
                    secret = requireNotNull(tunnelSecret) { "No paired secret" },
                    deviceId = requireNotNull(tunnelDeviceId) { "No device id" },
                ).connect(target)
            },
            port = port,
        )
        return runCatching { created.start() }.fold(
            onSuccess = { bound ->
                bridge = created
                val address = "127.0.0.1:$bound"
                mutableState.value = State(
                    running = true,
                    proxyAddress = address,
                    desktopName = desktopLabel,
                )
                "Internet sharing is on. Set your browser or Wi-Fi proxy to $address. " +
                    "Traffic leaves through ${desktop.serverName}."
            },
            onFailure = { error ->
                clearTunnel()
                val message = "Could not start internet sharing: ${error.message ?: "unknown error"}"
                mutableState.value = State(error = message)
                message
            },
        )
    }

    fun stop() {
        bridge?.let { runCatching { it.stop() } }
        bridge = null
        clearTunnel()
        mutableState.value = State()
    }

    /** Refreshes the counters the UI shows while sharing is running. */
    fun refreshStats() {
        val current = bridge ?: return
        val (requests, bytes) = current.stats()
        mutableState.value = mutableState.value.copy(
            requestsServed = requests,
            bytesRelayed = bytes,
        )
    }

    /** Called after the kill switch engages, so sharing cannot outlive it. */
    fun stopForKillSwitch() = stop()

    private fun clearTunnel() {
        tunnelHost = null
        tunnelSecret = null
        tunnelDeviceId = null
        desktopLabel = null
    }
}
