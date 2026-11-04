package com.opendroid.ai.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.opendroid.ai.core.browser.CloudServerClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** User-enabled keep-alive loop; a foreground notification stays visible while it is active. */
@AndroidEntryPoint
class CloudServerKeepAliveService : Service() {
    @Inject lateinit var cloudServerClient: CloudServerClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null
    private var offlineNotificationShown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        promoteToForeground()
        if (keepAliveJob?.isActive != true) {
            keepAliveJob = serviceScope.launch {
                while (true) {
                    try {
                        val health = cloudServerClient.pingAll()
                        if (health.isNotEmpty() && health.none { it.online }) {
                            showOfflineNotification()
                        } else if (health.any { it.online }) {
                            clearOfflineNotification()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed request means the Colab server is unavailable. Keep the
                        // loop alive so a later health check can clear the notification.
                        showOfflineNotification()
                    }
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenSwarm cluster keep-alive")
            .setContentText("Checking enabled servers every five minutes")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cluster keep-alive", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Periodic health checks for enabled OpenSwarm servers" },
        )
        manager.createNotificationChannel(
            NotificationChannel(OFFLINE_CHANNEL_ID, "Colab server alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Alerts when every configured Colab server is offline" },
        )
    }

    private fun showOfflineNotification() {
        if (offlineNotificationShown) return
        offlineNotificationShown = true
        val notification = NotificationCompat.Builder(this, OFFLINE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Colab Server Offline")
            .setContentText("Colab Server Offline. Please restart your Colab notebook.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Colab Server Offline. Please restart your Colab notebook."))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        getSystemService(NotificationManager::class.java).notify(OFFLINE_NOTIFICATION_ID, notification)
    }

    private fun clearOfflineNotification() {
        if (!offlineNotificationShown) return
        offlineNotificationShown = false
        getSystemService(NotificationManager::class.java).cancel(OFFLINE_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.opendroid.ai.action.START_CLOUD_KEEP_ALIVE"
        const val ACTION_STOP = "com.opendroid.ai.action.STOP_CLOUD_KEEP_ALIVE"
        private const val CHANNEL_ID = "openswarm_cluster_keep_alive"
        private const val OFFLINE_CHANNEL_ID = "openswarm_colab_offline"
        private const val NOTIFICATION_ID = 3041
        private const val OFFLINE_NOTIFICATION_ID = 3042
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, CloudServerKeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CloudServerKeepAliveService::class.java).setAction(ACTION_STOP))
        }
    }
}
