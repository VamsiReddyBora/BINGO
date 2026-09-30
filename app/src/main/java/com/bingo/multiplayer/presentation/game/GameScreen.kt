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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.graphicsLayer
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.domain.network.QuickChatPreferences
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.components.BingoBoardView
import com.bingo.multiplayer.presentation.components.BingoHeaderTracker
import com.bingo.multiplayer.presentation.components.BottomTurnProfileVsProfile
import com.bingo.multiplayer.presentation.components.EmojiReactionStripWithChat
import com.bingo.multiplayer.presentation.components.FloatingEmoteBar
import com.bingo.multiplayer.presentation.components.FloatingEmoteItem
import com.bingo.multiplayer.presentation.components.FloatingEmotesOverlay
import com.bingo.multiplayer.presentation.components.HeadToHeadScorecard
import com.bingo.multiplayer.presentation.components.RecentPicksQueuePill

/**
 * Modern Award-Winning Indie Game Match Screen.
 * Complete tactile styling, AMOLED Dark / Premium Light tokens, and victory celebration.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    incomingEmote: String? = null,
    incomingEmoteTimestamp: Long = 0L,
    onSendEmote: (String) -> Unit = {},
    pickedNumbersHistory: List<Int> = emptyList()
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    val quickChatPhrases = remember { QuickChatPreferences.getPhrases(context) }
    var showQuickChat by remember { mutableStateOf(false) }
    var currentPhrases by remember { mutableStateOf(quickChatPhrases) }

    // ── Floating Emotes State ──
    var activeEmotes by remember { mutableStateOf(listOf<FloatingEmoteItem>()) }

    fun spawnEmote(emoji: String, isSelf: Boolean, senderName: String? = null) {
        val randomX = if (isSelf) {
            0.55f + ((0..25).random() / 100f)
        } else {
            0.15f + ((0..25).random() / 100f)
        }
        activeEmotes = activeEmotes + FloatingEmoteItem(
            emoji = emoji,
            startXRatio = randomX,
            isSelf = isSelf,
            senderName = senderName
        )
    }

    LaunchedEffect(incomingEmote, incomingEmoteTimestamp) {
        if (!incomingEmote.isNullOrBlank()) {
            spawnEmote(incomingEmote, isSelf = false, senderName = opponentName)
        }
    }

    // ── Turn Urgency Countdown & Pulse ──
    val isUrgentTimer = turnTimeRemaining <= 5 && !isGamePaused && !isGameOver
    val urgentInfiniteTransition = rememberInfiniteTransition(label = "urgentCountdownTransition")
    val urgentTimerScale by if (isUrgentTimer) {
        urgentInfiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "urgentTimerScale"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    // Trigger haptic clock tick in the player's hands on countdown <= 5s
    LaunchedEffect(turnTimeRemaining, isMyTurn) {
        if (isUrgentTimer && isMyTurn) {
            try {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } catch (_: Exception) {}
        }
    }

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
        val isMultiplayerLobbyGame = (onReturnToLobby != null)
        AlertDialog(
            onDismissRequest = { showSurrenderDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = tokens.surface,
            title = {
                Text(
                    text = if (isMultiplayerLobbyGame) "Return to Lobby?" else "Exit Match?",
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Text(
                    text = if (isMultiplayerLobbyGame)
                        "Are you sure you want to leave this match? You will return to the lobby."
                    else
                        "Are you sure you want to leave the match and return to the main menu?",
                    color = tokens.cellNeutralText.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSurrenderDialog = false
                        if (isMultiplayerLobbyGame) {
                            onReturnToLobby?.invoke()
                        } else {
                            onSurrender()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accentOpponent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isMultiplayerLobbyGame) "Return to Lobby" else "Exit to Menu",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
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

    Scaffold(
        containerColor = tokens.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Modern Aesthetic Top Bar (Items 1, 2, 3)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Item 1: Top Left Door Open Exit Button
                    IconButton(
                        onClick = {
                            if (onReturnToLobby != null) {
                                showSurrenderDialog = true
                            } else {
                                onSurrender()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Exit Match",
                            tint = tokens.cellNeutralText.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Item 2: Top Center 🛜 Wifi Icon (Black) + Ping (Color varies based on value, perfectly centered!)
                    val pingColor = when {
                        pingMs <= 250L -> Color(0xFF16A34A)
                        pingMs <= 500L -> Color(0xFFEAB308)
                        else -> Color(0xFFDC2626)
                    }
                    val pingText = if (pingMs > 999L) "999+ms" else "${pingMs}ms"

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Ping",
                            tint = Color.Black,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = pingText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = pingColor
                        )
                    }

                    // Item 3: Top Right Pause Button FIRST, then ⌛ Timer NEXT
                    val hourglassFlipAngle by animateFloatAsState(
                        targetValue = (30 - turnTimeRemaining) * 180f,
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                        label = "hourglassFlip"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        // 1. Pause button comes first
                        IconButton(
                            onClick = onTogglePause,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isGamePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isGamePaused) "Resume Game" else "Pause Game",
                                tint = tokens.cellNeutralText.copy(alpha = 0.6f),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 2. Timer comes next
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.scale(urgentTimerScale)
                        ) {
                            Text(
                                text = "⌛",
                                fontSize = 16.sp,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = hourglassFlipAngle
                                }
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${turnTimeRemaining}s",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (turnTimeRemaining <= 5) Color(0xFFDC2626) else tokens.cellNeutralText
                            )
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
                InGameBottomBar(
                    pickedNumbersHistory = pickedNumbersHistory,
                    isMyTurn = isMyTurn,
                    isGameOver = isGameOver,
                    myAvatarUrl = myAvatarUrl,
                    myDisplayName = myDisplayName,
                    myUsername = myUsername,
                    opponentAvatarUrl = opponentAvatarUrl,
                    opponentName = opponentName,
                    opponentUsername = opponentUsername,
                    onSyncGame = onSyncGame
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Reserved empty space between top bar and Bingo board
                Spacer(modifier = Modifier.weight(0.12f))

                // Item 4: 5x5 Bingo Board with B-I-N-G-O letters atop columns & diagonal strikes
                BingoBoardView(
                    board = displayedBoard,
                    isInteractive = isMyTurn && !isGameOver && !isGamePaused,
                    onCellClicked = onCellPicked
                )

                // Reserved empty space between number table and emoji strip
                Spacer(modifier = Modifier.weight(0.18f))

                // Item 7: Pill shaped swipeable emoji reactions strip with quick chat just above bottom bar
                if (!isGameOver) {
                    EmojiReactionStripWithChat(
                        onSendEmote = { emote ->
                            spawnEmote(emote, isSelf = true)
                            onSendEmote(emote)
                        },
                        onToggleQuickChat = {
                            showQuickChat = !showQuickChat
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                }
            }

            // Floating Reaction Emotes Overlay (rising animated bubbles across randomized unique paths)
            FloatingEmotesOverlay(
                activeEmotes = activeEmotes,
                onEmoteFinished = { finishedId ->
                    activeEmotes = activeEmotes.filter { it.id != finishedId }
                }
            )

            // Item 4: Quick Chat Floating Toast Layer (Overlaid on top of the board, zero layout shift)
            if (showQuickChat && !isGameOver) {
                // Tap anywhere on the screen outside to dismiss automatically
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showQuickChat = false
                        }
                )

                // The Floating Toast Popover Box
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.surface,
                    shadowElevation = 8.dp,
                    border = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 48.dp)
                        .width(205.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(6.dp)
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        currentPhrases.forEach { phrase ->
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showQuickChat = false
                                    // Move tapped phrase to front (recent like emojis)
                                    currentPhrases = listOf(phrase) + currentPhrases.filter { it != phrase }
                                    QuickChatPreferences.recordUsedPhrase(context, phrase)
                                    spawnEmote(phrase, isSelf = true)
                                    onSendEmote(phrase)
                                },
                                color = tokens.backgroundSecondary,
                                shape = RoundedCornerShape(10.dp),
                                border = null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = phrase,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.cellNeutralText,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            velocity = 30.dp
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InGameBottomBar(
    pickedNumbersHistory: List<Int>,
    isMyTurn: Boolean,
    isGameOver: Boolean,
    myAvatarUrl: String?,
    myDisplayName: String?,
    myUsername: String?,
    opponentAvatarUrl: String?,
    opponentName: String,
    opponentUsername: String?,
    onSyncGame: () -> Unit
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

    var refreshAngle by remember { mutableFloatStateOf(0f) }
    val animatedRefreshAngle by animateFloatAsState(
        targetValue = refreshAngle,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "refreshAngle"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.surface,
        border = BorderStroke(0.5.dp, tokens.surfaceBorder),
        shadowElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            // Item 5: Left - 3-number FIFO sliding queue pill
            RecentPicksQueuePill(
                pickedNumbersHistory = pickedNumbersHistory,
                modifier = Modifier.align(Alignment.CenterStart)
            )

            // Item 6: Center - Profile vs Profile with smooth turn zoom (Dead Center!)
            BottomTurnProfileVsProfile(
                isMyTurn = isMyTurn,
                isGameOver = isGameOver,
                myAvatarUrl = myAvatarUrl,
                myDisplayName = myDisplayName,
                myUsername = myUsername,
                opponentAvatarUrl = opponentAvatarUrl,
                opponentName = opponentName,
                opponentUsername = opponentUsername,
                modifier = Modifier.align(Alignment.Center)
            )

            // Item 6: Right - Plain reload icon that rotates in the arrow direction (clockwise) and stops after 1 rotation
            IconButton(
                onClick = {
                    refreshAngle += 360f
                    onSyncGame()
                },
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Game",
                    tint = tokens.cellNeutralText.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            rotationZ = animatedRefreshAngle
                        }
                )
            }
        }
    }
}
