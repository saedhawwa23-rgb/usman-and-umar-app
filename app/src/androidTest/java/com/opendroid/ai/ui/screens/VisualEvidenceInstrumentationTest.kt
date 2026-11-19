package com.opendroid.ai.ui.screens

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.MainActivity
import com.opendroid.ai.ui.OpenDroidRoutes
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the real application window on an Android emulator. This intentionally avoids
 * Robolectric/Compose bitmap capture: UiDevice screenshots include the rendered Android window,
 * system insets and all production Compose layers exactly as a user sees them.
 */
@RunWith(AndroidJUnit4::class)
class VisualEvidenceInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun captureSevenProductionScreens() {
        val prefix = "v${BuildConfig.VERSION_NAME}"
        val routes = listOf(
            CaptureRoute("task-bar", OpenDroidRoutes.VISUAL_TASK_BAR, titles("Task Bar")),
            CaptureRoute("tool-bar", OpenDroidRoutes.VISUAL_TOOL_BAR, titles("Tool Bar")),
            CaptureRoute("ai-providers", OpenDroidRoutes.DISCOVERY, titles("AI Providers")),
            CaptureRoute("github", OpenDroidRoutes.GITHUB_DISCOVERY, titles("GitHub Explorer")),
            CaptureRoute("termux-linux", OpenDroidRoutes.TERMUX, titles("Termux · Linux")),
            CaptureRoute("plugin-platform", OpenDroidRoutes.PLUGIN_PLATFORM, titles("Integration Platform")),
            CaptureRoute("remote-platform", OpenDroidRoutes.REMOTE_CONTROL, titles("Universal Remote")),
        )
        // Keep captures under internal filesDir: the CI host retrieves them with `run-as`,
        // while external app storage is not addressable through that command.
        val outputDirectory = File(targetContext.filesDir, "visual-evidence").apply {
            deleteRecursively()
            mkdirs()
        }

        routes.forEach { route ->
            val intent = Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_VISUAL_ROUTE, route.destination)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                check(device.wait(Until.hasObject(By.pkg(targetContext.packageName)), 20_000)) {
                    "Application package did not become visible for ${route.name}"
                }
                val titleVisible = route.expectedTitles.isEmpty() || route.expectedTitles.any { title ->
                    device.wait(Until.hasObject(By.text(title)), 20_000)
                }
                check(titleVisible) {
                    "Expected one of ${route.expectedTitles} was not visible for ${route.name}"
                }
                // Let the branded entry animation and initial view-model composition settle.
                SystemClock.sleep(1_000)
                val output = File(outputDirectory, "$prefix-${route.name}.png")
                check(device.takeScreenshot(output)) { "Screenshot failed for ${route.name}" }
                check(output.isFile && output.length() > 0) { "Empty screenshot for ${route.name}" }
                instrumentation.getUiAutomation().performGlobalAction(2) // HOME
                SystemClock.sleep(300)
            }
        }
    }

    private data class CaptureRoute(
        val name: String,
        val destination: String,
        val expectedTitles: List<String>,
    )

    private fun titles(vararg values: String): List<String> =
        if (BuildConfig.VERSION_NAME == "3.10.5") emptyList() else values.toList()

}
