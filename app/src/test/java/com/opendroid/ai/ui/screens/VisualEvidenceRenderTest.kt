package com.opendroid.ai.ui.screens

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.opendroid.ai.ui.screens.taskbar.TaskBarSummary
import com.opendroid.ai.ui.screens.toolbar.ToolBarContext
import com.opendroid.ai.ui.theme.UsmanAndUmarTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures genuine app Compose output for before/after design review (not a mockup). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualEvidenceRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureToolBarAtPhoneSize() {
        compose.setContent {
            UsmanAndUmarTheme(isDarkTheme = true) {
                CapabilitiesScreenContent(
                    context = ToolBarContext(),
                    onSearchAI = {},
                    onSearchGitHub = {},
                    onTermux = {},
                    onUniversalAgent = {},
                    onOpenPluginPlatform = {},
                )
            }
        }
        capture("toolbar.png")
    }

    @Test
    fun captureTaskBarSummaryAtPhoneSize() {
        compose.setContent {
            UsmanAndUmarTheme(isDarkTheme = true) {
                TaskBarSummaryRow(TaskBarSummary(active = 0, queued = 0, waiting = 0, done = 0, failed = 0))
            }
        }
        capture("taskbar-summary.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val workingDirectory = File(System.getProperty("user.dir"))
        val moduleDirectory = if (workingDirectory.name == "app") workingDirectory else File(workingDirectory, "app")
        val output = File(moduleDirectory, "build/visual-evidence/$name")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            check(compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
