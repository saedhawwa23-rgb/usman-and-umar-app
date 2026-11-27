package com.opendroid.ai.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.opendroid.ai.core.agent.AgentToolEnvironment
import com.opendroid.ai.core.termux.TermuxIntegration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android side of the tool registry, bound to a real [Context].
 *
 * It was previously a private inner class of `ChatViewModel`, which meant only the chat agent
 * could execute a tool. Extracting it lets the chat agent and the remote-control executor run
 * the same actions through the same code, and keeps the tool set free of Android types so the
 * registry stays JVM-testable. Behaviour is unchanged from the previous implementation.
 */
@Singleton
class AndroidAgentToolEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termuxIntegration: TermuxIntegration,
    private val localAiAppExecutor: LocalAiAppExecutor,
) : AgentToolEnvironment {

    override fun listInstalledPackages(thirdPartyOnly: Boolean): String {
        val apps = installedApplications(context.packageManager)
            .filter { !thirdPartyOnly || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }.sorted()
        return "Native PackageManager result (${apps.size} package(s)):\n" +
            apps.take(500).joinToString("\n").ifBlank { "No packages found." }
    }

    override fun deviceProperty(command: String): String = when (command) {
        "getprop ro.build.version.release" -> "Android release: ${Build.VERSION.RELEASE}."
        "getprop ro.build.version.sdk" -> "Android API level: ${Build.VERSION.SDK_INT}."
        "getprop ro.product.model" -> "Device model: ${Build.MANUFACTURER} ${Build.MODEL}."
        else -> error("Unsupported Android property query.")
    }

    override fun batteryStatus(): String {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: error("Battery status is unavailable on this device.")
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown status"
        }
        return "Battery: ${if (percent in 0..100) "$percent%" else "unavailable"}, $statusLabel."
    }

    override fun networkStatus(): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: error("Network status is unavailable on this device.")
        val network = connectivity.activeNetwork ?: return "No active network connection."
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return "An active network is present, but its capabilities could not be read."
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "Active network: $transport; internet validated: ${if (validated) "yes" else "no"}."
    }

    override fun screenBrightness(): String {
        val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return "Screen brightness setting: ${if (value >= 0) value else "unavailable"} (0-255 scale)."
    }

    override fun displaySize(): String {
        val metrics = context.resources.displayMetrics
        return "Current app display metrics: ${metrics.widthPixels} x ${metrics.heightPixels} pixels."
    }

    override suspend fun runLinux(command: String): String {
        check(termuxIntegration.isInstalled()) {
            "This explicitly requested Linux task needs Termux. Install Termux, then approve the action again."
        }
        val id = termuxIntegration.runCode("bash", command).getOrElse { throw it }
        val result = withTimeoutOrNull(120_000) {
            termuxIntegration.results.first { rows -> rows.any { it.executionId == id } }
                .first { it.executionId == id }
        } ?: error("Termux did not return a result before timeout.")
        return buildString {
            append("Termux result · exitCode=").append(result.exitCode ?: "unknown")
            if (result.stdout.isNotBlank()) append("\nstdout:\n").append(result.stdout)
            if (result.stderr.isNotBlank()) append("\nstderr:\n").append(result.stderr)
            result.error?.let { append("\nerror:\n").append(it) }
        }
    }

    override suspend fun openApp(target: String): String {
        val packageManager = context.packageManager
        val installed = installedApplications(packageManager)
        val application = installed.firstOrNull { it.packageName.equals(target, ignoreCase = true) }
            ?: installed.filter { packageManager.getApplicationLabel(it).toString().equals(target, ignoreCase = true) }
                .singleOrNull()
            ?: error("No installed app matched '$target' uniquely. Use its exact package name if multiple apps share that label.")
        val intent = packageManager.getLaunchIntentForPackage(application.packageName)
            ?: error("${application.packageName} has no launchable activity.")
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        return "Opened ${packageManager.getApplicationLabel(application)} (${application.packageName}) using an Android launch intent."
    }

    override suspend fun openUrl(url: String): String {
        val uri = url.trim().toUri()
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Only HTTP and HTTPS URLs can be opened."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "The URL is invalid or contains embedded credentials." }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        require(intent.resolveActivity(context.packageManager) != null) { "No installed app can open this URL." }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        return "Opened URL: $uri"
    }

    override suspend fun useLocalApp(appName: String?, packageName: String?, prompt: String): String =
        localAiAppExecutor.execute(context, appName, packageName, prompt)

    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
}
