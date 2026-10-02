package com.bingo.multiplayer.presentation.nearby

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.network.HotspotAndWifiManager
import com.bingo.multiplayer.domain.network.LanDiscoveredGame
import com.bingo.multiplayer.domain.network.NearbyHostQrPayload
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
    onStartBroadcasting: (boardSize: Int) -> Unit,
    onStopBroadcasting: () -> Unit,
    onJoinDiscoveredGame: (LanDiscoveredGame) -> Unit,
    onLeaveJoinedGame: () -> Unit = {},
    onGoToLobby: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    // Dynamic board sizing is calculated silently in the background (no UI badges shown)
    val dynamicBoardSize = when {
        connectedPeers.size <= 2 -> 5
        connectedPeers.size <= 4 -> 6
        connectedPeers.size <= 6 -> 7
        else -> 8
    }

    // Stable room code and payload generated once per session to prevent QR regeneration
    val stableRoomCode = remember { "LAN_${(1000..9999).random()}" }

    // Hotspot & Wi-Fi reactive status checks
    var isHotspotActive by remember { mutableStateOf(HotspotAndWifiManager.isHotspotEnabled(context)) }
    var isWifiActive by remember { mutableStateOf(HotspotAndWifiManager.isWifiEnabled(context)) }

    // Periodic check to auto-detect when user returns from system settings
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1500L)
            isHotspotActive = HotspotAndWifiManager.isHotspotEnabled(context)
            isWifiActive = HotspotAndWifiManager.isWifiEnabled(context)
        }
    }

    // Auto-start broadcasting when in host mode and hotspot is genuinely active
    var hasAutoStartedBroadcast by remember { mutableStateOf(false) }
    LaunchedEffect(isHostMode, isHotspotActive) {
        if (isHostMode && isHotspotActive && !isHosting && !hasAutoStartedBroadcast) {
            hasAutoStartedBroadcast = true
            onStartBroadcasting(dynamicBoardSize)
        }
    }

    // QR Dialog & Scanner states
    var showQrDialog by remember { mutableStateOf(false) }
    var showScannerDialog by remember { mutableStateOf(false) }
    var selectedGameToJoin by remember { mutableStateOf<LanDiscoveredGame?>(null) }
    var joinPasswordInput by remember { mutableStateOf("") }
    var isConnectingHotspot by remember { mutableStateOf(false) }

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

    // ── Dialog 1: Host QR Code Display (Stable) ──
    if (showQrDialog) {
        val hostPayload = remember(stableRoomCode) {
            NearbyHostQrPayload(
                ssid = HotspotAndWifiManager.getHotspotName(context),
                password = HotspotAndWifiManager.getSavedHotspotPassword(context),
                roomCode = stableRoomCode,
                hostIp = "",
                hostName = connectedPeers.firstOrNull { it.isHost }?.displayName ?: "Nearby Host",
                boardSize = dynamicBoardSize
            )
        }
        NearbyHostQrDisplayDialog(
            payload = hostPayload,
            onDismiss = { showQrDialog = false }
        )
    }

    // ── Dialog 2: Joiner Camera QR Scanner ──
    if (showScannerDialog) {
        NearbyQrScannerDialog(
            onDismiss = { showScannerDialog = false },
            onQrScanned = { payload ->
                showScannerDialog = false
                Toast.makeText(context, "Host QR scanned! Connecting to ${payload.hostName}...", Toast.LENGTH_SHORT).show()

                if (payload.password.isNotBlank() && payload.ssid.isNotBlank()) {
                    isConnectingHotspot = true
                    HotspotAndWifiManager.connectToHostWifi(
                        context = context,
                        ssid = payload.ssid,
                        password = payload.password,
                        onConnected = {
                            isConnectingHotspot = false
                            val game = LanDiscoveredGame(
                                hostId = payload.roomCode,
                                hostDisplayName = payload.hostName,
                                roomCode = payload.roomCode,
                                hostIp = payload.hostIp,
                                boardSize = payload.boardSize
                            )
                            onJoinDiscoveredGame(game)
                        },
                        onError = { _ ->
                            isConnectingHotspot = false
                            val game = LanDiscoveredGame(
                                hostId = payload.roomCode,
                                hostDisplayName = payload.hostName,
                                roomCode = payload.roomCode,
                                hostIp = payload.hostIp,
                                boardSize = payload.boardSize
                            )
                            onJoinDiscoveredGame(game)
                        }
                    )
                } else {
                    val game = LanDiscoveredGame(
                        hostId = payload.roomCode,
                        hostDisplayName = payload.hostName,
                        roomCode = payload.roomCode,
                        hostIp = payload.hostIp,
                        boardSize = payload.boardSize
                    )
                    onJoinDiscoveredGame(game)
                }
            }
        )
    }

    // ── Dialog 3: Join Game Hotspot Password Dialog ──
    selectedGameToJoin?.let { targetGame ->
        val hostSsid = targetGame.ssid.ifBlank { "${targetGame.hostDisplayName}'s Hotspot" }

        AlertDialog(
            onDismissRequest = { selectedGameToJoin = null; joinPasswordInput = "" },
            title = {
                Text(
                    text = "Connect to Host Network",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = tokens.cellNeutralText
                )
            },
            text = {
                Column {
                    Text(
                        text = "Your phone is not yet connected to the host's hotspot. Connect below to enter the lobby:",
                        fontSize = 12.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tokens.backgroundSecondary,
                        border = BorderStroke(1.dp, tokens.surfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = tokens.accentBrand, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = hostSsid,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = joinPasswordInput,
                        onValueChange = { joinPasswordInput = it },
                        label = { Text("Hotspot Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            selectedGameToJoin = null
                            showScannerDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, tokens.surfaceBorder)
                    ) {
                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp), tint = tokens.accentBrand)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Host QR Instead", fontSize = 12.sp, color = tokens.cellNeutralText)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val game = targetGame
                        selectedGameToJoin = null
                        if (joinPasswordInput.isNotBlank()) {
                            isConnectingHotspot = true
                            HotspotAndWifiManager.connectToHostWifi(
                                context = context,
                                ssid = game.ssid.ifBlank { game.hostDisplayName },
                                password = joinPasswordInput,
                                onConnected = {
                                    isConnectingHotspot = false
                                    onJoinDiscoveredGame(game)
                                },
                                onError = { _ ->
                                    isConnectingHotspot = false
                                    onJoinDiscoveredGame(game)
                                }
                            )
                        } else {
                            onJoinDiscoveredGame(game)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryButtonBg)
                ) {
                    Text("Connect & Join", color = tokens.primaryButtonText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedGameToJoin = null }) {
                    Text("Cancel", color = tokens.textMuted)
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
                        text = if (isHostMode) "Hotspot Broadcasting • Waiting for Players" else "Zero-latency Wi-Fi & Hotspot • No Codes",
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
                if (!isHotspotActive && !isHosting) {
                    // Hotspot OFF Prompt with verified check (no blind bypass)
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
                                text = "Hotspot is Turned Off",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.cellNeutralText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Please turn on your phone's Mobile Hotspot so other players can connect and join your local match.",
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
                                onClick = {
                                    val actuallyOn = HotspotAndWifiManager.isHotspotEnabled(context)
                                    if (actuallyOn) {
                                        isHotspotActive = true
                                        onStartBroadcasting(dynamicBoardSize)
                                    } else {
                                        Toast.makeText(context, "Hotspot is not turned on yet. Please enable Mobile Hotspot in Settings.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, tokens.surfaceBorder)
                            ) {
                                Text("Verify Hotspot & Broadcast", fontSize = 12.sp, color = tokens.cellNeutralText)
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
                            // Top Row: Hosting label + Plain text Broadcasting + QR Button
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
                                        text = "HOSTING MATCH",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = tokens.cellNeutralText.copy(alpha = 0.6f)
                                    )
                                    if (isHosting) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Plain text without any box background
                                        Text(
                                            text = "Broadcasting...",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF16A34A)
                                        )
                                    }
                                }

                                // QR Code Button - Directly opens device Hotspot settings (shows phone's real SSID and native QR)
                                OutlinedButton(
                                    onClick = { HotspotAndWifiManager.openHotspotSettings(context) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, tokens.surfaceBorder),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode,
                                        contentDescription = "Show QR",
                                        tint = tokens.accentBrand,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "QR",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.cellNeutralText
                                    )
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
                                    text = "Waiting for players to connect to your hotspot or scan the QR code…",
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
                                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand)
                                    ) {
                                        Text(
                                            "Start",
                                            color = if (tokens.isDark) Color.Black else Color.White,
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
                if (!isWifiActive) {
                    // Wi-Fi OFF Prompt
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
                                text = "Please turn on Wi-Fi so your phone can discover nearby game hosts and connect to their hotspot.",
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
                                    color = tokens.accentBrand,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Connected to ${joinedGame.hostDisplayName}'s Game! Waiting for host to open lobby…",
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
                    // Available Nearby Games List + Scan QR Action
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

                        // Top Action: Scan QR Button
                        OutlinedButton(
                            onClick = { showScannerDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.surfaceBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR",
                                tint = tokens.accentBrand,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scan QR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
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
                                    text = "Scanning Wi-Fi / Hotspot for hosts…",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.cellNeutralText
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Or tap 'Scan QR' above to scan the host's screen directly to connect and join automatically!",
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
                                    isJoined = false,
                                    onJoin = {
                                        // Background check: already connected to host hotspot?
                                        val isConnected = HotspotAndWifiManager.isConnectedToHost(context, game.hostIp, game.ssid)
                                        if (isConnected) {
                                            onJoinDiscoveredGame(game)
                                        } else {
                                            selectedGameToJoin = game
                                        }
                                    }
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
                    containerColor = if (isJoined) Color(0xFF16A34A) else tokens.accentBrand,
                    disabledContainerColor = Color(0xFF16A34A)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Button text color is explicitly pure Black in dark mode when active
                Text(
                    text = if (isJoined) "Joined ✓" else "Join",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isJoined) Color.White else Color.Black
                )
            }
        }
    }
}
