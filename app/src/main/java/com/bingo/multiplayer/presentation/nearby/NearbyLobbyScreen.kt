package com.bingo.multiplayer.presentation.nearby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.LanDiscoveredGame
import com.bingo.multiplayer.presentation.common.PlayerAvatar

@Composable
fun NearbyLobbyScreen(
    discoveredGames: List<LanDiscoveredGame>,
    connectedPeers: List<Player>,
    isHosting: Boolean,
    joinedGame: LanDiscoveredGame? = null,
    onStartBroadcasting: (boardSize: Int) -> Unit,
    onStopBroadcasting: () -> Unit,
    onJoinDiscoveredGame: (LanDiscoveredGame) -> Unit,
    onLeaveJoinedGame: () -> Unit = {},
    onStartGame: () -> Unit,
    onBack: () -> Unit
) {
    val tokens = BingoTheme.colors
    var selectedBoardSize by remember { mutableIntStateOf(5) }

    // Pulsing radar animation for network scanning
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── Top Header Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = tokens.cellNeutralText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column {
                    Text(
                        text = "Nearby Network (LAN)",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = "Zero-latency Wi-Fi & Hotspot • No Room Codes",
                        fontSize = 11.5.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.55f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Host Panel ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = tokens.accentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isHosting) "HOSTING NEARBY GAME" else if (joinedGame != null) "CONNECTED TO MATCH" else "HOST A NEARBY GAME",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f)
                            )
                        }

                        if (isHosting) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF16A34A).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "BROADCASTING",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (joinedGame != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = tokens.cellPlayerPickBg
                            ) {
                                Text(
                                    text = "READY",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.accentBrand,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (joinedGame != null) {
                        // Currently Joined View (Peer waiting for host to start)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = tokens.accentBrand,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Connected to ${joinedGame.hostDisplayName}'s Game! Waiting for host to start...",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.cellNeutralText
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Players in Room (${connectedPeers.size}):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        connectedPeers.forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlayerAvatar(
                                    avatarPathOrUri = peer.avatarUrl,
                                    displayName = peer.displayName,
                                    size = 26.dp,
                                    username = peer.username.ifBlank { peer.displayName }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = peer.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.cellNeutralText)
                                if (peer.isHost) {
                                    Text(text = " (Host)", fontSize = 11.sp, color = tokens.accentBrand)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = onLeaveJoinedGame,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, tokens.surfaceBorder)
                        ) {
                            Text("Leave Match", color = tokens.cellNeutralText, fontSize = 12.sp)
                        }
                    } else if (!isHosting) {
                        Text(
                            text = "Grid Size:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.cellNeutralText.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5 to "5x5", 6 to "6x6", 7 to "7x7", 8 to "8x8").forEach { (size, label) ->
                                val selected = selectedBoardSize == size
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedBoardSize = size },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) tokens.accentOrange else tokens.backgroundSecondary,
                                    border = BorderStroke(1.dp, if (selected) tokens.accentOrange else tokens.surfaceBorder)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else tokens.cellNeutralText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { onStartBroadcasting(selectedBoardSize) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accentOrange)
                        ) {
                            Icon(imageVector = Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Broadcast & Host Match", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Currently Hosting View
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16A34A).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Broadcasting to nearby Wi-Fi & Hotspot peers...",
                                fontSize = 12.5.sp,
                                color = tokens.cellNeutralText
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Connected Players (${connectedPeers.size}):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        if (connectedPeers.isEmpty()) {
                            Text(
                                text = "Waiting for a friend to tap your game below...",
                                fontSize = 11.5.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.5f)
                            )
                        } else {
                            connectedPeers.forEach { peer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlayerAvatar(
                                        avatarPathOrUri = peer.avatarUrl,
                                        displayName = peer.displayName,
                                        size = 26.dp,
                                        username = peer.username.ifBlank { peer.displayName }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = peer.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.cellNeutralText)
                                    if (peer.isHost) {
                                        Text(text = " (Host)", fontSize = 11.sp, color = tokens.accentBrand)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onStopBroadcasting,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, tokens.surfaceBorder)
                            ) {
                                Text("Cancel", color = tokens.cellNeutralText, fontSize = 12.sp)
                            }

                            Button(
                                onClick = onStartGame,
                                modifier = Modifier.weight(1.5f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                enabled = connectedPeers.size >= 2
                            ) {
                                Text("Start Match", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 2: Discovered Games (Zero-Code Join) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB).copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AVAILABLE NEARBY GAMES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )
                }

                Text(
                    text = "${discoveredGames.size} Found",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accentBrand
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (discoveredGames.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.surface,
                    border = BorderStroke(1.dp, tokens.surfaceBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = tokens.accentOrange,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Scanning Wi-Fi / Hotspot for games...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = tokens.cellNeutralText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ask your friend to tap 'Broadcast & Host Match' above. The game will appear here automatically!",
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center,
                            color = tokens.cellNeutralText.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(discoveredGames) { game ->
                        DiscoveredGameCard(
                            game = game,
                            isJoined = (joinedGame?.roomCode == game.roomCode),
                            onJoin = { onJoinDiscoveredGame(game) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredGameCard(
    game: LanDiscoveredGame,
    isJoined: Boolean = false,
    onJoin: () -> Unit
) {
    val tokens = BingoTheme.colors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isJoined) Modifier.clickable { onJoin() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface,
        border = BorderStroke(1.2.dp, if (isJoined) tokens.accentBrand else tokens.surfaceBorder),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAvatar(
                avatarPathOrUri = game.avatarUrl,
                displayName = game.hostDisplayName,
                size = 44.dp,
                borderColor = tokens.accentBrand,
                username = game.hostUsername.ifBlank { game.hostDisplayName }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${game.hostDisplayName}'s Game",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = tokens.cellPlayerPickBg
                    ) {
                        Text(
                            text = "${game.boardSize}×${game.boardSize} Grid",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.accentBrand,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = if (isJoined) "• Joined" else "• Ready to Join",
                        fontSize = 11.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Button(
                onClick = onJoin,
                enabled = !isJoined,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isJoined) Color(0xFF16A34A) else tokens.accentBrand,
                    disabledContainerColor = Color(0xFF16A34A)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(if (isJoined) "Joined ✓" else "Join", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}
