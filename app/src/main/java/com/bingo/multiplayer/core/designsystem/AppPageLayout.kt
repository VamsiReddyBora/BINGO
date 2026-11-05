package com.bingo.multiplayer.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** MainActivity fits below system bars. Apply only to pages, never splash or popups. */
object AppPageLayout {
    val TopGap: Dp = 15.dp
}

fun Modifier.appPagePadding(horizontal: Dp, bottom: Dp): Modifier = padding(
    start = horizontal, top = AppPageLayout.TopGap, end = horizontal, bottom = bottom
)
