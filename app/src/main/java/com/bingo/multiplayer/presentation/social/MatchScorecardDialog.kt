package com.bingo.multiplayer.presentation.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MatchScorecardDialog(
    record: MatchRecord,
    onRematch: (String) -> Unit,
    onViewProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = BingoTheme.colors
    val dateFormat = remember { SimpleDateFormat("EEEE, MMM dd, yyyy • hh:mm a", Locale.getDefault()) }
    val actualLines = remember(record) {
        com.bingo.multiplayer.domain.engine.MatchScorecardStats.completedLines(
            record.boardSize, record.finalBoardNumbers, record.markedNumbers
        )
    }
    val hasSnapshot = actualLines != null

    val cleanOpponent = record.opponentName.trim().lowercase().removePrefix("@")
    val isRunner = record.matchTitle.endsWith("Runner 🥈", ignoreCase = true)
    val bannerColor = when {
        record.isDraw -> Color(0xFFEAB308) // Amber
        record.didWin -> Color(0xFF16A34A) // Green
        isRunner -> Color(0xFF3B82F6)
        else -> Color(0xFFDC2626) // Red
    }
    val outcomeTitle = when {
        record.isDraw -> "MATCH DRAW 🤝"
        record.didWin -> "VICTORY 🏆"
        isRunner -> "RUNNER-UP 🥈"
        else -> "DEFEAT 💔"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Outcome Banner ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bannerColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = outcomeTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = bannerColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = tokens.cellNeutralText.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Opponent Identity Card ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.backgroundSecondary,
                    border = BorderStroke(1.dp, tokens.surfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerAvatar(
                            avatarPathOrUri = null,
                            displayName = record.opponentName,
                            size = 46.dp,
                            borderColor = bannerColor,
                            username = cleanOpponent
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = record.opponentName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                            Text(
                                text = "${record.mode} • ${record.boardSize}×${record.boardSize} Board",
                                fontSize = 11.5.sp,
                                color = tokens.textMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Match Details Grid ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricChip(
                        title = "Turns",
                        value = if (hasSnapshot || record.turnsTaken > 0) "${record.turnsTaken}" else "--",
                        icon = Icons.Default.SwapHoriz,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        title = "Lines",
                        value = actualLines?.let { "$it/${record.boardSize}" }
                            ?: if (record.linesCompleted > 0) "${record.linesCompleted}/${record.boardSize}" else "--",
                        icon = Icons.Default.Timeline,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        title = "Duration",
                        value = if (hasSnapshot || record.durationSeconds > 0) "${record.durationSeconds / 60}m ${record.durationSeconds % 60}s" else "--",
                        icon = Icons.Default.Timer,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Miniature board preview for the recorded board size.
                if (hasSnapshot) {
                    Text(
                        text = "FINAL BOARD STATE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.textMuted
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (row in 0 until record.boardSize) {
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (col in 0 until record.boardSize) {
                                        val idx = row * record.boardSize + col
                                        val num = record.finalBoardNumbers[idx]
                                        val isMarked = record.markedNumbers.contains(num)

                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (isMarked) bannerColor.copy(alpha = 0.25f)
                                                    else tokens.surface
                                                )
                                                .border(
                                                    0.5.dp,
                                                    if (isMarked) bannerColor else tokens.surfaceBorder,
                                                    RoundedCornerShape(4.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$num",
                                                fontSize = 10.sp,
                                                fontWeight = if (isMarked) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isMarked) bannerColor else tokens.cellNeutralText
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Match Timestamp
                Text(
                    text = dateFormat.format(Date(record.timestamp)),
                    fontSize = 11.sp,
                    color = tokens.textMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // ── Action Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (cleanOpponent.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onViewProfile(cleanOpponent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (tokens.isDark) Color(0xFF383838) else tokens.surfaceBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.cellNeutralText),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Profile",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.cellNeutralText
                            )
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                onRematch(cleanOpponent)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.primaryButtonBg,
                                contentColor = tokens.primaryButtonText
                            ),
                            modifier = Modifier.weight(1.3f),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = tokens.primaryButtonText
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Rematch ⚔️",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.primaryButtonText
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.primaryButtonBg,
                                contentColor = tokens.primaryButtonText
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tokens.backgroundSecondary,
        border = BorderStroke(1.dp, tokens.surfaceBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.textMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.cellNeutralText
            )
            Text(
                text = title,
                fontSize = 9.5.sp,
                color = tokens.textMuted
            )
        }
    }
}
