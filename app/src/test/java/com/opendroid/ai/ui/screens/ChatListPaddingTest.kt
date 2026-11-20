package com.opendroid.ai.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListPaddingTest {
    /**
     * The composer is Scaffold's `bottomBar`, and Material3's Scaffold already returns the
     * bottom-bar height as the inner `bottom` padding it applies to content
     * (`bottom = bottomBarHeight?.toDp() ?: ...` in Scaffold.kt). Reserving it again in the
     * list's own contentPadding double-counts it and leaves a full screen of dead space
     * under the newest message.
     */
    @Test
    fun `list bottom padding never reserves the composer height a second time`() {
        assertEquals(16.dp, listBottomPaddingDp(composerHeightPx = 400, density = 2f))
    }

    @Test
    fun `list bottom padding stays a small constant gap`() {
        assertEquals(16.dp, listBottomPaddingDp(composerHeightPx = 0, density = 2f))
        assertEquals(16.dp, listBottomPaddingDp(composerHeightPx = 1080, density = 3f))
    }
}
