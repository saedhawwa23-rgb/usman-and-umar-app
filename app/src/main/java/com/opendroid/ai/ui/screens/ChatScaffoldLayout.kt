package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.opendroid.ai.ui.theme.DarkBackground

/**
 * The chat screen's layout skeleton: a top bar, a bottom composer, and a
 * reverse-layout message list that keeps the newest message directly above the
 * composer.
 *
 * [composer] is Scaffold's `bottomBar`, so Material3's Scaffold already reserves
 * its measured height as the inner bottom padding it applies to this content
 * (Scaffold.kt: `bottom = bottomBarHeight?.toDp() ?: insets.calculateBottomPadding()`).
 * The list must therefore not reserve [composerHeightPx] a second time;
 * [listBottomPaddingDp] is the only place that decides the list's bottom padding.
 *
 * This composable is intentionally free of `ChatViewModel`, Hilt, and the network so
 * the layout contract can be exercised in isolation.
 *
 * @param composerHeightPx the caller's single measured composer height, in pixels.
 * @param listState the list state owned by the caller (drives scroll-to-newest effects).
 * @param topBar the screen's top app bar.
 * @param composer the bottom composer. It measures itself into [composerHeightPx].
 * @param belowList content rendered under the list, inside the same column.
 * @param messages the message list items, newest first (see `reverseLayout`).
 */
@Composable
internal fun ChatScaffoldLayout(
    composerHeightPx: Int,
    listState: LazyListState,
    topBar: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    belowList: @Composable ColumnScope.() -> Unit = {},
    messages: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    Scaffold(
        topBar = topBar,
        bottomBar = composer,
        containerColor = DarkBackground,
        modifier = modifier,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        bottom = listBottomPaddingDp(composerHeightPx, density.density),
                    )
                ) {
                    messages()
                }

                belowList()
            }
        }
    }
}
