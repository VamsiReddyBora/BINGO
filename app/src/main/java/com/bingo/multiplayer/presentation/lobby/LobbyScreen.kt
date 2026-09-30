package com.bingo.multiplayer.presentation.lobby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Player

import com.bingo.multiplayer.domain.network.PlayerRegistryEntry
import com.bingo.multiplayer.presentation.common.PlayerAvatar
import com.bingo.multiplayer.presentation.common.PlayerProfileData
import com.bingo.multiplayer.presentation.common.PlayerProfileDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LobbyScreen(
    roomCode: String,
    players: List<Player>,
    isHost: Boolean,
    currentUser: com.bingo.multiplayer.domain.model.UserProfile? = null,
    currentUserId: String = "",
    friendsRepository: com.bingo.multiplayer.domain.repository.FriendsRepository? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSearchPlayer: (suspend (String) -> PlayerRegistryEntry?)? = null,
    onToggleReady: ((Boolean) -> Unit)? = null,
    onRemovePlayer: ((String) -> Unit)? = null,
    inactivityResetToken: Int = 0,
    onExtendLobby: () -> Unit = {},
    onExpireLobby: () -> Unit = {},
    isManualBoard: Boolean = false,
    onManualBoardChange: ((Boolean) -> Unit)? = null,
    onStartGame: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val localUid = currentUserId.ifBlank { currentUser?.uid ?: "" }

    val myPlayer = players.find {
        (localUid.isNotBlank() && it.id == localUid) ||
        (currentUser?.username?.isNotBlank() == true && (it.username.equals(currentUser.username, ignoreCase = true) || it.displayName.equals(currentUser.username, ignoreCase = true)))
    } ?: if (!isHost) players.firstOrNull { !it.isHost } else players.firstOrNull { it.isHost }

    var isMyReadyState by remember { mutableStateOf(myPlayer?.lobbyReadyStatus == LobbyLifecycleEngine.STATUS_READY) }
    var hasInitializedReady by remember { mutableStateOf(false) }

    LaunchedEffect(myPlayer?.id) {
        if (!hasInitializedReady && myPlayer != null) {
            isMyReadyState = myPlayer.lobbyReadyStatus == LobbyLifecycleEngine.STATUS_READY
            hasInitializedReady = true
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<PlayerRegistryEntry?>(null) }
    var searchPerformed by remember { mutableStateOf(false) }
    var selectedProfilePlayer by remember { mutableStateOf<PlayerProfileData?>(null) }
    var playerToKick by remember { mutableStateOf<Player?>(null) }
    var inactivitySecondsLeft by remember { mutableStateOf(300) }
    var showInactivityDialog by remember { mutableStateOf(false) }
    var countdownSecondsLeft by remember { mutableStateOf(30) }

    LaunchedEffect(inactivityResetToken, isHost) {
        if (!isHost) return@LaunchedEffect
        inactivitySecondsLeft = 300
        showInactivityDialog = false
        countdownSecondsLeft = 30
        while (isActive) {
            delay(1000L)
            if (!showInactivityDialog) {
                if (inactivitySecondsLeft > 0) {
                    inactivitySecondsLeft--
                } else {
                    showInactivityDialog = true
                    countdownSecondsLeft = 30
                }
            } else {
                if (countdownSecondsLeft > 0) {
                    countdownSecondsLeft--
                } else {
                    showInactivityDialog = false
                    onExpireLobby()
                    break
                }
            }
        }
    }

    val presenceMap by com.bingo.multiplayer.domain.network.PresenceManager.presenceFlow.collectAsState()
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(3000L)
            ticker = System.currentTimeMillis()
        }
    }

    val allFriends by (friendsRepository?.friends?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

    LaunchedEffect(players, searchResult, allFriends) {
        while (isActive) {
            val users = mutableListOf<String>()
            players.forEach {
                val uname = it.username.ifBlank { it.displayName.filter { c -> c.isLetterOrDigit() }.lowercase() }
                if (uname.isNotBlank()) users.add(uname)
            }
            allFriends.forEach { f ->
                val uname = f.username.trim().lowercase().removePrefix("@")
                if (uname.isNotBlank()) users.add(uname)
            }
            searchResult?.let { if (it.username.isNotBlank()) users.add(it.username) }
            val cleanList = users.distinct()
            if (cleanList.isNotEmpty()) {
                com.bingo.multiplayer.domain.network.PresenceManager.fetchCloudPresenceForUsers(cleanList)
            }
            delay(3000L)
        }
    }

    // Sizing Formula: 2 players = 5x5, 3 players = 6x6, 4 players = 7x7, 5+ players = 8x8
    val boardSize = (3 + players.size.coerceAtLeast(2)).coerceAtMost(8)

    val infiniteTransition = rememberInfiniteTransition(label = "refreshAnim")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                Text(
                    text = if (isHost) "Host Lobby" else "Match Lobby",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.cellNeutralText
                )

                Spacer(modifier = Modifier.weight(1f))

                // Refresh Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRefresh()
                        Toast.makeText(context, "Checking for players…", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = tokens.accentBrand,
                        modifier = Modifier
                            .size(22.dp)
                            .then(if (isRefreshing) Modifier.rotate(rotationAngle) else Modifier)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Role badge
                Text(
                    text = if (isHost) "Acting as a Host" else "Acting as a Player",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Room Code Card ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ROOM CODE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.accentBrand
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = roomCode,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        color = tokens.cellNeutralText
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Bingo Room Code", roomCode))
                                Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = tokens.cellNeutralText.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.8f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Join my Bingo room! Room code: $roomCode")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Room Code"))
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share Code",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Connected Players Card with Live Status ──
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
                            Text(
                                text = "PLAYERS IN LOBBY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.5f)
                            )


                        }

                        TextButton(
                            onClick = onRefresh,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = tokens.accentBrand
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sync",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.accentBrand
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    players.forEachIndexed { index, player ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = tokens.surfaceBorder.copy(alpha = 0.5f),
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isMe = (localUid.isNotBlank() && player.id == localUid) || (currentUser?.username?.isNotBlank() == true && (player.username.equals(currentUser.username, ignoreCase = true) || player.displayName.equals(currentUser.username, ignoreCase = true)))

                            @OptIn(ExperimentalFoundationApi::class)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            val cleanUser = player.username.ifBlank {
                                                player.displayName.filter { it.isLetterOrDigit() }.lowercase()
                                            }.trim().lowercase().removePrefix("@")
                                            val livePres = presenceMap[cleanUser]
                                            val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                                            val freshPlayer = player.copy(lastSeenTimestamp = effectiveTs)
                                            selectedProfilePlayer = PlayerProfileData.fromPlayer(freshPlayer, currentUser)
                                        },
                                        onLongClick = {
                                            if (isHost && !player.isHost) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                playerToKick = player
                                            }
                                        }
                                    )
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var remoteAvatar by remember(player.id, player.username, player.avatarUrl) {
                                    mutableStateOf(player.avatarUrl?.takeIf { !com.bingo.multiplayer.domain.network.isLocalFilePath(it) })
                                }
                                LaunchedEffect(player.username, remoteAvatar) {
                                    if (!isMe && remoteAvatar.isNullOrBlank() && player.username.isNotBlank() && onSearchPlayer != null) {
                                        val entry = onSearchPlayer(player.username)
                                        if (!entry?.avatarUrl.isNullOrBlank()) {
                                            remoteAvatar = entry?.avatarUrl
                                        }
                                    }
                                }
                                val effectiveAvatar = if (isMe) {
                                    currentUser?.avatarUrl?.takeIf { it.isNotBlank() } ?: currentUser?.avatarBase64 ?: player.avatarUrl
                                } else {
                                    remoteAvatar ?: player.avatarUrl
                                }

                                Box {
                                    PlayerAvatar(
                                        avatarPathOrUri = effectiveAvatar,
                                        displayName = player.displayName,
                                        size = 40.dp,
                                        borderWidth = 1.5.dp,
                                        borderColor = if (player.isHost) tokens.accentBrand else tokens.surfaceBorder,
                                        username = player.username.ifBlank { player.displayName }
                                    )
                                    if (player.isHost) {
                                        Text(
                                            text = "👑",
                                            fontSize = 20.sp,
                                            modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-7).dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = player.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.cellNeutralText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                val cleanUser = player.username.ifBlank {
                                    player.displayName.filter { it.isLetterOrDigit() }.lowercase()
                                }.trim().lowercase().removePrefix("@")

                                val livePres = presenceMap[cleanUser]
                                val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                                if (ticker >= 0L) Unit
                                val rawStatus = if (isMe) "online" else com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, effectiveTs)
                                val isOnline = rawStatus.equals("online", ignoreCase = true)
                                val displayStatus = when {
                                    isOnline -> "online"
                                    rawStatus.equals("offline", ignoreCase = true) -> "offline"
                                    else -> rawStatus
                                }
                                val statusColor = when {
                                    isOnline -> Color(0xFF16A34A)
                                    displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
                                    else -> Color(0xFFEAB308)
                                }

                                Text(
                                    text = displayStatus,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            if (!isMe && friendsRepository != null) {
                                val isAlreadyFriend = friendsRepository.isFriend(player.id) || friendsRepository.isFriend(player.displayName)
                                val hasSent = friendsRepository.hasSentRequest(player.displayName) || friendsRepository.hasSentRequest(player.id)

                                if (!isAlreadyFriend) {
                                    if (hasSent) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = tokens.accentBrand.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "Requested",
                                                color = tokens.accentBrand,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    currentUser?.let { me ->
                                                        val success = friendsRepository.sendFriendRequest(
                                                            targetUsername = player.displayName,
                                                            targetDisplayName = player.displayName,
                                                            targetUid = player.id,
                                                            currentUser = me
                                                        )
                                                        if (success) {
                                                            Toast.makeText(context, "Friend request sent to ${player.displayName}!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(tokens.accentBrand.copy(alpha = 0.12f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PersonAdd,
                                                contentDescription = "Add Friend",
                                                tint = tokens.accentBrand,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Trailing Minimalist Ready Status Indicator
                            val cleanUser = player.username.ifBlank {
                                player.displayName.filter { it.isLetterOrDigit() }.lowercase()
                            }.trim().lowercase().removePrefix("@")
                            val livePres = presenceMap[cleanUser]
                            val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: player.lastSeenTimestamp
                            val rawStatus = if (isMe) "online" else com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanUser, effectiveTs)

                            val effectivePlayer = if (isMe) {
                                player.copy(lobbyReadyStatus = if (isMyReadyState) LobbyLifecycleEngine.STATUS_READY else LobbyLifecycleEngine.STATUS_NOT_READY)
                            } else {
                                player
                            }

                            val lobbyStatus = LobbyLifecycleEngine.getPlayerLobbyStatus(effectivePlayer, isMe, rawStatus)

                            val (readyIcon, readyTint, readyDesc) = when (lobbyStatus) {
                                LobbyLifecycleEngine.PlayerLobbyStatus.IN_GAME -> Triple(Icons.Default.HourglassBottom, Color(0xFFF59E0B), "Reviewing Board")
                                LobbyLifecycleEngine.PlayerLobbyStatus.LEFT_LOBBY -> Triple(Icons.Default.Cancel, Color(0xFFEF4444), "Left Lobby")
                                LobbyLifecycleEngine.PlayerLobbyStatus.READY -> Triple(Icons.Default.CheckCircle, Color(0xFF16A34A), if (player.isHost) "Host Ready" else "Ready")
                                LobbyLifecycleEngine.PlayerLobbyStatus.NOT_READY -> Triple(Icons.Default.PauseCircle, Color(0xFFEAB308), "Not Ready")
                            }

                            val isPlayerLeft = (lobbyStatus == LobbyLifecycleEngine.PlayerLobbyStatus.LEFT_LOBBY)

                            if (isHost && !player.isHost && isPlayerLeft) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerToKick = player
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = readyIcon,
                                        contentDescription = "Remove Left Player",
                                        tint = readyTint,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = readyIcon,
                                    contentDescription = readyDesc,
                                    tint = readyTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (players.size < 2) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = tokens.cellNeutralBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Waiting for other players to enter room code $roomCode…",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Online Friends Card ──
            if (friendsRepository != null) {
                val onlineFriends = remember(allFriends, presenceMap, players) {
                    allFriends.filter { f ->
                        val cleanF = f.username.trim().lowercase().removePrefix("@")
                        val pres = presenceMap[cleanF]
                        val effectiveTs = pres?.timestamp?.takeIf { it > 0L } ?: f.lastSeenTimestamp
                        val isOnlineByPres = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanF, effectiveTs).equals("online", ignoreCase = true)
                        val notInRoom = players.none { p ->
                            p.id == f.uid ||
                            (f.username.isNotBlank() && p.username.equals(f.username, ignoreCase = true)) ||
                            p.displayName.equals(f.displayName, ignoreCase = true)
                        }
                        isOnlineByPres && notInRoom
                    }
                }

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
                            Text(
                                text = "ONLINE FRIENDS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.5f)
                            )
                            if (onlineFriends.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF16A34A).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${onlineFriends.size} online",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF16A34A),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (onlineFriends.isEmpty()) {
                            Text(
                                text = "No friends currently online",
                                fontSize = 12.sp,
                                color = tokens.cellNeutralText.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            )
                        } else {
                            onlineFriends.forEachIndexed { fIdx, friend ->
                                if (fIdx > 0) {
                                    HorizontalDivider(
                                        color = tokens.surfaceBorder.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                val cleanF = friend.username.trim().lowercase().removePrefix("@")
                                                val livePres = presenceMap[cleanF]
                                                val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: friend.lastSeenTimestamp
                                                val p = Player(
                                                    id = friend.uid,
                                                    displayName = friend.displayName,
                                                    username = friend.username,
                                                    avatarUrl = friend.avatarUrl,
                                                    lastSeenTimestamp = effectiveTs
                                                )
                                                selectedProfilePlayer = PlayerProfileData.fromPlayer(p, currentUser)
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PlayerAvatar(
                                            avatarPathOrUri = friend.avatarUrl,
                                            displayName = friend.displayName,
                                            size = 36.dp,
                                            borderWidth = 1.dp,
                                            borderColor = tokens.accentBrand,
                                            username = friend.username
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Text(
                                            text = friend.displayName,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tokens.cellNeutralText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        Spacer(modifier = Modifier.width(6.dp))

                                        val cleanF = friend.username.trim().lowercase().removePrefix("@")
                                        val livePres = presenceMap[cleanF]
                                        val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: friend.lastSeenTimestamp
                                        if (ticker >= 0L) Unit
                                        val statusText = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanF, effectiveTs)
                                        val isOnline = statusText.equals("online", ignoreCase = true)
                                        val displayStatus = when {
                                            isOnline -> "online"
                                            statusText.equals("offline", ignoreCase = true) -> "offline"
                                            else -> statusText
                                        }
                                        val statusColor = when {
                                            isOnline -> Color(0xFF16A34A)
                                            displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
                                            else -> Color(0xFFEAB308)
                                        }

                                        Text(
                                            text = displayStatus,
                                            fontSize = 10.5.sp,
                                            color = statusColor,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            val fromUser = currentUser?.username
                                                ?: players.firstOrNull { it.isHost }?.id
                                                ?: "Host"
                                            val fromName = currentUser?.displayName
                                                ?: players.firstOrNull { it.isHost }?.displayName
                                                ?: "Host"

                                            coroutineScope.launch {
                                                com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                                                    targetUsername = friend.username,
                                                    invite = com.bingo.multiplayer.domain.network.GameInvite(
                                                        fromUsername = fromUser,
                                                        fromDisplayName = fromName,
                                                        fromAvatarUrl = null,
                                                        roomCode = roomCode
                                                    )
                                                )
                                                Toast.makeText(context, "Invite sent to @${friend.username}!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Invite", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Search Player by Username Card ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = tokens.surface,
                border = BorderStroke(1.dp, tokens.surfaceBorder),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "FIND PLAYER BY USERNAME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tokens.cellNeutralText.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text("Enter @username", fontSize = 13.sp, color = tokens.cellNeutralText.copy(alpha = 0.4f))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = tokens.accentBrand,
                                unfocusedBorderColor = tokens.surfaceBorder
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val keyboardController = LocalSoftwareKeyboardController.current
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                if (searchQuery.isNotBlank() && onSearchPlayer != null) {
                                    isSearching = true
                                    searchResult = null
                                    searchPerformed = true
                                    coroutineScope.launch {
                                        searchResult = onSearchPlayer(searchQuery)
                                        isSearching = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                            enabled = !isSearching && searchQuery.isNotBlank()
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Search", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    val currentSearchResult = searchResult
                    if (currentSearchResult != null) {
                        val result = currentSearchResult
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.backgroundSecondary,
                            border = BorderStroke(1.dp, tokens.surfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val cleanFound = result.username.trim().lowercase().removePrefix("@")
                                            val livePres = presenceMap[cleanFound]
                                            val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: result.lastSeenTimestamp
                                            selectedProfilePlayer = PlayerProfileData.fromRegistryEntry(result.copy(lastSeenTimestamp = effectiveTs))
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PlayerAvatar(
                                        avatarPathOrUri = result.avatarUrl,
                                        displayName = result.displayName,
                                        size = 36.dp,
                                        borderWidth = 1.dp,
                                        borderColor = tokens.accentBrand,
                                        username = result.username
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = result.displayName,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.cellNeutralText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    val cleanFound = result.username.trim().lowercase().removePrefix("@")
                                    val livePres = presenceMap[cleanFound]
                                    val effectiveTs = livePres?.timestamp?.takeIf { it > 0L } ?: result.lastSeenTimestamp
                                    if (ticker >= 0L) Unit
                                    val statusText = com.bingo.multiplayer.domain.network.PresenceManager.getDisplayStatus(cleanFound, effectiveTs)
                                    val isOnline = statusText.equals("online", ignoreCase = true)
                                    val displayStatus = when {
                                        isOnline -> "online"
                                        statusText.equals("offline", ignoreCase = true) -> "offline"
                                        else -> statusText
                                    }
                                    val statusColor = when {
                                        isOnline -> Color(0xFF16A34A)
                                        displayStatus.equals("offline", ignoreCase = true) -> Color(0xFF94A3B8)
                                        else -> Color(0xFFEAB308)
                                    }
                                    Text(
                                        text = displayStatus,
                                        fontSize = 10.5.sp,
                                        color = statusColor,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (friendsRepository != null) {
                                        val isAlready = friendsRepository.isFriend(result.username) || friendsRepository.isFriend(result.uid)
                                        val hasSent = friendsRepository.hasSentRequest(result.username)
                                        if (!isAlready) {
                                            if (hasSent) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = tokens.accentBrand.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = "Requested",
                                                        color = tokens.accentBrand,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                    )
                                                }
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            currentUser?.let { me ->
                                                                val success = friendsRepository.sendFriendRequest(
                                                                    targetUsername = result.username,
                                                                    targetDisplayName = result.displayName,
                                                                    targetUid = result.uid,
                                                                    currentUser = me
                                                                )
                                                                if (success) {
                                                                    Toast.makeText(context, "Friend request sent to ${result.displayName}!", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(tokens.accentBrand.copy(alpha = 0.12f))
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PersonAdd,
                                                        contentDescription = "Add Friend",
                                                        tint = tokens.accentBrand,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            val fromUser = currentUser?.username
                                                ?: players.firstOrNull { it.isHost }?.id
                                                ?: "Host"
                                            val fromName = currentUser?.displayName
                                                ?: players.firstOrNull { it.isHost }?.displayName
                                                ?: "Host"

                                            coroutineScope.launch {
                                                com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                                                    targetUsername = result.username,
                                                    invite = com.bingo.multiplayer.domain.network.GameInvite(
                                                        fromUsername = fromUser,
                                                        fromDisplayName = fromName,
                                                        fromAvatarUrl = null,
                                                        roomCode = roomCode
                                                    )
                                                )
                                                Toast.makeText(context, "Invite sent to @${result.username}!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Invite", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else if (searchPerformed && !isSearching) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No player found with username \"$searchQuery\"",
                            fontSize = 12.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Action Buttons ──
            val effectivePlayers = players.map { p ->
                val isThisLocal = (localUid.isNotBlank() && p.id == localUid) ||
                                  (currentUser?.username?.isNotBlank() == true && (p.username.equals(currentUser.username, ignoreCase = true) || p.displayName.equals(currentUser.username, ignoreCase = true)))
                if (isThisLocal) {
                    p.copy(lobbyReadyStatus = if (isMyReadyState) LobbyLifecycleEngine.STATUS_READY else LobbyLifecycleEngine.STATUS_NOT_READY)
                } else {
                    p
                }
            }
            val allReady = LobbyLifecycleEngine.canStartMatch(effectivePlayers)
            val readyCount = LobbyLifecycleEngine.countReadyPlayers(effectivePlayers)

            if (isHost) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surface)
                        .clickable { onManualBoardChange?.invoke(!isManualBoard) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Manual designed board",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.cellNeutralText
                    )
                    Checkbox(
                        checked = isManualBoard,
                        onCheckedChange = { onManualBoardChange?.invoke(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = tokens.accentBrand,
                            uncheckedColor = tokens.surfaceBorder,
                            checkmarkColor = Color.White
                        )
                    )
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStartGame()
                    },
                    enabled = allReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accentBrand,
                        disabledContainerColor = tokens.surfaceBorder
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            players.size < 2 -> "Need at least 2 Players"
                            !allReady -> "Waiting for Players to be Ready ($readyCount/${players.size})"
                            else -> "Start Match (${boardSize}×${boardSize})"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (allReady) Color.White else tokens.cellNeutralText.copy(alpha = 0.5f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isMyReadyState) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isMyReadyState = false
                                onToggleReady?.invoke(false)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, tokens.accentBrand),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = tokens.accentBrand
                            )
                        ) {
                            Text(
                                text = "I'm Not Ready",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = tokens.accentBrand
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isMyReadyState = true
                                onToggleReady?.invoke(true)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.accentBrand
                            )
                        ) {
                            Text(
                                text = "I'm Ready",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (allReady) {
                            "All players ready! Waiting for host to start…"
                        } else {
                            "Waiting for host to start once everyone is ready ($readyCount/${players.size})"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.cellNeutralText.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        val targetKick = playerToKick
        if (targetKick != null) {
            AlertDialog(
                onDismissRequest = { playerToKick = null },
                title = {
                    Text("Remove Player", fontWeight = FontWeight.Bold, color = tokens.cellNeutralText)
                },
                text = {
                    Text("Remove ${targetKick.displayName} from the lobby?", color = tokens.cellNeutralText.copy(alpha = 0.85f))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val t = targetKick
                            playerToKick = null
                            if (t != null && onRemovePlayer != null) {
                                onRemovePlayer(t.id)
                                Toast.makeText(context, "${t.displayName} removed from lobby", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Remove", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { playerToKick = null },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = tokens.cellNeutralText)
                    }
                },
                containerColor = tokens.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        val profileToDisplay = selectedProfilePlayer
        if (profileToDisplay != null) {
            PlayerProfileDialog(
                playerData = profileToDisplay,
                currentUser = currentUser,
                friendsRepository = friendsRepository,
                onDismiss = { selectedProfilePlayer = null }
            )
        }

        if (isHost && showInactivityDialog) {
            AlertDialog(
                onDismissRequest = { /* Modal: require user selection or countdown */ },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Inactivity Detected",
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText,
                            fontSize = 18.sp
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "The lobby will expire in ${countdownSecondsLeft}s",
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.cellNeutralText,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "No match was started. Do you want to continue waiting or exit the lobby?",
                            fontSize = 13.sp,
                            color = tokens.cellNeutralText.copy(alpha = 0.7f)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showInactivityDialog = false
                            onExtendLobby()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accentBrand),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Wait +5mins",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showInactivityDialog = false
                            onExpireLobby()
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Text(
                            text = "Exit the lobby",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFEF4444)
                        )
                    }
                },
                containerColor = tokens.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
