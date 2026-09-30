package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import kotlin.math.PI
import kotlin.math.sin

val QUICK_EMOTES = listOf("🔥", "😱", "😂", "🎯", "👏")

data class FloatingEmoteItem(
    val id: Long = System.currentTimeMillis() + (0..10000).random(),
    val emoji: String,
    val startXRatio: Float = 0.5f,
    val isSelf: Boolean = true,
    val senderName: String? = null
)

/**
 * Expandable Floating Emote Action Bar.
 * Allows quick one-tap emoji reactions (🔥, 😱, 😂, 🎯, 👏) during the match.
 */
@Composable
fun FloatingEmoteBar(
    onEmoteSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        if (isExpanded) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 6.dp,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QUICK_EMOTES.forEach { emoji ->
                        var isPressed by remember { mutableStateOf(false) }
                        val pressScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.85f else 1.0f,
                            animationSpec = tween(durationMillis = 80),
                            label = "emotePressScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .scale(pressScale)
                                .clip(CircleShape)
                                .background(tokens.backgroundSecondary)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            isPressed = true
                                            tryAwaitRelease()
                                            isPressed = false
                                        },
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onEmoteSelected(emoji)
                                            isExpanded = false
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }

                    // Close Button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { isExpanded = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Emotes",
                            tint = tokens.cellNeutralText.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else {
            // Collapsed Floating Button
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isExpanded = true
                },
                shape = CircleShape,
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 4.dp,
                modifier = Modifier.size(42.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💬",
                        fontSize = 19.sp
                    )
                }
            }
        }
    }
}

/**
 * Animated Floating Emotes Overlay.
 * Renders floating reaction emojis rising from bottom to top with harmonic sway and fade-out.
 */
@Composable
fun FloatingEmotesOverlay(
    activeEmotes: List<FloatingEmoteItem>,
    onEmoteFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (activeEmotes.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        activeEmotes.forEach { emoteItem ->
            key(emoteItem.id) {
                SingleFloatingEmoteBubble(
                    item = emoteItem,
                    screenW = screenW.value,
                    screenH = screenH.value,
                    onFinished = { onEmoteFinished(emoteItem.id) }
                )
            }
        }
    }
}

@Composable
private fun SingleFloatingEmoteBubble(
    item: FloatingEmoteItem,
    screenW: Float,
    screenH: Float,
    onFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val tokens = BingoTheme.colors

    LaunchedEffect(item.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1800, easing = LinearEasing)
        )
        onFinished()
    }

    val p = progress.value

    // Physics calculations
    val startY = screenH * 0.78f
    val endY = screenH * 0.18f
    val currentY = startY + (endY - startY) * p

    val baseStartX = (screenW * item.startXRatio).coerceIn(40f, (screenW - 60f).coerceAtLeast(40f))
    // Gentle natural sinusoidal sway
    val swayX = sin(p * 3.5 * PI).toFloat() * 18f
    val currentX = baseStartX + swayX

    // Scale spring curve: pops to 1.35f, then settles at 1.05f
    val scale = when {
        p < 0.18f -> (p / 0.18f) * 1.35f
        p < 0.32f -> 1.35f - ((p - 0.18f) / 0.14f) * 0.30f
        else -> 1.05f
    }

    // Alpha: Solid up to 70%, then fades out
    val alpha = when {
        p < 0.08f -> p / 0.08f
        p > 0.70f -> (1f - (p - 0.70f) / 0.30f).coerceIn(0f, 1f)
        else -> 1f
    }

    Box(
        modifier = Modifier
            .offset(x = currentX.dp, y = currentY.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = tokens.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.emoji,
                        fontSize = 24.sp
                    )
                }
            }

            if (!item.isSelf && !item.senderName.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.surface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = item.senderName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentOpponent,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}
