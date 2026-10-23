package com.bingo.multiplayer.presentation.lobby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Friend

/** A five-row viewport; all friends remain available without composing the entire list. */
@Composable
internal fun LobbyFriendsList(
    friends: List<Friend>,
    friendRow: @Composable (Friend) -> Unit
) {
    val state = rememberLazyListState()
    val tokens = BingoTheme.colors
    val rowHeight = 56.dp
    val hasOverflow = friends.size > 5
    val firstFriendId = friends.firstOrNull()?.let { it.uid.ifBlank { it.username } }
    LaunchedEffect(firstFriendId) {
        // LazyColumn otherwise anchors the old first row and can hide a newly
        // promoted online friend above the five-row viewport.
        if (firstFriendId != null) state.scrollToItem(0)
    }

    Box(Modifier.fillMaxWidth().height(rowHeight * friends.size.coerceAtMost(5))) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = if (hasOverflow) 10.dp else 0.dp)
        ) {
            itemsIndexed(friends, key = { _, friend -> friend.uid.ifBlank { friend.username } }) { index, friend ->
                Column(Modifier.fillMaxWidth().height(rowHeight)) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                        friendRow(friend)
                    }
                    if (index < friends.lastIndex) {
                        HorizontalDivider(color = tokens.surfaceBorder.copy(alpha = 0.5f))
                    }
                }
            }
        }

        if (hasOverflow) {
            Canvas(Modifier.matchParentSize()) {
                val layout = state.layoutInfo
                val itemHeight = layout.visibleItemsInfo.firstOrNull()?.size?.toFloat()
                    ?: return@Canvas
                val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                val contentHeight = itemHeight * layout.totalItemsCount
                if (contentHeight <= viewport) return@Canvas
                val thumbHeight = (size.height * viewport / contentHeight).coerceIn(24.dp.toPx(), size.height)
                val offset = state.firstVisibleItemIndex * itemHeight + state.firstVisibleItemScrollOffset
                val progress = (offset / (contentHeight - viewport)).coerceIn(0f, 1f)
                val width = 3.dp.toPx()
                val corner = CornerRadius(width / 2f)
                drawRoundRect(
                    color = tokens.surfaceBorder.copy(alpha = 0.5f),
                    topLeft = Offset(size.width - width, 0f),
                    size = Size(width, size.height),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = tokens.accentBrand.copy(alpha = 0.8f),
                    topLeft = Offset(size.width - width, (size.height - thumbHeight) * progress),
                    size = Size(width, thumbHeight),
                    cornerRadius = corner
                )
            }
        }
    }
}
