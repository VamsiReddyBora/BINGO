package com.bingo.multiplayer.presentation.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.components.BingoBoardView
import com.bingo.multiplayer.presentation.components.BingoHeaderTracker

/**
 * Modern Award-Winning Indie Game Match Screen.
 * Complete tactile styling, AMOLED Dark / Premium Light tokens, and victory celebration.
 */
@Composable
fun GameScreen(
    board: Board,
    opponentBoard: Board? = null,
    isMyTurn: Boolean,
    turnTimeRemaining: Int,
    isGamePaused: Boolean = false,
    pausedByPlayerName: String = "",
    onTogglePause: () -> Unit = {},
    recentPick: RecentPick?,
    opponentName: String,
    isGameOver: Boolean,
    didPlayerWin: Boolean,
    isDraw: Boolean = false,
    onCellPicked: (Int) -> Unit,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    onSyncGame: () -> Unit = {},
    isHost: Boolean = true,
    pingMs: Long = 0L,
    wantsToPlayAgainName: String? = null,
    onRequestPlayAgain: () -> Unit = {},
    onSurrender: () -> Unit = onBackToMenu,
    onReturnToLobby: (() -> Unit)? = null,
    myPlayerId: String = "",
    opponentAvatarUrl: String? = null,
    opponentUsername: String? = null,
    myAvatarUrl: String? = null,
    myUsername: String? = null,
    myDisplayName: String? = null,
    isWaitingForOpponentBoard: Boolean = false,
    countdownSeconds: Int = -1,
    firstTurnPlayerName: String = ""
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // Trigger haptic vibration whenever the opponent picks a number
    LaunchedEffect(recentPick) {
        if (recentPick != null && recentPick.number > 0) {
            val isOpponentPick = if (myPlayerId.isNotBlank()) {
                recentPick.pickedByPlayerId != myPlayerId
            } else {
                !isMyTurn || recentPick.pickedByPlayerId.contains("ai", ignoreCase = true)
            }
            if (isOpponentPick) {
                try {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        var insetsController: WindowInsetsControllerCompat? = null
        if (window != null) {
            insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    var showSurrenderDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = !isGameOver) {
        showSurrenderDialog = true
    }
    BackHandler(enabled = isGameOver) {
        if (onReturnToLobby != null) {
            onReturnToLobby()
        } else {
            onBackToMenu()
        }
    }
    var reviewingOpponentBoard by remember { mutableStateOf(false) }
    var hasRequestedPlayAgain by remember(isGameOver) { mutableStateOf(false) }

    val displayedBoard = if (isGameOver && reviewingOpponentBoard && opponentBoard != null) {
        opponentBoard
    } else {
        board
    }

    if (showSurrenderDialog) {
        AlertDialog(
            onDismissRequest = { showSurrenderDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = "Leave Match?",
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to leave the match? Leaving the match will count as a loss.",
                    color = tokens.cellNeutralText.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSurrenderDialog = false
                        onSurrender()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accentOpponent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Leave Match", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSurrenderDialog = false }) {
                    Text("Stay", color = tokens.cellNeutralText)
                }
            }
        )
    }

    if (isGamePaused) {
        AlertDialog(
            onDismissRequest = { /* Modal */ },
            shape = RoundedCornerShape(20.dp),
            containerColor = tokens.surface,
            icon = {
                Icon(
                    imageVector = Icons.Default.PauseCircle,
                    contentDescription = null,
                    tint = tokens.accentBrand,
                    modifier = Modifier.size(44.dp)
                )
            },
            title = {
                Text(
                    text = "Match Paused",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = tokens.cellNeutralText,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = if (pausedByPlayerName.isNotBlank())
                        "$pausedByPlayerName paused the game."
                    else
                        "The game is currently paused.",
                    fontSize = 14.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = onTogglePause,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Resume Game", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSurrenderDialog = true }) {
                    Text("Exit Match", color = tokens.cellNeutralText)
                }
            }
        )
    }

    if (isWaitingForOpponentBoard) {
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
                        "Other player is arranging their board...\nGame will start automatically when ready.",
                    fontSize = 14.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSurrenderDialog = true }) {
                    Text("Leave Room", color = tokens.accentOpponent)
                }
            }
        )
    }

    if (countdownSeconds in 1..5) {
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
                        text = "$countdownSeconds",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = tokens.accentBrand
                    )
                }
            },
            title = {
                Text(
                    text = "Game Starting in $countdownSeconds",
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

    Scaffold(
        containerColor = tokens.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top control bar: minimalist exit, status badge, small tiny counter on top right, minimalist pause button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showSurrenderDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit Match",
                            tint = tokens.cellNeutralText.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            isGameOver -> tokens.backgroundSecondary
                            isGamePaused -> Color(0xFFF1F5F9)
                            isMyTurn -> tokens.cellPlayerPickBg
                            else -> tokens.cellOpponentPickBg
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    isGameOver -> "GAME OVER"
                                    isGamePaused -> "PAUSED"
                                    isMyTurn -> "YOUR TURN"
                                    else -> "$opponentName's Turn"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isGameOver -> tokens.cellNeutralText
                                    isGamePaused -> Color(0xFF475569)
                                    isMyTurn -> tokens.accentBrand
                                    else -> tokens.accentOpponent
                                }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isGameOver) {
                            // Real-time PUBG-style Ping Display
                            if (pingMs > 0L) {
                                val pingColor = when {
                                    pingMs < 80L -> Color(0xFF16A34A)
                                    pingMs < 150L -> Color(0xFFEAB308)
                                    else -> Color(0xFFDC2626)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = tokens.backgroundSecondary
                                ) {
                                    Text(
                                        text = "${pingMs}ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = pingColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            // Turn countdown timer
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (turnTimeRemaining <= 5 && !isGamePaused) Color(0xFFFFEDD5) else tokens.backgroundSecondary
                            ) {
                                Text(
                                    text = "⏱ ${turnTimeRemaining}s",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (turnTimeRemaining <= 5 && !isGamePaused) Color(0xFFEA580C) else tokens.cellNeutralText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = onTogglePause,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isGamePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isGamePaused) "Resume Game" else "Pause Game",
                                    tint = tokens.accentBrand,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(36.dp))
                        }
                    }
                }

                if (isGameOver) {
                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Game Over Result Banner ──
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            isDraw -> Color(0xFFFEF9C3)
                            didPlayerWin -> Color(0xFFFEF3C7)
                            else -> tokens.surface
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isDraw -> Color(0xFFEAB308)
                                didPlayerWin -> Color(0xFFF59E0B)
                                else -> tokens.surfaceBorder
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = when {
                                    isDraw -> "🤝 Draw Match!"
                                    didPlayerWin -> "🎉 B I N G O ! Victory!"
                                    else -> "MATCH ENDED • $opponentName Won"
                                },
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                color = when {
                                    isDraw -> Color(0xFF854D0E)
                                    didPlayerWin -> Color(0xFF92400E)
                                    else -> tokens.cellNeutralText
                                }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    isDraw -> "Both players completed their lines simultaneously! 🤝 Review gameplay below."
                                    didPlayerWin -> "You completed ${board.completedLinesCount} lines! Review gameplay below."
                                    else -> "$opponentName completed their lines. Switch boards below to review."
                                },
                                fontSize = 11.5.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── Post-Game Board Review Switcher ──
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // My Board
                            Surface(
                                onClick = { reviewingOpponentBoard = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (!reviewingOpponentBoard) tokens.surface else Color.Transparent,
                                border = if (!reviewingOpponentBoard) BorderStroke(1.dp, tokens.accentBrand) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlayerAvatar(
                                        avatarPathOrUri = myAvatarUrl,
                                        displayName = myDisplayName ?: "You",
                                        username = myUsername,
                                        size = 20.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "My Board (${board.completedLinesCount}/${board.size})",
                                        fontSize = 12.sp,
                                        fontWeight = if (!reviewingOpponentBoard) FontWeight.Bold else FontWeight.Medium,
                                        color = if (!reviewingOpponentBoard) tokens.accentBrand else tokens.cellNeutralText.copy(alpha = 0.6f)
                                    )
                                }
                            }

                            // Opponent's Board
                            Surface(
                                onClick = { reviewingOpponentBoard = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (reviewingOpponentBoard) tokens.surface else Color.Transparent,
                                border = if (reviewingOpponentBoard) BorderStroke(1.dp, tokens.accentOpponent) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlayerAvatar(
                                        avatarPathOrUri = opponentAvatarUrl,
                                        displayName = opponentName,
                                        username = opponentUsername,
                                        size = 20.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "$opponentName (${(opponentBoard?.completedLinesCount ?: 0)}/${board.size})",
                                        fontSize = 12.sp,
                                        fontWeight = if (reviewingOpponentBoard) FontWeight.Bold else FontWeight.Medium,
                                        color = if (reviewingOpponentBoard) tokens.accentOpponent else tokens.cellNeutralText.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                BingoHeaderTracker(
                    completedLines = displayedBoard.completedLinesCount,
                    targetLines = displayedBoard.size
                )
            }
        },
        bottomBar = {
            if (isGameOver) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = tokens.surface,
                    border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        if (isHost && !wantsToPlayAgainName.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = tokens.cellPlayerPickBg
                            ) {
                                Text(
                                    text = "🎮 $wantsToPlayAgainName wants to play again!",
                                    color = tokens.accentBrand,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp)
                                )
                            }
                        }

                        if (onReturnToLobby != null) {
                            Button(
                                onClick = onReturnToLobby,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.accentBrand
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Return to Lobby",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = onBackToMenu,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, tokens.surfaceBorder)
                                ) {
                                    Text("Main Menu", color = tokens.cellNeutralText, fontWeight = FontWeight.SemiBold)
                                }

                                if (isHost) {
                                    Button(
                                        onClick = onPlayAgain,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.accentBrand
                                        )
                                    ) {
                                        Text("Play Again", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (!hasRequestedPlayAgain) {
                                                hasRequestedPlayAgain = true
                                                onRequestPlayAgain()
                                            }
                                        },
                                        enabled = !hasRequestedPlayAgain,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.accentBrand,
                                            disabledContainerColor = tokens.surfaceBorder
                                        )
                                    ) {
                                        Text(
                                            text = if (hasRequestedPlayAgain) "Requested" else "Play Again",
                                            color = if (hasRequestedPlayAgain) tokens.cellNeutralText.copy(alpha = 0.5f) else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                GameFooterBar(
                    recentPick = recentPick,
                    opponentName = opponentName,
                    opponentAvatarUrl = opponentAvatarUrl,
                    opponentUsername = opponentUsername,
                    onSyncGame = onSyncGame,
                    onSurrender = { showSurrenderDialog = true }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            BingoBoardView(
                board = displayedBoard,
                isInteractive = isMyTurn && !isGameOver && !isGamePaused && !isWaitingForOpponentBoard && countdownSeconds <= 0,
                onCellClicked = onCellPicked
            )
        }
    }
}

@Composable
private fun GameFooterBar(
    recentPick: RecentPick?,
    opponentName: String,
    opponentAvatarUrl: String? = null,
    opponentUsername: String? = null,
    onSyncGame: () -> Unit = {},
    onSurrender: () -> Unit
) {
    val tokens = BingoTheme.colors
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        var insetsController: WindowInsetsControllerCompat? = null
        if (window != null) {
            insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.surface,
        border = BorderStroke(0.5.dp, tokens.surfaceBorder),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tokens.recentPickBg
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = tokens.recentPickText,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (recentPick != null) {
                            if (recentPick.number == -1) "Turn Passed" else "Last: #${recentPick.number}"
                        } else "No picks yet",
                        color = tokens.recentPickText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlayerAvatar(
                    avatarPathOrUri = opponentAvatarUrl,
                    displayName = opponentName,
                    username = opponentUsername,
                    size = 24.dp
                )
                Text(
                    text = "vs $opponentName",
                    style = BingoTheme.typography.cardSubtitle,
                    color = tokens.cellNeutralText,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onSyncGame
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync Game",
                        tint = tokens.cellNeutralText.copy(alpha = 0.65f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onSurrender
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Leave Match",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
