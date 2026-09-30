package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.domain.model.Board

private val BINGO_LETTERS = listOf('B', 'I', 'N', 'G', 'O')

/**
 * Minimal, clean Bingo Board grid with 3D tactile cells (laser strike overlay removed).
 */
@Composable
fun BingoBoardView(
    board: Board,
    isInteractive: Boolean,
    onCellClicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onBoardWidthMeasured: ((Dp) -> Unit)? = null
) {
    val size = board.size
    val spacing = when {
        size <= 5 -> 8.dp
        size == 6 -> 6.dp
        else -> 4.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val maxBoardWidth = maxWidth.coerceAtMost(maxHeight - 44.dp)
        LaunchedEffect(maxBoardWidth) {
            onBoardWidthMeasured?.invoke(maxBoardWidth)
        }

        Column(
            modifier = Modifier.width(maxBoardWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Column Letters Row (B I N G O directly above columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (c in 0 until size) {
                    val letter = if (c < BINGO_LETTERS.size) BINGO_LETTERS[c] else '★'
                    val isUnlocked = c < board.completedLinesCount || (board.isBingo && c < size)
                    val strikeProgress by animateFloatAsState(
                        targetValue = if (isUnlocked) 1f else 0f,
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        label = "boardColStrike_$c"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )

                        // Diagonal strike across the letter on completion
                        if (isUnlocked || strikeProgress > 0f) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val progress = if (isUnlocked) 1f else strikeProgress
                                val extensionPx = 5.dp.toPx()
                                val charBoxHalf = 12.dp.toPx()
                                val midX = this.size.width / 2f
                                val midY = this.size.height / 2f

                                // Diagonal strike from bottom-left to top-right
                                val startX = midX - charBoxHalf - extensionPx
                                val startY = midY + charBoxHalf + extensionPx
                                val targetEndX = midX + charBoxHalf + extensionPx
                                val targetEndY = midY - charBoxHalf - extensionPx

                                val curEndX = startX + (targetEndX - startX) * progress
                                val curEndY = startY + (targetEndY - startY) * progress

                                drawLine(
                                    color = Color.Black,
                                    start = Offset(startX, startY),
                                    end = Offset(curEndX, curEndY),
                                    strokeWidth = 3.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 5x5 Grid of Cells
            Box(
                modifier = Modifier
                    .size(maxBoardWidth)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (r in 0 until size) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            for (c in 0 until size) {
                                val cell = board.getCell(r, c)
                                BingoCell(
                                    cell = cell,
                                    boardDimension = size,
                                    enabled = isInteractive,
                                    onCellClick = { onCellClicked(cell.number) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean plain-text B-I-N-G-O progression tracker in black with realistic extended horizontal strike-through.
 * When a line is completed, a horizontal strike-off line draws smoothly across the letter extending
 * beyond the character boundaries for an authentic pen cross-out feel.
 */
@Composable
fun BingoHeaderTracker(
    completedLines: Int,
    targetLines: Int = 5,
    modifier: Modifier = Modifier
) {
    val extraStars = if (targetLines > 5) List(targetLines - 5) { '★' } else emptyList()
    val displayLetters = (BINGO_LETTERS.take(targetLines) + extraStars).take(targetLines)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayLetters.forEachIndexed { index, letter ->
            val isUnlocked = index < completedLines

            val strikeProgress by animateFloatAsState(
                targetValue = if (isUnlocked) 1f else 0f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                label = "strikeProgress_$index"
            )

            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Black
                )

                if (strikeProgress > 0f) {
                    Canvas(
                        modifier = Modifier.matchParentSize()
                    ) {
                        val extensionPx = 6.dp.toPx()
                        val startX = -extensionPx
                        val totalTargetWidth = size.width + (2 * extensionPx)
                        val currentEndX = startX + (totalTargetWidth * strikeProgress)
                        val centerY = size.height / 2f

                        drawLine(
                            color = Color.Black,
                            start = Offset(startX, centerY),
                            end = Offset(currentEndX, centerY),
                            strokeWidth = 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}
