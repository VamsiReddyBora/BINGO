package com.bingo.multiplayer.presentation.manual

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.core.designsystem.BingoTheme

@Composable
fun ManualBoardDesignScreen(
    boardSize: Int = 5,
    roomCode: String = "",
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

            // ── N x N Grid Layout ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = tokens.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (r in 0 until boardSize) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (c in 0 until boardSize) {
                                val idx = r * boardSize + c
                                val number = grid.getOrNull(idx)
                                val isMostRecent = (number != null && number == nextNumber - 1)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                number != null && isMostRecent -> tokens.accentBrand.copy(alpha = 0.22f)
                                                number != null -> tokens.background
                                                else -> tokens.surfaceBorder.copy(alpha = 0.35f)
                                            }
                                        )
                                        .border(
                                            width = if (isMostRecent) 2.dp else 1.dp,
                                            color = when {
                                                isMostRecent -> tokens.accentBrand
                                                number != null -> tokens.surfaceBorder
                                                else -> tokens.surfaceBorder.copy(alpha = 0.5f)
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            if (number == null && nextNumber <= totalCells) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val res = ManualBoardEngine.placeNextNumber(grid, idx, nextNumber, boardSize)
                                                if (res != null) {
                                                    grid = res.first
                                                    nextNumber = res.second
                                                }
                                            } else if (isMostRecent) {
                                                // Quick tap on the most recently placed number to undo it
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
                                            fontSize = if (boardSize > 6) 13.sp else 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isMostRecent) tokens.accentBrand else tokens.cellNeutralText
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.cellNeutralText.copy(alpha = 0.25f)
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
                    enabled = nextNumber > 1,
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
                    enabled = filledCount > 0,
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
                    enabled = !isComplete,
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
                    if (isComplete) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val finalNumbers = grid.map { it!! }
                        onBoardReady(finalNumbers)
                    }
                },
                enabled = isComplete,
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
                    tint = if (isComplete) Color.White else tokens.cellNeutralText.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isComplete) "Board Ready ✅" else "Board Ready ($filledCount/$totalCells)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isComplete) Color.White else tokens.cellNeutralText.copy(alpha = 0.4f)
                )
            }
        }
    }
}
