package com.bingo.multiplayer.presentation.nearby

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.HotspotAndWifiManager
import com.bingo.multiplayer.domain.network.NearbyHostQrPayload
import com.bingo.multiplayer.domain.network.LanDiscoveredGame
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun NearbyLobbyScreen(
    discoveredGames: List<LanDiscoveredGame>,
    connectedPeers: List<Player>,
    isHosting: Boolean,
    isHostMode: Boolean,
    joinedGame: LanDiscoveredGame? = null,
    currentRoomCode: String = "",
    onStartBroadcasting: (boardSize: Int) -> Unit,
    onStopBroadcasting: () -> Unit,
    onJoinDiscoveredGame: (LanDiscoveredGame) -> Unit,
    onLeaveJoinedGame: () -> Unit = {},
    onGoToLobby: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    var showHostQrDialog by remember { mutableStateOf(false) }
    var showScannerDialog by remember { mutableStateOf(false) }

    // Dynamic board sizing is calculated silently in the background
    val dynamicBoardSize = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
        .resolveBoardSize(isDynamicBoard = true, playerCount = connectedPeers.size.coerceAtLeast(1))

    // Hotspot & Wi-Fi reactive status checks
    var isHotspotActive by remember { mutableStateOf(HotspotAndWifiManager.isHotspotEnabled(context)) }
    var isWifiActive by remember { mutableStateOf(HotspotAndWifiManager.isWifiEnabled(context)) }
    val isNetworkActive = isHotspotActive || isWifiActive

    // Periodic check to auto-detect when user returns from system settings
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1500L)
            isHotspotActive = HotspotAndWifiManager.isHotspotEnabled(context)
            isWifiActive = HotspotAndWifiManager.isWifiEnabled(context)
        }
    }

    // Auto-start broadcasting when in host mode and network is genuinely active
    var hasAutoStartedBroadcast by remember { mutableStateOf(false) }
    LaunchedEffect(isHostMode, isNetworkActive) {
        if (isHostMode && isNetworkActive && !isHosting && !hasAutoStartedBroadcast) {
            hasAutoStartedBroadcast = true
            onStartBroadcasting(dynamicBoardSize)
        }
    }

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
                        text = if (isHostMode) "Nearby Network • Host" else "Nearby Network • Join",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.cellNeutralText
                    )
                    Text(
                        text = if (isHostMode) "Broadcasting on Local Network • Waiting for Players" else "Fast Local P2P • No Codes",
                        fontSize = 11.5.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.55f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ══════════════════════════════════════════
            // ── MODE A: HOST GAME VIEW ──
            // ══════════════════════════════════════════
            if (isHostMode) {
                if (!isNetworkActive && !isHosting) {
                    // Network OFF Prompt: Neither Hotspot nor Wi-Fi is active
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        shadowElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = null,
                                tint = tokens.accentOrange,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Network Not Connected",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Connect to Wi-Fi or turn on Mobile Hotspot so nearby friends can discover and join your match.",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = tokens.cellNeutralText.copy(alpha = 0.65f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { HotspotAndWifiManager.openHotspotSettings(context) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.accentOrange)
                            ) {
                                Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Turn On Hotspot in Settings", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { HotspotAndWifiManager.promptEnableWifi(context) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, tokens.surfaceBorder)
                            ) {
                                Icon(imageVector = Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp), tint = tokens.accentBrand)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Turn On Wi-Fi", fontSize = 12.sp, color = tokens.cellNeutralText)
                            }
                        }
                    }
                } else {
                    // Host Waiting Active Panel
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        shadowElevation = 1.dp
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            // Top Row: Hosting label + Broadcasting indicator
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = tokens.accentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "HOSTING MATCH",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = tokens.cellNeutralText.copy(alpha = 0.6f)
                                )
                                if (isHosting) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Broadcasting...",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF16A34A)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    OutlinedButton(
                                        onClick = { showHostQrDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp),
                                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QrCode,
                                            contentDescription = "QR Code",
                                            modifier = Modifier.size(14.dp),
                                            tint = tokens.accentBrand
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Show QR",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.cellNeutralText
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Connected Players List
                            Text(
                                text = "Connected Players (${connectedPeers.size}):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            if (connectedPeers.isEmpty()) {
                                Text(
                                    text = "Waiting for players to join your game…",
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
                                            size = 28.dp,
                                            username = peer.username.ifBlank { peer.displayName }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = peer.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = tokens.cellNeutralText
                                        )
                                        if (peer.isHost) {
                                            Text(text = " (Host)", fontSize = 11.sp, color = tokens.accentBrand)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Action Buttons: Stop / Start & Go to Lobby
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isHosting) {
                                    OutlinedButton(
                                        onClick = onStopBroadcasting,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                                    ) {
                                        Text("Stop", color = tokens.cellNeutralText, fontSize = 12.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = { onStartBroadcasting(dynamicBoardSize) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.primaryButtonBg,
                                            contentColor = tokens.primaryButtonText
                                        )
                                    ) {
                                        Text(
                                            "Start",
                                            color = tokens.primaryButtonText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // When min 2 players are connected, host gets "Go to Lobby"
                                Button(
                                    onClick = onGoToLobby,
                                    modifier = Modifier.weight(1.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                    enabled = connectedPeers.size >= 2
                                ) {
                                    Text("Go to Lobby", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // ══════════════════════════════════════════
            // ── MODE B: JOIN GAME VIEW ──
            // ══════════════════════════════════════════
            if (!isHostMode) {
                if (!isNetworkActive) {
                    // Network OFF Prompt
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.surface,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        shadowElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Wi-Fi is Turned Off",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Please turn on Wi-Fi so your phone can discover nearby game hosts on your network or hotspot.",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = tokens.cellNeutralText.copy(alpha = 0.65f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { HotspotAndWifiManager.promptEnableWifi(context) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg)
                            ) {
                                Text("Turn On Wi-Fi", color = tokens.primaryButtonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (joinedGame != null) {
                    val hostPeer = connectedPeers.firstOrNull { it.isHost }
                    val hostDisplayName = hostPeer?.displayName
                        ?: joinedGame.hostDisplayName.ifBlank { "Host" }
                    val isConnectedToHost = hostPeer != null

                    // Joiner Connected / Waiting State
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = if (isConnectedToHost) Color(0xFF16A34A) else tokens.accentBrand,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isConnectedToHost) {
                                        "Connected to $hostDisplayName's Game! Waiting for host to open lobby…"
                                    } else {
                                        "Connecting to $hostDisplayName's game session…"
                                    },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.cellNeutralText
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

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
                                        size = 28.dp,
                                        username = peer.username.ifBlank { peer.displayName }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = peer.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tokens.cellNeutralText)
                                    if (peer.isHost) {
                                        Text(text = " (Host)", fontSize = 11.sp, color = tokens.accentBrand)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedButton(
                                onClick = onLeaveJoinedGame,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, tokens.surfaceBorder)
                            ) {
                                Text("Leave Match", color = tokens.cellNeutralText, fontSize = 12.5.sp)
                            }
                        }
                    }
                } else {
                    // Available Nearby Games List
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { showScannerDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            border = BorderStroke(1.dp, tokens.surfaceBorder)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                modifier = Modifier.size(14.dp),
                                tint = tokens.accentBrand
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Scan QR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                        }
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
                                    text = "Searching for nearby hosts…",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.cellNeutralText
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Make sure you and the host are connected to the same Wi-Fi or Mobile Hotspot.",
                                    fontSize = 11.5.sp,
                                    textAlign = TextAlign.Center,
                                    color = tokens.cellNeutralText.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                OutlinedButton(
                                    onClick = { showScannerDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, tokens.surfaceBorder)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = tokens.accentBrand
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Scan Host QR Code",
                                        fontSize = 12.sp,
                                        color = tokens.cellNeutralText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
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
                                    isJoined = false,
                                    onJoin = {
                                        onJoinDiscoveredGame(game)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showHostQrDialog) {
            val hostPeer = connectedPeers.firstOrNull { it.isHost }
            val hostDisplayName = hostPeer?.displayName ?: "Host"
            NearbyHostQrDisplayDialog(
                payload = NearbyHostQrPayload(
                    ssid = HotspotAndWifiManager.getHotspotName(context),
                    password = HotspotAndWifiManager.getSavedHotspotPassword(context),
                    roomCode = currentRoomCode.ifBlank { "LAN_GAME" },
                    hostIp = HotspotAndWifiManager.getLocalIpAddress(),
                    hostName = hostDisplayName,
                    boardSize = dynamicBoardSize
                ),
                onDismiss = { showHostQrDialog = false }
            )
        }

        if (showScannerDialog) {
            NearbyQrScannerDialog(
                onDismiss = { showScannerDialog = false },
                onQrScanned = { payload ->
                    showScannerDialog = false
                    val scannedGame = LanDiscoveredGame(
                        hostId = "qr_${payload.hostName}",
                        hostDisplayName = payload.hostName,
                        hostUsername = payload.hostName,
                        avatarUrl = null,
                        boardSize = payload.boardSize,
                        roomCode = payload.roomCode,
                        hostIp = payload.hostIp.ifBlank { HotspotAndWifiManager.getGatewayIp(context) },
                        ssid = payload.ssid,
                        isInLobby = false,
                        broadcastTimestamp = System.currentTimeMillis()
                    )
                    onJoinDiscoveredGame(scannedGame)
                }
            )
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

            Text(
                text = game.hostDisplayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.cellNeutralText,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onJoin,
                enabled = !isJoined,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isJoined) Color(0xFF16A34A) else tokens.primaryButtonBg,
                    contentColor = if (isJoined) Color.White else tokens.primaryButtonText,
                    disabledContainerColor = Color(0xFF16A34A),
                    disabledContentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isJoined) "Joined ✓" else "Join",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isJoined) Color.White else tokens.primaryButtonText
                )
            }
        }
    }
}
