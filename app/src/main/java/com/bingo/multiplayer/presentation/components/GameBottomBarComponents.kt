package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.presentation.common.PlayerAvatar

/**
 * 3-Number Sliding FIFO Queue Pill (Item 5).
 * Holds up to 3 most recently chosen numbers with a thin border and smooth slide-push animation.
 */
@Composable
fun RecentPicksQueuePill(
    pickedNumbersHistory: List<Int>,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val validPicks = pickedNumbersHistory.filter { it > 0 }
    val lastThree = validPicks.takeLast(3)

    val slot0 = lastThree.getOrNull(0)
    val slot1 = lastThree.getOrNull(1)
    val slot2 = lastThree.getOrNull(2)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = tokens.backgroundSecondary,
        border = BorderStroke(0.7.dp, tokens.surfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RecentPickSlot(number = slot0, isLatest = (validPicks.size == 1 && slot0 != null))
            RecentPickSlot(number = slot1, isLatest = (validPicks.size == 2 && slot1 != null))
            RecentPickSlot(number = slot2, isLatest = (validPicks.size >= 3 && slot2 != null))
        }
    }
}

@Composable
private fun RecentPickSlot(
    number: Int?,
    isLatest: Boolean
) {
    val tokens = BingoTheme.colors

    AnimatedContent(
        targetState = number,
        transitionSpec = {
            (slideInHorizontally { width -> width } + fadeIn(tween(200))).togetherWith(
                slideOutHorizontally { width -> -width } + fadeOut(tween(200))
            )
        },
        label = "recentPickSlot"
    ) { num ->
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (num != null && isLatest) tokens.recentPickBg else Color.Transparent
                )
                .then(
                    if (num != null && isLatest) Modifier.border(1.dp, tokens.recentPickBorder, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (num != null) num.toString() else "·",
                fontSize = if (num != null) 12.sp else 16.sp,
                fontWeight = if (isLatest) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = when {
                    num == null -> tokens.cellNeutralText.copy(alpha = 0.25f)
                    isLatest -> tokens.recentPickText
                    else -> tokens.cellNeutralText
                }
            )
        }
    }
}

/**
 * Profile vs Profile with Turn Zoom Effect (Item 6).
 * Shows both player photos; the active player's avatar smoothly zooms larger while the other shrinks.
 */
@Composable
fun BottomTurnProfileVsProfile(
    isMyTurn: Boolean,
    isGameOver: Boolean,
    myAvatarUrl: String?,
    myDisplayName: String?,
    myUsername: String?,
    opponentAvatarUrl: String?,
    opponentName: String,
    opponentUsername: String?,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    val mySize by animateDpAsState(
        targetValue = if (isMyTurn && !isGameOver) 42.dp else 28.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "myAvatarZoom"
    )
    val oppSize by animateDpAsState(
        targetValue = if (!isMyTurn && !isGameOver) 42.dp else 28.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "oppAvatarZoom"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Player A (You)
        Box(
            modifier = Modifier
                .size(mySize)
                .clip(CircleShape)
                .border(
                    width = if (isMyTurn && !isGameOver) 2.dp else 1.dp,
                    color = if (isMyTurn && !isGameOver) tokens.accentBrand else tokens.surfaceBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            PlayerAvatar(
                avatarPathOrUri = myAvatarUrl,
                displayName = myDisplayName ?: "You",
                username = myUsername,
                size = mySize
            )
        }

        Text(
            text = "vs",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = tokens.cellNeutralText.copy(alpha = 0.45f)
        )

        // Player B (Opponent)
        Box(
            modifier = Modifier
                .size(oppSize)
                .clip(CircleShape)
                .border(
                    width = if (!isMyTurn && !isGameOver) 2.dp else 1.dp,
                    color = if (!isMyTurn && !isGameOver) tokens.accentOpponent else tokens.surfaceBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            PlayerAvatar(
                avatarPathOrUri = opponentAvatarUrl,
                displayName = opponentName,
                username = opponentUsername,
                size = oppSize
            )
        }
    }
}
