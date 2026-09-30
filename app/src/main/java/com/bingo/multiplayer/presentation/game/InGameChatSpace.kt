package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoColors
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.InGameChatMessage

/**
 * In-game WhatsApp-style chat space positioned between the 5x5 Bingo board and the emoji reactions strip.
 * - Seamless plain background with no card or heavy border.
 * - Displays light grey "Double tap to chat" hint when empty or idle.
 * - Double tapping triggers the text box & software keyboard.
 * - Messages animate and bounce into position (Self on right, Opponent on left).
 * - Scrollable space so older messages move upwards beneath the board.
 */
@Composable
fun InGameChatSpace(
    messages: List<InGameChatMessage>,
    onDoubleTapToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val listState = rememberLazyListState()

    // Auto-scroll to latest message whenever a new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            try {
                listState.animateScrollToItem(messages.size - 1)
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleTapToChat()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (messages.isEmpty()) {
            // Subtle hint displayed on the plain space: double tap to chat
            Text(
                text = "💬 Double tap to chat",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.cellNeutralText.copy(alpha = 0.35f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    InGameChatBubble(message = msg, tokens = tokens)
                }
            }
        }
    }
}

@Composable
private fun InGameChatBubble(
    message: InGameChatMessage,
    tokens: BingoColors
) {
    // Satisfying organic WhatsApp bounce-in animation
    val bounceScale = remember { Animatable(0.4f) }
    LaunchedEffect(message.id) {
        bounceScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    if (message.isSelf) {
        // Self message: aligned to the right (WhatsApp style soft green/mint or soft lavender)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 3.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                color = if (tokens.isDark) Color(0xFF005C4B) else Color(0xFFDCF8C6),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = bounceScale.value
                        scaleY = bounceScale.value
                    }
                    .widthIn(max = 240.dp)
            ) {
                Text(
                    text = message.text,
                    fontSize = 12.sp,
                    color = if (tokens.isDark) Color.White else Color(0xFF111827),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    } else {
        // Opponent message: aligned to the left (WhatsApp style crisp surface bubble)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                color = tokens.surface,
                border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = bounceScale.value
                        scaleY = bounceScale.value
                    }
                    .widthIn(max = 240.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    if (!message.senderName.isNullOrBlank()) {
                        Text(
                            text = message.senderName,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.accentOpponent,
                            modifier = Modifier.padding(bottom = 1.dp)
                        )
                    }
                    Text(
                        text = message.text,
                        fontSize = 12.sp,
                        color = tokens.cellNeutralText,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
