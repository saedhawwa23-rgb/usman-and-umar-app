package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the real production [ChatScaffoldLayout] - the skeleton `ChatScreen`
 * delegates to - with fake messages. No ChatViewModel, no Hilt, no network.
 *
 * The message list uses `reverseLayout = true`, so the newest message is the first
 * item, pinned to the bottom edge of the viewport, directly above the composer.
 *
 * NOT VERIFIED: these tests need a device/emulator. They cannot run in the CI
 * container used for this change.
 */
@RunWith(AndroidJUnit4::class)
class ChatLayoutInstrumentationTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * @param composerHeightDp the composer height. Growing it stands in for the IME
     *        insets that `imePadding()` adds when the keyboard opens.
     */
    private fun setScreen(composerHeightDp: Int) {
        compose.setContent {
            val listState = rememberLazyListState()
            var composerHeightPx by remember { mutableIntStateOf(0) }
            ChatScaffoldLayout(
                composerHeightPx = composerHeightPx,
                listState = listState,
                topBar = { Text("top") },
                composer = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(composerHeightDp.dp)
                            .background(Color.DarkGray)
                            .onGloballyPositioned { composerHeightPx = it.size.height }
                            .testTag(COMPOSER_TAG)
                    )
                },
                messages = {
                    items(listOf(NEWEST, OLDER)) { message ->
                        Text(
                            text = message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .testTag(if (message == NEWEST) NEWEST_TAG else OLDER_TAG),
                        )
                    }
                },
            )
        }
        compose.waitForIdle()
    }

    private fun assertNewestAboveComposer() {
        compose.onNodeWithTag(NEWEST_TAG).assertIsDisplayed()
        val newestBottom = compose.onNodeWithTag(NEWEST_TAG).fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = compose.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "newest message bottom ($newestBottom) must sit at or above the composer top ($composerTop)",
            newestBottom <= composerTop + 1f,
        )
    }

    /** The newest message must be visible, directly above the composer. */
    @Test
    fun newestMessageIsVisibleDirectlyAboveComposer() {
        setScreen(composerHeightDp = 200)
        assertNewestAboveComposer()
    }

    /**
     * Same invariant with the keyboard open: the composer is taller, so the list
     * viewport is shorter. Before the fix the list added the composer height a
     * second time and the newest message was pushed off the bottom of the viewport.
     */
    @Test
    fun newestMessageStaysVisibleAboveComposerWithKeyboardOpen() {
        setScreen(composerHeightDp = 360)
        assertNewestAboveComposer()
    }

    private companion object {
        const val COMPOSER_TAG = "chat-composer"
        const val NEWEST_TAG = "chat-newest-message"
        const val OLDER_TAG = "chat-older-message"
        const val NEWEST = "NEWEST-MESSAGE-MARKER"
        const val OLDER = "OLDER-MESSAGE-MARKER"
    }
}
