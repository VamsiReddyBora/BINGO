package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableFloatStateOf

/**
 * Clean, minimal Bingo Cell with responsive feedback and pulsing highlight for recent pick.
 */
@Composable
fun BingoCell(
    cell: Cell,
    boardDimension: Int,
    enabled: Boolean,
    onCellClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label = "cellPressScale"
    )

    val isOpponentRecent = cell.isRecentPick &&
        (cell.markState is CellMarkState.Marked && !cell.markState.isOwnPick)

    val infiniteTransition = rememberInfiniteTransition(label = "cellPulseAnim")
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

    val styling = resolveMinimalCellStyling(cell = cell)

    val animatedBg by animateColorAsState(targetValue = styling.backgroundColor, label = "cellBg")

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
            .scale(pressScale * pulseScale)
            .clip(shape)
            .pointerInput(isInteractive) {
                if (isInteractive) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCellClick()
                        }
                    )
                }
            }
            .drawBehind {
                drawRoundRect(
                    color = animatedBg,
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
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
            color = styling.textColor
        )
    }
}

private data class MinimalCellVisualTokens(
    val backgroundColor: Color,
    val textColor: Color,
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
        // Winning line completed (Increased greyscale with numbers shaded dark while preserving choices)
        cell.isPartOfCompletedLine -> {
            when {
                isOwn -> {
                    // Player choice in completed line: Rich grey with shaded dark purple number
                    MinimalCellVisualTokens(
                        backgroundColor = Color(0xFF94A3B8),
                        textColor = Color(0xFF2E0854),
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp
                    )
                }
                isOpponentRecent -> {
                    // Opponent recent pick in completed line: Rich grey with shaded dark orange number
                    MinimalCellVisualTokens(
                        backgroundColor = Color(0xFF94A3B8),
                        textColor = Color(0xFF7C2D12),
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp
                    )
                }
                isOpponent -> {
                    // Opponent choice in completed line: Rich grey with shaded dark blue number
                    MinimalCellVisualTokens(
                        backgroundColor = Color(0xFF94A3B8),
                        textColor = Color(0xFF082F49),
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp
                    )
                }
                else -> {
                    // Generic completed line cell: Clean strong greyscale with dark slate number
                    MinimalCellVisualTokens(
                        backgroundColor = Color(0xFF94A3B8),
                        textColor = Color(0xFF020617),
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp
                    )
                }
            }
        }

        // Player choice (ALWAYS Purple - whether recent pick or earlier! Plain, NO BORDER)
        isOwn -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellPlayerPickBg,
                textColor = tokens.cellPlayerPickText,
                borderColor = Color.Transparent,
                borderWidth = 0.dp
            )
        }

        // Opponent recent choice (Orange 🧡! Plain, NO BORDER)
        isOpponentRecent -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.recentPickBg,
                textColor = tokens.recentPickText,
                borderColor = Color.Transparent,
                borderWidth = 0.dp
            )
        }

        // Opponent earlier choice (Blue 💙! Plain, NO BORDER)
        isOpponent -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellOpponentPickBg,
                textColor = tokens.cellOpponentPickText,
                borderColor = Color.Transparent,
                borderWidth = 0.dp
            )
        }

        // Unpicked Cell (Clean flat white, clear outline)
        else -> {
            MinimalCellVisualTokens(
                backgroundColor = tokens.cellNeutralBg,
                textColor = tokens.cellNeutralText,
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
