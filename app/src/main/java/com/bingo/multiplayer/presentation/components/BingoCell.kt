package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.core.designsystem.ThemePreferences
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState

/**
 * Tactile 3D Bingo Cell with physical push-down button physics, bottom bevel shadow,
 * opponent radar ripple aura, and responsive haptic feedback.
 */
@Composable
fun BingoCell(
    cell: Cell,
    boardDimension: Int,
    enabled: Boolean,
    onCellClick: () -> Unit,
    modifier: Modifier = Modifier,
    turnGlowIntensity: Float = 0f
) {
    val haptic = LocalHapticFeedback.current
    val tokens = BingoTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = tween(durationMillis = 80),
        label = "cellPressScale"
    )

    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 80),
        label = "cellPressOffsetY"
    )

    val bevelHeight by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 3.2.dp,
        animationSpec = tween(durationMillis = 80),
        label = "cellBevelHeight"
    )

    val isOpponentRecent = cell.isRecentPick &&
        (cell.markState is CellMarkState.Marked && !cell.markState.isOwnPick)

    val infiniteTransition = rememberInfiniteTransition(label = "cellAnimTransition")

    // Rhythmic breathing pulse for opponent recent pick
    val pulseScale by if (isOpponentRecent) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "recentPickPulse"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    // Expanding radar ripple wave radiating outward from opponent recent pick
    val radarWaveProgress by if (isOpponentRecent) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1300, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radarWaveProgress"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val styling = resolveMinimalCellStyling(cell = cell)
    val animatedBg by animateColorAsState(targetValue = styling.backgroundColor, label = "cellBg")
    val animatedBevel by animateColorAsState(targetValue = styling.bevelColor, label = "cellBevel")

    val cornerRadius: Dp = when {
        boardDimension <= 5 -> 12.dp
        boardDimension == 6 -> 10.dp
        else -> 8.dp
    }
    val shape = RoundedCornerShape(cornerRadius)
    val isInteractive = enabled && !cell.isMarked

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = isInteractive,
                onClick = {
                    if (com.bingo.multiplayer.core.designsystem.SoundPreferences.isHapticsAllowed()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    onCellClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── 1. Opponent Radar Ripple Aura ──
        if (isOpponentRecent) {
            val waveScale = 1.0f + (radarWaveProgress * 0.32f)
            val waveAlpha = (1f - radarWaveProgress).coerceIn(0f, 1f) * 0.6f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = waveScale
                        scaleY = waveScale
                    }
                    .clip(shape)
                    .drawBehind {
                        drawRoundRect(
                            color = tokens.recentPickBg.copy(alpha = waveAlpha),
                            size = size,
                            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                        )
                    }
            )
        }

        // ── 2. Tactile 3D Cell Body ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pressScale * pulseScale
                    scaleY = pressScale * pulseScale
                    translationY = pressOffsetY.toPx()
                }
                .clip(shape)
                .drawBehind {
                    val crPx = cornerRadius.toPx()
                    val bPx = bevelHeight.toPx()

                    // Bottom 3D bevel / extruded thickness
                    if (bPx > 0f) {
                        drawRoundRect(
                            color = animatedBevel,
                            topLeft = Offset(0f, 0f),
                            size = size,
                            cornerRadius = CornerRadius(crPx, crPx)
                        )
                    }

                    // Elevated tile surface
                    val surfaceHeight = (size.height - bPx).coerceAtLeast(0f)
                    drawRoundRect(
                        color = animatedBg,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, surfaceHeight),
                        cornerRadius = CornerRadius(crPx, crPx)
                    )
                }
                .then(
                    if (styling.borderWidth > 0.dp && styling.borderColor != Color.Transparent) {
                        Modifier.border(
                            width = styling.borderWidth,
                            color = styling.borderColor,
                            shape = shape
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cell.number.toString(),
                fontSize = computeMinimalFontSize(boardDimension),
                fontWeight = when {
                    cell.isPartOfCompletedLine -> FontWeight.ExtraBold
                    cell.isMarked || cell.isRecentPick -> FontWeight.Bold
                    else -> FontWeight.Medium
                },
                color = styling.textColor,
                modifier = Modifier.offset(y = -((bevelHeight * 0.4f)))
            )
        }
    }
}

private data class MinimalCellVisualTokens(
    val backgroundColor: Color,
    val textColor: Color,
    val bevelColor: Color = Color.Transparent,
    val borderColor: Color = Color.Transparent,
    val borderWidth: Dp = 0.dp
)

@Composable
private fun resolveMinimalCellStyling(cell: Cell): MinimalCellVisualTokens {
    val tokens = BingoTheme.colors
    val markState = cell.markState as? CellMarkState.Marked
    val isOwn = markState?.isOwnPick == true
    val isOpponent = markState != null && !markState.isOwnPick
    val isOpponentRecent = isOpponent && cell.isRecentPick

    return when {
        // Opponent recent choice takes precedence for visibility of the latest move
        isOpponentRecent -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.recentPickBg,
                textColor = tokens.recentPickText,
                bevelColor = if (tokens.isDark) Color(0xFFEA580C) else Color(0xFFC2410C),
                borderColor = tokens.recentPickBorder,
                borderWidth = if (tokens.recentPickBorder != Color.Transparent && (tokens.isDark || ThemePreferences.cellBorderEnabled.value)) 1.dp else 0.dp
            )
        }

        // Winning line completed - dynamically reflects Line Completion color & Cell Border settings
        cell.isPartOfCompletedLine -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.completedLineBg,
                textColor = tokens.completedLineText,
                bevelColor = tokens.completedLineBg.copy(alpha = 0.8f),
                borderColor = tokens.completedLineBorder,
                borderWidth = if (tokens.completedLineBorder != Color.Transparent && ThemePreferences.cellBorderEnabled.value) 1.dp else 0.dp
            )
        }

        // Player choice (Lavender in light, Ice Blue in dark)
        isOwn -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellPlayerPickBg,
                textColor = tokens.cellPlayerPickText,
                bevelColor = tokens.cellPlayerPickBevel,
                borderColor = tokens.cellPlayerPickBorder,
                borderWidth = if (tokens.isDark || ThemePreferences.cellBorderEnabled.value) 1.dp else 0.dp
            )
        }

        // Opponent earlier choice (Blue in light, Orange in dark)
        isOpponent -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellOpponentPickBg,
                textColor = tokens.cellOpponentPickText,
                bevelColor = tokens.cellOpponentPickBevel,
                borderColor = tokens.cellOpponentPickBorder,
                borderWidth = if (tokens.isDark || ThemePreferences.cellBorderEnabled.value) 1.dp else 0.dp
            )
        }

        // Unpicked Cell (Clean flat white with soft shadow bevel and crisp border)
        else -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellNeutralBg,
                textColor = tokens.cellNeutralText,
                bevelColor = tokens.cellNeutralBevel,
                borderColor = tokens.cellNeutralBorder,
                borderWidth = 1.dp
            )
        }
    }
}

private fun computeMinimalFontSize(boardSize: Int): TextUnit {
    return when (boardSize) {
        4 -> 22.sp
        5 -> 19.sp
        6 -> 16.sp
        7 -> 13.sp
        else -> 11.sp
    }
}
