package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoColors
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.presentation.components.AnimatedEmoji

/**
 * In-game WhatsApp-style chat space positioned between the 5x5 Bingo board and the emoji reactions strip.
 * - Width matches the exact board width so the left and right edges align pixel-perfect with the board.
 * - Seamless plain background with no card or heavy border.
 * - Smooth vertical gradient blur/fade at the top so messages dissolve gracefully when scrolling under the board.
 * - Tightened vertical spacing between sender name and actual message to utilize space efficiently.
 * - Displays light grey "Double tap to chat" hint when empty or idle.
 * - Double tapping triggers the full-length text box & software keyboard.
 * - Messages animate and bounce into position (Self on right, Opponent on left).
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
            // Smooth gradient fade out at the top using offscreen compositing & DstIn blend mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Fade out the top 24dp smoothly into transparency instead of a sharp cut
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Transparent,
                                0.16f to Color.Black
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        InGameChatBubble(message = msg, tokens = tokens)
                    }
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

    val trimmed = message.text.trim()
    val isEmojiOnly = trimmed.isNotBlank() && trimmed.length <= 8 && !trimmed.any { it.isLetterOrDigit() }

    if (message.isSystemMessage) {
        // Centered system notice (e.g. "Player 3 left the game", "Player 3 joined the game")
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (tokens.isDark) Color(0xFF23272A).copy(alpha = 0.85f) else Color(0xFFE2E8F0).copy(alpha = 0.90f),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = bounceScale.value
                        scaleY = bounceScale.value
                    }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = message.text,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = if (tokens.isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    } else if (message.isSelf) {
        // Self message: aligned to the right (WhatsApp style soft green/mint or soft lavender)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 3.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                color = if (tokens.isDark) Color(0xFF262626) else Color(0xFFDCF8C6),
                border = if (tokens.isDark) BorderStroke(1.dp, Color(0xFF383838)) else null,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = bounceScale.value
                        scaleY = bounceScale.value
                    }
                    .widthIn(max = 240.dp)
            ) {
                if (isEmojiOnly) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        AnimatedEmoji(
                            emoji = trimmed,
                            fontSize = 22.sp
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = tokens.textPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
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
                color = if (tokens.isDark) Color(0xFF181818) else tokens.surface,
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (!message.senderName.isNullOrBlank()) {
                        Text(
                            text = message.senderName,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tokens.isDark) tokens.textSecondary else tokens.accentOpponent
                        )
                    }
                    if (isEmojiOnly) {
                        AnimatedEmoji(
                            emoji = trimmed,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    } else {
                        Text(
                            text = message.text,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
