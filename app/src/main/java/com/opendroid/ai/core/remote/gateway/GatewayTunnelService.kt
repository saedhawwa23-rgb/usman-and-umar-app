package com.opendroid.ai.core.remote.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.opendroid.ai.remote.gateway.GatewayConfig
import com.opendroid.ai.remote.gateway.GatewayPeer
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.StringReader

/**
 * Hosts the WireGuard tunnel that carries the phone's traffic to the desktop.
 *
 * The Go backend owns the actual tun device, so this service's job is lifecycle and reporting:
 * bring the tunnel up, publish counters, and make sure a stop really restores the phone's normal
 * routing. A tunnel that is left half-up is worse than one that never started, because every app
 * would lose internet with no obvious cause.
 */
class GatewayTunnelService : VpnService() {

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null
    private var scope: CoroutineScope? = null
    private var statsJob: Job? = null
    private var pendingConfig: Config? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown(null)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val peer = intent.toPeer()
                val allowed = intent.getStringArrayListExtra(EXTRA_ALLOWED_IPS).orEmpty()
                val fullTunnel = intent.getBooleanExtra(EXTRA_FULL_TUNNEL, true)
                if (peer == null || allowed.isEmpty()) {
                    report("The gateway configuration was incomplete, so nothing was started.")
                    teardown(null)
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                bringUp(peer, allowed, fullTunnel)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun bringUp(peer: GatewayPeer, allowed: List<String>, fullTunnel: Boolean) {
        val configText = GatewayConfig.render(
            phonePrivateKey = peer.privateKey,
            desktopPublicKey = peer.desktopPublicKey,
            endpoint = peer.endpoint,
            allowedIps = allowed,
            dns = GatewayConfig.DESKTOP_DNS,
        )
        // The rendered config carries the private key, so only the redacted form is ever logged.
        Log.i(TAG, "gateway up: fullTunnel=$fullTunnel\n${GatewayConfig.redact(configText)}")

        val config = try {
            Config.parse(StringReader(configText).buffered())
        } catch (e: Exception) {
            Log.e(TAG, "config rejected", e)
            report("The gateway configuration was rejected: ${e.message}")
            teardown(null)
            return
        }
        pendingConfig = config

        val goBackend = GoBackend(applicationContext)
        backend = goBackend
        val name = "usman-gateway"
        val tunnelImpl = object : Tunnel {
            override fun getName(): String = name
            override fun onStateChange(newState: Tunnel.State) {
                Log.i(TAG, "tunnel state=$newState")
                when (newState) {
                    Tunnel.State.DOWN -> {
                        publishStopped("The gateway stopped. Normal internet access is back.")
                        stopSelf()
                    }
                    Tunnel.State.TOGGLE -> Unit
                    else -> Unit
                }
            }
        }
        tunnel = tunnelImpl

        scope?.launch {
            try {
                goBackend.setState(tunnelImpl, Tunnel.State.UP, config)
                startStatsPolling(goBackend, tunnelImpl)
            } catch (e: Exception) {
                // A failed UP leaves no tunnel behind, but the user must still be told why.
                Log.e(TAG, "gateway failed to start", e)
                report("The gateway could not start: ${e.message ?: e.javaClass.simpleName}")
                publishStopped(null)
                teardown(null)
            }
        }
    }

    private fun startStatsPolling(goBackend: GoBackend, tunnelImpl: Tunnel) {
        statsJob?.cancel()
        statsJob = scope?.launch {
            while (isActive) {
                val stats = runCatching { goBackend.getStatistics(tunnelImpl) }.getOrNull()
                if (stats != null) {
                    val handshake = runCatching {
                        stats.peers().maxOfOrNull { key ->
                            stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L
                        } ?: 0L
                    }.getOrDefault(0L)
                    publishStats(stats.totalRx(), stats.totalTx(), handshake)
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun teardown(reason: String?) {
        statsJob?.cancel()
        statsJob = null
        val t = tunnel
        val b = backend
        if (t != null && b != null) {
            runCatching {
                b.setState(t, Tunnel.State.DOWN, pendingConfig)
            }.onFailure { Log.w(TAG, "down failed; the OS still drops the tun on service stop", it) }
        }
        tunnel = null
        backend = null
        pendingConfig = null
        publishStopped(reason)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onRevoke() {
        // The OS withdrew consent (another VPN app started, or the user turned it off). Drop
        // everything and say so rather than leaving the UI showing a tunnel that no longer exists.
        teardown("The VPN was turned off by Android or another VPN app.")
    }

    override fun onDestroy() {
        statsJob?.cancel()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun publishStats(rx: Long, tx: Long, handshake: Long) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, true)
            putExtra(EXTRA_RX, rx)
            putExtra(EXTRA_TX, tx)
            putExtra(EXTRA_HANDSHAKE, handshake)
        })
    }

    private fun publishStopped(reason: String?) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, false)
            reason?.let { putExtra(EXTRA_REASON, it) }
        })
    }

    private fun report(message: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, false)
            putExtra(EXTRA_REASON, message)
        })
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Internet gateway", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Internet gateway is on")
            .setContentText("Traffic is going through your computer.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun Intent.toPeer(): GatewayPeer? {
        val priv = getStringExtra(EXTRA_PRIVATE_KEY) ?: return null
        val pub = getStringExtra(EXTRA_DESKTOP_PUBLIC_KEY) ?: return null
        val endpoint = getStringExtra(EXTRA_ENDPOINT) ?: return null
        return runCatching { GatewayPeer(priv, pub, endpoint) }.getOrNull()
    }

    companion object {
        private const val TAG = "InternetGateway"
        private const val CHANNEL_ID = "internet_gateway"
        private const val NOTIFICATION_ID = 4711
        private const val STATS_INTERVAL_MS = 2000L

        const val ACTION_START = "com.opendroid.ai.gateway.START"
        const val ACTION_STOP = "com.opendroid.ai.gateway.STOP"
        const val ACTION_STATE = "com.opendroid.ai.gateway.STATE"

        const val EXTRA_PRIVATE_KEY = "private_key"
        const val EXTRA_DESKTOP_PUBLIC_KEY = "desktop_public_key"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_ALLOWED_IPS = "allowed_ips"
        const val EXTRA_FULL_TUNNEL = "full_tunnel"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_RX = "rx"
        const val EXTRA_TX = "tx"
        const val EXTRA_HANDSHAKE = "handshake"
        const val EXTRA_REASON = "reason"

        fun start(context: Context, peer: GatewayPeer, allowedIps: List<String>, fullTunnel: Boolean) {
            val intent = Intent(context, GatewayTunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PRIVATE_KEY, peer.privateKey)
                putExtra(EXTRA_DESKTOP_PUBLIC_KEY, peer.desktopPublicKey)
                putExtra(EXTRA_ENDPOINT, peer.endpoint)
                putStringArrayListExtra(EXTRA_ALLOWED_IPS, ArrayList(allowedIps))
                putExtra(EXTRA_FULL_TUNNEL, fullTunnel)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GatewayTunnelService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
