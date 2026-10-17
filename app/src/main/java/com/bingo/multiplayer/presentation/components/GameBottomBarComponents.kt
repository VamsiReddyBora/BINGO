package com.bingo.multiplayer.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.presentation.common.PlayerAvatar

/**
 * 3-Number Sliding FIFO Queue Pill (Item 5).
 * Holds up to 3 most recently chosen numbers with a thin border and smooth slide-push animation.
 */
@Composable
fun RecentPicksQueuePill(
    pickedNumbersHistory: List<Int>,
    modifier: Modifier = Modifier
) {
    val tokens = BingoTheme.colors
    val validPicks = pickedNumbersHistory.filter { it > 0 }
    val lastThree = validPicks.takeLast(3)

    val slot0 = lastThree.getOrNull(0)
    val slot1 = lastThree.getOrNull(1)
    val slot2 = lastThree.getOrNull(2)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = tokens.backgroundSecondary,
        border = BorderStroke(0.7.dp, tokens.surfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RecentPickSlot(number = slot0, isLatest = (validPicks.size == 1 && slot0 != null))
            RecentPickSlot(number = slot1, isLatest = (validPicks.size == 2 && slot1 != null))
            RecentPickSlot(number = slot2, isLatest = (validPicks.size >= 3 && slot2 != null))
        }
    }
}

@Composable
private fun RecentPickSlot(
    number: Int?,
    isLatest: Boolean
) {
    val tokens = BingoTheme.colors

    AnimatedContent(
        targetState = number,
        transitionSpec = {
            (slideInHorizontally { width -> width } + fadeIn(tween(200))).togetherWith(
                slideOutHorizontally { width -> -width } + fadeOut(tween(200))
            )
        },
        label = "recentPickSlot"
    ) { num ->
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (num != null && isLatest) tokens.recentPickBg else Color.Transparent
                )
                .then(
                    if (num != null && isLatest) Modifier.border(1.dp, tokens.recentPickBorder, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (num != null) num.toString() else "·",
                fontSize = if (num != null) 12.sp else 16.sp,
                fontWeight = if (isLatest) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = when {
                    num == null -> tokens.cellNeutralText.copy(alpha = 0.25f)
                    isLatest -> tokens.recentPickText
                    else -> tokens.cellNeutralText
                }
            )
        }
    }
}

/**
 * Profile vs Profile with Turn Zoom Effect (Item 6).
 * Shows both player photos; the active player's avatar smoothly zooms larger while the other shrinks.
 */
@Composable
fun BottomTurnProfileVsProfile(
    isMyTurn: Boolean,
    isGameOver: Boolean,
    myAvatarUrl: String?,
    myDisplayName: String?,
    myUsername: String?,
    opponentAvatarUrl: String?,
    opponentName: String,
    opponentUsername: String?,
    modifier: Modifier = Modifier,
    isOpponentDisconnected: Boolean = false,
    isOpponentAway: Boolean = false
) {
    val tokens = BingoTheme.colors

    val mySize by animateDpAsState(
        targetValue = if (isMyTurn && !isGameOver) 42.dp else 28.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "myAvatarZoom"
    )
    val oppSize by animateDpAsState(
        targetValue = if (!isMyTurn && !isGameOver) 42.dp else 28.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "oppAvatarZoom"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulseRing")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val myRingColor = Color(0xFF22C55E)
    val oppRingColor = when {
        isOpponentDisconnected -> Color(0xFFEF4444)
        isOpponentAway -> Color(0xFFEAB308)
        else -> Color(0xFF22C55E)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Player A (You)
        val myBorderColor = if (isMyTurn && !isGameOver) {
            myRingColor.copy(alpha = pulseAlpha)
        } else {
            myRingColor.copy(alpha = 0.85f)
        }

        Box(
            modifier = Modifier
                .size(mySize)
                .clip(CircleShape)
                .border(
                    width = if (isMyTurn && !isGameOver) 2.5.dp else 1.5.dp,
                    color = myBorderColor,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            PlayerAvatar(
                avatarPathOrUri = myAvatarUrl,
                displayName = myDisplayName ?: "You",
                username = myUsername,
                size = mySize
            )
        }

        Text(
            text = "vs",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = tokens.cellNeutralText.copy(alpha = 0.45f)
        )

        // Player B (Opponent)
        val isOppActive = !isMyTurn && !isGameOver
        val oppBorderColor = if (isOppActive && !isOpponentDisconnected && !isOpponentAway) {
            oppRingColor.copy(alpha = pulseAlpha)
        } else {
            oppRingColor
        }

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(oppSize)
                    .clip(CircleShape)
                    .border(
                        width = if (isOppActive) 2.5.dp else 1.5.dp,
                        color = oppBorderColor,
                        shape = CircleShape
                    )
                    .graphicsLayer { alpha = if (isOpponentDisconnected) 0.55f else 1.0f },
                contentAlignment = Alignment.Center
            ) {
                PlayerAvatar(
                    avatarPathOrUri = opponentAvatarUrl,
                    displayName = opponentName,
                    username = opponentUsername,
                    size = oppSize
                )
            }
            if (isOpponentDisconnected || isOpponentAway) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .align(Alignment.BottomEnd)
                        .background(if (isOpponentDisconnected) Color(0xFFEF4444) else Color(0xFFEAB308), CircleShape)
                        .border(1.dp, tokens.surface, CircleShape)
                )
            }
        }
    }
}

