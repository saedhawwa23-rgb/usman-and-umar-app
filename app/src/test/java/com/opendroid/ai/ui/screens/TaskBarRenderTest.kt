package com.opendroid.ai.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.opendroid.ai.ui.screens.taskbar.TaskBarSummary
import com.opendroid.ai.ui.theme.UsmanAndUmarTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Task Bar summary strip ([TaskBarSummaryRow]) on the JVM via Robolectric and
 * asserts the metric tiles show the counts the model computes. Rendering evidence only, not a
 * device test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(summary: TaskBarSummary) {
        compose.setContent {
            UsmanAndUmarTheme(isDarkTheme = true) {
                TaskBarSummaryRow(summary)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun rendersRunningQueuedWaitingAndDoneTiles() {
        render(TaskBarSummary(active = 3, queued = 2, waiting = 1, done = 5, failed = 0))
        compose.onNodeWithText("Running").assertIsDisplayed()
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.onNodeWithText("Waiting").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun rendersZeroTilesForAnEmptyTaskList() {
        render(TaskBarSummary(active = 0, queued = 0, waiting = 0, done = 0, failed = 0))
        compose.onNodeWithText("Running").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
