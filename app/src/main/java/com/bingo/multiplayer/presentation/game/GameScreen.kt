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
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bingo.multiplayer.domain.model.InGameChatMessage

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player
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
import com.bingo.multiplayer.presentation.components.MultiplayerTurnSpotlightBar
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
    incomingEmoteScale: Float = 1.0f,
    incomingEmoteTimestamp: Long = 0L,
    onSendEmote: (String, Float) -> Unit = { _, _ -> },
    incomingChatMessage: InGameChatMessage? = null,
    onSendChatMessage: (String) -> Unit = {},
    pickedNumbersHistory: List<Int> = emptyList(),
    matchSeed: Long = 0L,
    players: List<Player> = emptyList(),
    allPlayerBoards: Map<String, Board> = emptyMap(),
    currentTurnPlayerId: String = ""
) {
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    val quickChatPhrases = remember { QuickChatPreferences.getPhrases(context) }
    var showQuickChat by remember { mutableStateOf(false) }
    var currentPhrases by remember { mutableStateOf(quickChatPhrases) }

    // ── In-Game WhatsApp Style Chat State ──
    val gameMountTime = remember(matchSeed) { System.currentTimeMillis() }
    var lastHandledChatId by remember(matchSeed) { androidx.compose.runtime.mutableLongStateOf(0L) }
    var chatMessages by remember(matchSeed) { mutableStateOf(listOf<InGameChatMessage>()) }
    var customChatInput by remember { mutableStateOf("") }
    var isCustomChatFocused by remember { mutableStateOf(false) }
    var isFullLengthChatActive by remember { mutableStateOf(false) }
    var boardWidthDp by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
    val chatFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(incomingChatMessage, matchSeed) {
        if (incomingChatMessage != null &&
            incomingChatMessage.timestamp >= gameMountTime &&
            incomingChatMessage.id != lastHandledChatId
        ) {
            lastHandledChatId = incomingChatMessage.id
            chatMessages = chatMessages + incomingChatMessage
        }
    }

    fun submitCustomChatMessage() {
        val trimmed = customChatInput.trim().take(100)
        if (trimmed.isNotBlank()) {
            chatMessages = chatMessages + InGameChatMessage(
                text = trimmed,
                isSelf = true
            )
            onSendChatMessage(trimmed)
            customChatInput = ""
            showQuickChat = false
            isCustomChatFocused = false
            isFullLengthChatActive = false

            val opponentIsAi = opponentName.contains("ai", ignoreCase = true) ||
                    opponentUsername?.contains("ai", ignoreCase = true) == true ||
                    (onReturnToLobby == null && myPlayerId.isBlank())

            if (opponentIsAi) {
                coroutineScope.launch {
                    delay((1200..2400).random().toLong())
                    val aiReplies = listOf(
                        "Nice one! 🎯",
                        "Good move! 🍀",
                        "Game on! Let's see who wins! 🔥",
                        "I'm close to BINGO! ⚡",
                        "Haha nice! 😄",
                        "Thinking of my next number... 🧠",
                        "Watch out for my next pick! 🚀"
                    )
                    chatMessages = chatMessages + InGameChatMessage(
                        text = aiReplies.random(),
                        isSelf = false,
                        senderName = opponentName
                    )
                }
            }
        }
    }

    // ── Floating Emotes State ──
    var activeEmotes by remember(matchSeed) { mutableStateOf(listOf<FloatingEmoteItem>()) }

    fun spawnEmote(emoji: String, isSelf: Boolean, senderName: String? = null, scaleMultiplier: Float = 1.0f) {
        // Uniform random distribution from left (8%) to right (86%) across the whole screen width
        val randomX = (8..86).random() / 100f
        activeEmotes = activeEmotes + FloatingEmoteItem(
            emoji = emoji,
            startXRatio = randomX,
            isSelf = isSelf,
            senderName = senderName,
            scaleMultiplier = scaleMultiplier
        )
    }

    var lastHandledEmoteTimestamp by remember(matchSeed) { androidx.compose.runtime.mutableLongStateOf(0L) }

    LaunchedEffect(incomingEmote, incomingEmoteTimestamp, matchSeed) {
        if (!incomingEmote.isNullOrBlank() && incomingEmoteTimestamp > 0L && incomingEmoteTimestamp >= gameMountTime && incomingEmoteTimestamp != lastHandledEmoteTimestamp) {
            lastHandledEmoteTimestamp = incomingEmoteTimestamp
            spawnEmote(incomingEmote, isSelf = false, senderName = opponentName, scaleMultiplier = incomingEmoteScale)
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

    // Requirement 4: Match app background and adapt status bar icons (black/white) without hiding them
    val isDarkTheme = tokens.isDark
    val statusBarColor = tokens.background
    DisposableEffect(isDarkTheme, statusBarColor) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.show(WindowInsetsCompat.Type.statusBars())
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
            window.statusBarColor = statusBarColor.toArgb()
            window.navigationBarColor = statusBarColor.toArgb()
        }
        onDispose { }
    }
    var showSurrenderDialog by remember { mutableStateOf(false) }
    var isExitingMatch by remember { mutableStateOf(false) }

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
    var selectedReviewPlayerId by remember(myPlayerId, isGameOver) {
        mutableStateOf(myPlayerId.ifBlank { "local" })
    }
    var hasRequestedPlayAgain by remember(isGameOver) { mutableStateOf(false) }

    val reviewPlayers = remember(players, myPlayerId, opponentName, myDisplayName, myAvatarUrl, opponentAvatarUrl) {
        val filtered = players.filter { it.id.isNotBlank() }
        if (filtered.isNotEmpty()) {
            filtered
        } else {
            listOf(
                Player(
                    id = myPlayerId.ifBlank { "local" },
                    displayName = myDisplayName ?: "You",
                    username = myUsername ?: "",
                    avatarUrl = myAvatarUrl
                ),
                Player(
                    id = "opponent",
                    displayName = opponentName,
                    username = opponentUsername ?: "",
                    avatarUrl = opponentAvatarUrl
                )
            )
        }
    }

    // ── Victory/Defeat Stamp & 360° Radial Starburst Celebration State ──
    var showStampBadge by remember { mutableStateOf(false) }
    var isEmojiBurstActive by remember { mutableStateOf(false) }
    var animateStampDrop by remember { mutableStateOf(true) }
    var hasTriggeredCelebration by remember { mutableStateOf(false) }

    LaunchedEffect(isGameOver, didPlayerWin, isExitingMatch) {
        if (isExitingMatch) {
            showStampBadge = false
            isEmojiBurstActive = false
            animateStampDrop = false
            return@LaunchedEffect
        }
        if (isGameOver && didPlayerWin) {
            if (!hasTriggeredCelebration) {
                hasTriggeredCelebration = true
                isEmojiBurstActive = true
                animateStampDrop = true
            } else {
                showStampBadge = true
            }
        } else if (isGameOver && !didPlayerWin) {
            // Defeat or draw: show stamp badge directly without winner emoji projectile celebration
            showStampBadge = true
            isEmojiBurstActive = false
            animateStampDrop = true
        } else {
            hasTriggeredCelebration = false
            showStampBadge = false
            isEmojiBurstActive = false
            animateStampDrop = true
            selectedReviewPlayerId = myPlayerId.ifBlank { "local" }
        }
    }

    LaunchedEffect(selectedReviewPlayerId) {
        if (showStampBadge) {
            animateStampDrop = false
        }
    }

    val displayedBoard = if (isGameOver) {
        allPlayerBoards[selectedReviewPlayerId]
            ?: if (selectedReviewPlayerId == myPlayerId || selectedReviewPlayerId == "local") {
                board
            } else {
                opponentBoard ?: board
            }
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
                    color = tokens.textPrimary
                )
            },
            text = {
                Text(
                    text = if (isMultiplayerLobbyGame)
                        "Are you sure you want to leave this match? You will return to the lobby."
                    else
                        "Are you sure you want to leave the match and return to the main menu?",
                    color = tokens.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isExitingMatch = true
                        showSurrenderDialog = false
                        isEmojiBurstActive = false
                        showStampBadge = false
                        animateStampDrop = false
                        if (isMultiplayerLobbyGame) {
                            onReturnToLobby?.invoke()
                        } else {
                            onSurrender()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tokens.isDark) Color(0xFFEF4444) else tokens.accentOpponent
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
                    Text("Stay", color = tokens.textMuted)
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonBg,
                        contentColor = tokens.primaryButtonText
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Resume Game", color = tokens.primaryButtonText, fontWeight = FontWeight.Bold)
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
                            showSurrenderDialog = true
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

                    // Item 2: Top Center 🛜 Wifi Icon + Ping (Color varies based on value, perfectly centered!)
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
                            tint = tokens.cellNeutralText,
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
                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Post-Game Board Review Scrollable Strip (All Players) ──
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            reviewPlayers.forEach { player ->
                                val isSelected = (player.id == selectedReviewPlayerId) ||
                                        (reviewPlayers.size == 1) ||
                                        (selectedReviewPlayerId.isBlank() && (player.id == myPlayerId || player.id == "local"))
                                val isLocal = (player.id == myPlayerId || player.id == "local")
                                val playerBoardForTab = allPlayerBoards[player.id]
                                    ?: if (isLocal) board else (opponentBoard ?: board)
                                val linesCount = playerBoardForTab.completedLinesCount
                                val isBingo = playerBoardForTab.isBingo

                                Surface(
                                    onClick = {
                                        selectedReviewPlayerId = player.id
                                        animateStampDrop = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) tokens.surface else Color.Transparent,
                                    border = if (isSelected) {
                                        BorderStroke(1.5.dp, if (isLocal) tokens.accentBrand else tokens.accentOpponent)
                                    } else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PlayerAvatar(
                                            avatarPathOrUri = if (isLocal) (myAvatarUrl ?: player.avatarUrl) else player.avatarUrl,
                                            displayName = if (isLocal) (myDisplayName ?: "You") else player.displayName,
                                            username = if (isLocal) myUsername else player.username,
                                            size = 20.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val labelText = if (isLocal) "My Board" else player.displayName
                                        Text(
                                            text = "$labelText ($linesCount/${board.size})" + (if (isBingo) " 👑" else ""),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = when {
                                                isSelected && isLocal -> tokens.accentBrand
                                                isSelected -> tokens.accentOpponent
                                                else -> tokens.cellNeutralText.copy(alpha = 0.65f)
                                            }
                                        )
                                    }
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
                                color = tokens.badgeSurface,
                                border = BorderStroke(1.dp, tokens.badgeOutline)
                            ) {
                                Text(
                                    text = "🎮 $wantsToPlayAgainName wants to play again!",
                                    color = tokens.badgeContent,
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
                                    containerColor = tokens.primaryButtonBg
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = tokens.primaryButtonText,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Return to Lobby",
                                    color = tokens.primaryButtonText,
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
                                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = tokens.textPrimary
                                    )
                                ) {
                                    Text("Main Menu", color = tokens.textPrimary, fontWeight = FontWeight.SemiBold)
                                }

                                if (isHost) {
                                    Button(
                                        onClick = onPlayAgain,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.primaryButtonBg
                                        )
                                    ) {
                                        Text("Play Again", color = tokens.primaryButtonText, fontWeight = FontWeight.Bold)
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
                                            containerColor = tokens.primaryButtonBg,
                                            disabledContainerColor = tokens.surfaceBorder
                                        )
                                    ) {
                                        Text(
                                            text = if (hasRequestedPlayAgain) "Requested" else "Play Again",
                                            color = if (hasRequestedPlayAgain) tokens.textMuted else tokens.primaryButtonText,
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
                    onSyncGame = onSyncGame,
                    players = players,
                    currentTurnPlayerId = currentTurnPlayerId,
                    myPlayerId = myPlayerId
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
                // Reserved empty space between top bar and Bingo board: Stamped badge on game over
                Box(
                    modifier = Modifier
                        .weight(0.12f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isGameOver && showStampBadge && !isExitingMatch) {
                        val isReviewingLocal = (selectedReviewPlayerId == myPlayerId || selectedReviewPlayerId == "local")
                        val stampType = when {
                            isDraw -> StampResultType.DRAW
                            isReviewingLocal -> if (didPlayerWin) StampResultType.WON else StampResultType.LOST
                            displayedBoard.isBingo -> StampResultType.WON
                            else -> StampResultType.LOST
                        }
                        VictoryStampBadge(
                            resultType = stampType,
                            animateStampDrop = animateStampDrop
                        )
                    }
                }

                // Item 4: 5x5 Bingo Board with B-I-N-G-O letters atop columns & diagonal strikes
                val isWinningBoard = isGameOver && displayedBoard.isBingo


                BingoBoardView(
                    board = displayedBoard,
                    isInteractive = isMyTurn && !isGameOver && !isGamePaused,
                    onCellClicked = onCellPicked,
                    isWinningBoard = isWinningBoard,
                    onBoardWidthMeasured = { measuredWidth ->
                        boardWidthDp = measuredWidth
                    }
                )

                // Breathing room: slightly keep the messages and the board a bit far
                Spacer(modifier = Modifier.height(14.dp))

                // In-Game WhatsApp style Chat Space between number table and emoji reactions strip
                // Width matches exact board width so the left & right edges align pixel-perfect with cell 22 and cell 17
                InGameChatSpace(
                    messages = chatMessages,
                    onDoubleTapToChat = {
                        isFullLengthChatActive = true
                    },
                    modifier = Modifier
                        .then(
                            if (boardWidthDp != null) Modifier.width(boardWidthDp!!)
                            else Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                        .weight(0.24f)
                )

                // Item 7: Pill shaped swipeable emoji reactions strip with quick chat just above bottom bar
                if (!isGameOver) {
                    EmojiReactionStripWithChat(
                        onSendEmote = { emote, scale ->
                            spawnEmote(emote, isSelf = true, scaleMultiplier = scale)
                            onSendEmote(emote, scale)
                        },
                        onToggleQuickChat = {
                            showQuickChat = !showQuickChat
                            isCustomChatFocused = false
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

            // ── Fullscreen Winning Celebration Overlay (Only triggers on victory) ──
            if (isEmojiBurstActive && didPlayerWin && !isExitingMatch) {
                GameOverEmojiProjectileBurst(
                    resultType = StampResultType.WON,
                    modifier = Modifier.fillMaxSize(),
                    onApexReached = {
                        showStampBadge = true
                        animateStampDrop = true
                    },
                    onBurstFinished = {
                        isEmojiBurstActive = false
                    }
                )
            }

            // Item 4: Quick Chat Floating Toast Layer
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
                            isCustomChatFocused = false
                        }
                )

                // The Floating Toast Popover Box with .imePadding() to sit above keyboard
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.surface,
                    shadowElevation = 8.dp,
                    border = BorderStroke(0.5.dp, tokens.surfaceBorder),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 48.dp)
                        .imePadding()
                        .width(225.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(7.dp)
                    ) {
                        // 1. Quick chat phrases list (scrollable, top 4 recents visible)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 135.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            currentPhrases.forEach { phrase ->
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showQuickChat = false
                                        isCustomChatFocused = false
                                        // Move tapped phrase to front (recent like emojis)
                                        currentPhrases = listOf(phrase) + currentPhrases.filter { it != phrase }
                                        QuickChatPreferences.recordUsedPhrase(context, phrase)
                                        spawnEmote(phrase, isSelf = true, scaleMultiplier = 1.0f)
                                        onSendEmote(phrase, 1.0f)
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

                        HorizontalDivider(
                            color = tokens.surfaceBorder,
                            thickness = 0.6.dp,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )

                        // 2. Custom Message Opener: launches full-length text box across screen
                        Surface(
                            onClick = {
                                showQuickChat = false
                                isFullLengthChatActive = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = tokens.backgroundSecondary,
                            border = BorderStroke(0.6.dp, tokens.surfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "✏️ Custom message...",
                                    fontSize = 12.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.75f),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "0/100",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }

            // ── Full-Length Chat Text Box (Full width of the screen above the keyboard) ──
            if (isFullLengthChatActive && !isGameOver) {
                // Tap outside anywhere to dismiss
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isFullLengthChatActive = false
                        }
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = tokens.surface,
                    border = BorderStroke(0.8.dp, tokens.surfaceBorder),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicTextField(
                            value = customChatInput,
                            onValueChange = { if (it.length <= 100) customChatInput = it },
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = tokens.cellNeutralText,
                                fontWeight = FontWeight.Normal
                            ),
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                keyboardType = KeyboardType.Text
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { submitCustomChatMessage() }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(chatFocusRequester),
                            decorationBox = { innerTextField ->
                                if (customChatInput.isEmpty()) {
                                    Text(
                                        text = "Type a message...",
                                        fontSize = 13.5.sp,
                                        color = tokens.cellNeutralText.copy(alpha = 0.45f)
                                    )
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Character limit counter: 0/100
                        Text(
                            text = "${customChatInput.length}/100",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (customChatInput.length == 100) tokens.accentOpponent else tokens.cellNeutralText.copy(alpha = 0.45f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Send Button (compact, proportionate to text box)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (customChatInput.isNotBlank()) tokens.primaryButtonBg else tokens.backgroundSecondary)
                                .clickable(
                                    enabled = customChatInput.isNotBlank(),
                                    onClick = { submitCustomChatMessage() }
                                )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                tint = if (customChatInput.isNotBlank()) tokens.primaryButtonText else tokens.cellNeutralText.copy(alpha = 0.35f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    try {
                        chatFocusRequester.requestFocus()
                    } catch (_: Exception) {}
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
    onSyncGame: () -> Unit,
    players: List<Player> = emptyList(),
    currentTurnPlayerId: String = "",
    myPlayerId: String = ""
) {
    val tokens = BingoTheme.colors

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

            // Item 6: Center - Profile vs Profile (<=2 players) OR 3-Icon Spotlight Bar (>2 players)
            MultiplayerTurnSpotlightBar(
                players = players,
                currentTurnPlayerId = currentTurnPlayerId,
                myPlayerId = myPlayerId,
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
