package com.bingo.multiplayer.presentation.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.R
import com.bingo.multiplayer.presentation.components.EmojiParticleConfig
import com.bingo.multiplayer.presentation.components.MultiEmojiFloatingParticleSystem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class StampResultType {
    WON,
    LOST,
    @Deprecated("Draws are decommissioned; only WON, RUNNER, LOST, OFFLINE apply")
    DRAW,
    RUNNER,
    OFFLINE
}

/**
 * Rough sketch & chalk font for authentic stamped / chalkboard texture.
 */
private val ChalkSketchFont = FontFamily(
    Font(R.font.cabin_sketch_bold, FontWeight.Bold)
)

/**
 * Authentic rubber-stamp style badge pasted with force from height into the empty space
 * between the top bar and the 5x5 board.
 */
@Composable
fun VictoryStampBadge(
    resultType: StampResultType,
    modifier: Modifier = Modifier,
    animateStampDrop: Boolean = true,
    customText: String? = null
) {
    val haptic = LocalHapticFeedback.current

    val (defaultText, mainColor, bgColor) = when (resultType) {
        StampResultType.WON -> Triple(
            "YOU'VE WON!",
            Color(0xFF15803D), // Forest Stamped Green
            Color(0x1816A34A)
        )
        StampResultType.RUNNER -> Triple(
            "RUNNER!",
            Color(0xFF2563EB), // Vibrant Blue / Silver Runner
            Color(0x183B82F6)
        )
        StampResultType.LOST -> Triple(
            "YOU LOST!",
            Color(0xFFDC2626), // Stamped Red
            Color(0x18DC2626)
        )
        StampResultType.DRAW -> Triple(
            "DRAW!",
            Color(0xFFD97706), // Stamped Amber / Yellow
            Color(0x18F59E0B)
        )
        StampResultType.OFFLINE -> Triple(
            "OFFLINE",
            Color(0xFF6B7280), // Neutral Stamped Gray
            Color(0x186B7280)
        )
    }
    val text = customText?.takeIf { it.isNotBlank() } ?: defaultText

    val stampScale = remember { Animatable(if (animateStampDrop) 2.6f else 1.0f) }
    val stampAlpha = remember { Animatable(if (animateStampDrop) 0.0f else 1.0f) }
    var showDustEffect by remember { mutableStateOf(false) }

    LaunchedEffect(resultType, animateStampDrop) {
        if (animateStampDrop) {
            stampScale.snapTo(2.6f)
            stampAlpha.snapTo(0.0f)

            launch {
                stampAlpha.animateTo(1.0f, tween(durationMillis = 180, easing = LinearEasing))
            }
            stampScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            )

            try {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Exception) {}
            showDustEffect = true

            stampScale.animateTo(
                targetValue = 0.96f,
                animationSpec = tween(durationMillis = 60, easing = FastOutLinearInEasing)
            )
            stampScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
        } else {
            stampScale.snapTo(1.0f)
            stampAlpha.snapTo(1.0f)
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize()
            .widthIn(max = 280.dp)
            .graphicsLayer {
                scaleX = stampScale.value
                scaleY = stampScale.value
                alpha = stampAlpha.value
                rotationZ = -6.5f
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .border(
                    width = 2.8.dp,
                    color = mainColor,
                    shape = RoundedCornerShape(4.dp)
                )
                .background(
                    color = bgColor,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 274.dp)
                    .border(
                        width = 1.4.dp,
                        color = mainColor,
                        shape = RoundedCornerShape(2.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                val computedFontSize = when {
                    text.length > 16 -> 14.sp
                    text.length > 11 -> 17.sp
                    else -> 21.sp
                }
                Text(
                    text = text,
                    fontSize = computedFontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ChalkSketchFont,
                    letterSpacing = if (text.length > 12) 1.2.sp else 2.2.sp,
                    color = mainColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showDustEffect) {
            StampDustPuff(color = mainColor)
        }
    }
}

@Composable
private fun StampDustPuff(color: Color) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
    }

    if (progress.value < 1f) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = progress.value
            val center = Offset(size.width / 2f, size.height / 2f)
            val dustCount = 14
            val baseRadius = size.width * 0.48f

            for (i in 0 until dustCount) {
                val angle = (i * (360f / dustCount) + (i * 17f % 30f)) * (PI / 180f).toFloat()
                val dist = baseRadius + p * 38.dp.toPx() * (0.8f + (i % 3) * 0.25f)
                val x = center.x + cos(angle) * dist
                val y = center.y + sin(angle) * (dist * 0.65f)
                val particleRadius = (4.dp.toPx() * (1f - p * 0.5f)).coerceAtLeast(1f)
                val alpha = ((1f - p) * 0.7f).coerceIn(0f, 1f)

                drawCircle(
                    color = Color(0xFF9CA3AF).copy(alpha = alpha),
                    radius = particleRadius,
                    center = Offset(x, y)
                )
                if (i % 2 == 0) {
                    drawCircle(
                        color = color.copy(alpha = alpha * 0.8f),
                        radius = particleRadius * 0.7f,
                        center = Offset(x - 2f, y - 2f)
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen Winning Celebration Overlay:
 * Particle animation is intentionally deferred from the winning screen for now per user instruction.
 * A dedicated button in Settings allows testing and previewing it first.
 */
@Composable
fun GameOverEmojiProjectileBurst(
    resultType: StampResultType,
    modifier: Modifier = Modifier,
    onApexReached: () -> Unit = {},
    onBurstFinished: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        onApexReached()
        onBurstFinished()
    }
}

