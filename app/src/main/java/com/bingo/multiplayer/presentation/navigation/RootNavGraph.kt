package com.bingo.multiplayer.presentation.navigation

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.currentBackStackEntryAsState
import com.bingo.multiplayer.presentation.common.PermissionHelper
import com.bingo.multiplayer.domain.engine.AiDifficulty
import com.bingo.multiplayer.domain.engine.BingoAiPlayer
import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.AuthState
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.HotspotAndWifiManager
import com.bingo.multiplayer.domain.network.LanDiscoveryManager
import com.bingo.multiplayer.domain.network.LanDiscoveredGame
import com.bingo.multiplayer.domain.network.OnlineRoomSyncManager
import com.bingo.multiplayer.domain.network.RoomMessagePacket
import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.repository.FriendsRepository
import com.bingo.multiplayer.presentation.auth.AuthGateScreen
import com.bingo.multiplayer.presentation.auth.LoginScreen
import com.bingo.multiplayer.presentation.game.GameScreen
import com.bingo.multiplayer.presentation.lobby.LobbyScreen
import com.bingo.multiplayer.presentation.menu.MainMenuScreen
import com.bingo.multiplayer.presentation.nearby.NearbyChoiceScreen
import com.bingo.multiplayer.presentation.nearby.NearbyLobbyScreen
import com.bingo.multiplayer.presentation.online.JoinRoomScreen
import com.bingo.multiplayer.presentation.online.OnlineMatchChoiceScreen
import com.bingo.multiplayer.presentation.settings.SettingsScreen
import com.bingo.multiplayer.presentation.social.DashboardAndFriendsScreen
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.presentation.manual.ManualBoardDesignScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed class Screen(val route: String) {
    data object AuthGate : Screen("auth_gate")
    data object Login : Screen("login")
    data object MainMenu : Screen("main_menu")
    data object Settings : Screen("settings")
    data object OnlineChoice : Screen("online_choice")
    data object JoinRoom : Screen("join_room")
    data object Lobby : Screen("lobby")
    data object Game : Screen("game")
    data object Dashboard : Screen("dashboard")
    data object NearbyChoice : Screen("nearby_choice")
    data object NearbyLobby : Screen("nearby_lobby")
    data object ManualBoardDesign : Screen("manual_board_design")
    data object DeveloperNote : Screen("developer_note")
}

