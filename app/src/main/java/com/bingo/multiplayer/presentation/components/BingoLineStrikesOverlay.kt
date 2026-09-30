package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType

/**
 * Animated laser strike-through overlay rendered across completed rows, columns, and diagonals.
 * Features an electrifying neon glow, bright core beam, glowing spark head during animation,
 * and idle breathing glow for active completed lines.
 */
@Composable
fun BingoLineStrikesOverlay(
    completedLines: Set<LineCoordinate>,
    boardSize: Int,
    spacing: Dp,
    modifier: Modifier = Modifier
) {
    if (completedLines.isEmpty() || boardSize <= 0) return

    val infiniteTransition = rememberInfiniteTransition(label = "strikeGlowTransition")
    val idleGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleGlowAlpha"
    )

    // Track progress of each completed line so newly completed lines animate their entrance
    val animProgressMap = remember { mutableMapOf<LineCoordinate, Animatable<Float, *>>() }

    completedLines.forEach { line ->
        val anim = animProgressMap.getOrPut(line) {
            Animatable(0f)
        }
        LaunchedEffect(line) {
            if (anim.value < 1f) {
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val boardPx = size.width
        val spacingPx = spacing.toPx()
        val cellSizePx = (boardPx - (boardSize - 1) * spacingPx) / boardSize

        completedLines.forEach { line ->
            val progress = animProgressMap[line]?.value ?: 1f
            if (progress <= 0f) return@forEach

            val (startOffset, endOffset) = when (line.type) {
                LineType.ROW -> {
                    val y = line.index * (cellSizePx + spacingPx) + (cellSizePx / 2f)
                    val x1 = cellSizePx * 0.12f
                    val x2 = boardPx - cellSizePx * 0.12f
                    Offset(x1, y) to Offset(x2, y)
                }
                LineType.COLUMN -> {
                    val x = line.index * (cellSizePx + spacingPx) + (cellSizePx / 2f)
                    val y1 = cellSizePx * 0.12f
                    val y2 = boardPx - cellSizePx * 0.12f
                    Offset(x, y1) to Offset(x, y2)
                }
                LineType.MAIN_DIAGONAL -> {
                    val start = Offset(cellSizePx * 0.16f, cellSizePx * 0.16f)
                    val end = Offset(boardPx - cellSizePx * 0.16f, boardPx - cellSizePx * 0.16f)
                    start to end
                }
                LineType.ANTI_DIAGONAL -> {
                    val start = Offset(boardPx - cellSizePx * 0.16f, cellSizePx * 0.16f)
                    val end = Offset(cellSizePx * 0.16f, boardPx - cellSizePx * 0.16f)
                    start to end
                }
            }

            val currentEnd = Offset(
                x = startOffset.x + (endOffset.x - startOffset.x) * progress,
                y = startOffset.y + (endOffset.y - startOffset.y) * progress
            )

            // Neon colors: Golden / Amber radiance with hot white core
            val glowColor = Color(0xFFF59E0B)
            val outerGlowColor = Color(0xFFFBBF24).copy(alpha = idleGlowAlpha)
            val coreColor = Color(0xFFFFFBEB)

            // 1. Wide outer ambient glow
            drawLine(
                color = outerGlowColor,
                start = startOffset,
                end = currentEnd,
                strokeWidth = 11.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 2. Vibrant neon beam
            drawLine(
                color = glowColor,
                start = startOffset,
                end = currentEnd,
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 3. Crisp white-hot laser core
            drawLine(
                color = coreColor,
                start = startOffset,
                end = currentEnd,
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 4. Moving spark head while animating
            if (progress < 0.98f) {
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = currentEnd
                )
                drawCircle(
                    color = Color(0xFFF59E0B).copy(alpha = 0.8f),
                    radius = 9.dp.toPx(),
                    center = currentEnd
                )
            } else {
                // Fixed small terminal caps
                drawCircle(
                    color = glowColor,
                    radius = 3.5.dp.toPx(),
                    center = startOffset
                )
                drawCircle(
                    color = glowColor,
                    radius = 3.5.dp.toPx(),
                    center = endOffset
                )
            }
        }
    }
}