/**
 * Multiplayer Turn Spotlight Bar:
 * - If players.size <= 2: Displays the original 2-player Profile vs Profile with smooth turn zoom.
 * - If players.size > 2: Displays 3-icon circular spotlight:
 *   [Previous Player (Small, 28dp)]  [Current Player (Big Spotlight Zoomed, 44dp)]  [Next Player (Small, 28dp)]
 *   Rotates circularly:
 *   prevIdx = (currIdx - 1 + N) % N
 *   nextIdx = (currIdx + 1) % N
 */
@Composable
fun MultiplayerTurnSpotlightBar(
    players: List<Player>,
    currentTurnPlayerId: String,
    myPlayerId: String,
    isMyTurn: Boolean,
    isGameOver: Boolean,
    myAvatarUrl: String?,
    myDisplayName: String?,
    myUsername: String?,
    opponentAvatarUrl: String?,
    opponentName: String,
    opponentUsername: String?,
    modifier: Modifier = Modifier,
    disconnectedPlayerIds: List<String> = emptyList(),
    awayPlayerIds: List<String> = emptyList(),
    eliminatedPlayerIds: Set<String> = emptySet()
) {
    val activePlayers = remember(players) {
        players.filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    }

    if (activePlayers.size <= 2) {
        val oppPlayer = activePlayers.firstOrNull { it.id != myPlayerId }
        val isOppDisconnected = oppPlayer?.let { p ->
            disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
        } ?: false
        val isOppAway = oppPlayer?.let { p ->
            awayPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
        } ?: false

        BottomTurnProfileVsProfile(
            isMyTurn = isMyTurn,
            isGameOver = isGameOver,
            myAvatarUrl = myAvatarUrl,
            myDisplayName = myDisplayName,
            myUsername = myUsername,
            opponentAvatarUrl = opponentAvatarUrl,
            opponentName = opponentName,
            opponentUsername = opponentUsername,
            modifier = modifier,
            isOpponentDisconnected = isOppDisconnected,
            isOpponentAway = isOppAway
        )
    } else {
        val tokens = BingoTheme.colors
        val currIdx = remember(activePlayers, currentTurnPlayerId) {
            val idx = activePlayers.indexOfFirst { it.id == currentTurnPlayerId }
            if (idx >= 0) idx else 0
        }
        val prevIdx = (currIdx - 1 + activePlayers.size) % activePlayers.size
        val nextIdx = (currIdx + 1) % activePlayers.size

        val prevPlayer = activePlayers[prevIdx]
        val currPlayer = activePlayers[currIdx]
        val nextPlayer = activePlayers[nextIdx]

        val infiniteTransition = rememberInfiniteTransition(label = "spotlightPulseRing")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "spotlightPulseAlpha"
        )

        fun getPlayerRingColor(p: Player): Color {
            val isDisc = disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
            val isAway = awayPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
            return when {
                isDisc -> Color(0xFFEF4444)
                isAway -> Color(0xFFEAB308)
                else -> Color(0xFF22C55E)
            }
        }

        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Previous Player (Small Icon - 28dp)
            AnimatedContent(
                targetState = prevPlayer.id,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInHorizontally { -it / 2 }).togetherWith(
                        fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
                    )
                },
                label = "prevPlayerAnim"
            ) { targetId ->
                val p = activePlayers.firstOrNull { it.id == targetId } ?: prevPlayer
                val isDisconnected = disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isAway = awayPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isEliminated = eliminatedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val ringColor = getPlayerRingColor(p)

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, ringColor, CircleShape)
                            .graphicsLayer { alpha = if (isEliminated) 0.35f else if (isDisconnected) 0.5f else 0.85f },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerAvatar(
                            avatarPathOrUri = p.avatarUrl,
                            displayName = p.displayName,
                            username = p.username,
                            size = 28.dp
                        )
                    }
                    if (isDisconnected || isAway || isEliminated) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .align(Alignment.BottomEnd)
                                .background(if (isEliminated) Color.Gray else if (isDisconnected) Color(0xFFEF4444) else Color(0xFFEAB308), CircleShape)
                                .border(1.dp, tokens.surface, CircleShape)
                        )
                    }
                }
            }

            // Current Turn Player (Big Icon Spotlight - 44dp)
            AnimatedContent(
                targetState = currPlayer.id,
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(tween(300))).togetherWith(
                        fadeOut(tween(200)) + scaleOut(tween(200))
                    )
                },
                label = "currPlayerSpotlightAnim"
            ) { targetId ->
                val p = activePlayers.firstOrNull { it.id == targetId } ?: currPlayer
                val isDisconnected = disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isAway = awayPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isEliminated = eliminatedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val ringColor = getPlayerRingColor(p)
                val isCurrentActiveAndOnline = !isGameOver && !isDisconnected && !isAway
                val currBorderColor = if (isCurrentActiveAndOnline) ringColor.copy(alpha = pulseAlpha) else ringColor

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(
                                width = 2.5.dp,
                                color = currBorderColor,
                                shape = CircleShape
                            )
                            .graphicsLayer { alpha = if (isEliminated) 0.4f else if (isDisconnected) 0.65f else 1.0f },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerAvatar(
                            avatarPathOrUri = p.avatarUrl,
                            displayName = p.displayName,
                            username = p.username,
                            size = 44.dp
                        )
                    }
                    if (isDisconnected || isAway || isEliminated) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                                .background(if (isEliminated) Color.Gray else if (isDisconnected) Color(0xFFEF4444) else Color(0xFFEAB308), CircleShape)
                                .border(1.5.dp, tokens.surface, CircleShape)
                        )
                    }
                }
            }

            // Next Player (Small Icon - 28dp)
            AnimatedContent(
                targetState = nextPlayer.id,
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInHorizontally { it / 2 }).togetherWith(
                        fadeOut(tween(200)) + slideOutHorizontally { it / 2 }
                    )
                },
                label = "nextPlayerAnim"
            ) { targetId ->
                val p = activePlayers.firstOrNull { it.id == targetId } ?: nextPlayer
                val isDisconnected = disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isAway = awayPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val isEliminated = eliminatedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, p.id) }
                val ringColor = getPlayerRingColor(p)

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, ringColor, CircleShape)
                            .graphicsLayer { alpha = if (isEliminated) 0.35f else if (isDisconnected) 0.5f else 0.85f },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerAvatar(
                            avatarPathOrUri = p.avatarUrl,
                            displayName = p.displayName,
                            username = p.username,
                            size = 28.dp
                        )
                    }
                    if (isDisconnected || isAway || isEliminated) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .align(Alignment.BottomEnd)
                                .background(if (isEliminated) Color.Gray else if (isDisconnected) Color(0xFFEF4444) else Color(0xFFEAB308), CircleShape)
                                .border(1.dp, tokens.surface, CircleShape)
                        )
                    }
                }
            }
        }
    }
}