@Composable
fun RootNavGraph(
    authRepository: AuthRepository,
    friendsRepository: FriendsRepository,
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val engine = remember { BingoEngine() }
    val aiPlayer = remember { BingoAiPlayer() }
    val onlineRoomSync = remember { OnlineRoomSyncManager() }
    var roomCode by remember { mutableStateOf("") }
    var isHosting by remember { mutableStateOf(false) }
    val lanP2pSync = remember { com.bingo.multiplayer.domain.network.LanP2pSessionManager() }
    var isUsingP2p by remember { androidx.compose.runtime.mutableStateOf(false) }
    val lanDiscovery = remember { LanDiscoveryManager(context) }
    var isManualBoard by remember { mutableStateOf(false) }
    var isDynamicBoard by remember { mutableStateOf(false) }
    var selectedDynamicGridSize by remember { mutableIntStateOf(5) }
    var isHostLeftGame by remember { mutableStateOf(false) }
    val broadcastPacket: (RoomMessagePacket) -> Unit = { packet -> if (isUsingP2p) lanP2pSync.broadcastPacket(packet) else onlineRoomSync.broadcastPacket(packet) }
    val disconnectRoom: () -> Unit = { 
        if (isUsingP2p) {
            lanP2pSync.disconnect()
            com.bingo.multiplayer.domain.network.HotspotAndWifiManager.disconnectFromWifi(context)
        } else {
            onlineRoomSync.disconnect()
        }
        isManualBoard = false
        isDynamicBoard = false
        selectedDynamicGridSize = 5
        roomCode = ""
        isHostLeftGame = false
    }
    val coroutineScope = rememberCoroutineScope()

    val onlineRealTimePlayers by onlineRoomSync.players.collectAsState()
    val p2pRealTimePlayers by lanP2pSync.players.collectAsState()
    val realTimePlayers = if (isUsingP2p) p2pRealTimePlayers else onlineRealTimePlayers
    val isRefreshing by onlineRoomSync.isRefreshing.collectAsState()
    val discoveredGames by lanDiscovery.discoveredGames.collectAsState()
    var joinedLanGame by remember { mutableStateOf<LanDiscoveredGame?>(null) }
    var isNearbyHostMode by remember { mutableStateOf(false) }

    // Game Session State
    var currentGameMode by remember { mutableStateOf(GameMode.AI_EASY) }
    var currentAiDifficulty by remember { mutableStateOf(AiDifficulty.EASY) }
    var boardSize by remember { mutableIntStateOf(5) }
    var playerBoard by remember { mutableStateOf(engine.generateBoard(5)) }
    var opponentBoard by remember { mutableStateOf(engine.generateBoard(5)) }
    var allPlayerBoards by remember { mutableStateOf<Map<String, Board>>(emptyMap()) }
    var isMyTurn by remember { mutableStateOf(true) }
    var turnNumber by remember { mutableIntStateOf(1) }
    var turnTimer by remember { mutableIntStateOf(30) }
    var isGamePaused by remember { mutableStateOf(false) }
    var pausedByPlayerName by remember { mutableStateOf("") }
    var recentPick by remember { mutableStateOf<RecentPick?>(null) }
    var isGameOver by remember { mutableStateOf(false) }
    var didPlayerWin by remember { mutableStateOf(false) }
    var isDrawMatch by remember { mutableStateOf(false) }
    var isRunnerMatch by remember { mutableStateOf(false) }
    var winnerPlayerId by remember { mutableStateOf("") }
    var currentMatchSeed by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var isLocalBoardReady by remember { mutableStateOf(false) }
    var isOpponentBoardReady by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableIntStateOf(-1) }
    var firstTurnPlayerName by remember { mutableStateOf("") }
    var latestIncomingEmote by remember { mutableStateOf<String?>(null) }
    var latestIncomingEmoteScale by remember { mutableFloatStateOf(1.0f) }
    var latestIncomingEmoteTimestamp by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var latestIncomingChatMessage by remember { mutableStateOf<InGameChatMessage?>(null) }
    var matchChatHistory by remember { mutableStateOf<List<InGameChatMessage>>(emptyList()) }
    var opponentPlayerId by remember { mutableStateOf("") }
    var matchParticipants by remember { mutableStateOf<List<Player>>(emptyList()) }
    var randomizedTurnOrder by remember { mutableStateOf<List<Player>>(emptyList()) }
    val disconnectedPlayerIds = remember { mutableStateListOf<String>() }
    val consecutiveMissedTurns = remember { mutableStateMapOf<String, Int>() }
    var isStartingCountdown by remember { mutableStateOf(false) }

    // History and Turn Authority (reconciles network packets and prevents stalls)
    val pickedNumbersHistory = remember { mutableStateListOf<Int>() }
    val pickedByPlayerHistory = remember { mutableStateListOf<String>() }
    var currentTurnPlayerId by remember { mutableStateOf("") }
    var isProcessingTurn by remember { mutableStateOf(false) }

    // Online match state
    var wantsToPlayAgainPlayerName by remember { mutableStateOf<String?>(null) }
    var opponentDisconnectMessage by remember { mutableStateOf<String?>(null) }
    var opponentSurrenderMessage by remember { mutableStateOf<String?>(null) }
    val onlinePingMs by onlineRoomSync.pingMs.collectAsState()
    var lobbyInactivityResetToken by remember { mutableIntStateOf(0) }

    fun getEffectiveUser(): com.bingo.multiplayer.domain.model.UserProfile? {
        val state = authRepository.authState.value
        return (state as? AuthState.Authenticated)?.user ?: authRepository.getPersistedUserSync()
    }

    fun getLocalUid(): String {
        val user = getEffectiveUser()
        return user?.uid?.takeIf { it.isNotBlank() && it != "local_player" }
            ?: user?.username?.takeIf { it.isNotBlank() }?.let { "u_$it" }
            ?: authRepository.deviceId
    }

    fun getPlayerDisplayName(): String {
        val user = getEffectiveUser()
        return user?.displayName?.takeIf { it.isNotBlank() } ?: "Player"
    }

    fun getPlayerAvatarUrl(): String? {
        val user = getEffectiveUser()
        return user?.avatarBase64?.takeIf { it.isNotBlank() } ?: user?.avatarUrl
    }

    var lastForegroundResumeTimestamp by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }

    fun isPlayerDisconnected(playerId: String): Boolean {
        if (playerId.isBlank()) return false
        return disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, playerId) }
    }

    fun markPlayerDisconnected(playerId: String) {
        if (playerId.isBlank()) return
        if (!isPlayerDisconnected(playerId)) {
            disconnectedPlayerIds.add(playerId)
        }
    }

    fun markPlayerReconnected(playerId: String): Boolean {
        if (playerId.isBlank()) return false
        consecutiveMissedTurns.remove(playerId)
        val toRemove = disconnectedPlayerIds.filter { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, playerId) }
        if (toRemove.isNotEmpty()) {
            disconnectedPlayerIds.removeAll(toRemove.toSet())
            return true
        }
        return false
    }

    fun broadcastSystemChatMessage(text: String) {
        val sysMsg = InGameChatMessage(
            id = System.currentTimeMillis() + (0..1000).random(),
            text = text,
            isSelf = false,
            senderName = null,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = true
        )
        latestIncomingChatMessage = sysMsg
        matchChatHistory = matchChatHistory + sysMsg
        if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
            broadcastPacket(
                RoomMessagePacket(
                    type = "CHAT_MESSAGE",
                    playerId = getLocalUid(),
                    displayName = text,
                    username = "SYSTEM",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun isPlayerMe(p: Player): Boolean {
        val user = (authRepository.authState.value as? AuthState.Authenticated)?.user
        return com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerMe(
            p = p,
            myUid = getLocalUid(),
            myUsername = user?.username ?: "",
            myDisplayName = getPlayerDisplayName(),
            isHost = isHosting
        )
    }

    fun isPlayerMe(playerId: String): Boolean {
        if (playerId.isBlank()) return false
        val myUid = getLocalUid()
        if (com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(playerId, myUid)) return true
        val user = (authRepository.authState.value as? AuthState.Authenticated)?.user
        val myUser = user?.username ?: ""
        if (myUser.isNotBlank() && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(playerId, myUser)) return true
        val myDisplay = getPlayerDisplayName()
        if (myDisplay.isNotBlank() && playerId.equals(myDisplay, ignoreCase = true) && isHosting) return true
        return false
    }

    var incomingInvite by remember { mutableStateOf<com.bingo.multiplayer.domain.network.GameInvite?>(null) }
    var isJoiningRoom by remember { mutableStateOf(false) }
    val handledInviteRoomCodes = remember { mutableStateMapOf<String, Long>() }
    var showLeaveMatchDialog by remember { mutableStateOf(false) }
    val authStateValue by authRepository.authState.collectAsState()
    val currentAuthUser = (authStateValue as? AuthState.Authenticated)?.user

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            Screen.Game.route -> {
                com.bingo.multiplayer.domain.network.PresenceManager.setActivityState(
                    com.bingo.multiplayer.domain.network.AppActivityState.PLAYING
                )
            }
            Screen.Lobby.route, Screen.NearbyLobby.route, Screen.ManualBoardDesign.route -> {
                com.bingo.multiplayer.domain.network.PresenceManager.setActivityState(
                    com.bingo.multiplayer.domain.network.AppActivityState.IN_LOBBY
                )
            }
            else -> {
                com.bingo.multiplayer.domain.network.PresenceManager.setActivityState(
                    com.bingo.multiplayer.domain.network.AppActivityState.ONLINE
                )
            }
        }
    }

    val shouldInterceptBack = currentRoute != null &&
        currentRoute != Screen.MainMenu.route &&
        currentRoute != Screen.AuthGate.route &&
        currentRoute != Screen.Login.route

    BackHandler(enabled = shouldInterceptBack) {
        if (currentRoute == Screen.ManualBoardDesign.route) {
            return@BackHandler
        }
        if (currentRoute == Screen.Game.route && !isGameOver) {
            showLeaveMatchDialog = true
            return@BackHandler
        }

        if (currentRoute == Screen.Lobby.route ||
            currentRoute == Screen.NearbyLobby.route ||
            currentRoute == Screen.NearbyChoice.route ||
            currentRoute == Screen.JoinRoom.route ||
            currentRoute == Screen.OnlineChoice.route) {
            if (isHosting && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                coroutineScope.launch {
                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                }
            }
            disconnectRoom()
            lanDiscovery.stopBroadcasting()
            lanDiscovery.stopDiscovering()
            isHosting = false
        } else if (currentRoute == Screen.Game.route) {
            if (isHosting && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "HOST_LEFT",
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        username = currentAuthUser?.username ?: "",
                        isHost = true
                    )
                )
                coroutineScope.launch {
                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                }
            }
            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
            disconnectRoom()
            lanDiscovery.stopBroadcasting()
            lanDiscovery.stopDiscovering()
            isHosting = false
        }

        navController.navigate(Screen.MainMenu.route) {
            popUpTo(Screen.MainMenu.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    DisposableEffect(currentAuthUser?.username) {
        val username = currentAuthUser?.username
        if (username.isNullOrBlank()) {
            onDispose {}
        } else {
            val inviteListener = com.bingo.multiplayer.domain.network.GameInviteManager.startInviteListener(username) { invite ->
                val now = System.currentTimeMillis()
                val lastHandled = handledInviteRoomCodes[invite.roomCode] ?: 0L
                val isRecentlyHandled = (now - lastHandled) < 60_000L
                if (!isJoiningRoom && !isRecentlyHandled && roomCode != invite.roomCode && (now - invite.timestamp) < 120_000L) {
                    val currentDest = navController.currentDestination?.route
                    val isActivelyPlaying = (currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route) && !isGameOver
                    if (!isActivelyPlaying) {
                        incomingInvite = invite
                        if (com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value) {
                            com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
                        } else {
                            com.bingo.multiplayer.domain.network.BingoNotificationManager.showInviteNotification(context, invite)
                        }
                    }
                }
            }
            val requestListener = friendsRepository.startListeningForRequests(
                myUsername = username,
                currentUser = currentAuthUser,
                onNewRequest = { req ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "👥 @${req.fromUsername} sent you a friend request!", Toast.LENGTH_LONG).show()
                    }
                    com.bingo.multiplayer.domain.network.BingoNotificationManager.showFriendRequestNotification(
                        context = context,
                        fromUsername = req.fromUsername,
                        fromDisplayName = req.fromDisplayName,
                        forceShow = false
                    )
                },
                onFriendAccepted = { f ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "🎉 @${f.username} accepted your friend request! You are now friends.", Toast.LENGTH_LONG).show()
                    }
                    com.bingo.multiplayer.domain.network.BingoNotificationManager.showFriendAcceptedNotification(
                        context = context,
                        friendUsername = f.username,
                        friendDisplayName = f.displayName
                    )
                }
            )
            // Start presence and presence watcher for real-time friend status
            com.bingo.multiplayer.domain.network.PresenceManager.startPresence(username)
            com.bingo.multiplayer.domain.network.PresenceManager.startPresenceWatcher()

            onDispose {
                inviteListener.close()
                requestListener.close()
                // NOTE: Do NOT call PresenceManager.stopPresence() here!
                // Presence is managed by AppLifecycleObserver (app foreground/background).
                // stopPresence() is only called on actual sign-out.
            }
        }
    }

    // Observe App Foreground/Background transitions to handle match resume and prevent false disconnects
    LaunchedEffect(Unit) {
        com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.collect { isInForeground ->
            if (isInForeground) {
                val now = System.currentTimeMillis()
                lastForegroundResumeTimestamp = now
                val currentDest = navController.currentDestination?.route
                val isInGame = currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route
                if (isInGame && !isGameOver && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                    // 1. Reconnect MQTT if needed and refresh sync
                    onlineRoomSync.onForegroundResume()
                    // 2. Clear stale disconnect records accumulated while local device was asleep
                    disconnectedPlayerIds.clear()
                    // 3. Refresh heartbeat timestamps on local side for all active participants
                    val activeIds = (matchParticipants.ifEmpty { realTimePlayers }).map { it.id }.filter { it.isNotBlank() }
                    onlineRoomSync.resetInGameHeartbeats(activeIds)
                    lanP2pSync.resetInGameHeartbeats(activeIds)

                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val user = (authRepository.authState.value as? AuthState.Authenticated)?.user

                    // 4. Announce rejoin and request game sync to catch up on any turns missed while asleep
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "REJOIN_GAME",
                            playerId = myUid,
                            displayName = myName,
                            username = user?.username ?: "",
                            isHost = isHosting,
                            seed = currentMatchSeed,
                            boardSize = boardSize,
                            turnNumber = turnNumber,
                            currentTurnPlayerId = currentTurnPlayerId,
                            timestamp = now
                        )
                    )
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "SYNC_REQUEST",
                            playerId = myUid,
                            seed = currentMatchSeed,
                            timestamp = now
                        )
                    )
                    val localReconnected = InGameChatMessage(
                        id = now,
                        text = "🟢 $myName reconnected",
                        isSelf = false,
                        senderName = null,
                        timestamp = now,
                        isSystemMessage = true
                    )
                    matchChatHistory = matchChatHistory + localReconnected
                    latestIncomingChatMessage = localReconnected
                }
            }
        }
    }

    // Dual-channel background reconciliation: polls cloud KeyValue storage for pending invites and friend requests
    LaunchedEffect(currentAuthUser?.username) {
        val u = currentAuthUser?.username?.trim()?.lowercase()?.removePrefix("@")
        if (!u.isNullOrBlank()) {
            friendsRepository.syncFriendsAndRequests(u)
            while (isActive) {
                try {
                    val pendingInvites = com.bingo.multiplayer.domain.network.GameInviteManager.fetchInvitesForUser(u)
                    val now = System.currentTimeMillis()
                    val validInvite = pendingInvites.firstOrNull { inv ->
                        val lastHandled = handledInviteRoomCodes[inv.roomCode] ?: 0L
                        val isRecentlyHandled = (now - lastHandled) < 60_000L
                        !isJoiningRoom && !isRecentlyHandled && inv.roomCode != roomCode && (now - inv.timestamp) < 120_000L
                    }
                    if (validInvite != null && (incomingInvite == null || incomingInvite?.roomCode != validInvite.roomCode)) {
                        val currentDest = navController.currentDestination?.route
                        val isActivelyPlaying = (currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route) && !isGameOver
                        if (!isActivelyPlaying) {
                            incomingInvite = validInvite
                        }
                    }
                    friendsRepository.syncFriendsAndRequests(u)
                } catch (_: Exception) {}
                delay(2000L)
            }
        }
    }

    fun acceptAndJoinRoom(targetRoomCode: String) {
        if (isJoiningRoom) return
        val cleanCode = targetRoomCode.trim().uppercase()
        isJoiningRoom = true
        incomingInvite = null
        com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
        com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
        handledInviteRoomCodes[cleanCode] = System.currentTimeMillis()
        Toast.makeText(context, "Joining room #$cleanCode...", Toast.LENGTH_SHORT).show()

        coroutineScope.launch {
            try {
                // Ensure auth has settled to prevent guest fallback
                val user = authRepository.awaitAuthenticatedUser(2500L) ?: getEffectiveUser()
                val uid = user?.uid?.takeIf { it.isNotBlank() && it != "local_player" }
                    ?: user?.username?.takeIf { it.isNotBlank() }?.let { "u_$it" }
                    ?: getLocalUid()
                val displayName = user?.displayName?.takeIf { it.isNotBlank() } ?: getPlayerDisplayName()
                val avatar = user?.avatarBase64?.takeIf { it.isNotBlank() } ?: user?.avatarUrl ?: getPlayerAvatarUrl()

                val joinerVersion = System.currentTimeMillis()
                val localJoiner = Player(
                    id = uid,
                    displayName = displayName,
                    username = user?.username ?: "",
                    isHost = false,
                    avatarUrl = avatar,
                    gamesPlayed = user?.gamesPlayed ?: 0,
                    gamesWon = user?.gamesWon ?: 0,
                    currentStreak = user?.currentStreak ?: 0,
                    level = user?.level ?: 1,
                    lastSeenTimestamp = joinerVersion,
                    readyVersion = joinerVersion
                )

                var result = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.validateAndJoinRoom(cleanCode, localJoiner)
                if (result is com.bingo.multiplayer.domain.network.RoomJoinResult.NotFound) {
                    kotlinx.coroutines.delay(500L)
                    result = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.validateAndJoinRoom(cleanCode, localJoiner)
                }
                when (result) {
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.Success -> {
                        incomingInvite = null
                        val myU = user?.username?.trim()?.lowercase()?.removePrefix("@")
                        if (!myU.isNullOrBlank()) {
                            launch { com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(myU, cleanCode) }
                        }
                        roomCode = cleanCode
                        currentGameMode = GameMode.ONLINE_ROOM
                        isUsingP2p = false
                        isHosting = false
                        isManualBoard = result.room.isManualBoard
                        isDynamicBoard = result.room.isDynamicBoard
                        val returnedPlayer = result.room.players.find { it.id == localJoiner.id }
                        val effJoiner = if (returnedPlayer != null) {
                            localJoiner.copy(readyVersion = returnedPlayer.readyVersion)
                        } else localJoiner
                        onlineRoomSync.connectToRoom(cleanCode, effJoiner, initialPlayers = result.room.players)
                        navController.navigate(Screen.Lobby.route)
                    }
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.NotFound -> {
                        handledInviteRoomCodes.remove(cleanCode)
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.AlreadyFull -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.AlreadyStarted -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.Expired -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.Error -> {
                        handledInviteRoomCodes.remove(cleanCode)
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isJoiningRoom = false
            }
        }
    }

    // Listen to notification actions (e.g. Accept invite clicked from status bar, or Invite Friend clicked)
    val pendingJoinCode by com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingJoinRoomCode.collectAsState()
    LaunchedEffect(pendingJoinCode) {
        val code = pendingJoinCode
        if (!code.isNullOrBlank()) {
            com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingJoinRoomCode.value = null
            com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
            acceptAndJoinRoom(code)
        }
    }

    val pendingInviteFriend by com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingInviteFriendUsername.collectAsState()
    LaunchedEffect(pendingInviteFriend) {
        val friend = pendingInviteFriend
        if (!friend.isNullOrBlank()) {
            com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingInviteFriendUsername.value = null
            navController.navigate(Screen.Dashboard.route) {
                launchSingleTop = true
            }
        }
    }

    val pendingNavigateFriends by com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingNavigateToFriends.collectAsState()
    LaunchedEffect(pendingNavigateFriends) {
        if (pendingNavigateFriends) {
            com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingNavigateToFriends.value = false
            navController.navigate(Screen.Dashboard.route) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(Unit) {
        val staged = com.bingo.multiplayer.domain.network.GameInviteManager.consumeStagedInvite()
        if (staged != null) {
            val now = System.currentTimeMillis()
            val lastHandled = handledInviteRoomCodes[staged.roomCode] ?: 0L
            val isRecentlyHandled = (now - lastHandled) < 60_000L
            if (!isJoiningRoom && !isRecentlyHandled && roomCode != staged.roomCode && (now - staged.timestamp) < 120_000L) {
                val currentDest = navController.currentDestination?.route
                val isActivelyPlaying = (currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route) && !isGameOver
                if (!isActivelyPlaying) {
                    incomingInvite = staged
                }
            }
        }

        com.bingo.multiplayer.domain.network.GameInviteManager.foregroundInviteFlow.collect { invite ->
            val now = System.currentTimeMillis()
            val lastHandled = handledInviteRoomCodes[invite.roomCode] ?: 0L
            val isRecentlyHandled = (now - lastHandled) < 60_000L
            if (!isJoiningRoom && !isRecentlyHandled && roomCode != invite.roomCode && (now - invite.timestamp) < 120_000L) {
                val currentDest = navController.currentDestination?.route
                val isActivelyPlaying = (currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route) && !isGameOver
                if (!isActivelyPlaying) {
                    incomingInvite = invite
                }
            }
        }
    }

    fun togglePause() {
        val newPaused = !isGamePaused
        isGamePaused = newPaused
        pausedByPlayerName = if (newPaused) getPlayerDisplayName() else ""
        if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
            broadcastPacket(
                RoomMessagePacket(
                    type = if (newPaused) "GAME_PAUSED" else "GAME_RESUMED",
                    playerId = getLocalUid(),
                    displayName = getPlayerDisplayName()
                )
            )
        }
    }

    fun openNearbyChoice() {
        navController.navigate(Screen.NearbyChoice.route)
    }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        openNearbyChoice()
    }

    fun recordFinishedMatch(won: Boolean, isDraw: Boolean = false, isRunner: Boolean = false) {
        val myName = currentAuthUser?.username?.ifBlank { currentAuthUser.displayName } ?: getPlayerDisplayName()
        val isGroup = realTimePlayers.size > 2

        val matchTitle = if (isGroup) {
            when {
                isDraw -> "Group play draw 🤝"
                won -> "Group play won 🏆"
                isRunner -> "Group play runner 🥈"
                else -> "Group play lost 😑"
            }
        } else {
            val opponent = when (currentGameMode) {
                GameMode.AI_EASY -> "AI (Easy)"
                GameMode.AI_HARD -> "AI (Master)"
                GameMode.ONLINE_ROOM -> {
                    val opp = realTimePlayers.firstOrNull { it.id != getLocalUid() }
                    opp?.username?.ifBlank { opp.displayName } ?: "Opponent"
                }
                GameMode.NEARBY_NETWORK -> {
                    val opp = realTimePlayers.firstOrNull { it.id != getLocalUid() }
                    opp?.username?.ifBlank { opp.displayName } ?: "Peer"
                }
            }
            when {
                isDraw -> "$myName vs $opponent Draw 🤝"
                won -> "$myName vs $opponent Won 🏆"
                isRunner -> "$myName vs $opponent Runner 🥈"
                else -> "$myName vs $opponent Lost 😑"
            }
        }

        val oppName = when (currentGameMode) {
            GameMode.AI_EASY -> "AI (Easy)"
            GameMode.AI_HARD -> "AI (Master)"
            GameMode.ONLINE_ROOM -> realTimePlayers.firstOrNull { it.id != getLocalUid() }?.displayName ?: "Online Opponent"
            GameMode.NEARBY_NETWORK -> realTimePlayers.firstOrNull { it.id != getLocalUid() }?.displayName ?: "Nearby Peer"
        }
        val modeStr = when (currentGameMode) {
            GameMode.AI_EASY -> "Single Player (Easy)"
            GameMode.AI_HARD -> "Single Player (Master)"
            GameMode.ONLINE_ROOM -> "Online Match"
            GameMode.NEARBY_NETWORK -> "Nearby Network (LAN)"
        }
        authRepository.recordMatch(
            mode = modeStr,
            opponentName = oppName,
            didWin = won,
            boardSize = boardSize,
            matchTitle = matchTitle,
            isDraw = isDraw
        )
        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
        if (currentMatchSeed != 0L) {
            onlineRoomSync.recordCompletedSeed(currentMatchSeed)
        }
        if (isHosting && (currentGameMode == GameMode.ONLINE_ROOM) && roomCode.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING", seed = 0L)
            }
        }
    }


    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // Dynamic Board Sizing Formula: 5x5 (classic/unchecked), or 5x5 (2p), 6x6 (3p), 7x7 (4p), 8x8 (5+p) or host-selected size
    fun calculateBoardSize(playerCount: Int): Int {
        return com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard, selectedDynamicGridSize, playerCount)
    }

    fun isGroupMatch(): Boolean {
        val activeCount = realTimePlayers.count { it.id.isNotBlank() }
        return (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) &&
                (activeCount > 2 || allPlayerBoards.size > 2)
    }

    fun getActiveParticipants(): List<Player> {
        val fromMatch = matchParticipants.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (fromMatch.isNotEmpty()) return fromMatch
        val fromRealTime = realTimePlayers.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (fromRealTime.isNotEmpty()) return fromRealTime
        val myUid = getLocalUid()
        val other = opponentPlayerId.takeIf { it.isNotBlank() } ?: "opponent"
        return listOf(
            Player(id = myUid, displayName = getPlayerDisplayName(), isHost = isHosting),
            Player(id = other, displayName = "Opponent", isHost = !isHosting)
        )
    }

    data class MatchOutcome(
        val isGameOver: Boolean,
        val didPlayerWin: Boolean,
        val isDraw: Boolean,
        val isRunner: Boolean = false,
        val winnerPlayerId: String = "",
        val winReason: String = ""
    )

    fun evaluateMatchOutcome(activePickerId: String = ""): MatchOutcome {
        val isGroup = isGroupMatch()
        val myUid = getLocalUid()

        if (!isGroup) {
            // STRICT 2-PLAYER OR AI MATCH LOGIC
            val oppId = opponentPlayerId.ifBlank {
                if (currentGameMode == GameMode.AI_EASY || currentGameMode == GameMode.AI_HARD) "ai_bot"
                else {
                    val other = matchParticipants.firstOrNull { !isPlayerMe(it) }?.id ?: realTimePlayers.firstOrNull { !isPlayerMe(it) }?.id
                    other ?: "opponent"
                }
            }
            val pWon = playerBoard.isBingo && !isPlayerDisconnected(myUid)
            val oWon = opponentBoard.isBingo && !isPlayerDisconnected(oppId)
            return when {
                pWon && oWon -> {
                    // Turn-based priority: Whoever picked the winning number on their turn wins!
                    if (isPlayerMe(activePickerId)) {
                        MatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false, isRunner = false, winnerPlayerId = myUid, winReason = "Turn Pick")
                    } else if (com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(activePickerId, oppId) || activePickerId.isNotBlank()) {
                        MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false, isRunner = true, winnerPlayerId = oppId, winReason = "Turn Pick")
                    } else {
                        MatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false, isRunner = false, winnerPlayerId = myUid, winReason = "Turn Pick")
                    }
                }
                pWon -> MatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false, isRunner = false, winnerPlayerId = myUid, winReason = "Bingo")
                oWon -> {
                    MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false, isRunner = false, winnerPlayerId = oppId, winReason = "Bingo")
                }
                else -> MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }
        } else {
            // 3+ PLAYERS GROUP MATCH LOGIC
            val effBoards = if (allPlayerBoards.containsKey(myUid)) allPlayerBoards else (allPlayerBoards + (myUid to playerBoard))
            val completedPlayers = effBoards.filter { it.value.isBingo }.keys
            if (completedPlayers.isEmpty()) {
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }

            val now = System.currentTimeMillis()
            fun isPlayerActive(playerId: String): Boolean {
                if (playerId.isBlank()) return false
                if (isPlayerMe(playerId)) return !isPlayerDisconnected(myUid)
                if (isPlayerDisconnected(playerId)) return false
                val lastHb = if (isUsingP2p) lanP2pSync.getLastDirectHeartbeat(playerId) else onlineRoomSync.getLastDirectHeartbeat(playerId)
                if (lastHb > 0L && (now - lastHb) >= 10_000L) {
                    markPlayerDisconnected(playerId)
                    return false
                }
                return true
            }

            val activeCompletedPlayers = completedPlayers.filter { isPlayerActive(it) }
            if (activeCompletedPlayers.isEmpty()) {
                // Inactive/disconnected players may have completed BINGO on this pick, but they are NOT active!
                // Do NOT end the game or declare them winner; active players must continue playing.
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }

            // Winner selection among ACTIVE players only:
            // 1. If activePickerId completed BINGO on their turn, they are the undisputed WINNER
            val matchingPickerId = activeCompletedPlayers.firstOrNull { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, activePickerId) }
            val (winnerId, winReason) = if (activePickerId.isNotBlank() && matchingPickerId != null) {
                matchingPickerId to "Turn Pick"
            } else if (activeCompletedPlayers.size == 1) {
                activeCompletedPlayers.first() to "Solo Bingo"
            } else {
                // Multiple completed players on someone else's pick!
                val maxLines = activeCompletedPlayers.maxOf { effBoards[it]?.completedLinesCount ?: 0 }
                val topCandidates = activeCompletedPlayers.filter { (effBoards[it]?.completedLinesCount ?: 0) == maxLines }
                if (topCandidates.size == 1) {
                    topCandidates.first() to "Line Count"
                } else {
                    // Tie in lines! Use Turn-Order Priority based on randomized match order
                    val turnOrderUids = if (randomizedTurnOrder.isNotEmpty()) {
                        randomizedTurnOrder.map { it.id }
                    } else {
                        val parts = if (matchParticipants.isNotEmpty()) matchParticipants else realTimePlayers
                        parts.map { it.id }
                    }
                    val bestByTurn = topCandidates.minByOrNull { candidateId ->
                        com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.calculateTurnDistance(
                            turnOrder = turnOrderUids,
                            fromPlayerId = activePickerId,
                            toPlayerId = candidateId
                        )
                    } ?: topCandidates.first()
                    bestByTurn to "Turn Priority"
                }
            }

            val isLocalWinner = isPlayerMe(winnerId) && !isPlayerDisconnected(myUid)
            val isLocalRunner = (!isLocalWinner && !isPlayerDisconnected(myUid) && (activeCompletedPlayers.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(myUid))))

            return MatchOutcome(
                isGameOver = true,
                didPlayerWin = isLocalWinner,
                isDraw = false,
                isRunner = isLocalRunner,
                winnerPlayerId = winnerId,
                winReason = winReason
            )
        }
    }

    fun calculateNextTurnPlayerId(currentPickerId: String): String {
        val myUid = getLocalUid()
        return when (currentGameMode) {
            GameMode.AI_EASY, GameMode.AI_HARD -> {
                if (currentPickerId == "ai_bot") myUid else "ai_bot"
            }
            GameMode.ONLINE_ROOM, GameMode.NEARBY_NETWORK -> {
                val candidatePlayers = (if (matchParticipants.isNotEmpty()) matchParticipants else realTimePlayers)
                com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.calculateNextTurnPlayerId(
                    allParticipants = candidatePlayers,
                    disconnectedPlayerIds = disconnectedPlayerIds.toSet(),
                    currentPickerId = currentPickerId,
                    fallbackPlayerId = myUid,
                    matchSeed = currentMatchSeed,
                    customTurnOrder = randomizedTurnOrder
                )
            }
        }
    }

    fun startNewGame(
        mode: GameMode,
        difficulty: AiDifficulty = AiDifficulty.EASY,
        size: Int = 5,
        firstTurnPlayerId: String? = null,
        hostSeed: Long? = null,
        incomingPlayersList: List<Player> = emptyList()
    ) {
        currentGameMode = mode
        currentAiDifficulty = difficulty
        boardSize = size
        
        val baseSeed = hostSeed ?: kotlin.random.Random.nextLong()
        currentMatchSeed = baseSeed
        val myUid = getLocalUid()
        val user = (authRepository.authState.value as? AuthState.Authenticated)?.user
        val myUsername = user?.username ?: ""
        val myDisplay = getPlayerDisplayName()

        if (mode == GameMode.ONLINE_ROOM || mode == GameMode.NEARBY_NETWORK) {
            // 1. Gather all participants from incoming packet, cached match participants, or real-time lobby
            val rawParticipants = (if (incomingPlayersList.isNotEmpty()) incomingPlayersList else matchParticipants.ifEmpty { realTimePlayers })
                .filter { it.id.isNotBlank() || it.username.isNotBlank() }

            // 2. Ensure local player is always present in the list
            val myExisting = rawParticipants.find { isPlayerMe(it) }
            val myPlayerObj = myExisting ?: Player(
                id = myUid,
                displayName = myDisplay,
                username = myUsername,
                isHost = isHosting
            )

            val allParticipants = (rawParticipants + myPlayerObj)
                .distinctBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                .sortedWith(
                    compareByDescending<Player> { it.isHost }
                        .thenBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                )
            matchParticipants = allParticipants

            // 3. Deterministic, collision-free board generation for every participant
            val newBoards = mutableMapOf<String, Board>()
            var myBoardGenerated: Board? = null

            allParticipants.forEachIndexed { index, p ->
                val pSeed = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolvePlayerBoardSeed(baseSeed, p, index)
                val b = engine.generateBoard(size, pSeed)
                newBoards[p.id] = b
                if (isPlayerMe(p)) {
                    myBoardGenerated = b
                }
            }

            // In case local player was not recognized in loop (should never happen), generate unique local board
            val finalPlayerBoard = myBoardGenerated ?: run {
                val mySeed = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolvePlayerBoardSeed(baseSeed, myPlayerObj, if (isHosting) 0 else 1)
                engine.generateBoard(size, mySeed)
            }
            playerBoard = finalPlayerBoard
            newBoards[myUid] = finalPlayerBoard
            allPlayerBoards = newBoards

            // Opponent board for 2-player or spotlight review fallback
            val otherPlayer = allParticipants.firstOrNull { !isPlayerMe(it) }
            opponentBoard = if (otherPlayer != null) {
                newBoards[otherPlayer.id]
                    ?: engine.generateBoard(size, com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolvePlayerBoardSeed(baseSeed, otherPlayer, 1))
            } else {
                engine.generateBoard(size, baseSeed + 99999L)
            }
        } else {
            // SINGLE PLAYER / AI MODE: mathematically distinct seeds
            opponentPlayerId = "ai_bot"
            val mySeed = baseSeed xor 0x11111111L
            val aiSeed = baseSeed xor 0x22222222L
            playerBoard = engine.generateBoard(size, mySeed)
            opponentBoard = engine.generateBoard(size, aiSeed)
            allPlayerBoards = mapOf(
                myUid to playerBoard,
                "ai_bot" to opponentBoard
            )
        }

        disconnectedPlayerIds.clear()
        consecutiveMissedTurns.clear()
        pickedNumbersHistory.clear()
        pickedByPlayerHistory.clear()
        matchChatHistory = emptyList()
        latestIncomingChatMessage = null
        isProcessingTurn = false
        isGameOver = false
        didPlayerWin = false
        isDrawMatch = false
        isRunnerMatch = false
        winnerPlayerId = ""

        val activePlayersList = matchParticipants.ifEmpty { realTimePlayers }
        val generatedOrder = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.generateDeterministicTurnOrder(
            activePlayersList,
            baseSeed
        )
        randomizedTurnOrder = generatedOrder

        val allParticipantIds = activePlayersList.map { it.id }.filter { it.isNotBlank() }
        onlineRoomSync.resetInGameHeartbeats(allParticipantIds)
        lanP2pSync.resetInGameHeartbeats(allParticipantIds)

        val firstTurnUid = if (!firstTurnPlayerId.isNullOrBlank()) {
            firstTurnPlayerId
        } else if (generatedOrder.isNotEmpty()) {
            generatedOrder.first().id
        } else if (mode == GameMode.ONLINE_ROOM || mode == GameMode.NEARBY_NETWORK) {
            val candidateUids = activePlayersList.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
            val effCandidates = if (candidateUids.size >= 2) candidateUids else {
                val other = activePlayersList.firstOrNull { it.id != myUid }?.id
                    ?: opponentPlayerId.takeIf { it.isNotBlank() }
                    ?: "opponent"
                listOf(myUid, other).sorted()
            }
            ManualBoardEngine.determineRandomFirstTurn(baseSeed, effCandidates)
        } else {
            myUid
        }
        currentTurnPlayerId = firstTurnUid
        isMyTurn = (currentTurnPlayerId == myUid)

        if (mode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
            com.bingo.multiplayer.domain.network.OngoingMatchStore.saveOngoingMatch(
                context = context,
                matchData = com.bingo.multiplayer.domain.network.OngoingMatchData(
                    roomCode = roomCode,
                    matchSeed = currentMatchSeed,
                    boardSize = boardSize,
                    isDynamicBoard = isDynamicBoard,
                    isManualBoard = isManualBoard,
                    isHost = isHosting,
                    participants = matchParticipants,
                    playerBoard = playerBoard,
                    opponentBoard = opponentBoard,
                    allPlayerBoards = allPlayerBoards,
                    pickedNumbers = emptyList(),
                    pickedByPlayers = emptyList(),
                    turnNumber = 1,
                    currentTurnPlayerId = currentTurnPlayerId,
                    randomizedTurnOrder = generatedOrder,
                    chatMessages = emptyList()
                )
            )
        }

        turnNumber = 1
        turnTimer = 30
        isGamePaused = false
        pausedByPlayerName = ""
        recentPick = null
        isGameOver = false
        didPlayerWin = false
        isDrawMatch = false
        isHostLeftGame = false
        wantsToPlayAgainPlayerName = null
        opponentDisconnectMessage = null
        opponentSurrenderMessage = null
        latestIncomingEmote = null
        latestIncomingEmoteScale = 1.0f
        latestIncomingEmoteTimestamp = 0L
        latestIncomingChatMessage = null

        navController.navigate(Screen.Game.route)
    }

    fun startCountdownAndInitiateTurn() {
        if (isStartingCountdown || countdownSeconds >= 0) return
        isStartingCountdown = true
        coroutineScope.launch {
            try {
                val activeList = getActiveParticipants()
                val generatedOrder = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.generateDeterministicTurnOrder(
                    activeList,
                    currentMatchSeed
                )
                randomizedTurnOrder = generatedOrder

                val candidateUids = activeList.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
                val myUid = getLocalUid()
                val effCandidates = if (candidateUids.size >= 2) candidateUids else {
                    val other = opponentPlayerId.takeIf { it.isNotBlank() } ?: "opponent"
                    listOf(myUid, other).distinct().sorted()
                }
                val firstTurnUid = generatedOrder.firstOrNull()?.id
                    ?: ManualBoardEngine.determineRandomFirstTurn(currentMatchSeed, effCandidates)
                val assignedPlayer = activeList.find { it.id == firstTurnUid }
                firstTurnPlayerName = if (firstTurnUid == myUid) "You" else (assignedPlayer?.displayName ?: "Opponent")

                for (s in 5 downTo 1) {
                    countdownSeconds = s
                    delay(1000L)
                }
                countdownSeconds = 0
                delay(300L)
                countdownSeconds = -1

                currentTurnPlayerId = firstTurnUid
                isMyTurn = (currentTurnPlayerId == myUid)
                turnNumber = 1
                turnTimer = 30
                isGamePaused = false
                isGameOver = false
                didPlayerWin = false
                isDrawMatch = false
                latestIncomingEmote = null
                latestIncomingEmoteScale = 1.0f
                latestIncomingEmoteTimestamp = 0L
                latestIncomingChatMessage = null
                matchChatHistory = emptyList()

                val allParticipantIds = activeList.map { it.id }.filter { it.isNotBlank() }
                onlineRoomSync.resetInGameHeartbeats(allParticipantIds)
                lanP2pSync.resetInGameHeartbeats(allParticipantIds)

                if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.saveOngoingMatch(
                        context = context,
                        matchData = com.bingo.multiplayer.domain.network.OngoingMatchData(
                            roomCode = roomCode,
                            matchSeed = currentMatchSeed,
                            boardSize = boardSize,
                            isDynamicBoard = isDynamicBoard,
                            isManualBoard = isManualBoard,
                            isHost = isHosting,
                            participants = matchParticipants,
                            playerBoard = playerBoard,
                            opponentBoard = opponentBoard,
                            allPlayerBoards = allPlayerBoards,
                            pickedNumbers = emptyList(),
                            pickedByPlayers = emptyList(),
                            turnNumber = 1,
                            currentTurnPlayerId = currentTurnPlayerId,
                            randomizedTurnOrder = generatedOrder,
                            chatMessages = emptyList()
                        )
                    )
                }

                navController.navigate(Screen.Game.route) {
                    popUpTo(Screen.ManualBoardDesign.route) { inclusive = true }
                }
            } finally {
                isStartingCountdown = false
            }
        }
    }

    fun executePick(
        number: Int,
        isOwnPick: Boolean,
        pickerId: String,
        shouldBroadcast: Boolean = false
    ) {
        if (isGameOver || isGamePaused) return
        if (isOwnPick && (!isMyTurn || isProcessingTurn)) return
        if (number > 0 && number in pickedNumbersHistory) return

        if (isOwnPick) {
            isProcessingTurn = true
        }

        try {
            val isTimeoutPass = (number <= 0)

            if (!isTimeoutPass) {
                consecutiveMissedTurns.remove(pickerId)
                markPlayerReconnected(pickerId)
                pickedNumbersHistory.add(number)
                pickedByPlayerHistory.add(pickerId)

                val updatedPlayer = engine.markCell(
                    board = playerBoard,
                    number = number,
                    pickedByPlayerId = pickerId,
                    isOwnPick = isOwnPick,
                    turnNumber = turnNumber
                )

                val updatedOpponent = engine.markCell(
                    board = opponentBoard,
                    number = number,
                    pickedByPlayerId = pickerId,
                    isOwnPick = (pickerId != getLocalUid()),
                    turnNumber = turnNumber
                )

                val pick = RecentPick(
                    number = number,
                    pickedByPlayerId = pickerId,
                    turnNumber = turnNumber
                )

                allPlayerBoards = allPlayerBoards.mapValues { entry ->
                    engine.markCell(
                        board = entry.value,
                        number = number,
                        pickedByPlayerId = pickerId,
                        isOwnPick = (pickerId == entry.key),
                        turnNumber = turnNumber
                    )
                }

                playerBoard = updatedPlayer
                opponentBoard = updatedOpponent
                recentPick = pick
            } else {
                recentPick = RecentPick(
                    number = -1,
                    pickedByPlayerId = pickerId,
                    turnNumber = turnNumber
                )
                val skippedName = if (isPlayerMe(pickerId)) "You" else (
                    matchParticipants.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, pickerId) }?.displayName
                        ?: realTimePlayers.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, pickerId) }?.displayName
                        ?: "Player"
                )
                val skipText = if (isPlayerMe(pickerId)) "⏳ You missed turn - skipping" else "⏳ $skippedName missed turn - skipping"
                val skipSysMsg = InGameChatMessage(
                    id = System.currentTimeMillis() + (0..1000).random(),
                    text = skipText,
                    isSelf = false,
                    senderName = null,
                    timestamp = System.currentTimeMillis(),
                    isSystemMessage = true
                )
                matchChatHistory = matchChatHistory + skipSysMsg
                latestIncomingChatMessage = skipSysMsg
                if (shouldBroadcast && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                    broadcastSystemChatMessage(skipText)
                }
            }

            val outcome = if (!isTimeoutPass) evaluateMatchOutcome(pickerId) else MatchOutcome(false, false, false)

            turnNumber += 1
            turnTimer = 30
            isGameOver = outcome.isGameOver

            val nextPlayerId = calculateNextTurnPlayerId(pickerId)
            currentTurnPlayerId = nextPlayerId

            if (outcome.isGameOver) {
                isDrawMatch = outcome.isDraw
                didPlayerWin = outcome.didPlayerWin
                isRunnerMatch = outcome.isRunner
                winnerPlayerId = outcome.winnerPlayerId
                recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
            } else {
                isMyTurn = (nextPlayerId == getLocalUid())
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                        context = context,
                        playerBoard = playerBoard,
                        opponentBoard = opponentBoard,
                        allPlayerBoards = allPlayerBoards,
                        pickedNumbers = pickedNumbersHistory.toList(),
                        pickedByPlayers = pickedByPlayerHistory.toList(),
                        turnNumber = turnNumber,
                        currentTurnPlayerId = nextPlayerId,
                        chatMessages = matchChatHistory
                    )
                }
            }

            // Broadcast to peer with full picked history to guarantee reconciliation
            if (shouldBroadcast && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                val currentBoardHash = engine.computeBoardHash(playerBoard)
                val runnerIds = if (outcome.isGameOver) {
                    if (isGroupMatch()) {
                        val effBoards = if (allPlayerBoards.containsKey(getLocalUid())) allPlayerBoards else (allPlayerBoards + (getLocalUid() to playerBoard))
                        effBoards.filter { it.value.isBingo && !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.key, outcome.winnerPlayerId) && !isPlayerDisconnected(it.key) }.keys.toList()
                    } else {
                        val effOppId = opponentPlayerId.ifBlank {
                            val other = matchParticipants.firstOrNull { !isPlayerMe(it) }?.id ?: realTimePlayers.firstOrNull { !isPlayerMe(it) }?.id
                            other ?: "opponent"
                        }
                        if (outcome.isRunner) {
                            listOf(if (outcome.winnerPlayerId == getLocalUid()) effOppId else getLocalUid()).filter { !isPlayerDisconnected(it) }
                        } else if (outcome.didPlayerWin && opponentBoard.isBingo && !isPlayerDisconnected(effOppId)) {
                            listOf(effOppId)
                        } else emptyList()
                    }
                } else emptyList()

                broadcastPacket(
                    RoomMessagePacket(
                        type = if (isTimeoutPass) "TURN_TIMEOUT" else "PICK_NUMBER",
                        number = number,
                        playerId = pickerId,
                        turnNumber = turnNumber,
                        pickedHistory = pickedNumbersHistory.toList(),
                        pickedByHistory = pickedByPlayerHistory.toList(),
                        boardHash = currentBoardHash,
                        currentTurnPlayerId = nextPlayerId,
                        seed = currentMatchSeed,
                        winnerPlayerId = if (outcome.isGameOver) outcome.winnerPlayerId else "",
                        winReason = if (outcome.isGameOver) outcome.winReason else "",
                        runnerPlayerIds = runnerIds
                    )
                )
                if (playerBoard.isBingo) {
                    val winPacket = RoomMessagePacket(
                        type = "BINGO_CLAIMED",
                        number = number,
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        turnNumber = turnNumber,
                        seed = currentMatchSeed,
                        pickedHistory = pickedNumbersHistory.toList(),
                        pickedByHistory = pickedByPlayerHistory.toList(),
                        boardHash = currentBoardHash,
                        winnerPlayerId = if (outcome.isGameOver) outcome.winnerPlayerId else getLocalUid(),
                        winReason = if (outcome.isGameOver) outcome.winReason else "Bingo Claimed",
                        runnerPlayerIds = runnerIds
                    )
                    broadcastPacket(winPacket)
                    coroutineScope.launch {
                        delay(120L)
                        broadcastPacket(winPacket)
                    }
                }
            }
        } finally {
            if (isOwnPick) {
                isProcessingTurn = false
            }
        }
    }

    // ── Active Game Heartbeat & State Reconciliation (Runs every 2.5s strictly during active gameplay on Game screen) ──
    LaunchedEffect(currentGameMode) {
        while (isActive) {
            delay(2500L)
            val activeRoute = navController.currentDestination?.route
            val inActiveGameScreen = (activeRoute == Screen.Game.route)
            if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) &&
                !isGameOver &&
                roomCode.isNotEmpty() &&
                currentMatchSeed != 0L &&
                inActiveGameScreen
            ) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_SYNC",
                        turnNumber = turnNumber,
                        pickedHistory = pickedNumbersHistory.toList(),
                        pickedByHistory = pickedByPlayerHistory.toList(),
                        boardHash = engine.computeBoardHash(playerBoard),
                        currentTurnPlayerId = currentTurnPlayerId,
                        playerId = getLocalUid(),
                        seed = currentMatchSeed,
                        senderInstanceId = onlineRoomSync.instanceId
                    )
                )
            }
        }
    }

    fun handleIncomingPacket(packet: RoomMessagePacket) {
        val myUid = getLocalUid()
        if (packet.senderInstanceId.isNotBlank()) {
            if (packet.senderInstanceId == onlineRoomSync.instanceId) {
                // Discard echo of packet sent by this exact local app instance
                return
            }
        } else if (packet.playerId.isNotBlank() && packet.playerId == myUid) {
            // Discard echoes of packets we sent ourselves.
            // However, if we are a guest and packet is START_GAME or PLAY_AGAIN from host,
            // do NOT discard even if account IDs happen to match on test devices.
            if (isHosting || (packet.type != "START_GAME" && packet.type != "PLAY_AGAIN")) {
                return
            }
        }

        if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
            opponentPlayerId = packet.playerId
        }

        when (packet.type) {
            "START_GAME" -> {
                if (!isHosting) {
                    val currentDest = navController.currentDestination?.route
                    val isInGame = currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route
                    val isCompleted = onlineRoomSync.isSeedCompleted(packet.seed)
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.shouldStartNewMatch(
                            isHost = false,
                            incomingSeed = packet.seed,
                            currentMatchSeed = currentMatchSeed,
                            isGameOver = isGameOver,
                            isCurrentlyInGame = isInGame,
                            isCompletedSeed = isCompleted
                        )) {
                        return
                    }
                    onlineRoomSync.recordStartedSeed(packet.seed)
                    pickedNumbersHistory.clear()
                    pickedByPlayerHistory.clear()
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        onlineRoomSync.updateLocalReadyStatus("IN_GAME")
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus("IN_GAME")
                    }
                    val activeList = (if (packet.players.isNotEmpty()) packet.players else realTimePlayers)
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedWith(compareByDescending<Player> { it.isHost }.thenBy { it.id })
                    matchParticipants = activeList
                    disconnectedPlayerIds.clear()
                    consecutiveMissedTurns.clear()
                    matchChatHistory = emptyList()
                    latestIncomingChatMessage = null
                    if (packet.isManualBoard) {
                        isManualBoard = true
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
                        allPlayerBoards = emptyMap()
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        pickedNumbersHistory.clear()
                        pickedByPlayerHistory.clear()
                        isProcessingTurn = false
                        turnNumber = 1
                        currentTurnPlayerId = ""
                        isMyTurn = false
                        turnTimer = 30
                        isGamePaused = false
                        pausedByPlayerName = ""
                        recentPick = null
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        wantsToPlayAgainPlayerName = null
                        opponentDisconnectMessage = null
                        opponentSurrenderMessage = null
                        latestIncomingEmote = null
                        latestIncomingEmoteScale = 1.0f
                        latestIncomingEmoteTimestamp = 0L
                        latestIncomingChatMessage = null
                        navController.navigate(Screen.ManualBoardDesign.route)
                    } else {
                        isManualBoard = false
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
                        isLocalBoardReady = true
                        isOpponentBoardReady = true
                        countdownSeconds = -1
                        startNewGame(
                            mode = if (isUsingP2p) GameMode.NEARBY_NETWORK else GameMode.ONLINE_ROOM,
                            difficulty = AiDifficulty.EASY,
                            size = packet.boardSize,
                            firstTurnPlayerId = packet.currentTurnPlayerId.takeIf { it.isNotBlank() },
                            hostSeed = packet.seed,
                            incomingPlayersList = packet.players
                        )
                    }
                }
            }

            "GO_TO_LOBBY" -> {
                if (!isHosting && currentGameMode == GameMode.NEARBY_NETWORK) {
                    val currentDest = navController.currentDestination?.route
                    if (currentDest != Screen.Lobby.route && currentDest != Screen.Game.route && currentDest != Screen.ManualBoardDesign.route) {
                        navController.navigate(Screen.Lobby.route) {
                            launchSingleTop = true
                        }
                    }
                }
            }

            "PLAY_AGAIN" -> {
                if (!isHosting && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                    val currentDest = navController.currentDestination?.route
                    val isInGame = currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route
                    val isCompleted = onlineRoomSync.isSeedCompleted(packet.seed)
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.shouldStartNewMatch(
                            isHost = false,
                            incomingSeed = packet.seed,
                            currentMatchSeed = currentMatchSeed,
                            isGameOver = isGameOver,
                            isCurrentlyInGame = isInGame,
                            isCompletedSeed = isCompleted
                        )) {
                        return
                    }
                    onlineRoomSync.recordStartedSeed(packet.seed)
                    pickedNumbersHistory.clear()
                    pickedByPlayerHistory.clear()
                    val activeList = (if (packet.players.isNotEmpty()) packet.players else realTimePlayers)
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedWith(compareByDescending<Player> { it.isHost }.thenBy { it.id })
                    matchParticipants = activeList
                    disconnectedPlayerIds.clear()
                    consecutiveMissedTurns.clear()
                    matchChatHistory = emptyList()
                    latestIncomingChatMessage = null
                    if (packet.isManualBoard || isManualBoard) {
                        isManualBoard = true
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
                        allPlayerBoards = emptyMap()
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        pickedNumbersHistory.clear()
                        pickedByPlayerHistory.clear()
                        isProcessingTurn = false
                        turnNumber = 1
                        currentTurnPlayerId = ""
                        isMyTurn = false
                        turnTimer = 30
                        isGamePaused = false
                        pausedByPlayerName = ""
                        recentPick = null
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        wantsToPlayAgainPlayerName = null
                        opponentDisconnectMessage = null
                        opponentSurrenderMessage = null
                        latestIncomingEmote = null
                        latestIncomingEmoteScale = 1.0f
                        latestIncomingEmoteTimestamp = 0L
                        latestIncomingChatMessage = null
                        navController.navigate(Screen.ManualBoardDesign.route)
                    } else {
                        isManualBoard = false
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
                        isLocalBoardReady = true
                        isOpponentBoardReady = true
                        countdownSeconds = -1
                        startNewGame(
                            mode = currentGameMode,
                            difficulty = AiDifficulty.EASY,
                            size = packet.boardSize,
                            firstTurnPlayerId = null,
                            hostSeed = packet.seed,
                            incomingPlayersList = packet.players
                        )
                    }
                }
            }

            "BOARD_READY" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.pickedHistory.isNotEmpty() && packet.playerId.isNotBlank()) {
                        val mBoard = ManualBoardEngine.buildBoard(packet.pickedHistory, boardSize)
                        allPlayerBoards = allPlayerBoards + (packet.playerId to mBoard)
                        if (packet.playerId != getLocalUid()) {
                            opponentBoard = mBoard
                            isOpponentBoardReady = true
                        }
                    }
                    val participants = getActiveParticipants()
                    val expectedUids = participants.map { it.id }.filter { it.isNotBlank() }.toSet()
                    val allReady = expectedUids.isNotEmpty() && expectedUids.all { allPlayerBoards.containsKey(it) }
                    if (isLocalBoardReady && allReady && countdownSeconds < 0) {
                        startCountdownAndInitiateTurn()
                    }
                }
            }

            "PICK_NUMBER" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                        markPlayerReconnected(packet.playerId)
                    }
                    var anyNewPick = false

                    // 1. Reconcile any missing numbers from packet's history
                    packet.pickedHistory.forEachIndexed { idx, num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            val origPickerId = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            val historicalTurnNumber = idx + 1
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPickerId)
                            val isMine = isPlayerMe(origPickerId)
                            val isOpponentMine = !isMine
                            playerBoard = engine.markCell(
                                board = playerBoard,
                                number = num,
                                pickedByPlayerId = origPickerId,
                                isOwnPick = isMine,
                                turnNumber = historicalTurnNumber
                            )
                            opponentBoard = engine.markCell(
                                board = opponentBoard,
                                number = num,
                                pickedByPlayerId = origPickerId,
                                isOwnPick = isOpponentMine,
                                turnNumber = historicalTurnNumber
                            )
                            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                                engine.markCell(
                                    board = entry.value,
                                    number = num,
                                    pickedByPlayerId = origPickerId,
                                    isOwnPick = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(origPickerId, entry.key),
                                    turnNumber = historicalTurnNumber
                                )
                            }
                            recentPick = RecentPick(num, origPickerId, historicalTurnNumber)
                            anyNewPick = true
                        }
                    }

                    // 2. Direct single pick fallback
                    val isExpectedPicker = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, currentTurnPlayerId) ||
                            (packet.isHost && packet.number <= 0) ||
                            currentTurnPlayerId.isBlank()
                    if (!isExpectedPicker && (packet.turnNumber < turnNumber)) {
                        // Discard stale out-of-turn packets from previous turns
                        return
                    }

                    if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                        pickedNumbersHistory.add(packet.number)
                        pickedByPlayerHistory.add(packet.playerId)
                        val isMine = isPlayerMe(packet.playerId)
                        val isOpponentMine = !isMine
                        playerBoard = engine.markCell(
                            board = playerBoard,
                            number = packet.number,
                            pickedByPlayerId = packet.playerId,
                            isOwnPick = isMine,
                            turnNumber = turnNumber
                        )
                        opponentBoard = engine.markCell(
                            board = opponentBoard,
                            number = packet.number,
                            pickedByPlayerId = packet.playerId,
                            isOwnPick = isOpponentMine,
                            turnNumber = turnNumber
                        )
                        allPlayerBoards = allPlayerBoards.mapValues { entry ->
                            engine.markCell(
                                board = entry.value,
                                number = packet.number,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, entry.key),
                                turnNumber = turnNumber
                            )
                        }
                        recentPick = RecentPick(packet.number, packet.playerId, turnNumber)
                        anyNewPick = true
                    }

                    // 3. Evaluate win conditions
                    if (!isGameOver) {
                        val outcome = if (packet.winnerPlayerId.isNotBlank() && !isPlayerDisconnected(packet.winnerPlayerId)) {
                            val isLocalWinner = isPlayerMe(packet.winnerPlayerId) && !isPlayerDisconnected(myUid)
                            val isLocalRunner = !isLocalWinner && !isPlayerDisconnected(myUid) && (packet.runnerPlayerIds.filter { !isPlayerDisconnected(it) }.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(myUid)))
                            MatchOutcome(
                                isGameOver = true,
                                didPlayerWin = isLocalWinner,
                                isDraw = false,
                                isRunner = isLocalRunner,
                                winnerPlayerId = packet.winnerPlayerId,
                                winReason = packet.winReason
                            )
                        } else {
                            evaluateMatchOutcome(packet.playerId)
                        }
                        if (outcome.isGameOver) {
                            isGameOver = true
                            isDrawMatch = outcome.isDraw
                            didPlayerWin = outcome.didPlayerWin
                            isRunnerMatch = outcome.isRunner
                            winnerPlayerId = outcome.winnerPlayerId
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                        } else {
                            if (packet.turnNumber > turnNumber || anyNewPick) {
                                turnNumber = maxOf(packet.turnNumber, turnNumber + (if (anyNewPick && packet.turnNumber <= turnNumber) 1 else 0))
                                turnTimer = 30
                                val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                                    packet.currentTurnPlayerId
                                } else {
                                    calculateNextTurnPlayerId(packet.playerId)
                                }
                                currentTurnPlayerId = nextId
                                isMyTurn = (currentTurnPlayerId == myUid)
                                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                                    com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                        context = context,
                                        playerBoard = playerBoard,
                                        opponentBoard = opponentBoard,
                                        allPlayerBoards = allPlayerBoards,
                                        pickedNumbers = pickedNumbersHistory.toList(),
                                        pickedByPlayers = pickedByPlayerHistory.toList(),
                                        turnNumber = turnNumber,
                                        currentTurnPlayerId = currentTurnPlayerId,
                                        chatMessages = matchChatHistory
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "TURN_TIMEOUT" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    val shouldAdvance = (packet.turnNumber > turnNumber) ||
                            (packet.turnNumber == turnNumber && packet.playerId.isNotBlank() && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, currentTurnPlayerId))
                    if (shouldAdvance) {
                        turnNumber = maxOf(packet.turnNumber, turnNumber + 1)
                        turnTimer = 30
                        val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                            packet.currentTurnPlayerId
                        } else {
                            calculateNextTurnPlayerId(packet.playerId)
                        }
                        currentTurnPlayerId = nextId
                        isMyTurn = (currentTurnPlayerId == myUid)
                        recentPick = RecentPick(number = -1, pickedByPlayerId = packet.playerId, turnNumber = turnNumber)

                        val skippedPlayerName = matchParticipants.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.displayName
                            ?: realTimePlayers.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.displayName
                            ?: "Player"
                        val skipText = "📢 Turn skipped ($skippedPlayerName reconnecting...)"
                        val alreadyHasMsg = matchChatHistory.any { it.text == skipText && (System.currentTimeMillis() - it.timestamp) < 4000L }
                        if (!alreadyHasMsg) {
                            val sysMsg = InGameChatMessage(
                                id = System.currentTimeMillis() + (0..1000).random(),
                                text = skipText,
                                isSelf = false,
                                senderName = null,
                                timestamp = System.currentTimeMillis(),
                                isSystemMessage = true
                            )
                            matchChatHistory = matchChatHistory + sysMsg
                            latestIncomingChatMessage = sysMsg
                        }
                        if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                context = context,
                                playerBoard = playerBoard,
                                opponentBoard = opponentBoard,
                                allPlayerBoards = allPlayerBoards,
                                pickedNumbers = pickedNumbersHistory.toList(),
                                pickedByPlayers = pickedByPlayerHistory.toList(),
                                turnNumber = turnNumber,
                                currentTurnPlayerId = currentTurnPlayerId,
                                chatMessages = matchChatHistory
                            )
                        }
                    }
                }
            }

            "EMOTE", "CHAT_PHRASE" -> {
                latestIncomingEmote = packet.displayName
                latestIncomingEmoteScale = if (packet.number > 0) packet.number / 100f else 1.0f
                latestIncomingEmoteTimestamp = packet.timestamp
            }

            "CHAT_MESSAGE" -> {
                val isSys = packet.username == "SYSTEM"
                val newMsg = InGameChatMessage(
                    id = packet.timestamp,
                    text = packet.displayName,
                    isSelf = false,
                    senderName = if (isSys) null else (packet.username.takeIf { it.isNotBlank() } ?: "Opponent"),
                    timestamp = packet.timestamp,
                    isSystemMessage = isSys
                )
                val alreadyPresent = matchChatHistory.any {
                    (it.text == newMsg.text || (isSys && it.text.contains("reconnected", ignoreCase = true) && newMsg.text.contains("reconnected", ignoreCase = true))) &&
                    kotlin.math.abs(System.currentTimeMillis() - it.timestamp) < 5000L
                }
                if (!alreadyPresent) {
                    latestIncomingChatMessage = newMsg
                    matchChatHistory = matchChatHistory + newMsg
                    if (!isGameOver && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                            context = context,
                            playerBoard = playerBoard,
                            opponentBoard = opponentBoard,
                            allPlayerBoards = allPlayerBoards,
                            pickedNumbers = pickedNumbersHistory.toList(),
                            pickedByPlayers = pickedByPlayerHistory.toList(),
                            turnNumber = turnNumber,
                            currentTurnPlayerId = currentTurnPlayerId,
                            chatMessages = matchChatHistory
                        )
                    }
                }
            }

            "REJOIN_GAME" -> {
                if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                    markPlayerReconnected(packet.playerId)
                    val joinedName = packet.displayName.ifBlank {
                        matchParticipants.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.displayName
                            ?: realTimePlayers.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.displayName
                            ?: packet.username.ifBlank { "A player" }
                    }
                    val reconnectedMsg = "🟢 $joinedName reconnected"
                    val alreadyHasMsg = matchChatHistory.any {
                        it.text.contains("reconnected", ignoreCase = true) &&
                        (it.text.contains(joinedName, ignoreCase = true) || it.text == reconnectedMsg) &&
                        (System.currentTimeMillis() - it.timestamp) < 5000L
                    }
                    if (!alreadyHasMsg) {
                        val sysMsg = InGameChatMessage(
                            id = System.currentTimeMillis() + (0..1000).random(),
                            text = reconnectedMsg,
                            isSelf = false,
                            senderName = null,
                            timestamp = System.currentTimeMillis(),
                            isSystemMessage = true
                        )
                        matchChatHistory = matchChatHistory + sysMsg
                        latestIncomingChatMessage = sysMsg
                    }
                    val hostUid = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: ""
                    val isHostSender = packet.isHost || (hostUid.isNotBlank() && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, hostUid))
                    if (isHostSender) {
                        isHostLeftGame = false
                        matchParticipants = matchParticipants.map {
                            if (com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId)) {
                                it.copy(isHost = true)
                            } else it
                        }
                    }
                    val isHostGone = isHostLeftGame || (hostUid.isNotBlank() && disconnectedPlayerIds.contains(hostUid))
                    val activeRemaining = (matchParticipants.ifEmpty { realTimePlayers }).filter { it.id.isNotBlank() && it.id !in disconnectedPlayerIds }
                    val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                    val isSeniorActivePeer = activeRemaining.firstOrNull { !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.id == myUid
                    if (isHosting || currentTurnPlayerId == myUid || isActingHost || isSeniorActivePeer) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "GAME_SYNC",
                                turnNumber = turnNumber,
                                pickedHistory = pickedNumbersHistory.toList(),
                                pickedByHistory = pickedByPlayerHistory.toList(),
                                currentTurnPlayerId = currentTurnPlayerId,
                                boardHash = engine.computeBoardHash(playerBoard),
                                seed = currentMatchSeed,
                                playerId = myUid
                            )
                        )
                    }
                }
            }

            "GAME_SYNC" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    var anyNewPick = false

                    // 1. Reconcile missing numbers from packet's history
                    packet.pickedHistory.forEachIndexed { idx, num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            val origPickerId = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            val historicalTurnNumber = idx + 1
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPickerId)
                            val isMine = isPlayerMe(origPickerId)
                            val isOpponentMine = !isMine
                            playerBoard = engine.markCell(
                                board = playerBoard,
                                number = num,
                                pickedByPlayerId = origPickerId,
                                isOwnPick = isMine,
                                turnNumber = historicalTurnNumber
                            )
                            opponentBoard = engine.markCell(
                                board = opponentBoard,
                                number = num,
                                pickedByPlayerId = origPickerId,
                                isOwnPick = isOpponentMine,
                                turnNumber = historicalTurnNumber
                            )
                            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                                engine.markCell(
                                    board = entry.value,
                                    number = num,
                                    pickedByPlayerId = origPickerId,
                                    isOwnPick = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(origPickerId, entry.key),
                                    turnNumber = historicalTurnNumber
                                )
                            }
                            recentPick = RecentPick(num, origPickerId, historicalTurnNumber)
                            anyNewPick = true
                        }
                    }

                    // 2. Evaluate win conditions (only if game is not already concluded)
                    if (!isGameOver) {
                        val lastPicker = pickedByPlayerHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                            ?: packet.pickedByHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                            ?: currentTurnPlayerId
                        val outcome = evaluateMatchOutcome(lastPicker)
                        if (outcome.isGameOver) {
                            isGameOver = true
                            isDrawMatch = outcome.isDraw
                            didPlayerWin = outcome.didPlayerWin
                            isRunnerMatch = outcome.isRunner
                            winnerPlayerId = outcome.winnerPlayerId
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                        } else if (packet.turnNumber > turnNumber || (anyNewPick && packet.turnNumber >= turnNumber)) {
                            // Strictly newer turn or reconciled missed turn! Reconcile turn authority
                            turnNumber = maxOf(packet.turnNumber, turnNumber + (if (anyNewPick && packet.turnNumber == turnNumber) 1 else 0))
                            turnTimer = 30
                            if (packet.currentTurnPlayerId.isNotBlank()) {
                                currentTurnPlayerId = packet.currentTurnPlayerId
                                isMyTurn = (currentTurnPlayerId == myUid)
                            }
                            if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                    context = context,
                                    playerBoard = playerBoard,
                                    opponentBoard = opponentBoard,
                                    allPlayerBoards = allPlayerBoards,
                                    pickedNumbers = pickedNumbersHistory.toList(),
                                    pickedByPlayers = pickedByPlayerHistory.toList(),
                                    turnNumber = turnNumber,
                                    currentTurnPlayerId = currentTurnPlayerId,
                                    chatMessages = matchChatHistory
                                )
                            }
                        }
                    }
                }
            }

            "GAME_PAUSED" -> {
                isGamePaused = true
                pausedByPlayerName = packet.displayName.ifBlank { "Opponent" }
            }

            "GAME_RESUMED" -> {
                isGamePaused = false
                pausedByPlayerName = ""
            }


            "PLAY_AGAIN_REQUEST" -> {
                if (isHosting) {
                    val requester = packet.displayName.ifBlank { "Opponent" }
                    wantsToPlayAgainPlayerName = requester
                    Toast.makeText(context, "🎮 $requester wants to play again!", Toast.LENGTH_LONG).show()
                }
            }

            "BINGO_CLAIMED", "GAME_OVER" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                        // Mark final winning number if provided
                        if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                            pickedNumbersHistory.add(packet.number)
                            pickedByPlayerHistory.add(packet.playerId)
                            playerBoard = engine.markCell(
                                board = playerBoard,
                                number = packet.number,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = false,
                                turnNumber = turnNumber
                            )
                            opponentBoard = engine.markCell(
                                board = opponentBoard,
                                number = packet.number,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = true,
                                turnNumber = turnNumber
                            )
                            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                                engine.markCell(
                                    board = entry.value,
                                    number = packet.number,
                                    pickedByPlayerId = packet.playerId,
                                    isOwnPick = (packet.playerId == entry.key),
                                    turnNumber = turnNumber
                                )
                            }

                            recentPick = RecentPick(packet.number, packet.playerId, turnNumber)
                        }

                        val effectiveWinner = if (packet.winnerPlayerId.isNotBlank() && !isPlayerDisconnected(packet.winnerPlayerId)) {
                            packet.winnerPlayerId
                        } else if (!isPlayerDisconnected(packet.playerId)) {
                            packet.playerId
                        } else ""

                        val fallbackPicker = pickedByPlayerHistory.lastOrNull()?.takeIf { it.isNotBlank() } ?: packet.playerId
                        val outcome = if (effectiveWinner.isNotBlank()) {
                            val isLocalWinner = isPlayerMe(effectiveWinner) && !isPlayerDisconnected(myUid)
                            val isLocalRunner = !isLocalWinner && !isPlayerDisconnected(myUid) && (packet.runnerPlayerIds.filter { !isPlayerDisconnected(it) }.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(myUid)))
                            MatchOutcome(
                                isGameOver = true,
                                didPlayerWin = isLocalWinner,
                                isDraw = false,
                                isRunner = isLocalRunner,
                                winnerPlayerId = effectiveWinner,
                                winReason = packet.winReason.ifBlank { "Bingo Claimed" }
                            )
                        } else {
                            evaluateMatchOutcome(fallbackPicker)
                        }
                        if (outcome.isGameOver) {
                            isGameOver = true
                            didPlayerWin = outcome.didPlayerWin
                            isDrawMatch = outcome.isDraw
                            isRunnerMatch = outcome.isRunner
                            winnerPlayerId = outcome.winnerPlayerId
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                        }
                    }
                }
            }

            "SURRENDER" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    isGameOver = true
                    didPlayerWin = true
                    val opponent = packet.displayName.ifBlank { "Opponent" }
                    recordFinishedMatch(true)
                    opponentSurrenderMessage = "$opponent surrendered the match! You win!"
                }
            }

            "PLAYER_DISCONNECTED" -> {
                if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                    val destinationRoute = navController.currentDestination?.route
                    val inGame = (destinationRoute == Screen.Game.route)
                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    markPlayerDisconnected(packet.playerId)

                    val hostUid = participants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: ""
                    val isHostSender = packet.isHost || (hostUid.isNotBlank() && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, hostUid))
                    if (isHostSender) {
                        isHostLeftGame = true
                    }

                    if (inGame && !isGameOver) {
                        val activeRemaining = participants.filter {
                            it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                        }

                        val leftName = packet.displayName.ifBlank { packet.username.ifBlank { "A player" } }
                        val leftMsg = if (isHostSender) "👑 $leftName (Host) disconnected (in lobby)" else "🔴 $leftName disconnected (in lobby)"
                        val alreadyHasMsg = matchChatHistory.any { it.text == leftMsg && (System.currentTimeMillis() - it.timestamp) < 5000L }
                        if (!alreadyHasMsg) {
                            val sysMsg = InGameChatMessage(
                                id = System.currentTimeMillis() + (0..1000).random(),
                                text = leftMsg,
                                isSelf = false,
                                senderName = null,
                                timestamp = System.currentTimeMillis(),
                                isSystemMessage = true
                            )
                            matchChatHistory = matchChatHistory + sysMsg
                            latestIncomingChatMessage = sysMsg
                        }

                        // If it was the departed player's turn, advance turn immediately so active peer doesn't wait
                        if (currentTurnPlayerId == packet.playerId) {
                            val misses = (consecutiveMissedTurns[packet.playerId] ?: 0) + 1
                            consecutiveMissedTurns[packet.playerId] = misses

                            if (activeRemaining.size <= 1 && misses >= 3) {
                                isGameOver = true
                                val wonByForfeit = activeRemaining.any { it.id == myUid } || participants.size <= 2
                                didPlayerWin = wonByForfeit
                                isDrawMatch = false
                                isRunnerMatch = false
                                recordFinishedMatch(wonByForfeit)
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                                opponentDisconnectMessage = "Opponent disconnected. You win by forfeit!"
                            } else {
                                val nextId = calculateNextTurnPlayerId(packet.playerId)
                                currentTurnPlayerId = nextId
                                turnNumber += 1
                                turnTimer = 30
                                isMyTurn = (currentTurnPlayerId == myUid)
                                val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                                val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                                val isCoordinator = isHosting || isActingHost || activeRemaining.size <= 2
                                if (isCoordinator) {
                                    val skipText = "📢 Turn skipped ($leftName in lobby...)"
                                    broadcastSystemChatMessage(skipText)
                                    broadcastPacket(
                                        RoomMessagePacket(
                                            type = "TURN_TIMEOUT",
                                            playerId = packet.playerId,
                                            turnNumber = turnNumber,
                                            currentTurnPlayerId = nextId,
                                            seed = currentMatchSeed,
                                            pickedHistory = pickedNumbersHistory.toList(),
                                            pickedByHistory = pickedByPlayerHistory.toList()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "LEAVE", "HOST_LEFT" -> {
                val destinationRoute = navController.currentDestination?.route
                val inGame = (destinationRoute == Screen.Game.route)
                val isSenderHost = packet.isHost || packet.type == "HOST_LEFT" ||
                        realTimePlayers.find { it.id == packet.playerId }?.isHost == true ||
                        matchParticipants.find { it.id == packet.playerId }?.isHost == true

                if (isSenderHost && !isHosting) {
                    val hostName = packet.displayName.ifBlank { packet.username.ifBlank { "Host" } }
                    if (inGame && !isGameOver) {
                        isHostLeftGame = true
                        val hostId = packet.playerId.ifBlank {
                            matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: ""
                        }
                        if (hostId.isNotBlank()) {
                            markPlayerDisconnected(hostId)
                        }
                        val participants = matchParticipants.ifEmpty { realTimePlayers }
                        val activeRemaining = participants.filter {
                            it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                        }
                        if (activeRemaining.size <= 1 || isUsingP2p) {
                            isGameOver = true
                            val wonByForfeit = if (isUsingP2p) false else (activeRemaining.any { it.id == myUid } || participants.size <= 2)
                            didPlayerWin = wonByForfeit
                            isDrawMatch = false
                            isRunnerMatch = false
                            recordFinishedMatch(wonByForfeit)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                            opponentDisconnectMessage = if (isUsingP2p) "Host disconnected. LAN match ended." else "All opponents left the game."
                        } else {
                            // Host left, but 2+ players remain! Match continues!
                            val hostMsg = "👑 $hostName (Host) left the game"
                            broadcastSystemChatMessage(hostMsg)
                            if (currentTurnPlayerId == hostId) {
                                val nextId = calculateNextTurnPlayerId(hostId)
                                currentTurnPlayerId = nextId
                                turnNumber += 1
                                turnTimer = 30
                                isMyTurn = (currentTurnPlayerId == myUid)
                                val isActingHost = activeRemaining.firstOrNull()?.id == myUid
                                if (isActingHost) {
                                    broadcastPacket(
                                        RoomMessagePacket(
                                            type = "TURN_TIMEOUT",
                                            playerId = hostId,
                                            turnNumber = turnNumber,
                                            currentTurnPlayerId = nextId,
                                            seed = currentMatchSeed,
                                            pickedHistory = pickedNumbersHistory.toList(),
                                            pickedByHistory = pickedByPlayerHistory.toList()
                                        )
                                    )
                                }
                            }
                        }
                    } else if (!inGame) {
                        Toast.makeText(context, "Host left the lobby.", Toast.LENGTH_LONG).show()
                        disconnectRoom()
                        val exitDest = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.MainMenu.route
                        navController.navigate(exitDest) {
                            popUpTo(exitDest) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    return@handleIncomingPacket
                }

                if (inGame && !isGameOver && packet.playerId.isNotBlank() && packet.playerId != myUid) {
                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    markPlayerDisconnected(packet.playerId)
                    val activeRemaining = participants.filter {
                        it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                    }

                    val leftName = packet.displayName.ifBlank { packet.username.ifBlank { "A player" } }
                    val leftMsg = "$leftName disconnected (reconnecting...)"
                    val alreadyHasMsg = matchChatHistory.any { it.text == leftMsg && (System.currentTimeMillis() - it.timestamp) < 5000L }
                    if (!alreadyHasMsg) {
                        val sysMsg = InGameChatMessage(
                            id = System.currentTimeMillis() + (0..1000).random(),
                            text = leftMsg,
                            isSelf = false,
                            senderName = null,
                            timestamp = System.currentTimeMillis(),
                            isSystemMessage = true
                        )
                        matchChatHistory = matchChatHistory + sysMsg
                        latestIncomingChatMessage = sysMsg
                    }

                    // If it was the departed player's turn, advance turn so the game continues smoothly
                    if (currentTurnPlayerId == packet.playerId) {
                        val nextId = calculateNextTurnPlayerId(packet.playerId)
                        currentTurnPlayerId = nextId
                        turnNumber += 1
                        turnTimer = 30
                        isMyTurn = (currentTurnPlayerId == myUid)
                        val hostUid = participants.find { it.isHost }?.id ?: ""
                        val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                        val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                        val isCoordinator = isHosting || isActingHost || activeRemaining.size <= 2
                        if (isCoordinator) {
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "TURN_TIMEOUT",
                                    playerId = packet.playerId,
                                    turnNumber = turnNumber,
                                    currentTurnPlayerId = nextId,
                                    seed = currentMatchSeed,
                                    pickedHistory = pickedNumbersHistory.toList(),
                                    pickedByHistory = pickedByPlayerHistory.toList()
                                )
                            )
                        }
                    }
                } else if (!inGame) {
                    val isHostSender = packet.isHost || packet.type == "HOST_LEFT" || realTimePlayers.find { it.id == packet.playerId }?.isHost == true
                    if (isHostSender && !isHosting) {
                        Toast.makeText(context, "Host left the lobby.", Toast.LENGTH_LONG).show()
                        disconnectRoom()
                        isHosting = false
                        val exitDest = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.MainMenu.route
                        navController.navigate(exitDest) {
                            popUpTo(exitDest) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            }

            "SETTINGS_UPDATE" -> {
                if (!isHosting) {
                    isManualBoard = packet.isManualBoard
                    isDynamicBoard = packet.isDynamicBoard
                    if (packet.boardSize in 5..10) {
                        boardSize = packet.boardSize
                        selectedDynamicGridSize = packet.boardSize
                    } else if (!packet.isDynamicBoard) {
                        boardSize = 5
                    }
                }
            }

            "LOBBY_EXTEND" -> {
                lobbyInactivityResetToken++
                Toast.makeText(context, "Lobby extended by 5 minutes", Toast.LENGTH_SHORT).show()
            }

            "LOBBY_EXPIRED" -> {
                Toast.makeText(context, "Lobby was expired", Toast.LENGTH_SHORT).show()
                disconnectRoom()
                isHosting = false
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }

            "SYNC_REQUEST" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "GAME_SYNC",
                            turnNumber = turnNumber,
                            pickedHistory = pickedNumbersHistory.toList(),
                            pickedByHistory = pickedByPlayerHistory.toList(),
                            boardHash = engine.computeBoardHash(playerBoard),
                            seed = currentMatchSeed,
                            currentTurnPlayerId = currentTurnPlayerId,
                            playerId = myUid
                        )
                    )
                }
            }

            "KICK_PLAYER" -> {
                val targetId = packet.targetPlayerId.ifBlank { packet.playerId }
                if (targetId == myUid) {
                    Toast.makeText(context, "You were removed from the lobby by the host.", Toast.LENGTH_LONG).show()
                    disconnectRoom()
                    isHosting = false
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        launch { onlineRoomSync.incomingPackets.collect { handleIncomingPacket(it) } }
        launch { lanP2pSync.incomingPackets.collect { handleIncomingPacket(it) } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BingoTheme.colors.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.AuthGate.route,
            modifier = Modifier
                .fillMaxSize()
                .background(BingoTheme.colors.background)
        ) {
        // 1. Splash / Auth Gate
        composable(
            route = Screen.AuthGate.route,
            exitTransition = {
                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(350)) +
                androidx.compose.animation.scaleOut(targetScale = 1.25f, animationSpec = androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        ) {
            AuthGateScreen(
                authRepository = authRepository,
                onNavigateToLogin = {
                    if (navController.currentDestination?.route == Screen.AuthGate.route) {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.AuthGate.route) { inclusive = true }
                        }
                    }
                },
                onNavigateToMenu = {
                    if (navController.currentDestination?.route == Screen.AuthGate.route) {
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.AuthGate.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 2. Strict Login Screen
        composable(Screen.Login.route) {
            LoginScreen(
                authRepository = authRepository,
                onLoginSuccess = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // 3. Main Container Screen (3 tabs: Home, Dashboard, Settings with swipe & floating pill)
        composable(
            route = Screen.MainMenu.route,
            enterTransition = {
                androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(350)) +
                androidx.compose.animation.scaleIn(initialScale = 0.94f, animationSpec = androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        ) {
            com.bingo.multiplayer.presentation.menu.MainContainerScreen(
                authRepository = authRepository,
                friendsRepository = friendsRepository,
                onPlayAi = { difficulty ->
                    startNewGame(
                        mode = if (difficulty == AiDifficulty.EASY) GameMode.AI_EASY else GameMode.AI_HARD,
                        difficulty = difficulty,
                        size = 5
                    )
                },
                onPlayOnline = {
                    navController.navigate(Screen.OnlineChoice.route)
                },
                onPlayNearbyNetwork = {
                    val perms = PermissionHelper.getNearbyAndNotificationPermissions()
                    if (PermissionHelper.hasPermissions(context, perms)) {
                        openNearbyChoice()
                    } else {
                        nearbyPermissionLauncher.launch(perms.toTypedArray())
                    }
                },
                onOpenDeveloperNote = {
                    navController.navigate(Screen.DeveloperNote.route)
                },
                onSignedOut = {
                    com.bingo.multiplayer.domain.network.PresenceManager.stopPresence()
                    disconnectRoom()
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onInviteFriendToMatch = { friend ->
                    val user = getEffectiveUser()
                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val myUser = user?.username?.ifBlank { myUid } ?: myUid
                    val myAvatar = getPlayerAvatarUrl()
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
                    val localHost = Player(
                        id = myUid,
                        displayName = myName,
                        username = user?.username ?: "",
                        isHost = true,
                        avatarUrl = myAvatar,
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    val fromUser = myUser
                    val fromName = myName
                    coroutineScope.launch {
                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.createRoom(roomCode, localHost, 5)
                        val success = com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                            targetUsername = friend.username,
                            invite = com.bingo.multiplayer.domain.network.GameInvite(
                                fromUsername = fromUser,
                                fromDisplayName = fromName,
                                fromAvatarUrl = myAvatar,
                                roomCode = roomCode
                            )
                        )
                        if (success) {
                            Toast.makeText(context, "Inviting @${friend.username} to room $roomCode...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invite dispatched to @${friend.username}!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode)
                },
                onRejoinMatch = { matchData ->
                    coroutineScope.launch {
                        if (matchData.roomCode.startsWith("LAN_")) {
                            Toast.makeText(context, "Nearby match has already ended.", Toast.LENGTH_SHORT).show()
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                            return@launch
                        }
                        val activeRoom = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.getRoom(matchData.roomCode)
                        if (activeRoom != null && activeRoom.status == "CLOSED") {
                            Toast.makeText(context, "This match has already ended.", Toast.LENGTH_SHORT).show()
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                            return@launch
                        }

                        Toast.makeText(context, "Rejoining Room ${matchData.roomCode}...", Toast.LENGTH_SHORT).show()
                        val user = (authRepository.authState.value as? AuthState.Authenticated)?.user
                        val myUid = getLocalUid()
                        val me = Player(
                            id = myUid,
                            displayName = getPlayerDisplayName(),
                            username = user?.username ?: "",
                            avatarUrl = getPlayerAvatarUrl(),
                            isHost = matchData.isHost,
                            gamesPlayed = user?.gamesPlayed ?: 0,
                            gamesWon = user?.gamesWon ?: 0,
                            currentStreak = user?.currentStreak ?: 0,
                            level = user?.level ?: 1
                        )
                        roomCode = matchData.roomCode
                        isHosting = matchData.isHost
                        if (matchData.isHost) {
                            isHostLeftGame = false
                        }
                        currentGameMode = GameMode.ONLINE_ROOM
                        boardSize = matchData.boardSize
                        isDynamicBoard = matchData.isDynamicBoard
                        isManualBoard = matchData.isManualBoard
                        currentMatchSeed = matchData.matchSeed
                        matchParticipants = matchData.participants
                        randomizedTurnOrder = matchData.randomizedTurnOrder
                        turnNumber = matchData.turnNumber
                        currentTurnPlayerId = matchData.currentTurnPlayerId
                        isMyTurn = (currentTurnPlayerId == myUid)
                        turnTimer = 30
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = ""
                        disconnectedPlayerIds.clear()
                        consecutiveMissedTurns.clear()
                        lastForegroundResumeTimestamp = System.currentTimeMillis()
                        val participantIds = matchData.participants.map { it.id }.filter { it.isNotBlank() }
                        onlineRoomSync.resetInGameHeartbeats(participantIds)

                        if (matchData.playerBoard != null) {
                            playerBoard = matchData.playerBoard
                            opponentBoard = matchData.opponentBoard ?: engine.generateBoard(matchData.boardSize)
                            allPlayerBoards = matchData.allPlayerBoards
                            pickedNumbersHistory.clear()
                            pickedNumbersHistory.addAll(matchData.pickedNumbers)
                            pickedByPlayerHistory.clear()
                            pickedByPlayerHistory.addAll(matchData.pickedByPlayers)
                        } else {
                            startNewGame(
                                mode = GameMode.ONLINE_ROOM,
                                size = matchData.boardSize,
                                hostSeed = matchData.matchSeed,
                                incomingPlayersList = matchData.participants
                            )
                        }

                        if (matchData.chatMessages.isNotEmpty()) {
                            matchChatHistory = matchData.chatMessages
                        }
                        if (matchData.pickedNumbers.isNotEmpty()) {
                            recentPick = RecentPick(
                                number = matchData.pickedNumbers.last(),
                                pickedByPlayerId = matchData.pickedByPlayers.lastOrNull() ?: "",
                                turnNumber = matchData.turnNumber
                            )
                        }

                        onlineRoomSync.connectToRoom(matchData.roomCode, me)

                        navController.navigate(Screen.Game.route)

                        delay(400L)
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "REJOIN_GAME",
                                playerId = myUid,
                                displayName = getPlayerDisplayName(),
                                username = user?.username ?: "",
                                isHost = matchData.isHost,
                                seed = matchData.matchSeed,
                                boardSize = matchData.boardSize,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SYNC_REQUEST",
                                playerId = myUid,
                                seed = matchData.matchSeed
                            )
                        )
                        val now = System.currentTimeMillis()
                        val localReconnected = InGameChatMessage(
                            id = now,
                            text = "🟢 ${getPlayerDisplayName()} reconnected",
                            isSelf = false,
                            senderName = null,
                            timestamp = now,
                            isSystemMessage = true
                        )
                        matchChatHistory = matchChatHistory + localReconnected
                        latestIncomingChatMessage = localReconnected
                    }
                },
                initialPage = 0
            )
        }

        // 3b. Settings Screen (opens MainContainer at page 2)
        composable(Screen.Settings.route) {
            com.bingo.multiplayer.presentation.menu.MainContainerScreen(
                authRepository = authRepository,
                friendsRepository = friendsRepository,
                onPlayAi = { difficulty ->
                    startNewGame(
                        mode = if (difficulty == AiDifficulty.EASY) GameMode.AI_EASY else GameMode.AI_HARD,
                        difficulty = difficulty,
                        size = 5
                    )
                },
                onPlayOnline = {
                    navController.navigate(Screen.OnlineChoice.route)
                },
                onPlayNearbyNetwork = {
                    val perms = PermissionHelper.getNearbyAndNotificationPermissions()
                    if (PermissionHelper.hasPermissions(context, perms)) {
                        openNearbyChoice()
                    } else {
                        nearbyPermissionLauncher.launch(perms.toTypedArray())
                    }
                },
                onOpenDeveloperNote = {
                    navController.navigate(Screen.DeveloperNote.route)
                },
                onSignedOut = {
                    com.bingo.multiplayer.domain.network.PresenceManager.stopPresence()
                    disconnectRoom()
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onInviteFriendToMatch = { friend ->
                    val user = getEffectiveUser()
                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val myUser = user?.username?.ifBlank { myUid } ?: myUid
                    val myAvatar = getPlayerAvatarUrl()
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
                    val localHost = Player(
                        id = myUid,
                        displayName = myName,
                        username = user?.username ?: "",
                        isHost = true,
                        avatarUrl = myAvatar,
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    val fromUser = myUser
                    val fromName = myName
                    coroutineScope.launch {
                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.createRoom(roomCode, localHost, 5)
                        val success = com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                            targetUsername = friend.username,
                            invite = com.bingo.multiplayer.domain.network.GameInvite(
                                fromUsername = fromUser,
                                fromDisplayName = fromName,
                                fromAvatarUrl = myAvatar,
                                roomCode = roomCode
                            )
                        )
                        if (success) {
                            Toast.makeText(context, "Inviting @${friend.username} to room $roomCode...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invite dispatched to @${friend.username}!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode)
                },
                initialPage = 2
            )
        }

        // 3c. Dashboard & Friends Social Screen (opens MainContainer at page 1)
        composable(Screen.Dashboard.route) {
            com.bingo.multiplayer.presentation.menu.MainContainerScreen(
                authRepository = authRepository,
                friendsRepository = friendsRepository,
                onPlayAi = { difficulty ->
                    startNewGame(
                        mode = if (difficulty == AiDifficulty.EASY) GameMode.AI_EASY else GameMode.AI_HARD,
                        difficulty = difficulty,
                        size = 5
                    )
                },
                onPlayOnline = {
                    navController.navigate(Screen.OnlineChoice.route)
                },
                onPlayNearbyNetwork = {
                    val perms = PermissionHelper.getNearbyAndNotificationPermissions()
                    if (PermissionHelper.hasPermissions(context, perms)) {
                        openNearbyChoice()
                    } else {
                        nearbyPermissionLauncher.launch(perms.toTypedArray())
                    }
                },
                onOpenDeveloperNote = {
                    navController.navigate(Screen.DeveloperNote.route)
                },
                onSignedOut = {
                    com.bingo.multiplayer.domain.network.PresenceManager.stopPresence()
                    disconnectRoom()
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onInviteFriendToMatch = { friend ->
                    val user = getEffectiveUser()
                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val myUser = user?.username?.ifBlank { myUid } ?: myUid
                    val myAvatar = getPlayerAvatarUrl()
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
                    val localHost = Player(
                        id = myUid,
                        displayName = myName,
                        username = user?.username ?: "",
                        isHost = true,
                        avatarUrl = myAvatar,
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    val fromUser = myUser
                    val fromName = myName
                    coroutineScope.launch {
                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.createRoom(roomCode, localHost, 5)
                        val success = com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                            targetUsername = friend.username,
                            invite = com.bingo.multiplayer.domain.network.GameInvite(
                                fromUsername = fromUser,
                                fromDisplayName = fromName,
                                fromAvatarUrl = myAvatar,
                                roomCode = roomCode
                            )
                        )
                        if (success) {
                            Toast.makeText(context, "Inviting @${friend.username} to room $roomCode...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invite dispatched to @${friend.username}!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode)
                },
                initialPage = 1
            )
        }

        // 3d. Developer Note Screen (Full-Screen Gratitude & Feedback)
        composable(Screen.DeveloperNote.route) {
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
            com.bingo.multiplayer.presentation.menu.DeveloperNoteScreen(
                currentUser = user,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // 4. Online Match Choice (Host or Join?)
        composable(Screen.OnlineChoice.route) {
            BackHandler {
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            OnlineMatchChoiceScreen(
                onHostGame = {
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
                    val effUser = getEffectiveUser()
                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val myAvatar = getPlayerAvatarUrl()
                    val localHost = Player(
                        id = myUid,
                        displayName = myName,
                        username = effUser?.username ?: "",
                        isHost = true,
                        avatarUrl = myAvatar,
                        gamesPlayed = effUser?.gamesPlayed ?: 0,
                        gamesWon = effUser?.gamesWon ?: 0,
                        currentStreak = effUser?.currentStreak ?: 0,
                        level = effUser?.level ?: 1,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    coroutineScope.launch {
                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.createRoom(roomCode, localHost, 5)
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route) {
                        popUpTo(Screen.OnlineChoice.route) { inclusive = true }
                    }
                },
                onJoinGame = {
                    navController.navigate(Screen.JoinRoom.route) {
                        popUpTo(Screen.OnlineChoice.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 4b. Join Room Screen (Enter code)
        composable(Screen.JoinRoom.route) {
            BackHandler {
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val user = getEffectiveUser()
            JoinRoomScreen(
                onJoinRoom = { code ->
                    val cleanCode = code.trim().uppercase()
                    val joinerVersion = System.currentTimeMillis()
                    val effUser = getEffectiveUser()
                    val myUid = getLocalUid()
                    val myName = getPlayerDisplayName()
                    val myAvatar = getPlayerAvatarUrl()
                    val localJoiner = Player(
                        id = myUid,
                        displayName = myName,
                        username = effUser?.username ?: "",
                        isHost = false,
                        avatarUrl = myAvatar,
                        gamesPlayed = effUser?.gamesPlayed ?: 0,
                        gamesWon = effUser?.gamesWon ?: 0,
                        currentStreak = effUser?.currentStreak ?: 0,
                        level = effUser?.level ?: 1,
                        lastSeenTimestamp = joinerVersion,
                        readyVersion = joinerVersion
                    )
                    when (val result = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.validateAndJoinRoom(cleanCode, localJoiner)) {
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.Success -> {
                            roomCode = cleanCode
                            currentGameMode = GameMode.ONLINE_ROOM
                            isUsingP2p = false
                            isHosting = false
                            isManualBoard = result.room.isManualBoard
                            isDynamicBoard = result.room.isDynamicBoard
                            val returnedPlayer = result.room.players.find { it.id == localJoiner.id }
                            val effJoiner = if (returnedPlayer != null) {
                                localJoiner.copy(readyVersion = returnedPlayer.readyVersion)
                            } else localJoiner
                            onlineRoomSync.connectToRoom(cleanCode, effJoiner, initialPlayers = result.room.players)
                            if (result.room.status == "PLAYING") {
                                val matchData = com.bingo.multiplayer.domain.network.OngoingMatchStore.getOngoingMatch(context)
                                if (matchData != null && matchData.roomCode == cleanCode && matchData.playerBoard != null) {
                                    boardSize = matchData.boardSize
                                    isHosting = matchData.isHost
                                    if (matchData.isHost) {
                                        isHostLeftGame = false
                                    }
                                    currentMatchSeed = matchData.matchSeed
                                    matchParticipants = matchData.participants
                                    randomizedTurnOrder = matchData.randomizedTurnOrder
                                    playerBoard = matchData.playerBoard
                                    opponentBoard = matchData.opponentBoard ?: engine.generateBoard(matchData.boardSize)
                                    allPlayerBoards = matchData.allPlayerBoards
                                    pickedNumbersHistory.clear()
                                    pickedNumbersHistory.addAll(matchData.pickedNumbers)
                                    pickedByPlayerHistory.clear()
                                    pickedByPlayerHistory.addAll(matchData.pickedByPlayers)
                                    turnNumber = matchData.turnNumber
                                    currentTurnPlayerId = matchData.currentTurnPlayerId
                                    isMyTurn = (currentTurnPlayerId == getLocalUid())
                                    turnTimer = 30
                                    isGameOver = false
                                    didPlayerWin = false
                                    isDrawMatch = false
                                    isRunnerMatch = false
                                    winnerPlayerId = ""
                                    disconnectedPlayerIds.clear()
                                    lastForegroundResumeTimestamp = System.currentTimeMillis()
                                    val participantIds = matchData.participants.map { it.id }.filter { it.isNotBlank() }
                                    onlineRoomSync.resetInGameHeartbeats(participantIds)
                                    if (matchData.chatMessages.isNotEmpty()) {
                                        matchChatHistory = matchData.chatMessages
                                    }
                                    if (matchData.pickedNumbers.isNotEmpty()) {
                                        recentPick = RecentPick(
                                            number = matchData.pickedNumbers.last(),
                                            pickedByPlayerId = matchData.pickedByPlayers.lastOrNull() ?: "",
                                            turnNumber = matchData.turnNumber
                                        )
                                    }
                                    navController.navigate(Screen.Game.route) {
                                        popUpTo(Screen.JoinRoom.route) { inclusive = true }
                                    }
                                    coroutineScope.launch {
                                        delay(400L)
                                        broadcastPacket(
                                            RoomMessagePacket(
                                                type = "REJOIN_GAME",
                                                playerId = getLocalUid(),
                                                displayName = getPlayerDisplayName(),
                                                username = user?.username ?: "",
                                                isHost = isHosting,
                                                seed = matchData.matchSeed,
                                                currentTurnPlayerId = matchData.currentTurnPlayerId,
                                                turnNumber = matchData.turnNumber
                                            )
                                        )
                                        delay(300L)
                                        broadcastPacket(
                                            RoomMessagePacket(
                                                type = "SYNC_REQUEST",
                                                playerId = getLocalUid(),
                                                turnNumber = matchData.turnNumber
                                            )
                                        )
                                        val now = System.currentTimeMillis()
                                        val localReconnected = InGameChatMessage(
                                            id = now,
                                            text = "🟢 ${getPlayerDisplayName()} reconnected",
                                            isSelf = false,
                                            senderName = null,
                                            timestamp = now,
                                            isSystemMessage = true
                                        )
                                        matchChatHistory = matchChatHistory + localReconnected
                                        latestIncomingChatMessage = localReconnected
                                    }
                                    return@JoinRoomScreen null
                                }
                            }
                            navController.navigate(Screen.Lobby.route) {
                                popUpTo(Screen.JoinRoom.route) { inclusive = true }
                            }
                            null
                        }
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.NotFound -> result.message
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.AlreadyFull -> result.message
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.AlreadyStarted -> result.message
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.Expired -> result.message
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.Error -> result.message
                    }
                },
                onBack = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 5. Lobby Screen with Real-Time Presence & Refresh
        composable(Screen.Lobby.route) {
            BackHandler {
                if (isHosting) {
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "HOST_LEFT",
                            playerId = getLocalUid(),
                            isHost = true
                        )
                    )
                    if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        coroutineScope.launch {
                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                        }
                    }
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                } else if (!isHosting) {
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        onlineRoomSync.updateLocalReadyStatus("LEFT_LOBBY")
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus("LEFT_LOBBY")
                        lanDiscovery.stopDiscovering()
                    }
                }
                disconnectRoom()
                isHosting = false
                joinedLanGame = null
                val backDest = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.MainMenu.route
                navController.navigate(backDest) {
                    popUpTo(backDest) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user ?: getEffectiveUser()
            val ongoingMatchData = com.bingo.multiplayer.domain.network.OngoingMatchStore.getOngoingMatch(context)
            val hasActiveOngoing = (ongoingMatchData != null && !isGameOver && (roomCode.isBlank() || ongoingMatchData.roomCode == roomCode))
            val isKnownHost = isHosting ||
                (onlineRoomSync.currentHostId != null && getLocalUid() == onlineRoomSync.currentHostId) ||
                realTimePlayers.any { it.isHost && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, getLocalUid()) }
            if (isKnownHost && !isHosting) {
                isHosting = true
            }
            LobbyScreen(
                roomCode = roomCode,
                players = realTimePlayers,
                isHost = isKnownHost,
                currentUser = user,
                currentUserId = getLocalUid(),
                friendsRepository = if (currentGameMode == GameMode.NEARBY_NETWORK) null else friendsRepository,
                isRefreshing = if (currentGameMode == GameMode.NEARBY_NETWORK) false else isRefreshing,
                inactivityResetToken = lobbyInactivityResetToken,
                isNearbyNetwork = (currentGameMode == GameMode.NEARBY_NETWORK),
                hasActiveMatch = hasActiveOngoing,
                onRejoinMatch = {
                    val data = ongoingMatchData ?: return@LobbyScreen
                    if (data.playerBoard == null) return@LobbyScreen
                    val myUid = getLocalUid()
                    val me = Player(
                        id = myUid,
                        displayName = getPlayerDisplayName(),
                        username = user?.username ?: "",
                        avatarUrl = getPlayerAvatarUrl(),
                        isHost = data.isHost,
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1
                    )
                    roomCode = data.roomCode
                    isHosting = data.isHost
                    if (data.isHost) {
                        isHostLeftGame = false
                    }
                    currentGameMode = GameMode.ONLINE_ROOM
                    playerBoard = data.playerBoard
                    opponentBoard = data.opponentBoard ?: engine.generateBoard(data.boardSize)
                    allPlayerBoards = data.allPlayerBoards
                    pickedNumbersHistory.clear()
                    pickedNumbersHistory.addAll(data.pickedNumbers)
                    pickedByPlayerHistory.clear()
                    pickedByPlayerHistory.addAll(data.pickedByPlayers)
                    turnNumber = data.turnNumber
                    currentTurnPlayerId = data.currentTurnPlayerId
                    isMyTurn = (currentTurnPlayerId == myUid)
                    currentMatchSeed = data.matchSeed
                    boardSize = data.boardSize
                    matchParticipants = data.participants
                    randomizedTurnOrder = data.randomizedTurnOrder
                    if (data.chatMessages.isNotEmpty()) {
                        matchChatHistory = data.chatMessages
                    }
                    if (data.pickedNumbers.isNotEmpty()) {
                        recentPick = RecentPick(
                            number = data.pickedNumbers.last(),
                            pickedByPlayerId = data.pickedByPlayers.lastOrNull() ?: "",
                            turnNumber = data.turnNumber
                        )
                    }
                    disconnectedPlayerIds.clear()
                    consecutiveMissedTurns.clear()
                    lastForegroundResumeTimestamp = System.currentTimeMillis()
                    val participantIds = (data.participants.ifEmpty { matchParticipants }).map { it.id }.filter { it.isNotBlank() }
                    onlineRoomSync.resetInGameHeartbeats(participantIds)

                    onlineRoomSync.connectToRoom(data.roomCode, me)

                    navController.navigate(Screen.Game.route)

                    coroutineScope.launch {
                        delay(100L)
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "REJOIN_GAME",
                                playerId = myUid,
                                displayName = getPlayerDisplayName(),
                                username = user?.username ?: "",
                                isHost = data.isHost,
                                seed = data.matchSeed,
                                turnNumber = turnNumber,
                                currentTurnPlayerId = currentTurnPlayerId,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        delay(200L)
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SYNC_REQUEST",
                                playerId = myUid,
                                seed = data.matchSeed,
                                turnNumber = turnNumber,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    val now = System.currentTimeMillis()
                    val localReconnected = InGameChatMessage(
                        id = now,
                        text = "🟢 ${getPlayerDisplayName()} reconnected",
                        isSelf = false,
                        senderName = null,
                        timestamp = now,
                        isSystemMessage = true
                    )
                    matchChatHistory = matchChatHistory + localReconnected
                    latestIncomingChatMessage = localReconnected
                },
                onExtendLobby = {
                    lobbyInactivityResetToken++
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "LOBBY_EXTEND",
                            playerId = getLocalUid()
                        )
                    )
                    if (isHosting && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        coroutineScope.launch {
                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.heartbeatRoom(roomCode)
                        }
                    }
                    Toast.makeText(context, "Lobby extended by 5 minutes", Toast.LENGTH_SHORT).show()
                },
                onExpireLobby = {
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "LOBBY_EXPIRED",
                            playerId = getLocalUid()
                        )
                    )
                    if (isHosting && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        coroutineScope.launch {
                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                        }
                    }
                    Toast.makeText(context, "Lobby was expired", Toast.LENGTH_SHORT).show()
                    disconnectRoom()
                    isHosting = false
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onRefresh = {
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        coroutineScope.launch {
                            onlineRoomSync.refreshNow()
                        }
                    }
                },
                onSearchPlayer = if (currentGameMode == GameMode.NEARBY_NETWORK) null else { username ->
                    authRepository.sessionManager.clearRegistryCache(username)
                    authRepository.sessionManager.searchPlayerByUsername(username, forceRefresh = true)
                },
                onToggleReady = { ready ->
                    val status = if (ready) "READY" else "NOT_READY"
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        onlineRoomSync.updateLocalReadyStatus(status)
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus(status)
                    }
                },
                onRemovePlayer = { targetId ->
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        onlineRoomSync.removePlayer(targetId)
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.removePlayer(targetId)
                    }
                },
                isManualBoard = isManualBoard,
                onManualBoardChange = { manual ->
                    isManualBoard = manual
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "SETTINGS_UPDATE",
                            playerId = getLocalUid(),
                            isManualBoard = manual,
                            isDynamicBoard = isDynamicBoard
                        )
                    )
                },
                isDynamicBoard = isDynamicBoard,
                onDynamicBoardChange = { dynamic ->
                    isDynamicBoard = dynamic
                    val effSize = if (dynamic) selectedDynamicGridSize else 5
                    boardSize = effSize
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "SETTINGS_UPDATE",
                            playerId = getLocalUid(),
                            isManualBoard = isManualBoard,
                            isDynamicBoard = dynamic,
                            boardSize = effSize
                        )
                    )
                },
                selectedDynamicGridSize = selectedDynamicGridSize,
                onDynamicGridSizeChange = { newSize ->
                    selectedDynamicGridSize = newSize
                    boardSize = newSize
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "SETTINGS_UPDATE",
                            playerId = getLocalUid(),
                            isManualBoard = isManualBoard,
                            isDynamicBoard = isDynamicBoard,
                            boardSize = newSize
                        )
                    )
                },
                onStartGame = {
                    val targetSize = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard, selectedDynamicGridSize, realTimePlayers.size)
                    val seed = Random.nextLong().let { if (it == 0L) 1L else it }
                    val myId = getLocalUid()
                    val manualMode = isManualBoard

                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        coroutineScope.launch {
                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(
                                roomCode = roomCode,
                                status = "PLAYING",
                                seed = seed,
                                isManualBoard = manualMode,
                                boardSize = targetSize,
                                isDynamicBoard = isDynamicBoard
                            )
                        }
                        onlineRoomSync.updateLocalReadyStatus("IN_GAME")
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus("IN_GAME")
                        lanDiscovery.stopBroadcasting()
                        lanDiscovery.stopDiscovering()
                    }

                    val activeList = realTimePlayers.filter { it.id.isNotBlank() }
                        .distinctBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                        .sortedWith(
                            compareByDescending<Player> { it.isHost }
                                .thenBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                        )
                    val sanitizedList = activeList.map { it.copy(avatarUrl = null) }
                    matchParticipants = sanitizedList
                    val allUids = sanitizedList.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
                    val candidateUids = if (allUids.isNotEmpty()) allUids else listOf(myId)
                    val chosenFirstTurnUid = ManualBoardEngine.determineRandomFirstTurn(seed, candidateUids)

                    val startPacket = RoomMessagePacket(
                        type = "START_GAME",
                        boardSize = targetSize,
                        seed = seed,
                        playerId = myId,
                        currentTurnPlayerId = chosenFirstTurnUid,
                        isManualBoard = manualMode,
                        isDynamicBoard = isDynamicBoard,
                        players = sanitizedList
                    )
                    broadcastPacket(startPacket)
                    coroutineScope.launch {
                        delay(150L)
                        broadcastPacket(startPacket)
                        delay(250L)
                        broadcastPacket(startPacket)
                    }

                    if (manualMode) {
                        boardSize = targetSize
                        currentMatchSeed = seed
                        allPlayerBoards = emptyMap()
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        pickedNumbersHistory.clear()
                        isProcessingTurn = false
                        turnNumber = 1
                        currentTurnPlayerId = ""
                        isMyTurn = false
                        turnTimer = 30
                        isGamePaused = false
                        pausedByPlayerName = ""
                        recentPick = null
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        wantsToPlayAgainPlayerName = null
                        opponentDisconnectMessage = null
                        opponentSurrenderMessage = null
                        matchChatHistory = emptyList()
                        latestIncomingChatMessage = null
                        navController.navigate(Screen.ManualBoardDesign.route)
                    } else {
                        boardSize = targetSize
                        startNewGame(
                            mode = if (isUsingP2p) GameMode.NEARBY_NETWORK else GameMode.ONLINE_ROOM,
                            difficulty = AiDifficulty.EASY,
                            size = targetSize,
                            firstTurnPlayerId = chosenFirstTurnUid,
                            hostSeed = seed,
                            incomingPlayersList = sanitizedList
                        )
                    }
                },
                onBack = {
                    if (isHosting) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "HOST_LEFT",
                                playerId = getLocalUid(),
                                isHost = true
                            )
                        )
                        if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                            coroutineScope.launch {
                                com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                            }
                        }
                        lanDiscovery.stopBroadcasting()
                        lanDiscovery.stopDiscovering()
                    } else if (!isHosting) {
                        if (currentGameMode == GameMode.ONLINE_ROOM) {
                            onlineRoomSync.updateLocalReadyStatus("LEFT_LOBBY")
                        } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                            lanP2pSync.updateLocalReadyStatus("LEFT_LOBBY")
                            lanDiscovery.stopDiscovering()
                        }
                    }
                    disconnectRoom()
                    isHosting = false
                    joinedLanGame = null
                    val backDest = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.MainMenu.route
                    navController.navigate(backDest) {
                        popUpTo(backDest) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 5a. Nearby Network Choice Screen (Host Game vs Join Game)
        composable(Screen.NearbyChoice.route) {
            BackHandler {
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            NearbyChoiceScreen(
                onHostGame = {
                    isNearbyHostMode = true
                    navController.navigate(Screen.NearbyLobby.route)
                },
                onJoinGame = {
                    isNearbyHostMode = false
                    navController.navigate(Screen.NearbyLobby.route)
                },
                onBack = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 5b. Nearby Network (LAN / Hotspot) Lobby (Zero Room Codes)
        composable(Screen.NearbyLobby.route) {
            BackHandler {
                lanDiscovery.stopBroadcasting()
                lanDiscovery.stopDiscovering()
                disconnectRoom()
                isHosting = false
                joinedLanGame = null
                navController.navigate(Screen.NearbyChoice.route) {
                    popUpTo(Screen.NearbyChoice.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
            DisposableEffect(Unit) {
                lanDiscovery.startDiscovering(getLocalUid())
                onDispose {
                    lanDiscovery.stopDiscovering()
                }
            }

            NearbyLobbyScreen(
                discoveredGames = discoveredGames,
                connectedPeers = realTimePlayers,
                isHosting = isHosting,
                isHostMode = isNearbyHostMode,
                joinedGame = joinedLanGame,
                currentRoomCode = roomCode,
                onStartBroadcasting = { selectedSize ->
                    isHosting = true
                    isUsingP2p = true
                    currentGameMode = GameMode.NEARBY_NETWORK
                    joinedLanGame = null
                    val internalCode = "LAN_${(1000..9999).random()}"
                    roomCode = internalCode
                    boardSize = selectedSize
                    val localHost = Player(
                        id = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        username = user?.username ?: "",
                        isHost = true,
                        avatarUrl = getPlayerAvatarUrl(),
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1,
                        lobbyReadyStatus = "READY",
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    
                    lanP2pSync.connectAsHost(localHost)
                    lanDiscovery.startBroadcasting(
                        host = localHost,
                        boardSize = selectedSize,
                        internalRoomCode = internalCode,
                        ssid = HotspotAndWifiManager.getHotspotName(context)
                    )
                },
                onStopBroadcasting = {
                    isHosting = false
                    lanDiscovery.stopBroadcasting()
                    disconnectRoom()
                },
                onJoinDiscoveredGame = { game ->
                    isHosting = false
                    isUsingP2p = true
                    currentGameMode = GameMode.NEARBY_NETWORK
                    val effectiveHostIp = game.hostIp.ifBlank { HotspotAndWifiManager.getGatewayIp(context) }
                    joinedLanGame = game.copy(hostIp = effectiveHostIp)
                    roomCode = game.roomCode
                    boardSize = game.boardSize
                    val localJoiner = Player(
                        id = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        username = user?.username ?: "",
                        isHost = false,
                        avatarUrl = getPlayerAvatarUrl(),
                        gamesPlayed = user?.gamesPlayed ?: 0,
                        gamesWon = user?.gamesWon ?: 0,
                        currentStreak = user?.currentStreak ?: 0,
                        level = user?.level ?: 1,
                        lobbyReadyStatus = "READY",
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    lanP2pSync.connectAsClient(
                        hostIp = effectiveHostIp,
                        clientPlayer = localJoiner,
                        port = game.port,
                        fallbackIp = HotspotAndWifiManager.getGatewayIp(context)
                    )
                    if (game.isInLobby) {
                        navController.navigate(Screen.Lobby.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onLeaveJoinedGame = {
                    joinedLanGame = null
                    disconnectRoom()
                },
                onGoToLobby = {
                    lanP2pSync.isHostInLobby = true
                    lanDiscovery.updateLobbyState(true)
                    val goToLobbyPacket = RoomMessagePacket(
                        type = "GO_TO_LOBBY",
                        playerId = getLocalUid()
                    )
                    broadcastPacket(goToLobbyPacket)
                    coroutineScope.launch {
                        delay(150L)
                        broadcastPacket(goToLobbyPacket)
                        delay(250L)
                        broadcastPacket(goToLobbyPacket)
                    }
                    navController.navigate(Screen.Lobby.route) {
                        launchSingleTop = true
                    }
                },
                onBack = {
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    disconnectRoom()
                    isHosting = false
                    joinedLanGame = null
                    navController.navigate(Screen.NearbyChoice.route) {
                        popUpTo(Screen.NearbyChoice.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 5c. Manual Board Design Screen
        composable(Screen.ManualBoardDesign.route) {
            val opponentPlayer = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                realTimePlayers.firstOrNull { it.id != getLocalUid() }
            } else null

            val opponentDisplayName = when (currentGameMode) {
                GameMode.AI_EASY -> "AI (Easy)"
                GameMode.AI_HARD -> "AI (Master)"
                GameMode.ONLINE_ROOM -> opponentPlayer?.displayName ?: "Opponent"
                GameMode.NEARBY_NETWORK -> opponentPlayer?.displayName ?: "Nearby Peer"
            }

            val participants = getActiveParticipants()
            val expectedUids = participants.map { it.id }.filter { it.isNotBlank() }.toSet()
            val readyCount = expectedUids.count { allPlayerBoards.containsKey(it) }
            val totalCount = expectedUids.size.coerceAtLeast(2)
            val allReady = expectedUids.isNotEmpty() && expectedUids.all { allPlayerBoards.containsKey(it) }

            ManualBoardDesignScreen(
                boardSize = boardSize,
                roomCode = roomCode,
                opponentName = opponentDisplayName,
                isWaitingForOpponent = isLocalBoardReady && !allReady,
                readyPlayersCount = readyCount,
                totalPlayersCount = totalCount,
                countdownSeconds = countdownSeconds,
                firstTurnPlayerName = firstTurnPlayerName,
                onBoardReady = { boardNumbers ->
                    val mBoard = ManualBoardEngine.buildBoard(boardNumbers, boardSize)
                    playerBoard = mBoard
                    val myUid = getLocalUid()
                    var updatedBoards = allPlayerBoards + (myUid to mBoard)

                    if (currentGameMode == GameMode.AI_EASY || currentGameMode == GameMode.AI_HARD) {
                        val aiBoard = engine.generateBoard(boardSize, currentMatchSeed + 1)
                        opponentBoard = aiBoard
                        updatedBoards = updatedBoards + ("ai" to aiBoard)
                    }

                    allPlayerBoards = updatedBoards
                    isLocalBoardReady = true

                    val readyPacket = RoomMessagePacket(
                        type = "BOARD_READY",
                        playerId = myUid,
                        seed = currentMatchSeed,
                        pickedHistory = boardNumbers
                    )
                    broadcastPacket(readyPacket)
                    coroutineScope.launch {
                        delay(150L)
                        broadcastPacket(readyPacket)
                        delay(250L)
                        broadcastPacket(readyPacket)
                    }

                    val curExpected = getActiveParticipants().map { it.id }.filter { it.isNotBlank() }.toSet()
                    val curAllReady = (currentGameMode == GameMode.AI_EASY || currentGameMode == GameMode.AI_HARD) ||
                            (curExpected.isNotEmpty() && curExpected.all { updatedBoards.containsKey(it) })

                    if (curAllReady && countdownSeconds < 0) {
                        startCountdownAndInitiateTurn()
                    } else {
                        coroutineScope.launch {
                            while (isActive && isLocalBoardReady && countdownSeconds < 0) {
                                val latestExpected = getActiveParticipants().map { it.id }.filter { it.isNotBlank() }.toSet()
                                if (latestExpected.isNotEmpty() && latestExpected.all { allPlayerBoards.containsKey(it) }) {
                                    break
                                }
                                delay(1500L)
                                broadcastPacket(readyPacket)
                            }
                        }
                    }
                },
                onLeave = {
                    if (isHosting && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "HOST_LEFT",
                                playerId = getLocalUid(),
                                isHost = true
                            )
                        )
                        coroutineScope.launch {
                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                        }
                    } else if (!isHosting) {
                        if (currentGameMode == GameMode.ONLINE_ROOM) {
                            onlineRoomSync.updateLocalReadyStatus("LEFT_LOBBY")
                        } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                            lanP2pSync.updateLocalReadyStatus("LEFT_LOBBY")
                        }
                    }
                    disconnectRoom()
                    isHosting = false
                    matchParticipants = emptyList()
                    allPlayerBoards = emptyMap()
                    isLocalBoardReady = false
                    isOpponentBoardReady = false
                    isStartingCountdown = false
                    countdownSeconds = -1
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        // 6. Active Game Screen
        composable(Screen.Game.route) {
            BackHandler {
                if (!isGameOver) {
                    showLeaveMatchDialog = true
                } else {
                    isGameOver = false
                    didPlayerWin = false
                    isDrawMatch = false
                    if (currentMatchSeed != 0L) {
                        onlineRoomSync.recordCompletedSeed(currentMatchSeed)
                    }
                    currentMatchSeed = 0L
                    pickedNumbersHistory.clear()
                    pickedByPlayerHistory.clear()
                    isProcessingTurn = false
                    isMyTurn = false
                    turnNumber = 1
                    turnTimer = 30
                    isGamePaused = false
                    pausedByPlayerName = ""
                    recentPick = null
                    wantsToPlayAgainPlayerName = null
                    opponentDisconnectMessage = null
                    opponentSurrenderMessage = null
                    isLocalBoardReady = false
                    isOpponentBoardReady = false
                    countdownSeconds = -1
                    firstTurnPlayerName = ""
                    onlineRoomSync.resetMatchSession()
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        val targetRoute = Screen.Lobby.route
                        if (isHosting) {
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING", seed = 0L)
                                }
                                onlineRoomSync.updateLocalReadyStatus("READY")
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "ROOM_STATE",
                                        playerId = getLocalUid(),
                                        displayName = getPlayerDisplayName(),
                                        username = currentAuthUser?.username ?: "",
                                        isHost = true,
                                        readyStatus = "READY"
                                    )
                                )
                            } else {
                                lanP2pSync.updateLocalReadyStatus("READY")
                            }
                        } else {
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                onlineRoomSync.updateLocalReadyStatus("NOT_READY")
                            } else {
                                lanP2pSync.updateLocalReadyStatus("NOT_READY")
                            }
                        }
                        navController.navigate(targetRoute) {
                            popUpTo(targetRoute) { inclusive = true }
                        }
                    } else {
                        disconnectRoom()
                        lanDiscovery.stopBroadcasting()
                        lanDiscovery.stopDiscovering()
                        isHosting = false
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.MainMenu.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            }

            // AI Turn Handler (offline / single player modes)
            LaunchedEffect(turnNumber, isMyTurn, isGameOver, isGamePaused) {
                if (!isMyTurn && !isGameOver && !isGamePaused &&
                    (currentGameMode == GameMode.AI_EASY || currentGameMode == GameMode.AI_HARD)
                ) {
                    delay(700)
                    if (!isGamePaused && !isGameOver && !isMyTurn) {
                        val aiPick = aiPlayer.decideNextMove(
                            aiBoard = opponentBoard,
                            opponentBoard = playerBoard,
                            difficulty = currentAiDifficulty
                        )
                        executePick(number = aiPick, isOwnPick = false, pickerId = "ai_bot")
                    }
                }
            }

            // Turn Countdown Timer
            LaunchedEffect(turnNumber, isMyTurn, isGameOver, isGamePaused) {
                if (!isGameOver && !isGamePaused) {
                    turnTimer = 30
                    var skippedDueToDisconnect = false
                    while (turnTimer > 0 && !isGamePaused && !isGameOver) {
                        delay(1000L)
                        if (!isGamePaused && !isGameOver) {
                            turnTimer -= 1
                            // Check if current active player's connection is dead (> 10s of silence)
                            val isForeground = com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value
                            val now = System.currentTimeMillis()
                            val isWithinResumeGracePeriod = (now - lastForegroundResumeTimestamp) < 12_000L

                            if (isForeground && !isWithinResumeGracePeriod && !isMyTurn && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                                val activePicker = currentTurnPlayerId.ifBlank {
                                    val activeList = if (isUsingP2p) lanP2pSync.players.value else onlineRoomSync.players.value
                                    activeList.firstOrNull { it.id != getLocalUid() }?.id
                                }
                                if (!activePicker.isNullOrBlank()) {
                                    val lastHeartbeat = if (isUsingP2p) {
                                        lanP2pSync.getLastDirectHeartbeat(activePicker)
                                    } else {
                                        onlineRoomSync.getLastDirectHeartbeat(activePicker)
                                    }
                                    // If picker already in disconnected list (fast 2s skip) or heartbeat timed out >= 15s
                                    if (isPlayerDisconnected(activePicker) || (lastHeartbeat > 0L && (now - lastHeartbeat) >= 15_000L)) {
                                        val maxWaitSeconds = if (isPlayerDisconnected(activePicker)) 28 else 15
                                        if (turnTimer <= maxWaitSeconds) {
                                            skippedDueToDisconnect = true
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (skippedDueToDisconnect && !isGameOver && !isGamePaused) {
                        val activeList = if (isUsingP2p) lanP2pSync.players.value else onlineRoomSync.players.value
                        val activePicker = currentTurnPlayerId.ifBlank {
                            activeList.firstOrNull { it.id != getLocalUid() }?.id ?: "opponent"
                        }
                        markPlayerDisconnected(activePicker)

                        val participants = matchParticipants.ifEmpty { realTimePlayers }
                        val activeRemaining = participants.filter {
                            it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                        }

                        val misses = (consecutiveMissedTurns[activePicker] ?: 0) + 1
                        consecutiveMissedTurns[activePicker] = misses

                        if (activeRemaining.size <= 1 && misses >= 3) {
                            // Turn skipping stops: opponent missed 3 consecutive turns without reconnecting
                            isGameOver = true
                            val wonByForfeit = activeRemaining.any { it.id == getLocalUid() } || participants.size <= 2
                            didPlayerWin = wonByForfeit
                            isDrawMatch = false
                            isRunnerMatch = false
                            recordFinishedMatch(wonByForfeit)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                            opponentDisconnectMessage = "Opponent disconnected. You win by forfeit!"
                        } else {
                            val hostUid = activeList.find { it.isHost }?.id ?: ""
                            val isHostDisconnected = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(activePicker, hostUid)
                            val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                            val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == getLocalUid()
                            val isAuthorizedSkipper = isHosting || isHostDisconnected || isActingHost || activeRemaining.size <= 2
                            if (isAuthorizedSkipper) {
                                val pickerName = activeList.find { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, activePicker) }?.displayName ?: "Player"
                                val skipText = "📢 Turn skipped ($pickerName reconnecting...)"
                                broadcastSystemChatMessage(skipText)
                                executePick(
                                    number = -1,
                                    isOwnPick = false,
                                    pickerId = activePicker,
                                    shouldBroadcast = true
                                )
                            }
                        }
                    } else if (isMyTurn && !isGameOver && !isGamePaused && turnTimer == 0) {
                        // Pass the turn cleanly with no auto-picked number
                        executePick(
                            number = -1,
                            isOwnPick = true,
                            pickerId = getLocalUid(),
                            shouldBroadcast = true
                        )
                    } else if (!isMyTurn && !isGameOver && !isGamePaused && turnTimer == 0) {
                        // Fail-safe grace period: If peer missed their turn or packet was lost,
                        // only host or acting host rotates turn so peers don't skip redundantly
                        delay(2000L)
                        if (!isMyTurn && !isGameOver && !isGamePaused && turnTimer == 0) {
                            val activeList = if (isUsingP2p) lanP2pSync.players.value else onlineRoomSync.players.value
                            val participants = matchParticipants.ifEmpty { realTimePlayers }
                            val hostUid = activeList.find { it.isHost }?.id ?: ""
                            val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                            val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                            val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == getLocalUid()
                            if (isHosting || isActingHost || activeRemaining.size <= 2) {
                                val activePicker = currentTurnPlayerId.ifBlank {
                                    activeList.firstOrNull { it.id != getLocalUid() }?.id
                                        ?: opponentPlayerId.takeIf { it.isNotBlank() }
                                        ?: "opponent"
                                }
                                executePick(
                                    number = -1,
                                    isOwnPick = false,
                                    pickerId = activePicker,
                                    shouldBroadcast = true
                                )
                            }
                        }
                    }
                }
            }

            val opponentPlayer = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                realTimePlayers.firstOrNull { it.id != getLocalUid() }
            } else null

            val opponentDisplayName = when (currentGameMode) {
                GameMode.AI_EASY -> "AI (Easy)"
                GameMode.AI_HARD -> "AI (Master)"
                GameMode.ONLINE_ROOM -> opponentPlayer?.displayName ?: "Opponent"
                GameMode.NEARBY_NETWORK -> opponentPlayer?.displayName ?: "Nearby Peer"
            }
            val opponentAvatarUrl = opponentPlayer?.avatarUrl
            val opponentUsername = opponentPlayer?.username?.ifBlank { opponentPlayer.displayName } ?: opponentPlayer?.displayName
            val myAvatarUrl = getPlayerAvatarUrl()
            val myUsername = currentAuthUser?.username
            val myDisplayName = getPlayerDisplayName()

            val isCurrentHost = (currentGameMode != GameMode.ONLINE_ROOM) || isHosting
            val realtimeNetworkPing by com.bingo.multiplayer.domain.network.NetworkPingMonitor.pingMs.collectAsState()
            val realtimeOnlinePing by onlineRoomSync.pingMs.collectAsState()
            val realtimeLanPing by lanP2pSync.measuredPingMs.collectAsState()
            val currentPing = when {
                currentGameMode == GameMode.NEARBY_NETWORK -> {
                    if (realtimeLanPing > 0L) realtimeLanPing else 4L
                }
                currentGameMode == GameMode.ONLINE_ROOM && realtimeOnlinePing > 0L -> realtimeOnlinePing
                realtimeNetworkPing > 0L -> realtimeNetworkPing
                else -> 28L
            }

            val playerSummaryKeys = remember(realTimePlayers) {
                realTimePlayers.map { "${it.id}_${it.displayName}_${it.avatarUrl}_${it.isHost}" }
            }
            val gamePlayers = remember(playerSummaryKeys, currentGameMode, opponentDisplayName, randomizedTurnOrder) {
                when (currentGameMode) {
                    GameMode.ONLINE_ROOM, GameMode.NEARBY_NETWORK -> {
                        if (randomizedTurnOrder.isNotEmpty()) {
                            randomizedTurnOrder.map { rotP ->
                                realTimePlayers.find { it.id == rotP.id } ?: rotP
                            }
                        } else {
                            val filtered = realTimePlayers.filter { it.id.isNotBlank() }
                                .distinctBy { it.id }
                                .sortedWith(compareByDescending<Player> { it.isHost }.thenBy { it.id })
                            if (filtered.isNotEmpty()) {
                                filtered
                            } else {
                                listOf(
                                    Player(id = getLocalUid(), displayName = getPlayerDisplayName(), avatarUrl = getPlayerAvatarUrl(), username = currentAuthUser?.username ?: ""),
                                    Player(id = opponentPlayerId.ifBlank { "opponent" }, displayName = opponentDisplayName, avatarUrl = opponentAvatarUrl, username = opponentUsername ?: "")
                                )
                            }
                        }
                    }
                    GameMode.AI_EASY, GameMode.AI_HARD -> {
                        listOf(
                            Player(id = getLocalUid(), displayName = getPlayerDisplayName(), avatarUrl = getPlayerAvatarUrl(), username = currentAuthUser?.username ?: ""),
                            Player(id = "ai_bot", displayName = opponentDisplayName, isAi = true)
                        )
                    }
                }
            }

            GameScreen(
                board = playerBoard,
                opponentBoard = opponentBoard,
                allPlayerBoards = allPlayerBoards,
                players = gamePlayers,
                currentTurnPlayerId = currentTurnPlayerId,
                disconnectedPlayerIds = disconnectedPlayerIds.toList(),
                isMyTurn = isMyTurn,
                turnTimeRemaining = turnTimer,

                isGamePaused = isGamePaused,
                pausedByPlayerName = pausedByPlayerName,
                onTogglePause = { togglePause() },
                recentPick = recentPick,
                pickedNumbersHistory = pickedNumbersHistory.toList(),
                matchSeed = currentMatchSeed,
                opponentName = opponentDisplayName,
                isGameOver = isGameOver,
                didPlayerWin = didPlayerWin,
                isDraw = isDrawMatch,
                isRunner = isRunnerMatch,
                winnerPlayerId = winnerPlayerId,
                isHost = isCurrentHost,
                pingMs = currentPing,
                wantsToPlayAgainName = wantsToPlayAgainPlayerName,
                incomingEmote = latestIncomingEmote,
                incomingEmoteScale = latestIncomingEmoteScale,
                incomingEmoteTimestamp = latestIncomingEmoteTimestamp,
                onSendEmote = { emoji, scaleMultiplier ->
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "EMOTE",
                                playerId = getLocalUid(),
                                displayName = emoji,
                                number = (scaleMultiplier * 100).toInt(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                },
                incomingChatMessage = latestIncomingChatMessage,
                initialChatMessages = matchChatHistory,
                onSendChatMessage = { messageText ->
                    val myChat = InGameChatMessage(
                        id = System.currentTimeMillis() + (0..1000).random(),
                        text = messageText,
                        isSelf = true,
                        senderName = null,
                        timestamp = System.currentTimeMillis()
                    )
                    matchChatHistory = matchChatHistory + myChat
                    if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                            context = context,
                            playerBoard = playerBoard,
                            opponentBoard = opponentBoard,
                            allPlayerBoards = allPlayerBoards,
                            pickedNumbers = pickedNumbersHistory.toList(),
                            pickedByPlayers = pickedByPlayerHistory.toList(),
                            turnNumber = turnNumber,
                            currentTurnPlayerId = currentTurnPlayerId,
                            chatMessages = matchChatHistory
                        )
                    }
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "CHAT_MESSAGE",
                                playerId = getLocalUid(),
                                displayName = messageText,
                                username = getPlayerDisplayName(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                },
                onRequestPlayAgain = {
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "PLAY_AGAIN_REQUEST",
                                playerId = getLocalUid(),
                                displayName = getPlayerDisplayName()
                            )
                        )
                        Toast.makeText(context, "Play again request sent to Host!", Toast.LENGTH_SHORT).show()
                    }
                },
                onSurrender = {
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SURRENDER",
                                playerId = getLocalUid(),
                                displayName = getPlayerDisplayName()
                            )
                        )
                    }
                    isGameOver = false
                    didPlayerWin = false
                    latestIncomingEmote = null
                    latestIncomingEmoteTimestamp = 0L
                    recordFinishedMatch(false)
                    disconnectRoom()
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = true }
                    }
                },
                onCellPicked = { cellNumber ->
                    executePick(
                        number = cellNumber,
                        isOwnPick = true,
                        pickerId = getLocalUid(),
                        shouldBroadcast = true
                    )
                },
                onSyncGame = {
                    coroutineScope.launch {
                        Toast.makeText(context, "Syncing match state…", Toast.LENGTH_SHORT).show()
                        onlineRoomSync.refreshNow()
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SYNC_REQUEST",
                                playerId = getLocalUid()
                            )
                        )
                    }
                },
                onPlayAgain = {
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        if (isHosting) {
                            val seed = Random.nextLong().let { if (it == 0L) 1L else it }
                            val activeList = realTimePlayers.filter { it.id.isNotBlank() }
                                .distinctBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                                .sortedWith(
                                    compareByDescending<Player> { it.isHost }
                                        .thenBy { it.username.trim().lowercase().removePrefix("@").takeIf { u -> u.isNotBlank() } ?: it.id.trim().lowercase() }
                                )
                            val sanitizedList = activeList.map { it.copy(avatarUrl = null) }
                            matchParticipants = sanitizedList
                            val targetSize = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.resolveBoardSize(isDynamicBoard, selectedDynamicGridSize, sanitizedList.size)
                            boardSize = targetSize
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                coroutineScope.launch {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(
                                        roomCode = roomCode,
                                        status = "PLAYING",
                                        seed = seed,
                                        isManualBoard = isManualBoard,
                                        boardSize = targetSize,
                                        isDynamicBoard = isDynamicBoard
                                    )
                                }
                            }
                            val playAgainPacket = RoomMessagePacket(
                                type = "PLAY_AGAIN",
                                boardSize = targetSize,
                                seed = seed,
                                playerId = getLocalUid(),
                                isManualBoard = isManualBoard,
                                isDynamicBoard = isDynamicBoard,
                                players = sanitizedList
                            )
                            broadcastPacket(playAgainPacket)
                            coroutineScope.launch {
                                delay(150L)
                                broadcastPacket(playAgainPacket)
                                delay(250L)
                                broadcastPacket(playAgainPacket)
                            }
                            if (isManualBoard) {
                                currentMatchSeed = seed
                                allPlayerBoards = emptyMap()
                                isLocalBoardReady = false
                                isOpponentBoardReady = false
                                countdownSeconds = -1
                                firstTurnPlayerName = ""
                                pickedNumbersHistory.clear()
                                isProcessingTurn = false
                                turnNumber = 1
                                currentTurnPlayerId = ""
                                isMyTurn = false
                                turnTimer = 30
                                isGamePaused = false
                                pausedByPlayerName = ""
                                recentPick = null
                                isGameOver = false
                                didPlayerWin = false
                                isDrawMatch = false
                                wantsToPlayAgainPlayerName = null
                                opponentDisconnectMessage = null
                                opponentSurrenderMessage = null
                                latestIncomingEmote = null
                                latestIncomingEmoteScale = 1.0f
                                latestIncomingEmoteTimestamp = 0L
                                latestIncomingChatMessage = null
                                navController.navigate(Screen.ManualBoardDesign.route)
                            } else {
                                startNewGame(
                                    mode = currentGameMode,
                                    difficulty = currentAiDifficulty,
                                    size = boardSize,
                                    firstTurnPlayerId = null,
                                    hostSeed = seed,
                                    incomingPlayersList = sanitizedList
                                )
                            }
                        }
                    } else {
                        startNewGame(currentGameMode, currentAiDifficulty, boardSize)
                    }
                },
                onBackToMenu = {
                    val wasOver = isGameOver
                    if (wasOver) {
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                        if (isHosting && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "HOST_LEFT",
                                    playerId = getLocalUid(),
                                    displayName = getPlayerDisplayName(),
                                    username = currentAuthUser?.username ?: "",
                                    isHost = true
                                )
                            )
                            if (roomCode.isNotBlank()) {
                                coroutineScope.launch {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                                }
                            }
                        }
                    } else {
                        // Match still active: save state so player can rejoin from Main Menu!
                        if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                context = context,
                                playerBoard = playerBoard,
                                opponentBoard = opponentBoard,
                                allPlayerBoards = allPlayerBoards,
                                pickedNumbers = pickedNumbersHistory.toList(),
                                pickedByPlayers = pickedByPlayerHistory.toList(),
                                turnNumber = turnNumber,
                                currentTurnPlayerId = currentTurnPlayerId,
                                chatMessages = matchChatHistory
                            )
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "PLAYER_DISCONNECTED",
                                    playerId = getLocalUid(),
                                    displayName = getPlayerDisplayName(),
                                    username = currentAuthUser?.username ?: "",
                                    isHost = isHosting
                                )
                            )
                        }
                    }
                    isGameOver = false
                    didPlayerWin = false
                    isDrawMatch = false
                    isRunnerMatch = false
                    winnerPlayerId = ""
                    currentMatchSeed = 0L
                    allPlayerBoards = emptyMap()
                    pickedNumbersHistory.clear()
                    disconnectedPlayerIds.clear()
                    isProcessingTurn = false
                    isMyTurn = false
                    turnNumber = 1
                    turnTimer = 30
                    isGamePaused = false
                    pausedByPlayerName = ""
                    recentPick = null
                    wantsToPlayAgainPlayerName = null
                    opponentDisconnectMessage = null
                    opponentSurrenderMessage = null
                    latestIncomingEmote = null
                    latestIncomingEmoteScale = 1.0f
                    latestIncomingEmoteTimestamp = 0L
                    latestIncomingChatMessage = null
                    isLocalBoardReady = false
                    isOpponentBoardReady = false
                    matchParticipants = emptyList()
                    randomizedTurnOrder = emptyList()
                    isStartingCountdown = false
                    countdownSeconds = -1
                    firstTurnPlayerName = ""
                    onlineRoomSync.resetMatchSession()
                    disconnectRoom()
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = true }
                    }
                },
                onReturnToLobby = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    {
                        val wasOver = isGameOver
                        if (wasOver) {
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                            if (currentMatchSeed != 0L) {
                                onlineRoomSync.recordCompletedSeed(currentMatchSeed)
                            }
                            matchChatHistory = emptyList()
                            latestIncomingChatMessage = null
                        } else {
                            // Active match in progress: persist game state and notify peers of temporary disconnect
                            if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && roomCode.isNotBlank()) {
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                    context = context,
                                    playerBoard = playerBoard,
                                    opponentBoard = opponentBoard,
                                    allPlayerBoards = allPlayerBoards,
                                    pickedNumbers = pickedNumbersHistory.toList(),
                                    pickedByPlayers = pickedByPlayerHistory.toList(),
                                    turnNumber = turnNumber,
                                    currentTurnPlayerId = currentTurnPlayerId,
                                    chatMessages = matchChatHistory
                                )
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "PLAYER_DISCONNECTED",
                                        playerId = getLocalUid(),
                                        displayName = getPlayerDisplayName(),
                                        username = currentAuthUser?.username ?: "",
                                        isHost = isHosting
                                    )
                                )
                            }
                        }
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = ""
                        currentMatchSeed = 0L
                        pickedNumbersHistory.clear()
                        pickedByPlayerHistory.clear()
                        isProcessingTurn = false
                        isMyTurn = false
                        turnTimer = 30
                        isGamePaused = false
                        pausedByPlayerName = ""
                        recentPick = null
                        wantsToPlayAgainPlayerName = null
                        opponentDisconnectMessage = null
                        opponentSurrenderMessage = null
                        latestIncomingEmote = null
                        latestIncomingEmoteScale = 1.0f
                        latestIncomingEmoteTimestamp = 0L
                        latestIncomingChatMessage = null
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        isStartingCountdown = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        onlineRoomSync.resetMatchSession()
                        val hostUid = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: ""
                        val isKnownHost = isHosting || 
                            (onlineRoomSync.currentHostId != null && getLocalUid() == onlineRoomSync.currentHostId) || 
                            realTimePlayers.any { it.isHost && com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, getLocalUid()) }
                        if (isKnownHost && !isHosting) {
                            isHosting = true
                        }
                        val isHostActuallyGone = isHostLeftGame && (hostUid.isBlank() || isPlayerDisconnected(hostUid))
                        if (!isHosting && isHostActuallyGone) {
                            Toast.makeText(context, "Host left the lobby.", Toast.LENGTH_LONG).show()
                            disconnectRoom()
                            isHosting = false
                            val exitDest = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.MainMenu.route
                            navController.navigate(exitDest) {
                                popUpTo(exitDest) { inclusive = false }
                                launchSingleTop = true
                            }
                        } else {
                            val targetRoute = Screen.Lobby.route
                            if (wasOver) {
                                if (isHosting) {
                                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING", seed = 0L)
                                        }
                                        onlineRoomSync.updateLocalReadyStatus("READY")
                                        broadcastPacket(
                                            RoomMessagePacket(
                                                type = "ROOM_STATE",
                                                playerId = getLocalUid(),
                                                displayName = getPlayerDisplayName(),
                                                username = currentAuthUser?.username ?: "",
                                                isHost = true,
                                                readyStatus = "READY"
                                            )
                                        )
                                    } else {
                                        lanP2pSync.updateLocalReadyStatus("READY")
                                        lanP2pSync.isHostInLobby = true
                                        broadcastPacket(
                                            RoomMessagePacket(
                                                type = "GO_TO_LOBBY",
                                                playerId = getLocalUid()
                                            )
                                        )
                                        lanDiscovery.startBroadcasting(
                                            host = Player(
                                                id = getLocalUid(),
                                                displayName = getPlayerDisplayName(),
                                                username = currentAuthUser?.username ?: "",
                                                isHost = true,
                                                avatarUrl = getPlayerAvatarUrl(),
                                                lobbyReadyStatus = "READY"
                                            ),
                                            boardSize = boardSize,
                                            internalRoomCode = roomCode,
                                            ssid = HotspotAndWifiManager.getHotspotName(context)
                                        )
                                    }
                                } else {
                                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                                        onlineRoomSync.updateLocalReadyStatus("NOT_READY")
                                    } else {
                                        lanP2pSync.updateLocalReadyStatus("NOT_READY")
                                    }
                                }
                            }
                            navController.navigate(targetRoute) {
                                popUpTo(targetRoute) { inclusive = true }
                            }
                        }
                    }
                } else null,
                myPlayerId = getLocalUid(),
                opponentAvatarUrl = opponentAvatarUrl,
                opponentUsername = opponentUsername,
                myAvatarUrl = myAvatarUrl,
                myUsername = myUsername,
                myDisplayName = myDisplayName
            )
        }
    }

    LaunchedEffect(incomingInvite?.roomCode) {
        if (incomingInvite != null) {
            try {
                val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(200)
                }
            } catch (_: Exception) {}
        }
    }

    val currentInvite = incomingInvite
    if (currentInvite != null) {
        AlertDialog(
            onDismissRequest = {
                currentInvite.let { handledInviteRoomCodes[it.roomCode] = System.currentTimeMillis() }
                com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
                com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
                incomingInvite = null
            },
            title = {
                Text(
                    text = "🎮 Match Invitation",
                    fontWeight = FontWeight.Bold,
                    color = BingoTheme.colors.cellNeutralText
                )
            },
            text = {
                Text(
                    text = "${currentInvite.fromDisplayName} (@${currentInvite.fromUsername}) invited you to play Bingo in room #${currentInvite.roomCode}!",
                    color = BingoTheme.colors.cellNeutralText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val inviteToJoin = currentInvite
                        incomingInvite = null
                        com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
                        com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
                        handledInviteRoomCodes[inviteToJoin.roomCode] = System.currentTimeMillis()
                        coroutineScope.launch {
                            currentAuthUser?.let { u ->
                                com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(u.username, inviteToJoin.roomCode)
                            }
                        }
                        acceptAndJoinRoom(inviteToJoin.roomCode)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BingoTheme.colors.primaryButtonBg,
                        contentColor = BingoTheme.colors.primaryButtonText
                    )
                ) {
                    Text("Accept & Play", color = BingoTheme.colors.primaryButtonText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val inviteToDecline = currentInvite
                        incomingInvite = null
                        com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
                        com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInviteNotification(context)
                        handledInviteRoomCodes[inviteToDecline.roomCode] = System.currentTimeMillis()
                        coroutineScope.launch {
                            currentAuthUser?.let { u ->
                                com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(u.username, inviteToDecline.roomCode)
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BingoTheme.colors.textPrimary)
                ) {
                    Text("Decline", color = BingoTheme.colors.textPrimary)
                }
            },
            containerColor = BingoTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Opponent Surrender Notification Dialog
    opponentSurrenderMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { opponentSurrenderMessage = null },
            title = {
                Text("Opponent Surrendered", fontWeight = FontWeight.Bold, color = BingoTheme.colors.cellNeutralText)
            },
            text = {
                Text(msg, color = BingoTheme.colors.cellNeutralText)
            },
            confirmButton = {
                Button(
                    onClick = { opponentSurrenderMessage = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BingoTheme.colors.primaryButtonBg,
                        contentColor = BingoTheme.colors.primaryButtonText
                    )
                ) {
                    Text("Claim Victory", color = BingoTheme.colors.primaryButtonText, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = BingoTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Opponent Disconnect Notification Dialog
    opponentDisconnectMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {
                opponentDisconnectMessage = null
                disconnectRoom()
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = true }
                }
            },
            title = {
                Text("Opponent Disconnected", fontWeight = FontWeight.Bold, color = BingoTheme.colors.cellNeutralText)
            },
            text = {
                Text(msg, color = BingoTheme.colors.cellNeutralText)
            },
            confirmButton = {
                Button(
                    onClick = {
                        opponentDisconnectMessage = null
                        disconnectRoom()
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.MainMenu.route) { inclusive = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BingoTheme.colors.primaryButtonBg,
                        contentColor = BingoTheme.colors.primaryButtonText
                    )
                ) {
                    Text("Back to Menu", color = BingoTheme.colors.primaryButtonText, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = BingoTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Accidental Exit Prevention: Leave Match Confirmation Dialog
    if (showLeaveMatchDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveMatchDialog = false },
            title = {
                Text(
                    text = "Leave Match?",
                    fontWeight = FontWeight.Bold,
                    color = BingoTheme.colors.cellNeutralText
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to leave the match? Leaving the match will count as a loss.",
                    color = BingoTheme.colors.cellNeutralText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveMatchDialog = false
                        if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                            if (isHosting) {
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "HOST_LEFT",
                                        playerId = getLocalUid(),
                                        displayName = getPlayerDisplayName(),
                                        username = currentAuthUser?.username ?: "",
                                        isHost = true
                                    )
                                )
                                if (roomCode.isNotBlank()) {
                                    coroutineScope.launch {
                                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                                    }
                                }
                            } else {
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "SURRENDER",
                                        playerId = getLocalUid(),
                                        displayName = getPlayerDisplayName()
                                    )
                                )
                            }
                        }
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context)
                        isGameOver = false
                        didPlayerWin = false
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        recordFinishedMatch(false)
                        disconnectRoom()
                        lanDiscovery.stopBroadcasting()
                        lanDiscovery.stopDiscovering()
                        isHosting = false
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.MainMenu.route) { inclusive = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BingoTheme.colors.accentOpponent)
                ) {
                    Text("Leave Match", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showLeaveMatchDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BingoTheme.colors.textPrimary)
                ) {
                    Text("Stay", color = BingoTheme.colors.textPrimary)
                }
            },
            containerColor = BingoTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
    }
}
