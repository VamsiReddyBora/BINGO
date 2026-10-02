package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Animated Emoji Component.
 * Features a subtle, calm, organic breathing effect running 100% on the GPU via graphicsLayer.
 * Zero recomposition overhead, zero layout passes.
 */
@Composable
fun AnimatedEmoji(
    emoji: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp,
    enableAnimation: Boolean = true
) {
    if (!enableAnimation) {
        Text(text = emoji, fontSize = fontSize, modifier = modifier)
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "SubtleBreathingEmoji_$emoji")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    Box(
        modifier = modifier
            .wrapContentSize()
            .graphicsLayer {
                scaleX = breathScale
                scaleY = breathScale
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = fontSize
        )
    }
}
