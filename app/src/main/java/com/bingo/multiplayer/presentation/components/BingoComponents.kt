package com.bingo.multiplayer.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Board

private val BINGO_LETTERS = listOf('B', 'I', 'N', 'G', 'O')

/**
 * Minimal, clean Bingo Board grid with 3D tactile cells and animated neon laser strike overlay.
 */
@Composable
fun BingoBoardView(
    board: Board,
    isInteractive: Boolean,
    onCellClicked: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val size = board.size
    val spacing = when {
        size <= 4 -> 8.dp
        size == 5 -> 6.dp
        size == 6 -> 5.dp
        else -> 4.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val maxBoardWidth = maxWidth.coerceAtMost(maxHeight)

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

            // Laser strike overlay rendered directly over completed lines
            BingoLineStrikesOverlay(
                completedLines = board.completedLines,
                boardSize = size,
                spacing = spacing,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Minimal B-I-N-G-O letter progression tracker.
 */
@Composable
fun BingoHeaderTracker(
    completedLines: Int,
    targetLines: Int = 5,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val extraStars = if (targetLines > 5) List(targetLines - 5) { '★' } else emptyList()
        val displayLetters = (BINGO_LETTERS.take(targetLines) + extraStars).take(targetLines)
        displayLetters.forEachIndexed { index, letter ->
            val isUnlocked = index < completedLines
            val shape = RoundedCornerShape(10.dp)

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(
                        if (isUnlocked) tokens.completedLetterGradientEnd else tokens.surface
                    )
                    .then(
                        if (isUnlocked) {
                            Modifier
                        } else {
                            Modifier.background(tokens.surface)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUnlocked) Color.White else tokens.cellNeutralText.copy(alpha = 0.4f)
                )
            }
        }
    }
}
