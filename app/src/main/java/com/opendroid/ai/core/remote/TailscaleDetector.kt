package com.opendroid.ai.core.remote

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What the phone can learn about Tailscale without embedding it.
 *
 * Tailscale's Android client owns the system VPN slot. USMAN AND UMAR's own gateway
 * ([com.opendroid.ai.core.remote.gateway.GatewayTunnelService]) also needs that slot, and Android
 * permits exactly one at a time. So this class only observes; it never starts, stops or reconfigures
 * Tailscale, and it never claims the app is running a tailnet of its own.
 */
class TailscaleDetector(private val context: Context) {

    /** Tailscale's Android package, as published on the Play Store. */
    private val tailscalePackages = listOf("com.tailscale.ipn")

    data class State(
        val installed: Boolean = false,
        /** True when a VPN transport is active; could be Tailscale or any other VPN app. */
        val vpnActive: Boolean = false,
        /** The phone's own address inside the tailnet, when one is present. */
        val tailnetAddress: String? = null,
        val detail: String = "",
    ) {
        /**
         * Tailscale occupies the VPN slot, so our own tunnel cannot start while it runs.
         *
         * This is the conflict the user must be told about, not worked around: there is no
         * supported way for two VpnService instances to be active together.
         */
        val blocksGateway: Boolean get() = vpnActive
    }

    fun inspect(): State {
        val installed = isInstalled()
        val vpnActive = isVpnTransportActive()
        val tailnet = tailnetAddress()

        val detail = when {
            !installed && !vpnActive ->
                "Tailscale is not installed and no VPN is active."
            !installed && vpnActive ->
                "Another VPN is active. Tailscale is not installed."
            installed && !vpnActive ->
                "Tailscale is installed but not connected."
            tailnet != null ->
                "Connected to a tailnet with address $tailnet."
            else ->
                "A VPN is active. If that is Tailscale, it is connected but no tailnet address " +
                    "was readable on this device."
        }

        return State(installed, vpnActive, tailnet, detail)
    }

    private fun isInstalled(): Boolean = tailscalePackages.any { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * True when any network reports a VPN transport.
     *
     * Android does not expose which app owns it, so a true reading is not proof that Tailscale is
     * the one running. The UI says "a VPN" for that reason.
     */
    private fun isVpnTransportActive(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Read a tailnet address from the interfaces Tailscale publishes.
     *
     * Tailscale's Android client routes through a TUN interface rather than a named one, so this
     * only succeeds when an address in the CGNAT range (100.64.0.0/10) is bound to a local
     * interface. A null result means "not readable", not "not connected".
     */
    private fun tailnetAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { isTailnetRange(it) }
    }.getOrNull()

    companion object {
        /** Tailscale hands out addresses from the CGNAT block 100.64.0.0/10. */
        fun isTailnetRange(address: String): Boolean {
            val parts = address.split('.')
            if (parts.size != 4) return false
            val first = parts[0].toIntOrNull() ?: return false
            val second = parts[1].toIntOrNull() ?: return false
            return first == 100 && second in 64..127
        }
    }
}
