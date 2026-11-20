package com.opendroid.ai.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Breathing gap kept between the newest message and the composer. */
internal val CHAT_LIST_BASE_GAP: Dp = 16.dp

/**
 * Bottom content padding for the reverse-layout message list.
 *
 * The chat Scaffold hosts the composer in its `bottomBar` slot, and Material3's
 * Scaffold already reports the bottom-bar height back through the inner
 * `PaddingValues` it hands to content (see Scaffold.kt:
 * `bottom = bottomBarHeight?.toDp() ?: insets.calculateBottomPadding()`). That
 * inner padding is applied by the parent container, so the list must not
 * reserve the composer height a second time - it only adds the base gap.
 *
 * [composerHeightPx] stays the caller's single measured source of truth for the
 * composer height; Scaffold consumes that same measurement, so it is not added
 * again here.
 */
internal fun listBottomPaddingDp(
    @Suppress("UNUSED_PARAMETER") composerHeightPx: Int,
    @Suppress("UNUSED_PARAMETER") density: Float,
): Dp = CHAT_LIST_BASE_GAP
