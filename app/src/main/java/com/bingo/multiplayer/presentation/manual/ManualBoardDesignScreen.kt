package com.bingo.multiplayer.presentation.manual

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.ManualBoardEngine

/**
 * AMOLED & Premium Light Mode Manual Board Design Screen.
 * Uses the exact same clean boxes as the Game Screen (clean white boxes, hairline borders).
 * Waiting popup and synchronized countdown are displayed over this clean background
 * before any navigation to the active Game Screen occurs.
 */
@Composable
fun ManualBoardDesignScreen(
    boardSize: Int = 5,
    roomCode: String = "",
    opponentName: String = "",
    isWaitingForOpponent: Boolean = false,
    countdownSeconds: Int = -1,
    firstTurnPlayerName: String = "",
    onBoardReady: (List<Int>) -> Unit,
    onLeave: () -> Unit
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val totalCells = boardSize * boardSize

    var grid by remember { mutableStateOf(ManualBoardEngine.createEmptyGrid(boardSize)) }
    var nextNumber by remember { mutableIntStateOf(1) }
    var showLeaveDialog by remember { mutableStateOf(false) }

    val isComplete = ManualBoardEngine.isBoardComplete(grid, boardSize)
    val filledCount = grid.count { it != null }

    BackHandler {
        showLeaveDialog = true
    }

    // Leave Confirmation Dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Leave Room?",
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to leave while arranging your board?",
                    color = tokens.cellNeutralText.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveDialog = false
                        onLeave()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentOpponent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Leave", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Stay", color = tokens.cellNeutralText)
                }
            }
        )
    }

    // ── Waiting for other players to arrange their boards (Clean white background, NOT GameScreen) ──
    if (isWaitingForOpponent && countdownSeconds < 0) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.background)
        ) {
            AlertDialog(
                onDismissRequest = { /* Modal */ },
                shape = RoundedCornerShape(20.dp),
                containerColor = tokens.surface,
                icon = {
                    Text(text = "⌛", fontSize = 36.sp)
                },
                title = {
                    Text(
                        text = "Arranging the board",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = tokens.cellNeutralText,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = if (opponentName.isNotBlank())
                            "$opponentName is arranging their board...\nGame will start automatically when ready."
                        else
                            "Waiting for other players to arrange their board...\nGame will start automatically when ready.",
                        fontSize = 14.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showLeaveDialog = true }) {
                        Text("Leave Room", color = tokens.accentOpponent)
                    }
                }
            )
        }
        return
    }

    // ── Synchronized 5-Second Countdown Dialog (Clean white background, NOT GameScreen) ──
    if (countdownSeconds in 0..5) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.background)
        ) {
            AlertDialog(
                onDismissRequest = { /* Modal */ },
                shape = RoundedCornerShape(24.dp),
                containerColor = tokens.surface,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(tokens.accentBrand.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (countdownSeconds > 0) "$countdownSeconds" else "🚀",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = tokens.accentBrand
                        )
                    }
                },
                title = {
                    Text(
                        text = if (countdownSeconds > 0) "Game Starting in $countdownSeconds" else "Starting Game...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = tokens.cellNeutralText,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Both boards ready!",
                            fontSize = 13.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.background)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (firstTurnPlayerName.isNotBlank())
                                    "🎯 First Turn: $firstTurnPlayerName"
                                else
                                    "🎯 First turn assigned randomly",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = tokens.accentBrand
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surface)
                        .clickable { showLeaveDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = tokens.cellNeutralText,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Design Your Board",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    if (roomCode.isNotBlank()) {
                        Text(
                            text = "Room: $roomCode",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.cellNeutralText.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Completion status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isComplete) Color(0xFF16A34A).copy(alpha = 0.15f)
                            else tokens.accentBrand.copy(alpha = 0.12f)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isComplete) "Complete ✅" else "$filledCount / $totalCells",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isComplete) Color(0xFF16A34A) else tokens.accentBrand
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Instruction & Next Number Prompt ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = tokens.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isComplete) "Board Filled!" else "Tap any blank box",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = tokens.cellNeutralText
                        )
                        Text(
                            text = if (isComplete) "Click 'Board Ready' below to proceed."
                            else "Assigning numbers sequentially 1 to $totalCells",
                            fontSize = 12.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.65f)
                        )
                    }

                    if (!isComplete) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(tokens.accentBrand)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Next: $nextNumber",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF16A34A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── N x N Grid Layout (Identical clean white boxes to GameScreen) ──
            val spacing = when {
                boardSize <= 4 -> 8.dp
                boardSize == 5 -> 6.dp
                boardSize == 6 -> 5.dp
                else -> 4.dp
            }

            val cornerRadius: Dp = when {
                boardSize <= 5 -> 12.dp
                boardSize == 6 -> 10.dp
                else -> 8.dp
            }
            val cellShape = RoundedCornerShape(cornerRadius)

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxBoardWidth = maxWidth.coerceAtMost(maxHeight)

                Column(
                    modifier = Modifier
                        .size(maxBoardWidth)
                        .aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (r in 0 until boardSize) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            for (c in 0 until boardSize) {
                                val idx = r * boardSize + c
                                val number = grid.getOrNull(idx)
                                val isMostRecent = (number != null && number == nextNumber - 1)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(cellShape)
                                        .background(tokens.cellNeutralBg)
                                        .border(
                                            width = if (isMostRecent) 1.5.dp else 1.dp,
                                            color = if (isMostRecent) tokens.accentBrand else tokens.cellNeutralBorder,
                                            shape = cellShape
                                        )
                                        .clickable(enabled = !isWaitingForOpponent && countdownSeconds < 0) {
                                            if (number == null && nextNumber <= totalCells) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val res = ManualBoardEngine.placeNextNumber(grid, idx, nextNumber, boardSize)
                                                if (res != null) {
                                                    grid = res.first
                                                    nextNumber = res.second
                                                }
                                            } else if (isMostRecent) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                val res = ManualBoardEngine.undoLastNumber(grid, nextNumber)
                                                if (res != null) {
                                                    grid = res.first
                                                    nextNumber = res.second
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (number != null) {
                                        Text(
                                            text = number.toString(),
                                            fontSize = computeMinimalFontSize(boardSize),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isMostRecent) tokens.accentBrand else tokens.cellNeutralText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Helper Actions: Undo / Clear / Auto-Fill ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val res = ManualBoardEngine.undoLastNumber(grid, nextNumber)
                        if (res != null) {
                            grid = res.first
                            nextNumber = res.second
                        }
                    },
                    enabled = nextNumber > 1 && !isWaitingForOpponent && countdownSeconds < 0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Undo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val res = ManualBoardEngine.clearAll(boardSize)
                        grid = res.first
                        nextNumber = res.second
                    },
                    enabled = filledCount > 0 && !isWaitingForOpponent && countdownSeconds < 0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val res = ManualBoardEngine.autoFillRemaining(grid, boardSize)
                        grid = res.first
                        nextNumber = res.second
                    },
                    enabled = !isComplete && !isWaitingForOpponent && countdownSeconds < 0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Auto Fill",
                        modifier = Modifier.size(16.dp),
                        tint = tokens.accentBrand
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Auto Fill", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tokens.accentBrand)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Board Ready Button ──
            Button(
                onClick = {
                    if (isComplete && !isWaitingForOpponent) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val finalNumbers = grid.map { it!! }
                        onBoardReady(finalNumbers)
                    }
                },
                enabled = isComplete && !isWaitingForOpponent && countdownSeconds < 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.accentBrand,
                    disabledContainerColor = tokens.surfaceBorder.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isComplete && !isWaitingForOpponent) Color.White else tokens.cellNeutralText.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isWaitingForOpponent -> "Waiting for Opponent... ⌛"
                        isComplete -> "Board Ready ✅"
                        else -> "Board Ready ($filledCount/$totalCells)"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isComplete && !isWaitingForOpponent) Color.White else tokens.cellNeutralText.copy(alpha = 0.4f)
                )
            }
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
