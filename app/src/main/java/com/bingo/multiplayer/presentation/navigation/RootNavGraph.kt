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
import com.bingo.multiplayer.presentation.nearby.NearbyChoiceScreen
import com.bingo.multiplayer.presentation.nearby.NearbyLobbyScreen
import com.bingo.multiplayer.presentation.online.JoinRoomScreen
import com.bingo.multiplayer.presentation.online.OnlineMatchChoiceScreen
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
import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.network.ReliableMatchClient
import com.bingo.multiplayer.domain.network.ReliableMatchTransport
import com.bingo.multiplayer.domain.network.ReliableMatchCheckpoint
import com.bingo.multiplayer.domain.network.OngoingMatchStore
import com.bingo.multiplayer.domain.network.OngoingMatchData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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
    var isCheckingMatchCompatibility by remember { mutableStateOf(false) }
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
    var activeWinReason by remember { mutableStateOf("") }
    var activeRunnerPlayerIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentMatchSeed by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var matchStartTimeMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var isLocalBoardReady by remember { mutableStateOf(false) }
    var isOpponentBoardReady by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableIntStateOf(-1) }
    var firstTurnPlayerName by remember { mutableStateOf("") }
    var latestIncomingEmote by remember { mutableStateOf<String?>(null) }
    var latestIncomingEmoteScale by remember { mutableFloatStateOf(1.0f) }
    var latestIncomingEmoteTimestamp by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var latestIncomingChatMessage by remember { mutableStateOf<InGameChatMessage?>(null) }
    var matchChatHistory by remember { mutableStateOf<List<InGameChatMessage>>(emptyList()) }
    var currentTypingPlayerName by remember { mutableStateOf<String?>(null) }
    var currentTypingPlayerId by remember { mutableStateOf("") }
    var currentTypingTimestamp by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var opponentPlayerId by remember { mutableStateOf("") }
    var matchParticipants by remember { mutableStateOf<List<Player>>(emptyList()) }
    var randomizedTurnOrder by remember { mutableStateOf<List<Player>>(emptyList()) }
    val disconnectedPlayerIds = remember { mutableStateListOf<String>() }
    val consecutiveMissedTurns = remember { mutableStateMapOf<String, Int>() }
    var isStartingCountdown by remember { mutableStateOf(false) }

    // History and Turn Authority (reconciles network packets and prevents stalls)
    val pickedNumbersHistory = remember { mutableStateListOf<Int>() }
    val pickedByPlayerHistory = remember { mutableStateListOf<String>() }
    val pickedTurnIdsHistory = remember { mutableStateListOf<Int>() }
    var currentTurnPlayerId by remember { mutableStateOf("") }
    var isProcessingTurn by remember { mutableStateOf(false) }
    var isReconcilingMatch by remember { mutableStateOf(false) }

    // Online match state
    var wantsToPlayAgainPlayerName by remember { mutableStateOf<String?>(null) }
    var opponentDisconnectMessage by remember { mutableStateOf<String?>(null) }
    var opponentSurrenderMessage by remember { mutableStateOf<String?>(null) }
    val onlinePingMs by onlineRoomSync.pingMs.collectAsState()
    var lobbyInactivityResetToken by remember { mutableIntStateOf(0) }

    // 1v1 Online Match Heartbeat & Interruption Dialog State
    var isOpponentUnreachable by remember { mutableStateOf(false) }
    var interruptionEpisodeCount by remember { mutableIntStateOf(0) }
    var interruptionPrompt by remember { mutableStateOf(InterruptionPromptState()) }
    var matchSystemMessages by remember { mutableStateOf<List<InGameChatMessage>>(emptyList()) }
    var peerAvailability by remember { mutableStateOf(PeerAvailabilityState.SYNCING) }
    var peerStateSynchronized by remember { mutableStateOf(false) }
    var presentationMatchSeed by remember { mutableLongStateOf(0L) }
    var presentationAccountUid by remember { mutableStateOf("") }
    var statusSerial by remember { mutableLongStateOf(0L) }
    var isClaimVictoryEligible by remember { mutableStateOf(false) }
    var frozenRemainingTurnSeconds by remember { mutableStateOf<Int?>(null) }
    var frozenRemainingTurnMillis by remember { mutableStateOf<Long?>(null) }
    var reliableClient by remember { mutableStateOf<ReliableMatchClient?>(null) }
    var isHostDisconnected by remember { mutableStateOf(false) }
    var reliableElapsedPlayMillis by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var reliableStartAttempt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val reliableScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    DisposableEffect(Unit) { onDispose { reliableClient?.close(); reliableScope.cancel() } }


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

    fun prepareMatchPresentation(seed: Long, saved: OngoingMatchData? = null) {
        if (presentationMatchSeed == seed && presentationAccountUid == getLocalUid() && saved == null) return
        presentationMatchSeed = seed
        presentationAccountUid = getLocalUid()
        matchSystemMessages = saved?.systemMessages ?: emptyList()
        interruptionPrompt = saved?.interruptionPrompt ?: InterruptionPromptState()
        peerAvailability = PeerAvailabilityState.SYNCING
        peerStateSynchronized = false
        statusSerial = 0L
    }

    fun reportMatchStatus(event: String, text: String) {
        if (currentGameMode != GameMode.ONLINE_ROOM || matchParticipants.size != 2 || currentMatchSeed == 0L) return
        prepareMatchPresentation(currentMatchSeed)
        val uid = getLocalUid()
        val seed = currentMatchSeed
        val notice = MatchSystemMessages.notice(seed, uid, event, text, System.currentTimeMillis())
        if (matchSystemMessages.any { it.id == notice.id }) return
        matchSystemMessages = MatchSystemMessages.merge(matchSystemMessages, listOf(notice))
        matchChatHistory = MatchSystemMessages.merge(matchChatHistory, listOf(notice))
        coroutineScope.launch(Dispatchers.IO) { OngoingMatchStore.savePresentation(context, uid, seed, notice = notice) }
    }

    fun savePrompt(prompt: InterruptionPromptState) {
        interruptionPrompt = InterruptionPromptState.merge(interruptionPrompt, prompt)
        val uid = getLocalUid()
        val seed = currentMatchSeed
        val captured = interruptionPrompt
        coroutineScope.launch(Dispatchers.IO) { OngoingMatchStore.savePresentation(context, uid, seed, prompt = captured) }
    }

    fun canResumeReliableMatch(): Boolean = !isReconcilingMatch && peerStateSynchronized &&
        peerAvailability in setOf(PeerAvailabilityState.RESPONSIVE, PeerAvailabilityState.RECOVERING)

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
    LaunchedEffect(currentAuthUser?.uid) {
        reliableClient?.let { if (it.accountId != currentAuthUser?.uid) {
            reliableStartAttempt++; it.close(); reliableClient = null; isHostDisconnected = false
        } }
    }


    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(currentTypingTimestamp, currentMatchSeed, isGameOver, currentRoute) {
        if (isGameOver || currentRoute != Screen.Game.route) {
            currentTypingPlayerName = null
            currentTypingPlayerId = ""
        } else if (currentTypingPlayerName != null) {
            delay(5000L)
            currentTypingPlayerName = null
            currentTypingPlayerId = ""
        }
    }

    LaunchedEffect(currentRoute, currentGameMode, roomCode, isGameOver) {
        when (currentRoute) {
            Screen.Game.route -> {
                com.bingo.multiplayer.domain.network.PresenceManager.setActivityState(
                    if (isGameOver) com.bingo.multiplayer.domain.network.AppActivityState.ONLINE
                    else com.bingo.multiplayer.domain.network.AppActivityState.PLAYING,
                    detail = if (isGameOver) "" else when (currentGameMode) {
                        GameMode.AI_EASY -> "vs AI (Easy)"
                        GameMode.AI_HARD -> "vs AI (Master)"
                        GameMode.ONLINE_ROOM -> "Online Match"
                        GameMode.NEARBY_NETWORK -> "Nearby LAN"
                    },
                    roomCode = if (!isGameOver && currentGameMode == GameMode.ONLINE_ROOM) roomCode else ""
                )
            }
            Screen.Lobby.route, Screen.NearbyLobby.route, Screen.ManualBoardDesign.route -> {
                com.bingo.multiplayer.domain.network.PresenceManager.setActivityState(
                    com.bingo.multiplayer.domain.network.AppActivityState.IN_LOBBY,
                    detail = if (currentGameMode == GameMode.ONLINE_ROOM) "Room $roomCode" else "Nearby LAN",
                    roomCode = if (currentGameMode == GameMode.ONLINE_ROOM) roomCode else ""
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
        if (currentRoute == Screen.Game.route && isHostDisconnected) return@BackHandler
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
            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
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

    DisposableEffect(currentAuthUser?.uid, currentAuthUser?.username) {
        friendsRepository.setAccount(currentAuthUser)
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
                onNewRequest = { req ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "👥 @${req.fromUsername} sent you a friend request!", Toast.LENGTH_LONG).show()
                    }
                },
                onFriendAccepted = { f ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "🎉 @${f.username} accepted your friend request! You are now friends.", Toast.LENGTH_LONG).show()
                    }
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
                    if (matchParticipants.size != 2 || currentGameMode != GameMode.ONLINE_ROOM) {
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
            val canShowInApp = com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowInAppInvite(context)
            if (canShowInApp && !isJoiningRoom && !isRecentlyHandled && roomCode != staged.roomCode && (now - staged.timestamp) < 120_000L) {
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
            val canShowInApp = com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowInAppInvite(context)
            if (canShowInApp && !isJoiningRoom && !isRecentlyHandled && roomCode != invite.roomCode && (now - invite.timestamp) < 120_000L) {
                val currentDest = navController.currentDestination?.route
                val isActivelyPlaying = (currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route) && !isGameOver
                if (!isActivelyPlaying) {
                    incomingInvite = invite
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        com.bingo.multiplayer.domain.network.PresenceManager.friendOnlineAlertFlow.collect { friendName ->
            try {
                if (com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowInAppPlayerOnline(context)) {
                    android.widget.Toast.makeText(context.applicationContext, "🟢 $friendName is online", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }
    }

    fun handlePauseAction(pause: Boolean) {
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
            if (reliableClient?.isHost != true || isReconcilingMatch || (!pause && !canResumeReliableMatch())) return
            if (pause) savePrompt(interruptionPrompt.decide())
            reliableClient?.request(if (pause) "PAUSE" else "RESUME")
            return
        }
        if (pause && !isGamePaused) {
            val remMs = frozenRemainingTurnMillis ?: (turnTimer * 1000L)
            frozenRemainingTurnMillis = remMs
            val remSec = ((remMs + 999L) / 1000L).toInt()
            frozenRemainingTurnSeconds = remSec
            isGamePaused = true
            pausedByPlayerName = getPlayerDisplayName()
            if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_PAUSED",
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        remainingTurnMillis = remMs,
                        isPaused = true,
                        pauseOwnerId = getLocalUid()
                    )
                )
            }
        } else if (!pause && isGamePaused) {
            val remMs = frozenRemainingTurnMillis ?: (turnTimer * 1000L)
            val remSec = ((remMs + 999L) / 1000L).toInt()
            isGamePaused = false
            pausedByPlayerName = ""
            turnTimer = remSec
            frozenRemainingTurnMillis = null
            frozenRemainingTurnSeconds = null
            if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_RESUMED",
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        remainingTurnMillis = remMs,
                        isPaused = false
                    )
                )
            }
        }
        if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
            val remMs = frozenRemainingTurnMillis ?: (turnTimer * 1000L)
            com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                context = context,
                playerBoard = playerBoard,
                opponentBoard = opponentBoard,
                allPlayerBoards = allPlayerBoards,
                pickedNumbers = pickedNumbersHistory.toList(),
                pickedByPlayers = pickedByPlayerHistory.toList(),
                turnIds = pickedTurnIdsHistory.toList(),
                turnNumber = turnNumber,
                currentTurnPlayerId = currentTurnPlayerId,
                chatMessages = matchChatHistory,
                isPaused = isGamePaused,
                remainingTurnMillis = remMs,
                pauseOwnerId = pausedByPlayerName,
                committedEpisodeCount = interruptionEpisodeCount,
                accountUid = getLocalUid()
            )
        }
    }

    fun togglePause() {
        handlePauseAction(!isGamePaused)
    }

    fun openNearbyChoice() {
        navController.navigate(Screen.NearbyChoice.route)
    }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        openNearbyChoice()
    }

    fun recordFinishedMatch(won: Boolean, isDraw: Boolean = false, isRunner: Boolean = false, stableResultId: String? = null, expectedAccountUid: String? = null): Boolean {
        val myName = currentAuthUser?.username?.ifBlank { currentAuthUser.displayName } ?: getPlayerDisplayName()
        val recordedPlayers = matchParticipants.ifEmpty { realTimePlayers }
        val isGroup = recordedPlayers.size > 2

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
                    val opp = recordedPlayers.firstOrNull { it.id != getLocalUid() }
                    opp?.username?.ifBlank { opp.displayName } ?: "Opponent"
                }
                GameMode.NEARBY_NETWORK -> {
                    val opp = recordedPlayers.firstOrNull { it.id != getLocalUid() }
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
            GameMode.ONLINE_ROOM -> recordedPlayers.firstOrNull { it.id != getLocalUid() }?.displayName ?: "Online Opponent"
            GameMode.NEARBY_NETWORK -> recordedPlayers.firstOrNull { it.id != getLocalUid() }?.displayName ?: "Nearby Peer"
        }
        val modeStr = when (currentGameMode) {
            GameMode.AI_EASY -> "Single Player (Easy)"
            GameMode.AI_HARD -> "Single Player (Master)"
            GameMode.ONLINE_ROOM -> "Online Match"
            GameMode.NEARBY_NETWORK -> "Nearby Network (LAN)"
        }
        val recorded = authRepository.recordMatch(
            mode = modeStr,
            opponentName = oppName,
            didWin = won,
            boardSize = boardSize,
            matchTitle = matchTitle,
            isDraw = isDraw,
            opponentUid = if (!isGroup && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK))
                recordedPlayers.firstOrNull { it.id != getLocalUid() }?.id.orEmpty() else "",
            turnsTaken = pickedByPlayerHistory.count { isPlayerMe(it) },
            linesCompleted = com.bingo.multiplayer.domain.engine.MatchScorecardStats.completedLines(
                playerBoard.size, playerBoard.cells.map { it.number },
                playerBoard.cells.filter { it.isMarked }.map { it.number }
            ) ?: 0,
            durationSeconds = if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                reliableElapsedPlayMillis / 1000L
            else if (matchStartTimeMs > 0L) ((System.currentTimeMillis() - matchStartTimeMs) / 1000L).coerceAtLeast(0L) else 0L,
            finalBoardNumbers = playerBoard.cells.map { it.number },
            markedNumbers = playerBoard.cells.filter { it.isMarked }.map { it.number },
            stableResultId = stableResultId,
            expectedAccountUid = expectedAccountUid
        )
        if (!recorded) return false
        if (stableResultId == null) com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
        if (currentMatchSeed != 0L) {
            onlineRoomSync.recordCompletedSeed(currentMatchSeed)
        }
        if (isHosting && (currentGameMode == GameMode.ONLINE_ROOM) && roomCode.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING", seed = 0L)
            }
        }
        return true
    }

    LaunchedEffect(reliableClient) {
        val client = reliableClient ?: return@LaunchedEffect
        var appliedSequence = -1L
        var appliedResult = ""
        var lastStatus = ""
        var previousPause: Boolean? = null
        client.view.collect { v ->
            if (client.accountId != getLocalUid()) { client.close(); return@collect }
            if (client !== reliableClient || currentGameMode != GameMode.ONLINE_ROOM || client.activeMatchId != currentMatchSeed.toString()) return@collect
            prepareMatchPresentation(currentMatchSeed)
            if (v.message != lastStatus) {
                if (v.message.isNotBlank()) reportMatchStatus("sync:${++statusSerial}:${System.currentTimeMillis()}", v.message)
                else if (lastStatus.isNotBlank()) reportMatchStatus("synced:${++statusSerial}:${System.currentTimeMillis()}", "Match synchronized.")
                lastStatus = v.message
            }
            isHostDisconnected = v.hostDisconnected
            if (v.hostDisconnected) showLeaveMatchDialog = false
            isReconcilingMatch = v.waiting
            turnTimer = ((v.remainingMillis + 999) / 1000).toInt()
            val snapshot = v.snapshot ?: return@collect
            reliableElapsedPlayMillis = snapshot.elapsedPlayMillis
            if (snapshot.sequence != appliedSequence) {
                if (appliedSequence >= 0 && !v.waiting && snapshot.picks.size > pickedNumbersHistory.size) {
                    com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playPickSound()
                    if (snapshot.picks.last().playerId == getLocalUid()) com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playVibration(context)
                }
                appliedSequence = snapshot.sequence
                val uid = getLocalUid()
                snapshot.boards[uid]?.let { playerBoard = it }
                snapshot.boards.entries.firstOrNull { it.key != uid }?.value?.let { opponentBoard = it }
                allPlayerBoards = snapshot.boards
                matchParticipants = snapshot.participants
                randomizedTurnOrder = snapshot.order.mapNotNull { id -> snapshot.participants.find { it.id == id } }
                pickedNumbersHistory.clear(); pickedNumbersHistory.addAll(snapshot.picks.map { it.number })
                pickedByPlayerHistory.clear(); pickedByPlayerHistory.addAll(snapshot.picks.map { it.playerId })
                pickedTurnIdsHistory.clear(); pickedTurnIdsHistory.addAll(snapshot.picks.map { it.turn })
                snapshot.picks.lastOrNull()?.let {
                    if (PickIdentity.of(recentPick) != PickIdentity(it.number, it.playerId, it.turn))
                        recentPick = RecentPick(it.number, it.playerId, it.turn)
                }
                matchChatHistory = MatchSystemMessages.merge(snapshot.chat.map { msg -> InGameChatMessage(
                    id = msg.localId(), text = msg.text, isSelf = msg.playerId == uid,
                    senderName = msg.name, timestamp = msg.timestamp) }, matchSystemMessages)
            }
            turnNumber = snapshot.turn
            currentTurnPlayerId = snapshot.currentPlayerId
            isMyTurn = !v.waiting && currentTurnPlayerId == getLocalUid()
            if (previousPause != snapshot.paused && (previousPause != null || snapshot.paused)) {
                val hostName = snapshot.participants.find { it.id == snapshot.hostId }?.displayName ?: "Host"
                reportMatchStatus("pause:${snapshot.sequence}", if (snapshot.paused) "$hostName paused the match." else "$hostName resumed the match.")
            }
            previousPause = snapshot.paused
            isGamePaused = snapshot.paused
            pausedByPlayerName = snapshot.participants.find { it.id == snapshot.pauseOwnerId }?.displayName.orEmpty()
            val result = snapshot.final
            if (result != null && !v.waiting && result.id != appliedResult) {
                val won = result.winnerId == getLocalUid()
                val runner = !won && playerBoard.isBingo && result.reason in setOf("BINGO", "TURN_PICK")
                val resultAccount = getLocalUid()
                var recorded = false
                while (!recorded && client === reliableClient && getLocalUid() == resultAccount) {
                    recorded = withContext(Dispatchers.IO) { recordFinishedMatch(won, isRunner = runner, stableResultId = resultAccount + ":" + result.id, expectedAccountUid = resultAccount) }
                    if (!recorded) delay(1000)
                }
                if (recorded) {
                    appliedResult = result.id
                    isGameOver = true; didPlayerWin = won; isRunnerMatch = runner
                    winnerPlayerId = result.winnerId
                    activeWinReason = when (result.reason) {
                        "UNREACHABLE" -> "Opponent Unreachable (Claimed Victory)"
                        "SURRENDER" -> "Opponent Surrendered"
                        "TURN_PICK" -> "Turn Pick"
                        else -> "Bingo"
                    }
                    activeRunnerPlayerIds = if (runner) listOf(getLocalUid()) else emptyList()
                    onlineRoomSync.stopMatchHeartbeat()
                    isOpponentUnreachable = false; isClaimVictoryEligible = false
                }
            }
        }
    }

    fun executeClaimVictory() {
        if (currentGameMode != GameMode.ONLINE_ROOM || matchParticipants.size != 2) return
        val policy = onlineRoomSync.matchHeartbeatPolicy ?: return
        if (policy.canClaimVictory(getLocalUid())) reliableClient?.claim(policy.currentEpisodeId.orEmpty())
    }

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
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
                val isWithinResumeGracePeriod = (now - lastForegroundResumeTimestamp) < 12_000L
                val lastHb = if (isUsingP2p) lanP2pSync.getLastDirectHeartbeat(playerId) else onlineRoomSync.getLastDirectHeartbeat(playerId)
                if (!isWithinResumeGracePeriod && lastHb > 0L && (now - lastHb) >= 15_000L) {
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
                val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                val fullyDisconnected = if (isTwoPlayerOnline) {
                    emptySet()
                } else {
                    disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
                }
                com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.calculateNextTurnPlayerId(
                    allParticipants = candidatePlayers,
                    disconnectedPlayerIds = fullyDisconnected,
                    currentPickerId = currentPickerId,
                    fallbackPlayerId = myUid,
                    matchSeed = currentMatchSeed,
                    customTurnOrder = randomizedTurnOrder
                )
            }
        }
    }

    fun startReliableMatch(saved: OngoingMatchData? = null) {
        if (currentGameMode != GameMode.ONLINE_ROOM || matchParticipants.size != 2) return
        val startAttempt = ++reliableStartAttempt
        reliableClient?.close(); reliableClient = null
        isHostDisconnected = saved?.hostWaitSnapshot != null || (saved != null && !saved.isHost)
        isReconcilingMatch = true
        prepareMatchPresentation(currentMatchSeed, saved)
        val uid = getLocalUid()
        val seed = currentMatchSeed
        val participants = matchParticipants.toList()
        val code = roomCode
        val manual = saved?.isManualBoard ?: isManualBoard
        val dynamic = saved?.isDynamicBoard ?: isDynamicBoard
        val initialTurn = saved?.turnNumber ?: turnNumber
        val boards = if (saved != null) saved.allPlayerBoards else allPlayerBoards
        val picks = pickedNumbersHistory.mapIndexed { i, n -> MatchPick(n, pickedByPlayerHistory.getOrNull(i).orEmpty(), pickedTurnIdsHistory.getOrNull(i) ?: -1) }
        val order = randomizedTurnOrder.map { it.id }.ifEmpty { participants.map { it.id }.sorted() }
        val current = currentTurnPlayerId
        reliableScope.launch {
            val ownerPrefs = context.applicationContext.getSharedPreferences("reliable_match_installation", android.content.Context.MODE_PRIVATE)
            val installation = synchronized(OngoingMatchStore) {
                ownerPrefs.getString("installation", null)?.takeIf { it.isNotBlank() }
                    ?: java.util.UUID.randomUUID().toString().takeIf { ownerPrefs.edit().putString("installation", it).commit() }
            } ?: return@launch
            val host = participants.singleOrNull { it.isHost }?.id ?: return@launch
            val original = saved?.reliableSnapshot
            val initial = original ?: ReliableMatchSnapshot(matchId = seed.toString(), roomCode = code, seed = seed,
                hostId = host, ownerId = if (host == uid) installation else "awaiting-host",
                participants = participants, boards = if (picks.any { it.turn < 1 }) emptyMap() else boards,
                order = order, currentPlayerId = current, turn = initialTurn,
                picks = if (picks.any { it.turn < 1 }) emptyList() else picks,
                paused = saved?.isPaused ?: false, remainingMillis = saved?.remainingTurnMillis ?: 30_000)
            val transport = object : ReliableMatchTransport {
                override val connected get() = onlineRoomSync.isConnected
                override val healthy get() = onlineRoomSync.isLocalObservationHealthy()
                override suspend fun subscribe(seed: Long) = onlineRoomSync.reliableSubscribe(seed)
                override suspend fun publish(packet: RoomMessagePacket, retained: Boolean, stateTopic: Boolean) = onlineRoomSync.reliablePublish(packet, retained, stateTopic)
            }
            val checkpoints = object : ReliableMatchCheckpoint {
                override fun saveHostWait(snapshot: ReliableMatchSnapshot?): Boolean =
                    OngoingMatchStore.saveHostWait(context, uid, seed, snapshot)
                override fun save(snapshot: ReliableMatchSnapshot, pending: Boolean): Boolean {
                    val mine = snapshot.boards[uid] ?: return false
                    val other = snapshot.boards.entries.firstOrNull { it.key != uid }?.value ?: return false
                    val old = OngoingMatchStore.getOngoingMatch(context, uid)
                    val episode = if (host == uid) snapshot.episodes[participants.first { it.id != uid }.id] else null
                    val data = OngoingMatchData(roomCode = code, matchSeed = seed, boardSize = mine.size,
                        isManualBoard = manual, isDynamicBoard = dynamic,
                        isHost = host == uid, participants = snapshot.participants, playerBoard = mine,
                        opponentBoard = other, allPlayerBoards = snapshot.boards,
                        pickedNumbers = snapshot.picks.map { it.number }, pickedByPlayers = snapshot.picks.map { it.playerId },
                        turnIds = snapshot.picks.map { it.turn }, turnNumber = snapshot.turn, currentTurnPlayerId = snapshot.currentPlayerId,
                        randomizedTurnOrder = snapshot.order.map { id -> snapshot.participants.first { it.id == id } },
                        chatMessages = snapshot.chat.map { msg -> InGameChatMessage(id = msg.localId(), text = msg.text,
                            isSelf = msg.playerId == uid, senderName = msg.name, timestamp = msg.timestamp) },
                        systemMessages = if (presentationMatchSeed == seed && presentationAccountUid == uid) matchSystemMessages else emptyList(),
                        interruptionPrompt = if (presentationMatchSeed == seed && presentationAccountUid == uid) interruptionPrompt else InterruptionPromptState(),
                        is1v1Match = true, accountUid = uid, protocolVersion = 2, matchRevision = snapshot.revision,
                        isPaused = snapshot.paused, remainingTurnMillis = snapshot.remainingMillis, pauseOwnerId = snapshot.pauseOwnerId,
                        committedEpisodeCount = episode?.count ?: old?.committedEpisodeCount ?: 0,
                        currentEpisodeId = episode?.id ?: old?.currentEpisodeId,
                        isEpisodeOpen = episode?.open ?: old?.isEpisodeOpen ?: false,
                        peerAvailabilityState = old?.peerAvailabilityState ?: "SYNCING",
                        startedAt = old?.startedAt ?: System.currentTimeMillis(),
                        reliableSnapshot = snapshot, reliablePending = pending, hostWaitSnapshot = old?.hostWaitSnapshot)
                    return OngoingMatchStore.saveReliableCheckpoint(context, data)
                }
            }
            withContext(Dispatchers.Main) {
                if (startAttempt != reliableStartAttempt || getLocalUid() != uid || currentMatchSeed != seed || currentGameMode != GameMode.ONLINE_ROOM) return@withContext
                reliableClient = ReliableMatchClient(initial, uid, installation, object : Clock {
                    override fun elapsedRealtime() = android.os.SystemClock.elapsedRealtime()
                }, transport, checkpoints, reliableScope, original != null && !saved.reliablePending,
                    onSynchronized = { onlineRoomSync.notifyStateSynchronized() }, initialHostWait = saved?.hostWaitSnapshot)
            }
        }
    }

    fun activateMatchHeartbeatIf1v1() {
        val activeList = matchParticipants.ifEmpty { realTimePlayers }
        if (currentGameMode == GameMode.ONLINE_ROOM && (matchParticipants.size == 2 || activeList.size == 2)) {
            val peerUid = activeList.firstOrNull { !isPlayerMe(it) }?.id
                ?: matchParticipants.firstOrNull { !isPlayerMe(it) }?.id
                ?: opponentPlayerId.takeIf { it.isNotBlank() }
                ?: ""
            if (peerUid.isNotBlank() && currentMatchSeed != 0L) {
                onlineRoomSync.startMatchHeartbeat(currentMatchSeed.toString(), peerUid)
                startReliableMatch()
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
        reliableClient?.close(); reliableClient = null; isHostDisconnected = false; reliableStartAttempt++
        currentGameMode = mode
        currentAiDifficulty = difficulty
        boardSize = size
        
        val baseSeed = hostSeed ?: kotlin.random.Random.nextLong()
        currentMatchSeed = baseSeed
        prepareMatchPresentation(baseSeed)
        matchStartTimeMs = System.currentTimeMillis()
        currentTypingPlayerName = null
        currentTypingPlayerId = ""
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
        pickedTurnIdsHistory.clear()
        matchChatHistory = emptyList()
        latestIncomingChatMessage = null
        isProcessingTurn = false
        isGameOver = false
        didPlayerWin = false
        isDrawMatch = false
        isRunnerMatch = false
        winnerPlayerId = ""
        activeWinReason = ""
        activeRunnerPlayerIds = emptyList()

        isOpponentUnreachable = false
        interruptionEpisodeCount = 0
        isClaimVictoryEligible = false
        frozenRemainingTurnSeconds = null
        frozenRemainingTurnMillis = null
        isReconcilingMatch = false
        onlineRoomSync.stopMatchHeartbeat()

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
                    chatMessages = emptyList(),
                    is1v1Match = (matchParticipants.size == 2),
                    accountUid = myUid
                )
            )
        }

        turnNumber = 1
        turnTimer = 30
        isGamePaused = false
        pausedByPlayerName = ""
        recentPick = null

        if (mode == GameMode.ONLINE_ROOM && !isManualBoard) {
            activateMatchHeartbeatIf1v1()
        }
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
                isRunnerMatch = false
                winnerPlayerId = ""
                activeWinReason = ""
                activeRunnerPlayerIds = emptyList()
                latestIncomingEmote = null
                latestIncomingEmoteScale = 1.0f
                latestIncomingEmoteTimestamp = 0L
                latestIncomingChatMessage = null
                matchChatHistory = emptyList()

                val allParticipantIds = activeList.map { it.id }.filter { it.isNotBlank() }
                onlineRoomSync.resetInGameHeartbeats(allParticipantIds)
                lanP2pSync.resetInGameHeartbeats(allParticipantIds)

                isOpponentUnreachable = false
                interruptionEpisodeCount = 0
                isClaimVictoryEligible = false
                frozenRemainingTurnSeconds = null

                activateMatchHeartbeatIf1v1()

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
                            chatMessages = emptyList(),
                            is1v1Match = (matchParticipants.size == 2),
                            accountUid = getLocalUid()
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

    fun restoreAndRejoinOngoingMatch(matchData: com.bingo.multiplayer.domain.network.OngoingMatchData) {
        if (matchData.roomCode.startsWith("LAN_")) {
            Toast.makeText(context, "Nearby match has already ended.", Toast.LENGTH_SHORT).show()
            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
            return
        }

        // Check if this match has already concluded via a durable result tombstone (HB11)
        val tombstone = com.bingo.multiplayer.domain.network.OngoingMatchStore.getMatchResultTombstone(context, matchData.matchSeed)
        if (tombstone != null) {
            Toast.makeText(context, "Match has already concluded: ${tombstone.winReason}", Toast.LENGTH_LONG).show()
            val won = isPlayerMe(tombstone.winnerUid)
            isGameOver = true
            didPlayerWin = won
            isDrawMatch = false
            isRunnerMatch = false
            winnerPlayerId = tombstone.winnerUid
            activeWinReason = tombstone.winReason
            recordFinishedMatch(won = won)
            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
            return
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
        prepareMatchPresentation(matchData.matchSeed, matchData)
        matchParticipants = matchData.participants
        randomizedTurnOrder = matchData.randomizedTurnOrder
        turnNumber = matchData.turnNumber
        currentTurnPlayerId = matchData.currentTurnPlayerId
        isMyTurn = (currentTurnPlayerId == myUid)

        isGamePaused = matchData.isPaused
        pausedByPlayerName = if (matchData.isPaused) (matchData.pauseOwnerId ?: "Opponent") else ""
        if (matchData.isPaused && matchData.remainingTurnMillis != null && matchData.remainingTurnMillis > 0L) {
            val remSec = ((matchData.remainingTurnMillis + 999L) / 1000L).toInt()
            frozenRemainingTurnSeconds = remSec
            turnTimer = remSec
        } else {
            turnTimer = 30
        }
        interruptionEpisodeCount = matchData.committedEpisodeCount
        isOpponentUnreachable = false
        isClaimVictoryEligible = false

        isGameOver = false
        didPlayerWin = false
        isDrawMatch = false
        isRunnerMatch = false
        winnerPlayerId = ""
        activeWinReason = ""
        activeRunnerPlayerIds = emptyList()
        disconnectedPlayerIds.clear()
        consecutiveMissedTurns.clear()
        lastForegroundResumeTimestamp = System.currentTimeMillis()

        val participantIds = matchData.participants.map { it.id }.filter { it.isNotBlank() }
        onlineRoomSync.resetInGameHeartbeats(participantIds)

        isReconcilingMatch = true
        if (matchData.playerBoard != null) {
            playerBoard = matchData.playerBoard
            if (matchData.opponentBoard != null) opponentBoard = matchData.opponentBoard
            else if (matchData.participants.size != 2) opponentBoard = engine.generateBoard(matchData.boardSize)
            allPlayerBoards = matchData.allPlayerBoards
            pickedNumbersHistory.clear()
            pickedNumbersHistory.addAll(matchData.pickedNumbers)
            pickedByPlayerHistory.clear()
            pickedByPlayerHistory.addAll(matchData.pickedByPlayers)
            pickedTurnIdsHistory.clear()
            pickedTurnIdsHistory.addAll(matchData.turnIds)
        } else {
            Toast.makeText(context, "Saved match board data is missing. Awaiting synchronization...", Toast.LENGTH_SHORT).show()
        }

        matchChatHistory = MatchSystemMessages.merge(matchData.chatMessages, matchData.systemMessages)
        if (matchData.pickedNumbers.isNotEmpty()) {
            recentPick = RecentPick(
                number = matchData.pickedNumbers.last(),
                pickedByPlayerId = matchData.pickedByPlayers.lastOrNull() ?: "",
                turnNumber = matchData.turnIds.lastOrNull() ?: matchData.turnNumber
            )
        }

        // Connect to room FIRST (cleans old session and subscribes to room)
        onlineRoomSync.connectToRoom(matchData.roomCode, me)

        // Attach match heartbeat AFTER connectToRoom so disconnect() doesn't wipe it!
        if (matchData.is1v1Match || matchData.participants.size == 2) {
            val peerUid = (matchData.participants.ifEmpty { matchParticipants }).firstOrNull { it.id != myUid }?.id ?: ""
            if (peerUid.isNotBlank() && matchData.matchSeed != 0L) {
                val restoredPolicy = com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicy(
                    matchId = matchData.matchSeed.toString(),
                    peerUid = peerUid,
                    connectionGeneration = onlineRoomSync.activeMatchConnectionGeneration
                )
                restoredPolicy.restoreCheckpoint(
                    savedEpisodeCount = matchData.committedEpisodeCount,
                    savedEpisodeOpen = matchData.isEpisodeOpen,
                    savedEpisodeId = matchData.currentEpisodeId,
                    generation = onlineRoomSync.activeMatchConnectionGeneration
                )
                onlineRoomSync.startMatchHeartbeat(matchData.matchSeed.toString(), peerUid, restoredPolicy)
            }
        }

        if (matchData.participants.size == 2) startReliableMatch(matchData)
        navController.navigate(Screen.Game.route)
        if (matchData.participants.size == 2) return

        coroutineScope.launch {
            delay(300L)
            broadcastPacket(
                RoomMessagePacket(
                    type = "REJOIN_GAME",
                    playerId = myUid,
                    displayName = getPlayerDisplayName(),
                    username = user?.username ?: "",
                    isHost = isHosting,
                    seed = currentMatchSeed,
                    boardSize = boardSize,
                    turnNumber = turnNumber,
                    currentTurnPlayerId = currentTurnPlayerId,
                    timestamp = System.currentTimeMillis()
                )
            )
            broadcastPacket(
                RoomMessagePacket(
                    type = "SYNC_REQUEST",
                    playerId = myUid,
                    seed = currentMatchSeed,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun executePick(
        number: Int,
        isOwnPick: Boolean,
        pickerId: String,
        shouldBroadcast: Boolean = false
    ) {
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
            if (number > 0 && isOwnPick) reliableClient?.request("PICK", number = number)
            return
        }
        if (isGameOver || isGamePaused || isReconcilingMatch) return
        if (isOwnPick && (!isMyTurn || isProcessingTurn)) return
        if (number > 0 && number in pickedNumbersHistory) return

        if (isOwnPick) {
            isProcessingTurn = true
        }

        try {
            val isTimeoutPass = (number <= 0)

            if (!isTimeoutPass) {
                // Play audio and tactile haptic feedback for picked number
                com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playPickSound()
                if (isOwnPick) {
                    com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playVibration(context)
                }

                consecutiveMissedTurns.remove(pickerId)
                markPlayerReconnected(pickerId)
                pickedNumbersHistory.add(number)
                pickedByPlayerHistory.add(pickerId)
                pickedTurnIdsHistory.add(turnNumber)

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
                activeWinReason = outcome.winReason
                recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
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
                        turnIds = pickedTurnIdsHistory.toList(),
                        turnNumber = turnNumber,
                        currentTurnPlayerId = nextPlayerId,
                        chatMessages = matchChatHistory,
                        accountUid = getLocalUid()
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
                activeRunnerPlayerIds = runnerIds

                broadcastPacket(
                    RoomMessagePacket(
                        type = if (isTimeoutPass) "TURN_TIMEOUT" else "PICK_NUMBER",
                        number = number,
                        playerId = pickerId,
                        turnNumber = turnNumber,
                        pickedHistory = pickedNumbersHistory.toList(),
                        pickedByHistory = pickedByPlayerHistory.toList(),
                        turnIds = pickedTurnIdsHistory.toList(),
                        boardHash = currentBoardHash,
                        currentTurnPlayerId = nextPlayerId,
                        seed = currentMatchSeed,
                        matchId = currentMatchSeed.toString(),
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
                inActiveGameScreen && !(currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
            ) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_SYNC",
                        turnNumber = turnNumber,
                        pickedHistory = pickedNumbersHistory.toList(),
                        pickedByHistory = pickedByPlayerHistory.toList(),
                        turnIds = pickedTurnIdsHistory.toList(),
                        boardHash = engine.computeBoardHash(playerBoard),
                        currentTurnPlayerId = currentTurnPlayerId,
                        playerId = getLocalUid(),
                        seed = currentMatchSeed,
                        senderInstanceId = onlineRoomSync.instanceId,
                        isPaused = isGamePaused,
                        pauseOwnerId = pausedByPlayerName,
                        remainingTurnMillis = frozenRemainingTurnMillis ?: (turnTimer * 1000L)
                    )
                )
            }
        }
    }

    fun handleIncomingPacket(packet: RoomMessagePacket) {
        if (currentGameMode == GameMode.ONLINE_ROOM && packet.type in setOf("START_GAME", "PLAY_AGAIN") &&
            packet.players.ifEmpty { realTimePlayers }.size == 2 && (packet.protocolVersion != 2 || packet.hostWaitVersion != 1)) {
            Toast.makeText(context, "Both players need the updated multiplayer version.", Toast.LENGTH_LONG).show()
            return
        }
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2 && currentMatchSeed != 0L) {
            if (packet.type.startsWith("MATCH_V2_")) { reliableClient?.receive(packet); return }
            if (packet.type in setOf("PICK_NUMBER", "TURN_TIMEOUT", "GAME_SYNC", "GAME_SYNC_ACK", "GAME_PAUSED", "GAME_RESUMED",
                "CLAIM_VICTORY", "BINGO_CLAIMED", "SURRENDER", "REJOIN_GAME", "SYNC_REQUEST", "CHAT_MESSAGE",
                "PLAYER_DISCONNECTED", "LEAVE", "HOST_LEFT")) return
        }
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
            val isKnownParticipant = matchParticipants.isEmpty() || matchParticipants.any { it.id == packet.playerId }
            if (isKnownParticipant) {
                opponentPlayerId = packet.playerId
            }
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
                    pickedTurnIdsHistory.clear()
                    isReconcilingMatch = false
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
                        pickedTurnIdsHistory.clear()
                        isReconcilingMatch = false
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
                    pickedTurnIdsHistory.clear()
                    isReconcilingMatch = false
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
                        pickedTurnIdsHistory.clear()
                        isReconcilingMatch = false
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
                        isRunnerMatch = false
                        winnerPlayerId = ""
                        activeWinReason = ""
                        activeRunnerPlayerIds = emptyList()
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
                            val historicalTurnNumber = packet.turnIds.getOrNull(idx) ?: (idx + 1)
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPickerId)
                            pickedTurnIdsHistory.add(historicalTurnNumber)
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

                    // Ensure strict chronological ordering when packet's history is authoritative
                    if (packet.pickedHistory.size >= pickedNumbersHistory.size &&
                        packet.pickedHistory.containsAll(pickedNumbersHistory) &&
                        packet.pickedHistory != pickedNumbersHistory.toList()
                    ) {
                        pickedNumbersHistory.clear()
                        pickedNumbersHistory.addAll(packet.pickedHistory)
                        pickedByPlayerHistory.clear()
                        packet.pickedHistory.indices.forEach { i ->
                            val pId = packet.pickedByHistory.getOrNull(i)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            pickedByPlayerHistory.add(pId)
                        }
                        if (packet.turnIds.isNotEmpty()) {
                            pickedTurnIdsHistory.clear()
                            pickedTurnIdsHistory.addAll(packet.turnIds)
                        }
                    }

                    // 2. Direct single pick fallback
                    val isExpectedPicker = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, currentTurnPlayerId) ||
                            (packet.isHost && packet.number <= 0) ||
                            currentTurnPlayerId.isBlank()
                    if (!isExpectedPicker && packet.turnNumber <= turnNumber && packet.number > 0) {
                        // Discard stale out-of-turn packets from previous turns or concurrent moves that arrived after turn already rotated
                        return
                    }

                    if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                        pickedNumbersHistory.add(packet.number)
                        pickedByPlayerHistory.add(packet.playerId)
                        pickedTurnIdsHistory.add(packet.turnNumber)
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
                            activeWinReason = outcome.winReason
                            activeRunnerPlayerIds = packet.runnerPlayerIds.ifEmpty {
                                if (isGroupMatch()) {
                                    val eff = if (allPlayerBoards.containsKey(myUid)) allPlayerBoards else (allPlayerBoards + (myUid to playerBoard))
                                    eff.filter { it.value.isBingo && !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.key, outcome.winnerPlayerId) && !isPlayerDisconnected(it.key) }.keys.toList()
                                } else if (outcome.isRunner) listOf(myUid) else emptyList()
                            }
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
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
                                        turnIds = pickedTurnIdsHistory.toList(),
                                        turnNumber = turnNumber,
                                        currentTurnPlayerId = currentTurnPlayerId,
                                        chatMessages = matchChatHistory,
                                        accountUid = myUid
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
                        val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                        if (!isTwoPlayerOnline && packet.playerId.isNotBlank()) {
                            markPlayerDisconnected(packet.playerId)
                            val misses = (consecutiveMissedTurns[packet.playerId] ?: 0) + 1
                            consecutiveMissedTurns[packet.playerId] = misses
                        }
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

                        val participants = matchParticipants.ifEmpty { realTimePlayers }
                        val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                        val misses = consecutiveMissedTurns[packet.playerId] ?: 0
                        if (!isTwoPlayerOnline && activeRemaining.size <= 1 && misses >= 3) {
                            isGameOver = true
                            val wonByForfeit = activeRemaining.any { it.id == myUid } || participants.size <= 2
                            didPlayerWin = wonByForfeit
                            isDrawMatch = false
                            isRunnerMatch = false
                            winnerPlayerId = if (wonByForfeit) myUid else ""
                            activeWinReason = "Opponent Disconnected"
                            activeRunnerPlayerIds = emptyList()
                            recordFinishedMatch(wonByForfeit)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                            opponentDisconnectMessage = "Opponent disconnected. You win by forfeit!"
                        }

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
                                turnIds = pickedTurnIdsHistory.toList(),
                                turnNumber = turnNumber,
                                currentTurnPlayerId = currentTurnPlayerId,
                                chatMessages = matchChatHistory,
                                accountUid = myUid
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

            "CHAT_TYPING" -> {
                val knownPeer = matchParticipants.ifEmpty { realTimePlayers }.any {
                    com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId)
                }
                if (knownPeer && packet.playerId != myUid && !isGameOver &&
                    navController.currentDestination?.route == Screen.Game.route &&
                    (packet.seed == 0L || packet.seed == currentMatchSeed)) {
                    val isTyping = packet.displayName == "1" || packet.displayName.equals("true", ignoreCase = true)
                    if (isTyping) {
                        currentTypingPlayerId = packet.playerId
                        currentTypingPlayerName = packet.username.ifBlank { "Opponent" }
                        currentTypingTimestamp = android.os.SystemClock.elapsedRealtime()
                    } else if (currentTypingPlayerId == packet.playerId) {
                        currentTypingPlayerName = null
                        currentTypingPlayerId = ""
                    }
                }
            }

            "CHAT_MESSAGE" -> {
                if (packet.playerId == currentTypingPlayerId) {
                    currentTypingPlayerName = null
                    currentTypingPlayerId = ""
                }
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
                            chatMessages = matchChatHistory,
                            accountUid = myUid
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
                    val isHostGone = isHostLeftGame || (hostUid.isNotBlank() && isPlayerDisconnected(hostUid))
                    val activeRemaining = (matchParticipants.ifEmpty { realTimePlayers }).filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                    val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                    val isSeniorActivePeer = activeRemaining.firstOrNull { !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.id == myUid
                    if (isHosting || currentTurnPlayerId == myUid || isActingHost || isSeniorActivePeer) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "GAME_SYNC",
                                turnNumber = turnNumber,
                                pickedHistory = pickedNumbersHistory.toList(),
                                pickedByHistory = pickedByPlayerHistory.toList(),
                                turnIds = pickedTurnIdsHistory.toList(),
                                currentTurnPlayerId = currentTurnPlayerId,
                                boardHash = engine.computeBoardHash(playerBoard),
                                seed = currentMatchSeed,
                                playerId = myUid,
                                isPaused = isGamePaused,
                                pauseOwnerId = pausedByPlayerName,
                                remainingTurnMillis = frozenRemainingTurnMillis ?: (turnTimer * 1000L),
                                winnerPlayerId = if (isGameOver) winnerPlayerId else "",
                                winReason = if (isGameOver) activeWinReason else "",
                                runnerPlayerIds = if (isGameOver) activeRunnerPlayerIds else emptyList()
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
                            val historicalTurnNumber = packet.turnIds.getOrNull(idx) ?: (idx + 1)
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPickerId)
                            pickedTurnIdsHistory.add(historicalTurnNumber)
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

                    // Ensure strict chronological ordering when packet's history is authoritative
                    if (packet.pickedHistory.size >= pickedNumbersHistory.size &&
                        packet.pickedHistory.containsAll(pickedNumbersHistory) &&
                        packet.pickedHistory != pickedNumbersHistory.toList()
                    ) {
                        pickedNumbersHistory.clear()
                        pickedNumbersHistory.addAll(packet.pickedHistory)
                        pickedByPlayerHistory.clear()
                        packet.pickedHistory.indices.forEach { i ->
                            val pId = packet.pickedByHistory.getOrNull(i)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            pickedByPlayerHistory.add(pId)
                        }
                        if (packet.turnIds.isNotEmpty()) {
                            pickedTurnIdsHistory.clear()
                            pickedTurnIdsHistory.addAll(packet.turnIds)
                        }
                    }

                    // Reconcile pause state
                    if (packet.isPaused) {
                        isGamePaused = true
                        pausedByPlayerName = packet.pauseOwnerId.ifBlank { "Opponent" }
                        val remMs = packet.remainingTurnMillis
                        val remSec = if (remMs > 0L) ((remMs + 999L) / 1000L).toInt() else turnTimer
                        frozenRemainingTurnMillis = if (remMs > 0L) remMs else (turnTimer * 1000L)
                        frozenRemainingTurnSeconds = remSec
                        turnTimer = remSec
                    } else {
                        if (isGamePaused) {
                            isGamePaused = false
                            pausedByPlayerName = ""
                            frozenRemainingTurnMillis = null
                            frozenRemainingTurnSeconds = null
                        }
                        if (packet.remainingTurnMillis > 0L) {
                            val remSec = ((packet.remainingTurnMillis + 999L) / 1000L).toInt()
                            turnTimer = remSec
                        }
                    }

                    // 2. Evaluate win conditions (only if game is not already concluded)
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
                            val lastPicker = pickedByPlayerHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                                ?: packet.pickedByHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                                ?: currentTurnPlayerId
                            evaluateMatchOutcome(lastPicker)
                        }
                        if (outcome.isGameOver) {
                            isGameOver = true
                            isDrawMatch = outcome.isDraw
                            didPlayerWin = outcome.didPlayerWin
                            isRunnerMatch = outcome.isRunner
                            winnerPlayerId = outcome.winnerPlayerId
                            activeWinReason = outcome.winReason
                            activeRunnerPlayerIds = packet.runnerPlayerIds.ifEmpty {
                                if (isGroupMatch()) {
                                    val eff = if (allPlayerBoards.containsKey(myUid)) allPlayerBoards else (allPlayerBoards + (myUid to playerBoard))
                                    eff.filter { it.value.isBingo && !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.key, outcome.winnerPlayerId) && !isPlayerDisconnected(it.key) }.keys.toList()
                                } else if (outcome.isRunner) listOf(myUid) else emptyList()
                            }
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
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
                                    turnIds = pickedTurnIdsHistory.toList(),
                                    turnNumber = turnNumber,
                                    currentTurnPlayerId = currentTurnPlayerId,
                                    chatMessages = matchChatHistory,
                                    accountUid = myUid
                                )
                            }
                        }
                    }
                    isReconcilingMatch = false
                    if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                        onlineRoomSync.notifyStateSynchronized()
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "GAME_SYNC_ACK",
                                playerId = myUid,
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString()
                            )
                        )
                    }
                }
            }

            "GAME_SYNC_ACK" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                    onlineRoomSync.notifyStateSynchronized()
                }
            }

            "GAME_PAUSED" -> {
                isGamePaused = true
                pausedByPlayerName = packet.displayName.ifBlank { "Opponent" }
                val remainderMs = if (packet.remainingTurnMillis > 0L) packet.remainingTurnMillis else (turnTimer * 1000L)
                val remainderSec = ((remainderMs + 999L) / 1000L).toInt()
                frozenRemainingTurnMillis = remainderMs
                frozenRemainingTurnSeconds = remainderSec
                turnTimer = remainderSec
                if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                        context = context,
                        isPaused = true,
                        remainingTurnMillis = remainderMs,
                        pauseOwnerId = pausedByPlayerName,
                        accountUid = myUid
                    )
                }
            }

            "GAME_RESUMED" -> {
                isGamePaused = false
                pausedByPlayerName = ""
                val remainderMs = if (packet.remainingTurnMillis > 0L) packet.remainingTurnMillis else (frozenRemainingTurnMillis ?: ((frozenRemainingTurnSeconds ?: turnTimer) * 1000L))
                val remainderSec = ((remainderMs + 999L) / 1000L).toInt()
                frozenRemainingTurnMillis = null
                frozenRemainingTurnSeconds = null
                turnTimer = remainderSec
                if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                        context = context,
                        isPaused = false,
                        remainingTurnMillis = remainderMs,
                        pauseOwnerId = "",
                        accountUid = myUid
                    )
                }
            }

            "CLAIM_VICTORY" -> return // New policy accepts only the controller's retained final state.

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
                            activeWinReason = outcome.winReason
                            activeRunnerPlayerIds = packet.runnerPlayerIds.ifEmpty {
                                if (isGroupMatch()) {
                                    val eff = if (allPlayerBoards.containsKey(myUid)) allPlayerBoards else (allPlayerBoards + (myUid to playerBoard))
                                    eff.filter { it.value.isBingo && !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.key, outcome.winnerPlayerId) && !isPlayerDisconnected(it.key) }.keys.toList()
                                } else if (outcome.isRunner) listOf(myUid) else emptyList()
                            }
                            recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        }
                    }
                }
            }

            "SURRENDER" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    val surrenderId = packet.playerId
                    val surrenderName = packet.displayName.ifBlank { packet.username.ifBlank { "Opponent" } }
                    if (surrenderId.isNotBlank()) {
                        markPlayerDisconnected(surrenderId)
                        consecutiveMissedTurns[surrenderId] = 3
                    }

                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    val activeRemaining = participants.filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }

                    if (activeRemaining.size <= 1) {
                        isGameOver = true
                        val won = activeRemaining.any { isPlayerMe(it.id) } || participants.size <= 2
                        didPlayerWin = won
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = if (won) myUid else ""
                        activeWinReason = "Opponent Surrendered"
                        activeRunnerPlayerIds = emptyList()
                        recordFinishedMatch(won)
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        opponentSurrenderMessage = "$surrenderName surrendered the match! You win!"
                    } else {
                        // Group match continues among remaining active participants!
                        broadcastSystemChatMessage("🏳️ $surrenderName surrendered the match")
                        if (currentTurnPlayerId == surrenderId) {
                            val nextId = calculateNextTurnPlayerId(surrenderId)
                            currentTurnPlayerId = nextId
                            turnNumber += 1
                            turnTimer = 30
                            isMyTurn = (currentTurnPlayerId == myUid)
                            val hostUid = participants.find { it.isHost }?.id ?: ""
                            val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                            val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                            if (isHosting || isActingHost || activeRemaining.size <= 2) {
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "TURN_TIMEOUT",
                                        playerId = surrenderId,
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

            "PLAYER_DISCONNECTED" -> {
                if (packet.playerId.isNotBlank() && packet.playerId != myUid) {
                    val destinationRoute = navController.currentDestination?.route
                    val inGame = (destinationRoute == Screen.Game.route || destinationRoute == Screen.ManualBoardDesign.route)
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
                        val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                        if (currentTurnPlayerId == packet.playerId && !isTwoPlayerOnline) {
                            val misses = (consecutiveMissedTurns[packet.playerId] ?: 0) + 1
                            consecutiveMissedTurns[packet.playerId] = misses

                            if (activeRemaining.size <= 1 && misses >= 3) {
                                isGameOver = true
                                val wonByForfeit = activeRemaining.any { it.id == myUid } || participants.size <= 2
                                didPlayerWin = wonByForfeit
                                isDrawMatch = false
                                isRunnerMatch = false
                                winnerPlayerId = if (wonByForfeit) myUid else ""
                                activeWinReason = "Opponent Disconnected"
                                activeRunnerPlayerIds = emptyList()
                                recordFinishedMatch(wonByForfeit)
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
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
                val inGame = (destinationRoute == Screen.Game.route || destinationRoute == Screen.ManualBoardDesign.route)
                val isSenderHost = packet.isHost || packet.type == "HOST_LEFT" ||
                        realTimePlayers.find { it.id == packet.playerId }?.isHost == true ||
                        matchParticipants.find { it.id == packet.playerId }?.isHost == true

                if (isSenderHost && !isHosting) {
                    val hostName = packet.displayName.ifBlank { packet.username.ifBlank { "Host" } }
                    if (inGame && !isGameOver) {
                        val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                        if (isTwoPlayerOnline) {
                            val hostId = packet.playerId.ifBlank {
                                matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: ""
                            }
                            if (hostId.isNotBlank()) {
                                markPlayerDisconnected(hostId)
                            }
                            return@handleIncomingPacket
                        }
                        isHostLeftGame = true
                        val hostId = packet.playerId.ifBlank {
                            matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: ""
                        }
                        if (hostId.isNotBlank()) {
                            markPlayerDisconnected(hostId)
                            consecutiveMissedTurns[hostId] = 3
                        }
                        val participants = matchParticipants.ifEmpty { realTimePlayers }
                        val activeRemaining = participants.filter {
                            it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                        }
                        if (activeRemaining.size <= 1 || isUsingP2p) {
                            isGameOver = true
                            val wonByForfeit = if (isUsingP2p) true else (activeRemaining.any { it.id == myUid } || participants.size <= 2)
                            didPlayerWin = wonByForfeit
                            isDrawMatch = false
                            isRunnerMatch = false
                            winnerPlayerId = if (wonByForfeit) myUid else ""
                            activeWinReason = if (isUsingP2p) "Host Disconnected" else "Opponent Left"
                            activeRunnerPlayerIds = emptyList()
                            recordFinishedMatch(wonByForfeit)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                            opponentDisconnectMessage = if (isUsingP2p) "Host disconnected. You win by forfeit!" else "All opponents left the game."
                        } else {
                            // Host left, but 2+ players remain! Match continues!
                            val hostMsg = "👑 $hostName (Host) left the game"
                            broadcastSystemChatMessage(hostMsg)
                            val isActingHost = activeRemaining.firstOrNull()?.id == myUid
                            if (isActingHost) {
                                isHosting = true
                            }
                            if (currentTurnPlayerId == hostId) {
                                val nextId = calculateNextTurnPlayerId(hostId)
                                currentTurnPlayerId = nextId
                                turnNumber += 1
                                turnTimer = 30
                                isMyTurn = (currentTurnPlayerId == myUid)
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
                    val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                    if (isTwoPlayerOnline) {
                        markPlayerDisconnected(packet.playerId)
                        return@handleIncomingPacket
                    }
                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    markPlayerDisconnected(packet.playerId)
                    consecutiveMissedTurns[packet.playerId] = 3
                    val activeRemaining = participants.filter {
                        it.id.isNotBlank() && !isPlayerDisconnected(it.id)
                    }

                    if (activeRemaining.size <= 1) {
                        isGameOver = true
                        val wonByForfeit = activeRemaining.any { it.id == myUid } || participants.size <= 2
                        didPlayerWin = wonByForfeit
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = if (wonByForfeit) myUid else ""
                        activeWinReason = "Opponent Left"
                        activeRunnerPlayerIds = emptyList()
                        recordFinishedMatch(wonByForfeit)
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        opponentDisconnectMessage = "Opponent left the match. You win by forfeit!"
                    } else {
                        val hostUid = participants.find { it.isHost }?.id ?: ""
                        val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                        val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                        if (isActingHost) {
                            isHosting = true
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
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    val hostUid = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: ""
                    val isHostGone = isHostLeftGame || (hostUid.isNotBlank() && isPlayerDisconnected(hostUid))
                    val activeRemaining = (matchParticipants.ifEmpty { realTimePlayers }).filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                    val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == myUid
                    val isSeniorActivePeer = activeRemaining.firstOrNull { !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it.id, packet.playerId) }?.id == myUid
                    if (isHosting || currentTurnPlayerId == myUid || isActingHost || isSeniorActivePeer) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "GAME_SYNC",
                                turnNumber = turnNumber,
                                pickedHistory = pickedNumbersHistory.toList(),
                                pickedByHistory = pickedByPlayerHistory.toList(),
                                turnIds = pickedTurnIdsHistory.toList(),
                                boardHash = engine.computeBoardHash(playerBoard),
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString(),
                                currentTurnPlayerId = currentTurnPlayerId,
                                playerId = myUid,
                                isPaused = isGamePaused,
                                remainingTurnMillis = (frozenRemainingTurnSeconds ?: turnTimer) * 1000L,
                                pauseOwnerId = pausedByPlayerName,
                                claimEpisodeCount = interruptionEpisodeCount,
                                winnerPlayerId = if (isGameOver) winnerPlayerId else "",
                                winReason = if (isGameOver) activeWinReason else "",
                                runnerPlayerIds = if (isGameOver) activeRunnerPlayerIds else emptyList()
                            )
                        )
                    }
                }
            }

            "KICK_PLAYER" -> {
                val targetId = packet.targetPlayerId.ifBlank { packet.playerId }
                if (targetId == myUid) {
                    Toast.makeText(context, "You were removed from the lobby by the host.", Toast.LENGTH_LONG).show()
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
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
                androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
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
                    restoreAndRejoinOngoingMatch(matchData)
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
                                val matchData = com.bingo.multiplayer.domain.network.OngoingMatchStore.getOngoingMatch(context, getLocalUid())
                                if (matchData != null && matchData.roomCode == cleanCode && matchData.playerBoard != null) {
                                    restoreAndRejoinOngoingMatch(matchData)
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
            val ongoingMatchData = com.bingo.multiplayer.domain.network.OngoingMatchStore.getOngoingMatch(context, getLocalUid())
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
                pingMs = if (currentGameMode == GameMode.ONLINE_ROOM) onlinePingMs else null,
                inactivityResetToken = lobbyInactivityResetToken,
                isNearbyNetwork = (currentGameMode == GameMode.NEARBY_NETWORK),
                hasActiveMatch = hasActiveOngoing,
                onRejoinMatch = {
                    val data = ongoingMatchData ?: return@LobbyScreen
                    restoreAndRejoinOngoingMatch(data)
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
                isCheckingCompatibility = isCheckingMatchCompatibility,
                onStartGame = start@{
                    if (isCheckingMatchCompatibility) return@start
                    val expectedRoom = roomCode
                    val expectedUid = getLocalUid()
                    val expectedMode = currentGameMode
                    val expectedPlayers = realTimePlayers.map { it.id }.sorted()
                    isCheckingMatchCompatibility = true
                    coroutineScope.launch {
                        try {
                            if (expectedMode == GameMode.ONLINE_ROOM && expectedPlayers.size == 2 &&
                                !onlineRoomSync.awaitReliableCompatibility(expectedPlayers, expectedUid)) {
                                if (roomCode != expectedRoom || getLocalUid() != expectedUid ||
                                    navController.currentDestination?.route != Screen.Lobby.route) return@launch
                                val message = when (onlineRoomSync.reliableCompatibilityStatus(expectedPlayers, expectedUid)) {
                                    com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.INCOMPATIBLE ->
                                        "Both players need the updated multiplayer app."
                                    com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement.Status.INVALID_PARTICIPANTS ->
                                        "The lobby changed. Please leave and rejoin the room."
                                    else -> "Waiting for the opponent's connection. Please try Start Match again."
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            if (roomCode != expectedRoom || getLocalUid() != expectedUid || currentGameMode != expectedMode ||
                                realTimePlayers.map { it.id }.sorted() != expectedPlayers ||
                                navController.currentDestination?.route != Screen.Lobby.route ||
                                !com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.canStartMatch(realTimePlayers)) return@launch
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

                            val isTwoPlayer = (currentGameMode == GameMode.ONLINE_ROOM && sanitizedList.size == 2)
                            val startPacket = RoomMessagePacket(
                                type = "START_GAME",
                                boardSize = targetSize,
                                seed = seed,
                                matchId = seed.toString(),
                                protocolVersion = if (isTwoPlayer) 2 else 0,
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
                                pickedByPlayerHistory.clear()
                                pickedTurnIdsHistory.clear()
                                isReconcilingMatch = false
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
                                isRunnerMatch = false
                                winnerPlayerId = ""
                                activeWinReason = ""
                                activeRunnerPlayerIds = emptyList()
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
                        } finally {
                            isCheckingMatchCompatibility = false
                        }
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
                    ?: matchParticipants.firstOrNull { it.id != getLocalUid() }
            } else null

            val opponentDisplayName = when (currentGameMode) {
                GameMode.AI_EASY -> "AI (Easy)"
                GameMode.AI_HARD -> "AI (Master)"
                GameMode.ONLINE_ROOM -> opponentPlayer?.displayName ?: "Opponent"
                GameMode.NEARBY_NETWORK -> opponentPlayer?.displayName ?: "Nearby Peer"
            }

            val participants = getActiveParticipants().filter { !isPlayerDisconnected(it.id) }
            val expectedUids = participants.map { it.id }.filter { it.isNotBlank() }.toSet()
            val readyCount = expectedUids.count { allPlayerBoards.containsKey(it) }
            val totalCount = expectedUids.size.coerceAtLeast(2)
            val allReady = expectedUids.isNotEmpty() && expectedUids.all { allPlayerBoards.containsKey(it) }

            LaunchedEffect(isGameOver) {
                if (isGameOver) {
                    navController.navigate(Screen.Game.route) {
                        popUpTo(Screen.ManualBoardDesign.route) { inclusive = true }
                    }
                }
            }

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

                    val curExpected = getActiveParticipants().filter { !isPlayerDisconnected(it.id) }.map { it.id }.filter { it.isNotBlank() }.toSet()
                    val curAllReady = (currentGameMode == GameMode.AI_EASY || currentGameMode == GameMode.AI_HARD) ||
                            (curExpected.isNotEmpty() && curExpected.all { updatedBoards.containsKey(it) })

                    if (curAllReady && countdownSeconds < 0) {
                        startCountdownAndInitiateTurn()
                    } else {
                        coroutineScope.launch {
                            while (isActive && isLocalBoardReady && countdownSeconds < 0) {
                                val latestExpected = getActiveParticipants().filter { !isPlayerDisconnected(it.id) }.map { it.id }.filter { it.isNotBlank() }.toSet()
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
                if (isHostDisconnected) return@BackHandler
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
                    pickedTurnIdsHistory.clear()
                    isReconcilingMatch = false
                    frozenRemainingTurnMillis = null
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
            LaunchedEffect(turnNumber, isMyTurn, isGameOver, isGamePaused, isReconcilingMatch) {
                if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) return@LaunchedEffect
                if (!isGameOver && !isGamePaused && !isReconcilingMatch) {
                    val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                    if (frozenRemainingTurnSeconds != null) {
                        turnTimer = frozenRemainingTurnSeconds!!
                        frozenRemainingTurnSeconds = null
                    } else {
                        turnTimer = 30
                    }
                    var skippedDueToDisconnect = false
                    while (turnTimer > 0 && !isGamePaused && !isGameOver && !isReconcilingMatch) {
                        delay(1000L)
                        if (!isGamePaused && !isGameOver && !isReconcilingMatch) {
                            if (isTwoPlayerOnline && !onlineRoomSync.isConnected) {
                                // Suspend turn timer if local transport is disconnected
                                continue
                            }
                            turnTimer -= 1
                            // Check if current active player's connection is dead (> 10s of silence)
                            val isForeground = com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value
                            val now = System.currentTimeMillis()
                            val isWithinResumeGracePeriod = (now - lastForegroundResumeTimestamp) < 12_000L

                            if (!isTwoPlayerOnline && isForeground && !isWithinResumeGracePeriod && !isMyTurn && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
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
                    if (skippedDueToDisconnect && !isGameOver && !isGamePaused && !isReconcilingMatch) {
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
                            winnerPlayerId = if (wonByForfeit) getLocalUid() else ""
                            activeWinReason = "Opponent Disconnected"
                            activeRunnerPlayerIds = emptyList()
                            recordFinishedMatch(wonByForfeit)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
                            opponentDisconnectMessage = "Opponent disconnected. You win by forfeit!"
                        } else {
                            val hostUid = activeList.find { it.isHost }?.id ?: ""
                            val isLegacyHostTurn = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(activePicker, hostUid)
                            val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                            val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == getLocalUid()
                            val isAuthorizedSkipper = isHosting || isLegacyHostTurn || isActingHost || activeRemaining.size <= 2
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
                    } else if (isMyTurn && !isGameOver && !isGamePaused && !isReconcilingMatch && turnTimer == 0) {
                        // Pass the turn cleanly with no auto-picked number
                        executePick(
                            number = -1,
                            isOwnPick = true,
                            pickerId = getLocalUid(),
                            shouldBroadcast = true
                        )
                    } else if (!isMyTurn && !isGameOver && !isGamePaused && !isReconcilingMatch && turnTimer == 0) {
                        if (isTwoPlayerOnline) {
                            // F001: For 1v1 online, eliminate 2000ms delay and activeRemaining <= 2 shortcut. Turn expires at exactly 30s.
                            val activeList = onlineRoomSync.players.value
                            val hostUid = activeList.find { it.isHost }?.id ?: ""
                            val isHostGone = isHostLeftGame || isPlayerDisconnected(hostUid)
                            val isActingHost = isHostGone
                            if (isHosting || isActingHost) {
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
                        } else {
                            // Non-1v1 multi-player / LAN fail-safe grace period
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
            }

            // Presentation observes sync completion too, which need not create a policy decision.
            LaunchedEffect(currentMatchSeed, isGameOver) {
                if (currentGameMode != GameMode.ONLINE_ROOM || matchParticipants.size != 2) return@LaunchedEffect
                while (!isGameOver && isActive) {
                    onlineRoomSync.matchHeartbeatPolicy?.toSnapshot()?.takeIf { it.matchId == currentMatchSeed.toString() }?.let {
                        peerAvailability = it.state
                        peerStateSynchronized = it.isStateSynchronized
                    }
                    delay(250L)
                }
            }

            // Collect 1v1 Online Match Heartbeat Decisions
            LaunchedEffect(currentMatchSeed, isGameOver) {
                if (isGameOver) {
                    isOpponentUnreachable = false
                    isClaimVictoryEligible = false
                    interruptionEpisodeCount = 0
                } else {
                    onlineRoomSync.matchHeartbeatDecisions.collect { decision ->
                        if (decision is HeartbeatDecision.NoOp) return@collect
                        val policy = onlineRoomSync.matchHeartbeatPolicy ?: return@collect
                        val observingUid = getLocalUid()
                        val observingSeed = currentMatchSeed
                        var observed = policy.toSnapshot()
                        if (observed.state == PeerAvailabilityState.UNREACHABLE && reliableClient?.isHost == false) reliableClient?.waitForHost()
                        // Persist the episode before presenting its actions. Retry a failed write
                        // without publishing eligibility, and never write into a later match/account.
                        while (isActive) {
                            val saved = withContext(Dispatchers.IO) {
                                OngoingMatchStore.updateMatchGameState(context = context,
                                    committedEpisodeCount = observed.committedEpisodeCount,
                                    currentEpisodeId = observed.currentEpisodeId,
                                    isEpisodeOpen = observed.isEpisodeOpen,
                                    peerAvailabilityState = observed.state.name,
                                    clearNullableEpisodeFields = true,
                                    expectedMatchSeed = observingSeed, accountUid = observingUid)
                            }
                            if (getLocalUid() != observingUid || currentMatchSeed != observingSeed || onlineRoomSync.matchHeartbeatPolicy !== policy) return@collect
                            if (saved) {
                                val latest = policy.toSnapshot()
                                if (latest.committedEpisodeCount == observed.committedEpisodeCount && latest.currentEpisodeId == observed.currentEpisodeId &&
                                    latest.isEpisodeOpen == observed.isEpisodeOpen && latest.state == observed.state) break
                                observed = latest
                                continue
                            }
                            isClaimVictoryEligible = false
                            isOpponentUnreachable = false
                            reliableClient?.requestSync()
                            delay(500)
                            observed = policy.toSnapshot()
                        }
                        prepareMatchPresentation(observingSeed)
                        peerAvailability = observed.state
                        peerStateSynchronized = observed.isStateSynchronized
                        val peerName = matchParticipants.find { it.id == policy.peerUid }?.displayName ?: "Opponent"
                        if (observed.state == PeerAvailabilityState.UNREACHABLE && observed.isLocalHealthy) {
                            reportMatchStatus("disconnect:${observed.currentEpisodeId}", "$peerName disconnected (warning ${observed.committedEpisodeCount})")
                            if (reliableClient?.isHost == true) savePrompt(InterruptionPromptState.open(
                                interruptionPrompt, observed.currentEpisodeId.orEmpty(), observed.committedEpisodeCount,
                                System.currentTimeMillis(), isGamePaused))
                        } else if (observed.state in setOf(PeerAvailabilityState.RECOVERING, PeerAvailabilityState.RESPONSIVE) && observed.committedEpisodeCount > 0) {
                            reportMatchStatus("reconnect:${observed.committedEpisodeCount}", "$peerName reconnected")
                        }
                        isOpponentUnreachable = observed.state == PeerAvailabilityState.UNREACHABLE && policy.isLocalHealthy
                        interruptionEpisodeCount = observed.committedEpisodeCount
                        isClaimVictoryEligible = policy.canClaimVictory(observingUid) && reliableClient?.isHost == true
                        reliableClient?.peerEpisode(policy.peerUid, MatchEpisode(observed.committedEpisodeCount,
                            observed.currentEpisodeId.orEmpty(), observed.isEpisodeOpen,
                            observed.state == PeerAvailabilityState.UNREACHABLE))
                        // A peer can return without either MQTT connection dropping (e.g. lost
                        // probes). A new game-state handshake must complete before host Resume.
                        if (observed.state == PeerAvailabilityState.RECOVERING && !observed.isStateSynchronized)
                            reliableClient?.requestSync()
                        if (observed.state == PeerAvailabilityState.UNREACHABLE && reliableClient?.isHost == false) reliableClient?.waitForHost()
                    }
                }
            }

            val opponentPlayer = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                realTimePlayers.firstOrNull { it.id != getLocalUid() }
                    ?: matchParticipants.firstOrNull { it.id != getLocalUid() }
            } else null

            val opponentDisplayName = when (currentGameMode) {
                GameMode.AI_EASY -> "AI (Easy)"
                GameMode.AI_HARD -> "AI (Master)"
                GameMode.ONLINE_ROOM -> opponentPlayer?.displayName ?: "Opponent"
                GameMode.NEARBY_NETWORK -> opponentPlayer?.displayName ?: "Nearby Peer"
            }
            val opponentAvatarUrl = opponentPlayer?.avatarUrl
            // Avatar lookup uses account identity; display names need not be unique usernames.
            val opponentUsername = opponentPlayer?.username?.takeIf { it.isNotBlank() }
            val myAvatarUrl = getPlayerAvatarUrl()
            val myUsername = currentAuthUser?.username
            val myDisplayName = getPlayerDisplayName()

            val reliable1v1 = currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2
            val isCurrentHost = if (reliable1v1) reliableClient?.isHost ?: (matchParticipants.singleOrNull { it.isHost }?.id == getLocalUid())
                else (currentGameMode != GameMode.ONLINE_ROOM) || isHosting
            val peerConnectionText = when (peerAvailability) {
                PeerAvailabilityState.UNREACHABLE -> "$opponentDisplayName disconnected"
                PeerAvailabilityState.RECOVERING -> if (peerStateSynchronized) "$opponentDisplayName reconnected" else "$opponentDisplayName reconnected; synchronizing…"
                PeerAvailabilityState.RESPONSIVE -> "$opponentDisplayName online"
                PeerAvailabilityState.LOCAL_UNAVAILABLE -> "Checking connection…"
                PeerAvailabilityState.SYNCING -> "Synchronizing with $opponentDisplayName…"
            }
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
                hostDisconnected = currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2 && isHostDisconnected && !isGameOver,
                gameplaySuspended = isReconcilingMatch,
                useAcceptedChatHistory = currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2,
                canControlPause = !reliable1v1 || isCurrentHost,
                canResumeGame = !reliable1v1 || canResumeReliableMatch(),
                opponentConnectionStatus = if (reliable1v1) peerConnectionText else "",
                hostName = matchParticipants.singleOrNull { it.isHost }?.displayName ?: "Host",
                interruptionPrompt = interruptionPrompt,
                onContinueCountdown = { savePrompt(interruptionPrompt.decide()) },
                board = playerBoard,
                opponentBoard = opponentBoard,
                allPlayerBoards = allPlayerBoards,
                players = gamePlayers,
                currentTurnPlayerId = currentTurnPlayerId,
                disconnectedPlayerIds = disconnectedPlayerIds.toList(),
                typingPlayerName = currentTypingPlayerName,
                onSetTyping = { typing ->
                    if (!isGameOver && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                        broadcastPacket(RoomMessagePacket(
                            type = "CHAT_TYPING", playerId = getLocalUid(),
                            username = getPlayerDisplayName(), displayName = if (typing) "1" else "0",
                            seed = currentMatchSeed, timestamp = System.currentTimeMillis()
                        ))
                    }
                },
                isMyTurn = isMyTurn,
                turnTimeRemaining = turnTimer,

                isGamePaused = isGamePaused,
                pausedByPlayerName = pausedByPlayerName,
                onTogglePause = { togglePause() },
                isOpponentUnreachable = isOpponentUnreachable && reliableClient?.isHost != false,
                isClaimVictoryEligible = isClaimVictoryEligible,
                onPauseGame = { handlePauseAction(true) },
                onResumeGame = { handlePauseAction(false) },
                onClaimVictory = { executeClaimVictory() },
                recentPick = recentPick,
                pickedNumbersHistory = pickedNumbersHistory.toList(),
                matchSeed = currentMatchSeed,
                opponentName = opponentDisplayName,
                isGameOver = isGameOver,
                didPlayerWin = didPlayerWin,
                isRunner = isRunnerMatch,
                winnerPlayerId = winnerPlayerId,
                isHost = isCurrentHost,
                pingMs = currentPing,
                wantsToPlayAgainName = wantsToPlayAgainPlayerName,
                incomingEmote = latestIncomingEmote,
                incomingEmoteScale = latestIncomingEmoteScale,
                incomingEmoteTimestamp = latestIncomingEmoteTimestamp,
                onSendEmote = { emoji, scaleMultiplier ->
                    if (isHostDisconnected) return@GameScreen
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
                onSendChatMessage = chat@{ messageText ->
                    if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                        reliableClient?.request("CHAT", text = messageText)
                        return@chat
                    }
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
                            chatMessages = matchChatHistory,
                            accountUid = getLocalUid()
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
                onSurrender = surrender@{
                    if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                        reliableClient?.request("SURRENDER")
                        return@surrender
                    }
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
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = true }
                    }
                },
                onCellPicked = { cellNumber ->
                    if (!isReconcilingMatch) {
                        executePick(
                            number = cellNumber,
                            isOwnPick = true,
                            pickerId = getLocalUid(),
                            shouldBroadcast = true
                        )
                    }
                },
                onSyncGame = sync@{
                    if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                        reliableClient?.requestSync()
                        return@sync
                    }
                    coroutineScope.launch {
                        Toast.makeText(context, "Syncing match state…", Toast.LENGTH_SHORT).show()
                        isReconcilingMatch = true
                        onlineRoomSync.refreshNow()
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SYNC_REQUEST",
                                playerId = getLocalUid(),
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString()
                            )
                        )
                    }
                },
                onPlayAgain = {
                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        val hostUid = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: ""
                        val isHostGone = isHostLeftGame || (hostUid.isNotBlank() && isPlayerDisconnected(hostUid))
                        val activeRemaining = (matchParticipants.ifEmpty { realTimePlayers }).filter { it.id.isNotBlank() && !isPlayerDisconnected(it.id) }
                        val isActingHost = isHostGone && activeRemaining.firstOrNull()?.id == getLocalUid()
                        if (isActingHost) {
                            isHosting = true
                        }
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
                                pickedByPlayerHistory.clear()
                                pickedTurnIdsHistory.clear()
                                isReconcilingMatch = false
                                frozenRemainingTurnMillis = null
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
                                isRunnerMatch = false
                                winnerPlayerId = ""
                                activeWinReason = ""
                                activeRunnerPlayerIds = emptyList()
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
                    reliableClient?.leave(); reliableClient = null; isHostDisconnected = false; reliableStartAttempt++
                    onlineRoomSync.stopMatchHeartbeat()
                    val wasOver = isGameOver
                    if (wasOver) {
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
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
                                chatMessages = matchChatHistory,
                                accountUid = getLocalUid()
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
                    activeWinReason = ""
                    activeRunnerPlayerIds = emptyList()
                    currentMatchSeed = 0L
                    allPlayerBoards = emptyMap()
                    pickedNumbersHistory.clear()
                    pickedByPlayerHistory.clear()
                    pickedTurnIdsHistory.clear()
                    isReconcilingMatch = false
                    frozenRemainingTurnMillis = null
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
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
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
                                    chatMessages = matchChatHistory,
                                    accountUid = getLocalUid()
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
                        activeWinReason = ""
                        activeRunnerPlayerIds = emptyList()
                        reliableClient?.leave(); reliableClient = null; isHostDisconnected = false; reliableStartAttempt++
                        onlineRoomSync.stopMatchHeartbeat()
                        currentMatchSeed = 0L
                        pickedNumbersHistory.clear()
                        pickedByPlayerHistory.clear()
                        pickedTurnIdsHistory.clear()
                        isReconcilingMatch = false
                        frozenRemainingTurnMillis = null
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
                com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playVibration(context, durationMs = 200L)
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
    if (showLeaveMatchDialog && !isHostDisconnected) {
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
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
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
