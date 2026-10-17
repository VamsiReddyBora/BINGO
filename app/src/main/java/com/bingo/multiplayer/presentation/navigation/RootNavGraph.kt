package com.bingo.multiplayer.presentation.navigation

import android.widget.Toast
import com.bingo.multiplayer.domain.notifications.*
import com.bingo.multiplayer.domain.network.toEvent
import com.bingo.multiplayer.domain.network.toInvite
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
import com.bingo.multiplayer.domain.network.MultiplayerSnapshotCheckpoint
import com.bingo.multiplayer.domain.network.MultiplayerSnapshotReconciler
import com.bingo.multiplayer.domain.network.MultiplayerGameplayReducer
import com.bingo.multiplayer.domain.network.MultiplayerGameplayContext
import com.bingo.multiplayer.domain.network.MultiplayerResultAuthority
import com.bingo.multiplayer.domain.network.MultiplayerLifecyclePacketGate
import com.bingo.multiplayer.domain.network.MultiplayerGameplayState
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
        isUsingP2p = false
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
    var manualStartJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var awaitingHostStateSync by remember { mutableStateOf(false) }

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

    // Multiplayer Match Lifecycle & Host Referee State (3-8 players)
    var hostAbsenceHoldRemainingSeconds by remember { mutableIntStateOf(0) }
    var hostReturningCountdown by remember { mutableStateOf<Int?>(null) }
    var pauseRemainingSeconds by remember { mutableIntStateOf(0) }
    var canExtendPause by remember { mutableStateOf(false) }
    var turnInactivityPlayerId by remember { mutableStateOf<String?>(null) }
    var turnInactivityAutoSkipSeconds by remember { mutableIntStateOf(10) }
    var eliminatedPlayerIds by remember { mutableStateOf(setOf<String>()) }
    var multiplayerLifecyclePolicy by remember { mutableStateOf<com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy?>(null) }
    var frozenTurnNumber by remember { mutableIntStateOf(-1) }
    var frozenMatchSeed by remember { mutableLongStateOf(0L) }
    var lastAppliedPauseTimestamp by remember { mutableLongStateOf(0L) }
    var multiplayerSnapshotCheckpoint by remember { mutableStateOf(MultiplayerSnapshotCheckpoint()) }
    val multiplayerPacketGate = remember { MultiplayerLifecyclePacketGate() }
    val lifecycleSendSequence = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var optimisticPendingPickNumber by remember { mutableStateOf<Int?>(null) }

    val isMultiplayerSuspended = isReconcilingMatch || hostAbsenceHoldRemainingSeconds > 0 || (hostReturningCountdown != null && hostReturningCountdown!! > 0)


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
        if (playerId in multiplayerSnapshotCheckpoint.surrenderedPlayerIds) return true
        return disconnectedPlayerIds.any { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, playerId) }
    }

    fun markPlayerDisconnected(playerId: String) {
        if (playerId.isBlank()) return
        if (!isPlayerDisconnected(playerId)) {
            disconnectedPlayerIds.add(playerId)
        }
    }

    fun markPlayerReconnected(playerId: String): Boolean {
        if (playerId.isBlank() || playerId in multiplayerSnapshotCheckpoint.surrenderedPlayerIds) return false
        consecutiveMissedTurns.remove(playerId)
        val toRemove = disconnectedPlayerIds.filter { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, playerId) }
        if (toRemove.isNotEmpty()) {
            disconnectedPlayerIds.removeAll(toRemove.toSet())
            return true
        }
        return false
    }

    fun persistMultiplayerControl(): Boolean {
        if (currentGameMode != GameMode.ONLINE_ROOM || matchParticipants.size <= 2 || roomCode.isBlank()) return true
        val policy = multiplayerLifecyclePolicy
        return OngoingMatchStore.updateMatchGameState(
            context = context, accountUid = getLocalUid(), expectedMatchSeed = currentMatchSeed,
            isPaused = isGamePaused,
            remainingTurnMillis = (if (isGamePaused) frozenRemainingTurnMillis else null) ?: (turnTimer * 1000L),
            turnNumber = turnNumber,
            currentTurnPlayerId = currentTurnPlayerId,
            pauseOwnerId = pausedByPlayerName,
            pauseRemainingSeconds = if (isGamePaused) (policy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
            pauseDeadlineTimestamp = if (isGamePaused && policy != null) System.currentTimeMillis() + policy.pauseRemainingMillis else null,
            pauseControlRevision = policy?.pauseEpisode,
            versionedPauseControl = policy?.hasVersionedPauseControl,
            pauseControlTimestamp = maxOf(policy?.lastAppliedPauseTimestamp ?: 0L, lastAppliedPauseTimestamp),
            multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint,
            hostHoldRemainingSeconds = policy?.hostHoldRemainingSeconds
        )
    }

    val broadcastPacket: (RoomMessagePacket) -> Unit = publish@ { original ->
        val inMatchScreen = navController.currentDestination?.route in setOf(Screen.Game.route, Screen.ManualBoardDesign.route)
        val outgoingSeed = if (original.type in setOf("HOST_LEFT", "PLAYER_DISCONNECTED") && !inMatchScreen) 0L else currentMatchSeed
        var packet = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)
            MultiplayerGameplayReducer.prepareOutgoing(original, outgoingSeed, getLocalUid()) else original
        val legacyNetworkMatch = currentGameMode == GameMode.NEARBY_NETWORK ||
            (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size > 2)
        if (legacyNetworkMatch && packet.type in MultiplayerLifecyclePacketGate.scopedTypes) {
            packet = packet.copy(senderInstanceId = packet.senderInstanceId.ifBlank { onlineRoomSync.instanceId },
                roomCode = packet.roomCode.ifBlank { roomCode },
                lifecycleSequence = packet.lifecycleSequence.takeIf { it > 0L } ?: lifecycleSendSequence.incrementAndGet())
            // A delayed resend retains the match in which it was constructed.
            if (inMatchScreen && packet.seed != currentMatchSeed) return@publish
        }
        if (legacyNetworkMatch && isHosting && currentMatchSeed != 0L &&
            packet.type in setOf("GAME_SYNC", "GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND")) {
            if (packet.type == "GAME_SYNC") {
                multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(revision = multiplayerSnapshotCheckpoint.revision + 1L)
                packet = packet.copy(sequence = multiplayerSnapshotCheckpoint.revision,
                    surrenderedPlayerIds = multiplayerSnapshotCheckpoint.surrenderedPlayerIds.toList(),
                    winnerPlayerId = if (isGameOver) winnerPlayerId else packet.winnerPlayerId,
                    isMatchFinished = isGameOver,
                    winReason = if (isGameOver) activeWinReason else packet.winReason,
                    runnerPlayerIds = if (isGameOver) activeRunnerPlayerIds else packet.runnerPlayerIds)
            }
            packet = packet.copy(seed = currentMatchSeed, matchId = currentMatchSeed.toString(),
                pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: packet.pauseEpisode)
            // Commit the ordering fence before publishing so a restored host cannot reuse it.
            if (!isGameOver && !persistMultiplayerControl()) return@publish
        }
        if (isUsingP2p) lanP2pSync.broadcastPacket(packet) else onlineRoomSync.broadcastPacket(packet)
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
                    seed = currentMatchSeed,
                    matchId = currentMatchSeed.toString(),
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
            val inviteListener = com.bingo.multiplayer.domain.network.GameInviteManager.startInviteListener(username) {
                NotificationRuntime.account(context)?.let { NotificationWork.sync(context, it) }
            }
            val requestListener = friendsRepository.startListeningForRequests(
                myUsername = username,
                onNewRequest = { req -> NotificationRuntime.account(context)?.let {
                    NotificationWork.sync(context, it, NotificationPolicy.eventId("friend_request", req.id))
                } },
                onFriendAccepted = { NotificationRuntime.account(context)?.let { NotificationWork.sync(context, it) } }
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
            } else {
                if (isHosting && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    frozenRemainingTurnMillis = (turnTimer * 1000L)
                    persistMultiplayerControl()
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
                    NotificationRuntime.account(context)?.let { NotificationWork.sync(context, it) }
                    friendsRepository.syncFriendsAndRequests(u)
                } catch (_: Exception) {}
                delay(2000L)
            }
        }
    }

    fun acceptAndJoinRoom(targetRoomCode: String, sourceInvite: com.bingo.multiplayer.domain.network.GameInvite? = null) {
        if (isJoiningRoom) return
        val cleanCode = targetRoomCode.trim().uppercase()
        val account = NotificationRuntime.account(context)
        val activeMatch = ((navController.currentDestination?.route == Screen.Game.route ||
            navController.currentDestination?.route == Screen.ManualBoardDesign.route) && !isGameOver) || NotificationRuntime.isBusy(context)
        if (!NotificationPolicy.canJoinInvite(activeMatch, roomCode == cleanCode, account != null)) {
            if (activeMatch) Toast.makeText(context, "Finish your current match before accepting another invitation.", Toast.LENGTH_LONG).show()
            return
        }
        if (sourceInvite != null && (NotificationRuntime.ledger(context).handled(account!!.uid, sourceInvite.eventId) || !NotificationPolicy.eligible(sourceInvite.toEvent(), account, System.currentTimeMillis()) ||
            !NotificationRuntime.finishInvite(context, sourceInvite))) {
            Toast.makeText(context, "This invitation has expired or is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        isJoiningRoom = true
        incomingInvite = null
        com.bingo.multiplayer.domain.network.GameInviteManager.clearForegroundInvite()
        com.bingo.multiplayer.domain.network.BingoNotificationManager.cancelInvites(context)
        Toast.makeText(context, "Joining room #$cleanCode...", Toast.LENGTH_SHORT).show()

        coroutineScope.launch {
            try {
                // Ensure auth has settled to prevent guest fallback
                val user = authRepository.awaitAuthenticatedUser(2500L)
                if (user?.uid != account?.uid || NotificationRuntime.account(context) != account) return@launch
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
                if (NotificationRuntime.account(context) != account || NotificationRuntime.isBusy(context)) return@launch
                when (result) {
                    is com.bingo.multiplayer.domain.network.RoomJoinResult.Success -> {
                        incomingInvite = null
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
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isJoiningRoom = false
            }
        }
    }

    LaunchedEffect(currentAuthUser?.uid, currentAuthUser?.username) {
        incomingInvite = null
        NotificationRuntime.bind(context, currentAuthUser?.uid, currentAuthUser?.username)
    }
    LaunchedEffect(currentRoute, isGameOver, currentMatchSeed) {
        val active = (currentRoute == Screen.Game.route || currentRoute == Screen.ManualBoardDesign.route) && !isGameOver
        NotificationRuntime.updateMatchActive(context, active)
        if (active) incomingInvite = null
    }
    val notificationAction by com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingAction.collectAsState()
    var notificationFriendTarget by remember { mutableStateOf<String?>(null) }
    var notificationRequestsTarget by remember { mutableStateOf(false) }
    LaunchedEffect(notificationAction, currentAuthUser?.uid, currentRoute) {
        val action = notificationAction ?: return@LaunchedEffect
        val account = NotificationRuntime.account(context) ?: return@LaunchedEffect
        if (currentAuthUser?.uid != account.uid || currentRoute in listOf(Screen.AuthGate.route, Screen.Login.route)) return@LaunchedEffect
        com.bingo.multiplayer.domain.network.BingoNotificationManager.pendingAction.value = null
        if (account.generation != action.generation || !NotificationPolicy.eligible(action.event, account, System.currentTimeMillis())) return@LaunchedEffect
        if (NotificationRuntime.isBusy(context)) {
            Toast.makeText(context, "Finish your current match before opening this notification.", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        when (action.action) {
            com.bingo.multiplayer.domain.network.BingoNotificationManager.ACTION_ACCEPT_INVITE -> acceptAndJoinRoom(action.event.roomCode, action.event.toInvite())
            com.bingo.multiplayer.domain.network.BingoNotificationManager.ACTION_VIEW_INVITE -> {
                notificationRequestsTarget = true
                navController.navigate(Screen.Dashboard.route) { launchSingleTop = true }
                NotificationWork.sync(context, account, action.event.id)
            }
            com.bingo.multiplayer.domain.network.BingoNotificationManager.ACTION_INVITE_FRIEND -> {
                if (NotificationRuntime.isFriend(context, action.event.senderUsername)) {
                    notificationFriendTarget = action.event.senderUsername
                    navController.navigate(Screen.Dashboard.route) { launchSingleTop = true }
                }
            }
            com.bingo.multiplayer.domain.network.BingoNotificationManager.ACTION_VIEW_FRIENDS -> {
                notificationRequestsTarget = true
                navController.navigate(Screen.Dashboard.route) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(Unit) {
        com.bingo.multiplayer.domain.network.GameInviteManager.foregroundInviteFlow.collect { invite ->
            val account = NotificationRuntime.account(context) ?: return@collect
            if (!NotificationRuntime.isBusy(context) &&
                com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowInAppInvite(context) &&
                NotificationPolicy.eligible(invite.toEvent(), account, System.currentTimeMillis()) &&
                !NotificationRuntime.ledger(context).handled(account.uid, invite.eventId)) incomingInvite = invite
        }
    }
    LaunchedEffect(incomingInvite?.eventId) {
        val invite = incomingInvite ?: return@LaunchedEffect
        delay((invite.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        NotificationRuntime.finishInvite(context, invite)
        if (incomingInvite?.eventId == invite.eventId) incomingInvite = null
    }

    fun handlePauseAction(pause: Boolean) {
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
            if (reliableClient?.isHost != true || isReconcilingMatch || (!pause && !canResumeReliableMatch())) return
            if (pause) savePrompt(interruptionPrompt.decide())
            reliableClient?.request(if (pause) "PAUSE" else "RESUME")
            return
        }
        if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
            val isHostPlayer = isHosting || (onlineRoomSync.currentHostId != null && getLocalUid() == onlineRoomSync.currentHostId) || (matchParticipants.singleOrNull { it.isHost }?.id == getLocalUid()) || (realTimePlayers.singleOrNull { it.isHost }?.id == getLocalUid())
            if (!isHostPlayer) return
        }
        if (pause && !isGamePaused) {
            val remMs = if (isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L)
            frozenRemainingTurnMillis = remMs
            val remSec = ((remMs + 999L) / 1000L).toInt()
            frozenRemainingTurnSeconds = remSec
            isGamePaused = true
            pausedByPlayerName = getPlayerDisplayName()
            multiplayerLifecyclePolicy?.pauseMatch()
            pauseRemainingSeconds = 30
            canExtendPause = false
            val nowMs = System.currentTimeMillis()
            lastAppliedPauseTimestamp = nowMs
            if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_PAUSED",
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        remainingTurnMillis = remMs,
                        isPaused = true,
                        pauseOwnerId = getLocalUid(),
                        timestamp = nowMs,
                        pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 1
                    )
                )
            }
        } else if (!pause && isGamePaused) {
            val remMs = if (isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L)
            val remSec = ((remMs + 999L) / 1000L).toInt()
            isGamePaused = false
            pausedByPlayerName = ""
            turnTimer = remSec
            frozenRemainingTurnMillis = null
            frozenRemainingTurnSeconds = null
            multiplayerLifecyclePolicy?.resumeMatch(withCountdown = false)
            pauseRemainingSeconds = 0
            canExtendPause = false
            hostReturningCountdown = null
            val nowMs = System.currentTimeMillis()
            lastAppliedPauseTimestamp = nowMs
            if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = "GAME_RESUMED",
                        playerId = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        remainingTurnMillis = remMs,
                        isPaused = false,
                        timestamp = nowMs
                    )
                )
            }
        }
        if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
            val remMs = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L)
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
                pauseRemainingSeconds = if (isGamePaused) 30 else 0,
                pauseDeadlineTimestamp = if (isGamePaused) (System.currentTimeMillis() + 30_000L) else null,
                pauseOwnerId = pausedByPlayerName,
                eliminatedPlayerIds = eliminatedPlayerIds.toList(),
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
                    val lastPick = snapshot.picks.lastOrNull()
                    if (lastPick != null && lastPick.playerId != getLocalUid()) {
                        com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playPickSound()
                    }
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

                if (optimisticPendingPickNumber != null && snapshot.picks.any { it.number == optimisticPendingPickNumber }) {
                    optimisticPendingPickNumber = null
                }
            } else {
                if (optimisticPendingPickNumber != null && !v.hasPendingIntent && v.pendingPickNumber == null) {
                    optimisticPendingPickNumber = null
                    isProcessingTurn = false
                }
            }
            turnNumber = snapshot.turn
            currentTurnPlayerId = snapshot.currentPlayerId
            isMyTurn = !v.waiting && currentTurnPlayerId == getLocalUid()
            if (optimisticPendingPickNumber == null && (isMyTurn || v.waiting || currentTurnPlayerId != getLocalUid())) {
                isProcessingTurn = false
            }
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
            val pWon = playerBoard.isBingo && myUid !in eliminatedPlayerIds && myUid !in multiplayerSnapshotCheckpoint.surrenderedPlayerIds
            val oWon = opponentBoard.isBingo && oppId !in eliminatedPlayerIds && oppId !in multiplayerSnapshotCheckpoint.surrenderedPlayerIds
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

            val eligibleCompletedPlayers = completedPlayers.filter {
                it.isNotBlank() && it !in eliminatedPlayerIds && it !in multiplayerSnapshotCheckpoint.surrenderedPlayerIds
            }
            if (eligibleCompletedPlayers.isEmpty()) {
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }

            // Winner selection among eligible completed players:
            // 1. If activePickerId completed BINGO on their turn, they are the undisputed WINNER
            val matchingPickerId = eligibleCompletedPlayers.firstOrNull { com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPlayerIdMatch(it, activePickerId) }
            val (winnerId, winReason) = if (activePickerId.isNotBlank() && matchingPickerId != null) {
                matchingPickerId to "Turn Pick"
            } else if (eligibleCompletedPlayers.size == 1) {
                eligibleCompletedPlayers.first() to "Solo Bingo"
            } else {
                // Multiple completed players on someone else's pick!
                val maxLines = eligibleCompletedPlayers.maxOf { effBoards[it]?.completedLinesCount ?: 0 }
                val topCandidates = eligibleCompletedPlayers.filter { (effBoards[it]?.completedLinesCount ?: 0) == maxLines }
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

            val isLocalWinner = isPlayerMe(winnerId)
            val isLocalRunner = (!isLocalWinner && (eligibleCompletedPlayers.any { isPlayerMe(it) } || playerBoard.isBingo))

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
                } else if (currentGameMode == GameMode.ONLINE_ROOM && candidatePlayers.size > 2) {
                    eliminatedPlayerIds
                } else {
                    disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
                }
                com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.calculateNextTurnPlayerId(
                    allParticipants = candidatePlayers,
                    disconnectedPlayerIds = fullyDisconnected + multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
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

    fun activateMatchHeartbeatIf1v1(startReliableCoordinator: Boolean = true) {
        val activeList = matchParticipants.ifEmpty { realTimePlayers }
        if (currentGameMode == GameMode.ONLINE_ROOM && (matchParticipants.size == 2 || activeList.size == 2)) {
            val peerUid = activeList.firstOrNull { !isPlayerMe(it) }?.id
                ?: matchParticipants.firstOrNull { !isPlayerMe(it) }?.id
                ?: opponentPlayerId.takeIf { it.isNotBlank() }
                ?: ""
            if (peerUid.isNotBlank() && currentMatchSeed != 0L) {
                onlineRoomSync.startMatchHeartbeat(currentMatchSeed.toString(), peerUid)
                if (startReliableCoordinator) {
                    startReliableMatch()
                }
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
            val botPlayer = Player(
                id = "ai_bot",
                displayName = if (difficulty == AiDifficulty.EASY) "Easy Bot" else "Master Bot",
                username = "bot",
                isHost = false
            )
            val myPlayerObj = Player(
                id = myUid,
                displayName = myDisplay,
                username = myUsername,
                isHost = true
            )
            matchParticipants = listOf(myPlayerObj, botPlayer)
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
        lastAppliedPauseTimestamp = 0L
        multiplayerSnapshotCheckpoint = MultiplayerSnapshotCheckpoint()
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
        } else if (mode == GameMode.AI_EASY || mode == GameMode.AI_HARD) {
            myUid
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

        if (mode == GameMode.ONLINE_ROOM) {
            activateMatchHeartbeatIf1v1(startReliableCoordinator = !isManualBoard)
        }
        if (mode == GameMode.ONLINE_ROOM && activePlayersList.size > 2) {
            val hostPlayer = activePlayersList.find { it.isHost }
            val hostUid = hostPlayer?.id ?: (if (isHosting) myUid else opponentPlayerId)
            val participantIds = activePlayersList.map { it.id }.filter { it.isNotBlank() }
            multiplayerLifecyclePolicy = com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy(
                matchId = currentMatchSeed.toString(),
                localUid = myUid,
                isHost = isHosting,
                hostUid = hostUid,
                initialParticipants = participantIds
            ).also { policy ->
                policy.onTurnStarted(firstTurnUid, turnNumber = 1, forceReset = true)
            }
            hostAbsenceHoldRemainingSeconds = 0
            hostReturningCountdown = null
            pauseRemainingSeconds = 0
            canExtendPause = false
            turnInactivityPlayerId = null
            turnInactivityAutoSkipSeconds = 10
            eliminatedPlayerIds = emptySet()
        } else {
            multiplayerLifecyclePolicy = null
            hostAbsenceHoldRemainingSeconds = 0
            hostReturningCountdown = null
            pauseRemainingSeconds = 0
            canExtendPause = false
            turnInactivityPlayerId = null
            turnInactivityAutoSkipSeconds = 10
            eliminatedPlayerIds = emptySet()
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
        manualStartJob?.cancel()
        isStartingCountdown = true
        val expectedSeed = currentMatchSeed
        val expectedRoom = roomCode
        val expectedAccount = getLocalUid()
        manualStartJob = coroutineScope.launch {
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
                    if (navController.currentDestination?.route != Screen.ManualBoardDesign.route || currentMatchSeed != expectedSeed || roomCode != expectedRoom || isGameOver || getLocalUid() != expectedAccount) {
                        countdownSeconds = -1
                        return@launch
                    }
                    countdownSeconds = s
                    delay(1000L)
                }
                if (navController.currentDestination?.route != Screen.ManualBoardDesign.route || currentMatchSeed != expectedSeed || roomCode != expectedRoom || isGameOver || getLocalUid() != expectedAccount) {
                    countdownSeconds = -1
                    return@launch
                }
                countdownSeconds = 0
                delay(300L)
                countdownSeconds = -1

                if (navController.currentDestination?.route != Screen.ManualBoardDesign.route || currentMatchSeed != expectedSeed || roomCode != expectedRoom || isGameOver || getLocalUid() != expectedAccount) {
                    return@launch
                }

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

                if (currentGameMode == GameMode.ONLINE_ROOM && activeList.size > 2) {
                    val hostPlayer = activeList.find { it.isHost }
                    val hostUid = hostPlayer?.id ?: (if (isHosting) myUid else opponentPlayerId)
                    val participantIds = activeList.map { it.id }.filter { it.isNotBlank() }
                    multiplayerLifecyclePolicy = com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy(
                        matchId = currentMatchSeed.toString(),
                        localUid = myUid,
                        isHost = isHosting,
                        hostUid = hostUid,
                        initialParticipants = participantIds
                    ).also { policy ->
                        policy.onTurnStarted(firstTurnUid, turnNumber = 1, forceReset = true)
                    }
                }

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
        multiplayerSnapshotCheckpoint = matchData.multiplayerSnapshotCheckpoint
        lastAppliedPauseTimestamp = matchData.pauseControlTimestamp
        prepareMatchPresentation(matchData.matchSeed, matchData)
        matchParticipants = matchData.participants
        randomizedTurnOrder = matchData.randomizedTurnOrder
        turnNumber = matchData.turnNumber
        currentTurnPlayerId = matchData.currentTurnPlayerId
        isMyTurn = (currentTurnPlayerId == myUid)

        val nowMs = System.currentTimeMillis()
        val effectivePauseDeadline = matchData.pauseDeadlineTimestamp
            ?: if (matchData.isPaused) (matchData.lastUpdatedAt + (matchData.pauseRemainingSeconds ?: 30) * 1000L) else null
        val pauseRemainingMs = if (matchData.isPaused && effectivePauseDeadline != null) {
            effectivePauseDeadline - nowMs
        } else 0L

        if (matchData.isPaused && pauseRemainingMs > 0L) {
            val remSec = ((pauseRemainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
            isGamePaused = true
            pausedByPlayerName = matchData.pauseOwnerId ?: "Opponent"
            pauseRemainingSeconds = remSec
        } else {
            isGamePaused = false
            pausedByPlayerName = ""
            pauseRemainingSeconds = 0
        }

        if (matchData.remainingTurnMillis != null && matchData.remainingTurnMillis > 0L) {
            val remSec = ((matchData.remainingTurnMillis + 999L) / 1000L).toInt().coerceIn(1, 30)
            if (isGamePaused) {
                frozenRemainingTurnSeconds = remSec
            }
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

        if (matchData.participants.size > 2) {
            val hostPlayer = matchData.participants.find { it.isHost }
            val hostUid = hostPlayer?.id ?: (if (matchData.isHost) myUid else opponentPlayerId)
            val participantIds = matchData.participants.map { it.id }.filter { it.isNotBlank() }
            val policy = com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy(
                matchId = matchData.matchSeed.toString(),
                localUid = myUid,
                isHost = matchData.isHost,
                hostUid = hostUid,
                initialParticipants = participantIds,
                initialHostHoldRemainingSeconds = matchData.hostHoldRemainingSeconds
            )
            if (matchData.currentTurnPlayerId.isNotBlank()) {
                policy.onTurnStarted(matchData.currentTurnPlayerId, turnNumber = matchData.turnNumber, forceReset = true)
            }
            matchData.eliminatedPlayerIds.forEach { elimId ->
                policy.kickOrEliminatePlayer(elimId)
            }
            matchData.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.forEach(policy::recordSurrender)
            policy.restorePauseCheckpoint(
                isPaused = isGamePaused, remainingSeconds = pauseRemainingSeconds,
                controlRevision = matchData.pauseControlRevision,
                versioned = matchData.versionedPauseControl,
                timestamp = matchData.pauseControlTimestamp
            )
            // Expiry while the host process was gone is a new authoritative resume.
            if (matchData.isHost && matchData.isPaused && !isGamePaused) {
                policy.restorePauseCheckpoint(true, 0, matchData.pauseControlRevision,
                    matchData.versionedPauseControl, matchData.pauseControlTimestamp)
                policy.resumeMatch(withCountdown = false)
            }
            multiplayerLifecyclePolicy = policy
            eliminatedPlayerIds = matchData.eliminatedPlayerIds.toSet()
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

    fun gameplayContext(): MultiplayerGameplayContext {
        val participants = getActiveParticipants()
        val host = participants.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: (if (isHosting) getLocalUid() else opponentPlayerId)
        val excluded = if (currentGameMode == GameMode.ONLINE_ROOM && participants.size > 2) eliminatedPlayerIds
            else disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
        return MultiplayerGameplayContext(currentMatchSeed, getLocalUid(), host, participants,
            randomizedTurnOrder, excluded + multiplayerSnapshotCheckpoint.surrenderedPlayerIds, allowPeerTimeouts = currentGameMode == GameMode.NEARBY_NETWORK,
            isActive = (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) &&
                navController.currentDestination?.route in setOf(Screen.Game.route, Screen.ManualBoardDesign.route),
            requiresHostResultConfirmation = currentGameMode == GameMode.ONLINE_ROOM && participants.size > 2)
    }

    fun broadcastAcceptedGameplayState() {
        if (!isHosting) return
        broadcastPacket(RoomMessagePacket(
            type = "GAME_SYNC", playerId = getLocalUid(), isHost = true,
            seed = currentMatchSeed, matchId = currentMatchSeed.toString(),
            turnNumber = turnNumber, currentTurnPlayerId = currentTurnPlayerId,
            pickedHistory = pickedNumbersHistory.toList(), pickedByHistory = pickedByPlayerHistory.toList(),
            turnIds = pickedTurnIdsHistory.toList(), boardHash = engine.computeBoardHash(playerBoard),
            isPaused = isGamePaused, pauseRemainingSeconds = multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds,
            pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0, pauseOwnerId = pausedByPlayerName,
            remainingTurnMillis = frozenRemainingTurnMillis ?: (turnTimer * 1000L),
            eliminatedPlayerIds = eliminatedPlayerIds.toList(), winnerPlayerId = winnerPlayerId.takeIf { isGameOver }.orEmpty(),
            winReason = activeWinReason.takeIf { isGameOver }.orEmpty(), runnerPlayerIds = activeRunnerPlayerIds
        ))
    }

    fun executePick(
        number: Int,
        isOwnPick: Boolean,
        pickerId: String,
        shouldBroadcast: Boolean = false
    ) {
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
            if (number > 0 && isOwnPick) {
                if (isGameOver || isGamePaused || isReconcilingMatch || isHostDisconnected) return
                if (!isMyTurn || isProcessingTurn || optimisticPendingPickNumber != null) return
                if (reliableClient?.canRequestPick() == false) return
                if (number in pickedNumbersHistory) return
                val cell = playerBoard.cells.find { it.number == number }
                if (cell != null && cell.isMarked) return

                // 1. 0ms Immediate Sound & Tactile Haptic Vibration
                com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playPickSound()
                com.bingo.multiplayer.core.designsystem.BingoSoundEffects.playVibration(context)

                // 2. 0ms Optimistic Pending Overlay
                optimisticPendingPickNumber = number

                // 3. Immediate Turn Lock to Prevent Spamming
                isMyTurn = false
                isProcessingTurn = true

                // 4. Background Reliable Network Intent
                reliableClient?.request("PICK", number = number)

                // Safe fallback to reset isProcessingTurn and rollback pending overlay if network response is delayed or drops
                coroutineScope.launch {
                    delay(5000L)
                    if (optimisticPendingPickNumber == number) {
                        optimisticPendingPickNumber = null
                        if (isProcessingTurn && isMyTurn) {
                            isProcessingTurn = false
                        }
                    }
                }
            }
            return
        }
        if (isGameOver || isGamePaused || isMultiplayerSuspended) return
        if (isOwnPick && (!isMyTurn || isProcessingTurn)) return
        if (number > 0 && number in pickedNumbersHistory) return

        if (isOwnPick) {
            isProcessingTurn = true
            isMyTurn = false
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
            frozenRemainingTurnSeconds = null
            frozenRemainingTurnMillis = null
            val confirmedOutcome = outcome.isGameOver && MultiplayerResultAuthority.mayFinalize(gameplayContext())
            isGameOver = confirmedOutcome

            val nextPlayerId = calculateNextTurnPlayerId(pickerId)
            currentTurnPlayerId = nextPlayerId
            multiplayerLifecyclePolicy?.onTurnStarted(
                newTurnPlayerId = nextPlayerId,
                turnNumber = turnNumber,
                forceReset = true
            )

            if (confirmedOutcome) {
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
                        accountUid = getLocalUid(), expectedMatchSeed = currentMatchSeed,
                        multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint, remainingTurnMillis = turnTimer * 1000L
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
                        turnIds = pickedTurnIdsHistory.toList(),
                        matchId = currentMatchSeed.toString(),
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
                if (isHosting && outcome.isGameOver) broadcastAcceptedGameplayState()
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
                        pauseRemainingSeconds = if (isGamePaused) (multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
                        pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0,
                        pauseOwnerId = pausedByPlayerName,
                        eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                        remainingTurnMillis = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L)
                    )
                )
            }
        }
    }

    fun saveGameplayState() {
        if (isGameOver || currentGameMode != GameMode.ONLINE_ROOM || roomCode.isBlank()) return
        OngoingMatchStore.updateMatchGameState(context = context, accountUid = getLocalUid(), expectedMatchSeed = currentMatchSeed,
            playerBoard = playerBoard, opponentBoard = opponentBoard, allPlayerBoards = allPlayerBoards,
            pickedNumbers = pickedNumbersHistory.toList(), pickedByPlayers = pickedByPlayerHistory.toList(),
            turnIds = pickedTurnIdsHistory.toList(), turnNumber = turnNumber, currentTurnPlayerId = currentTurnPlayerId,
            remainingTurnMillis = frozenRemainingTurnMillis ?: (turnTimer * 1000L), chatMessages = matchChatHistory,
            multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint)
    }

    fun applyLifecycleAction(action: com.bingo.multiplayer.domain.engine.LifecycleAction) {
        when (action) {
            is com.bingo.multiplayer.domain.engine.LifecycleAction.HostAbsenceHold -> {
                hostAbsenceHoldRemainingSeconds = action.secondsRemaining
                hostReturningCountdown = null
                if (frozenRemainingTurnSeconds == null && turnTimer > 0) {
                    frozenRemainingTurnSeconds = turnTimer
                    frozenRemainingTurnMillis = turnTimer * 1000L
                    frozenTurnNumber = turnNumber
                    frozenMatchSeed = currentMatchSeed
                }
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.HostReturningCountdown -> {
                hostReturningCountdown = action.secondsRemaining
                hostAbsenceHoldRemainingSeconds = 0
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.HostResumed -> {
                hostReturningCountdown = null
                hostAbsenceHoldRemainingSeconds = 0
                if (frozenRemainingTurnSeconds != null && frozenTurnNumber == turnNumber && frozenMatchSeed == currentMatchSeed) {
                    turnTimer = frozenRemainingTurnSeconds!!
                }
                frozenRemainingTurnSeconds = null
                frozenRemainingTurnMillis = null
                frozenTurnNumber = -1
                frozenMatchSeed = 0L
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.HostTerminated -> {
                hostAbsenceHoldRemainingSeconds = 0
                hostReturningCountdown = null
                isGameOver = true
                didPlayerWin = false
                isDrawMatch = false
                isRunnerMatch = false
                winnerPlayerId = ""
                activeWinReason = "Host Disconnected"
                activeRunnerPlayerIds = emptyList()
                opponentDisconnectMessage = "Host disconnected and did not return. Match ended"
                com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, getLocalUid())
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.PauseTick -> {
                pauseRemainingSeconds = action.secondsRemaining
                canExtendPause = action.canExtend
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.PauseResumingCountdown -> {
                hostReturningCountdown = action.secondsRemaining
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.PauseAutoResumed -> {
                isGamePaused = false
                pauseRemainingSeconds = 0
                canExtendPause = false
                hostReturningCountdown = null
                val remMs = if (isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L)
                frozenRemainingTurnMillis = null
                frozenRemainingTurnSeconds = null
                frozenTurnNumber = -1
                frozenMatchSeed = 0L
                val nowMs = System.currentTimeMillis()
                if (isHosting) lastAppliedPauseTimestamp = nowMs
                if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                        context = context,
                        pauseControlRevision = multiplayerLifecyclePolicy?.pauseEpisode,
                        versionedPauseControl = multiplayerLifecyclePolicy?.hasVersionedPauseControl,
                        pauseControlTimestamp = multiplayerLifecyclePolicy?.lastAppliedPauseTimestamp,
                        isPaused = false,
                        pauseRemainingSeconds = 0,
                        pauseDeadlineTimestamp = null,
                        remainingTurnMillis = remMs,
                        eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                        accountUid = getLocalUid()
                    )
                }
                if (isHosting) {
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "GAME_RESUMED",
                            playerId = getLocalUid(),
                            displayName = getPlayerDisplayName(),
                            remainingTurnMillis = remMs,
                            isPaused = false,
                            timestamp = nowMs
                        )
                    )
                }
                if (!isHosting) {
                    awaitingHostStateSync = true
                }
                Toast.makeText(context, "▶️ Match resumed", Toast.LENGTH_SHORT).show()
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.TurnPlayerInactive -> {
                if (isHosting && multiplayerLifecyclePolicy?.isCurrentTurnAction(action, currentTurnPlayerId, turnNumber) == true && !isGameOver && !isGamePaused) {
                    turnInactivityPlayerId = action.playerId
                    turnInactivityAutoSkipSeconds = action.autoSkipSecondsRemaining
                }
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.TurnAutoSkipped -> {
                if (isHosting && multiplayerLifecyclePolicy?.isCurrentTurnAction(action, currentTurnPlayerId, turnNumber) == true && !isGameOver && !isGamePaused) {
                    turnInactivityPlayerId = null
                    val skippedName = matchParticipants.find { it.id == action.playerId }?.displayName ?: "Player"
                    val skipMsg = "📢 Turn auto-skipped ($skippedName unresponsive)"
                    broadcastSystemChatMessage(skipMsg)

                    val nextId = calculateNextTurnPlayerId(action.playerId)
                    currentTurnPlayerId = nextId
                    turnNumber += 1
                    turnTimer = 30
                    frozenRemainingTurnMillis = null; frozenRemainingTurnSeconds = null
                    isMyTurn = (currentTurnPlayerId == getLocalUid())
                    multiplayerLifecyclePolicy?.onTurnStarted(nextId, turnNumber = turnNumber, forceReset = true)
                    saveGameplayState()
                    persistMultiplayerControl()
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "TURN_TIMEOUT",
                            playerId = action.playerId,
                            turnNumber = turnNumber,
                            currentTurnPlayerId = nextId,
                            seed = currentMatchSeed,
                            pickedHistory = pickedNumbersHistory.toList(),
                            pickedByHistory = pickedByPlayerHistory.toList()
                        )
                    )
                }
            }
            is com.bingo.multiplayer.domain.engine.LifecycleAction.NoOp -> {}
        }
    }

    fun gameplayState() = MultiplayerGameplayState(
        picks = pickedNumbersHistory.mapIndexed { i, n -> MatchPick(n, pickedByPlayerHistory.getOrNull(i).orEmpty(), pickedTurnIdsHistory.getOrNull(i) ?: (i + 1)) },
        playerBoard = playerBoard, opponentBoard = opponentBoard, allPlayerBoards = allPlayerBoards,
        turnNumber = turnNumber, currentPlayerId = currentTurnPlayerId,
        remainingTurnMillis = frozenRemainingTurnMillis ?: (turnTimer * 1000L),
        isPaused = isGamePaused, isSuspended = isMultiplayerSuspended, isGameOver = isGameOver,
        disconnectedPlayerIds = disconnectedPlayerIds.toSet(), missedTurns = consecutiveMissedTurns.toMap(),
        surrenderedPlayerIds = multiplayerSnapshotCheckpoint.surrenderedPlayerIds
    )

    fun applyGameplayState(state: MultiplayerGameplayState) {
        val changedTurn = turnNumber != state.turnNumber || currentTurnPlayerId != state.currentPlayerId
        playerBoard = state.playerBoard; opponentBoard = state.opponentBoard; allPlayerBoards = state.allPlayerBoards
        pickedNumbersHistory.clear(); pickedNumbersHistory.addAll(state.picks.map { it.number })
        pickedByPlayerHistory.clear(); pickedByPlayerHistory.addAll(state.picks.map { it.playerId })
        pickedTurnIdsHistory.clear(); pickedTurnIdsHistory.addAll(state.picks.map { it.turn })
        turnNumber = state.turnNumber; currentTurnPlayerId = state.currentPlayerId
        turnTimer = ((state.remainingTurnMillis + 999L) / 1000L).toInt()
        isMyTurn = isPlayerMe(currentTurnPlayerId)
        disconnectedPlayerIds.clear(); disconnectedPlayerIds.addAll(state.disconnectedPlayerIds)
        consecutiveMissedTurns.clear(); consecutiveMissedTurns.putAll(state.missedTurns)
        multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(surrenderedPlayerIds = state.surrenderedPlayerIds)
        state.surrenderedPlayerIds.forEach { multiplayerLifecyclePolicy?.recordSurrender(it) }
        if (changedTurn) {
            turnInactivityPlayerId = null
            frozenRemainingTurnMillis = state.remainingTurnMillis.takeIf { isGamePaused || isMultiplayerSuspended }
            frozenRemainingTurnSeconds = frozenRemainingTurnMillis?.let { ((it + 999L) / 1000L).toInt() }
            frozenTurnNumber = turnNumber; frozenMatchSeed = currentMatchSeed
            multiplayerLifecyclePolicy?.onTurnStarted(currentTurnPlayerId, turnNumber = turnNumber, forceReset = true)
        }
    }

    fun requestGameplayCorrection() {
        if (isHosting) broadcastAcceptedGameplayState()
        else broadcastPacket(RoomMessagePacket(type = "SYNC_REQUEST", playerId = getLocalUid(), seed = currentMatchSeed))
    }

    fun handleIncomingPacket(packet: RoomMessagePacket) {
        if (currentGameMode == GameMode.ONLINE_ROOM && packet.type in setOf("START_GAME", "PLAY_AGAIN") &&
            packet.players.ifEmpty { realTimePlayers }.size == 2 && (packet.protocolVersion != 2 || packet.hostWaitVersion != 1)) {
            Toast.makeText(context, "Both players need the updated multiplayer version.", Toast.LENGTH_LONG).show()
            return
        }
        if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2 && currentMatchSeed != 0L) {
            if (packet.type.startsWith("MATCH_V2_")) { reliableClient?.receive(packet); return }
            if (packet.type in MultiplayerGameplayReducer.reliable1v1BlockedTypes) return
        }
        if (packet.type in MultiplayerGameplayReducer.gameplayTypes &&
            !MultiplayerGameplayReducer.belongsToMatch(packet, gameplayContext())) return
        val rules = gameplayContext()
        val legacyMatch = rules.isActive && (currentGameMode == GameMode.NEARBY_NETWORK ||
            (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size > 2))
        val session = if (isUsingP2p) "nearby:${onlineRoomSync.instanceId}" else onlineRoomSync.roomSessionId
        if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) &&
            !multiplayerPacketGate.accepts(packet, rules, roomCode, session)) return
        val myUid = getLocalUid()
        if (packet.senderInstanceId.isNotBlank()) {
            if (packet.senderInstanceId == onlineRoomSync.instanceId) {
                // Discard echo of packet sent by this exact local app instance
                return
            }
        } else if (MultiplayerGameplayReducer.sender(packet).isNotBlank() && MultiplayerGameplayReducer.sender(packet) == myUid) {
            // Discard echoes of packets we sent ourselves.
            // However, if we are a guest and packet is START_GAME or PLAY_AGAIN from host,
            // do NOT discard even if account IDs happen to match on test devices.
            if (isHosting || (packet.type != "START_GAME" && packet.type != "PLAY_AGAIN")) {
                return
            }
        }

        val packetSenderId = MultiplayerGameplayReducer.sender(packet)
        if (packetSenderId.isNotBlank() && packetSenderId != myUid &&
            (matchParticipants.isEmpty() || matchParticipants.any { it.id == packetSenderId })) {
            opponentPlayerId = packetSenderId
        }
        // A buffered packet is state data, not proof that the sender is online now.
        if (legacyMatch && packet.type == "MULTIPLAYER_PRESENCE_REQUEST") {
            if (com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value) {
                multiplayerPacketGate.respond(packet, rules, roomCode)?.let(broadcastPacket)
            }
            return
        }
        if (legacyMatch && packet.type == "MULTIPLAYER_PRESENCE_RESPONSE") {
            if (multiplayerPacketGate.acceptResponse(packet, rules, roomCode, session)) {
                multiplayerLifecyclePolicy?.let { policy ->
                    val action = if (packet.playerId == policy.hostUid) policy.onHostHeartbeat()
                        else policy.onPeerHeartbeat(packet.playerId)
                    applyLifecycleAction(action)
                }
            }
            return
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
                    if (navController.currentDestination?.route != Screen.ManualBoardDesign.route) return
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (allPlayerBoards.containsKey(packet.playerId)) {
                        // Already accepted board for this participant; duplicate is idempotent
                        return
                    }
                    val totalCells = boardSize * boardSize
                    if (packet.pickedHistory.size == totalCells && packet.playerId.isNotBlank() && ManualBoardEngine.isBoardComplete(packet.pickedHistory, boardSize)) {
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
                    if (isLocalBoardReady && allReady && countdownSeconds < 0 && navController.currentDestination?.route == Screen.ManualBoardDesign.route) {
                        startCountdownAndInitiateTurn()
                    }
                }
            }

            "PICK_NUMBER" -> {
                if (currentGameMode != GameMode.ONLINE_ROOM && currentGameMode != GameMode.NEARBY_NETWORK) return
                val rules = gameplayContext()
                val decision = MultiplayerGameplayReducer.pick(packet, rules, gameplayState())
                if (!decision.accepted) {
                    requestGameplayCorrection()
                    return
                }
                applyGameplayState(decision.state)
                val picker = decision.state.picks.last().playerId
                markPlayerReconnected(picker)
                recentPick = decision.state.picks.last().let { RecentPick(it.number, it.playerId, it.turn) }
                if (!isHosting && rules.participantId(MultiplayerGameplayReducer.sender(packet)) == rules.hostId) {
                    // Only the host's actual history confirms prior marks; local pending marks cannot confirm themselves.
                    multiplayerSnapshotCheckpoint = MultiplayerSnapshotReconciler.recordHostProgress(multiplayerSnapshotCheckpoint, packet)
                }
                val outcome = evaluateMatchOutcome(picker)
                if (outcome.isGameOver && MultiplayerResultAuthority.mayFinalize(rules)) {
                    isGameOver = true; isDrawMatch = outcome.isDraw; didPlayerWin = outcome.didPlayerWin
                    isRunnerMatch = outcome.isRunner; winnerPlayerId = outcome.winnerPlayerId; activeWinReason = outcome.winReason
                    activeRunnerPlayerIds = allPlayerBoards.filter { it.value.isBingo && it.key != outcome.winnerPlayerId && !isPlayerDisconnected(it.key) }.keys.toList()
                    recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                    OngoingMatchStore.clearOngoingMatch(context, myUid)
                } else saveGameplayState()
                if (isHosting) broadcastAcceptedGameplayState()
                if (packet.pickedHistory.any { n -> decision.state.picks.none { it.number == n } }) requestGameplayCorrection()
            }

            "TURN_TIMEOUT" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    val rules = gameplayContext()
                    val decision = MultiplayerGameplayReducer.timeout(packet, rules, gameplayState())
                    if (!decision.accepted) {
                        requestGameplayCorrection()
                        return
                    }
                    run {
                        val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                        applyGameplayState(decision.state)
                        val skippedId = rules.participantId(packet.playerId) ?: return
                        if (rules.participantId(MultiplayerGameplayReducer.sender(packet)) == rules.hostId) {
                            multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(turnNumber = turnNumber)
                        }
                        recentPick = RecentPick(number = -1, pickedByPlayerId = skippedId, turnNumber = turnNumber - 1)

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
                        saveGameplayState()
                        if (isHosting) broadcastAcceptedGameplayState()
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
                if (packet.seed != 0L && currentMatchSeed != 0L && packet.seed != currentMatchSeed) {
                    return
                }
                if (packet.playerId == currentTypingPlayerId) {
                    currentTypingPlayerName = null
                    currentTypingPlayerId = ""
                }
                val isSys = packet.username == "SYSTEM"
                val senderIdentity = packet.playerId.ifBlank { packet.username }
                val stableId = packet.timestamp * 1000L + (kotlin.math.abs((senderIdentity + packet.displayName).hashCode()) % 1000L)
                val newMsg = InGameChatMessage(
                    id = stableId,
                    text = packet.displayName,
                    isSelf = false,
                    senderName = if (isSys) null else (packet.username.takeIf { it.isNotBlank() } ?: "Opponent"),
                    timestamp = packet.timestamp,
                    isSystemMessage = isSys
                )
                val alreadyPresent = matchChatHistory.any {
                    it.id == newMsg.id || (it.senderName == newMsg.senderName && it.text == newMsg.text && kotlin.math.abs(newMsg.timestamp - it.timestamp) < 2000L)
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
                    if (packet.playerId in eliminatedPlayerIds || packet.playerId in (multiplayerLifecyclePolicy?.surrenderedPlayerIds ?: emptySet<String>()) || packet.playerId in multiplayerSnapshotCheckpoint.surrenderedPlayerIds) {
                        return
                    }
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
                                pauseRemainingSeconds = if (isGamePaused) (multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
                                pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0,
                                pauseOwnerId = pausedByPlayerName,
                                eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                remainingTurnMillis = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L),
                                winnerPlayerId = if (isGameOver) winnerPlayerId else "",
                                winReason = if (isGameOver) activeWinReason else "",
                                runnerPlayerIds = if (isGameOver) activeRunnerPlayerIds else emptyList(),
                                chatMessages = matchChatHistory.toList()
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

                    if (isHosting) {
                        if (isGameOver || isGamePaused || isMultiplayerSuspended) {
                            broadcastAcceptedGameplayState()
                            return
                        }
                        // 1. Host Authoritative Move Recovery & Validation:
                        // Validates candidate moves against turn identity, expected picker, and legal numbers.
                        // Rejects late moves for turns already closed by timeout, pass, or prior pick.
                        val candidatePlayers = if (matchParticipants.isNotEmpty()) matchParticipants else realTimePlayers
                        val fullyDisconnected = if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) {
                            emptySet()
                        } else if (candidatePlayers.size > 2) {
                            eliminatedPlayerIds
                        } else {
                            disconnectedPlayerIds.filter { (consecutiveMissedTurns[it] ?: 0) >= 3 }.toSet()
                        }

                        val recoveryResult = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.reconcileRecoveredPicks(
                            candidatePickedHistory = packet.pickedHistory,
                            candidatePickedByHistory = packet.pickedByHistory,
                            candidateTurnIds = packet.turnIds,
                            senderPlayerId = packet.playerId,
                            currentTurnNumber = turnNumber,
                            currentTurnPlayerId = currentTurnPlayerId,
                            existingPickedNumbers = pickedNumbersHistory.toList(),
                            existingTurnIds = pickedTurnIdsHistory.toList(),
                            allParticipants = candidatePlayers,
                            disconnectedPlayerIds = fullyDisconnected + multiplayerSnapshotCheckpoint.surrenderedPlayerIds,
                            fallbackPlayerId = myUid,
                            matchSeed = currentMatchSeed,
                            customTurnOrder = randomizedTurnOrder,
                            boardSize = boardSize
                        )

                        if (recoveryResult.acceptedPicks.isNotEmpty()) {
                            for (acceptedPick in recoveryResult.acceptedPicks) {
                                val num = acceptedPick.number
                                val origPickerId = acceptedPick.pickerId
                                val historicalTurnNumber = acceptedPick.turnNumber

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
                            }

                            // Evaluate win conditions strictly on the accepted moves
                            if (!isGameOver) {
                                val lastPicker = recoveryResult.acceptedPicks.last().pickerId
                                val outcome = evaluateMatchOutcome(lastPicker)
                                if (outcome.isGameOver) {
                                    isGameOver = true
                                    isDrawMatch = outcome.isDraw
                                    didPlayerWin = outcome.didPlayerWin
                                    isRunnerMatch = outcome.isRunner
                                    winnerPlayerId = outcome.winnerPlayerId
                                    activeWinReason = outcome.winReason
                                    recordFinishedMatch(won = outcome.didPlayerWin, isDraw = outcome.isDraw, isRunner = outcome.isRunner)
                                    com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                                } else if (recoveryResult.turnAdvanced) {
                                    turnNumber = recoveryResult.nextTurnNumber
                                    currentTurnPlayerId = recoveryResult.nextTurnPlayerId
                                    turnTimer = 30
                                    frozenRemainingTurnSeconds = null
                                    frozenRemainingTurnMillis = null
                                    isMyTurn = (currentTurnPlayerId == myUid)
                                    multiplayerLifecyclePolicy?.onTurnStarted(
                                        newTurnPlayerId = recoveryResult.nextTurnPlayerId,
                                        turnNumber = recoveryResult.nextTurnNumber,
                                        forceReset = true
                                    )
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
                                            isPaused = isGamePaused,
                                            pauseRemainingSeconds = pauseRemainingSeconds,
                                            eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                            accountUid = myUid
                                        )
                                    }
                                }
                            }
                            if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "GAME_SYNC",
                                        turnNumber = turnNumber,
                                        pickedHistory = pickedNumbersHistory.toList(),
                                        pickedByHistory = pickedByPlayerHistory.toList(),
                                        turnIds = pickedTurnIdsHistory.toList(),
                                        boardHash = engine.computeBoardHash(playerBoard),
                                        currentTurnPlayerId = currentTurnPlayerId,
                                        playerId = myUid,
                                        seed = currentMatchSeed,
                                        matchId = currentMatchSeed.toString(),
                                        isHost = true,
                                        senderInstanceId = onlineRoomSync.instanceId,
                                        isPaused = isGamePaused,
                                        pauseRemainingSeconds = if (isGamePaused) (multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
                                        pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0,
                                        pauseOwnerId = pausedByPlayerName,
                                        eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                        remainingTurnMillis = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L),
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        } else {
                            // If guest sent an unaccepted pick (e.g. from an expired/closed turn) or conflicting state,
                            // immediately publish host authoritative state so the returning guest discards rejected local moves.
                            val guestHasUnacceptedPicks = packet.pickedHistory.any { it > 0 && it !in pickedNumbersHistory }
                            val guestHasMismatchedTurn = packet.turnNumber != turnNumber || (packet.currentTurnPlayerId.isNotBlank() && packet.currentTurnPlayerId != currentTurnPlayerId)
                            if (guestHasUnacceptedPicks || guestHasMismatchedTurn) {
                                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                                    broadcastPacket(
                                        RoomMessagePacket(
                                            type = "GAME_SYNC",
                                            turnNumber = turnNumber,
                                            pickedHistory = pickedNumbersHistory.toList(),
                                            pickedByHistory = pickedByPlayerHistory.toList(),
                                            turnIds = pickedTurnIdsHistory.toList(),
                                            boardHash = engine.computeBoardHash(playerBoard),
                                            currentTurnPlayerId = currentTurnPlayerId,
                                            playerId = myUid,
                                            seed = currentMatchSeed,
                                            matchId = currentMatchSeed.toString(),
                                            isHost = true,
                                            senderInstanceId = onlineRoomSync.instanceId,
                                            isPaused = isGamePaused,
                                            pauseRemainingSeconds = if (isGamePaused) (multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
                                            pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0,
                                            pauseOwnerId = pausedByPlayerName,
                                            eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                            remainingTurnMillis = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: (turnTimer * 1000L)) else (turnTimer * 1000L),
                                            timestamp = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // 2. Guest Reconciles Authoritative State from Host
                        val hostPlayerId = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: opponentPlayerId
                        val isSenderHost = (packet.playerId == hostPlayerId) || packet.isHost

                        if (isSenderHost) {
                            val application = MultiplayerSnapshotReconciler.reconcile(
                                packet = packet, matchSeed = currentMatchSeed, hostId = hostPlayerId, localId = myUid,
                                checkpoint = multiplayerSnapshotCheckpoint,
                                localPicks = pickedNumbersHistory.mapIndexed { i, number ->
                                    MatchPick(number, pickedByPlayerHistory.getOrNull(i).orEmpty(), pickedTurnIdsHistory.getOrNull(i) ?: (i + 1))
                                },
                                playerBoard = playerBoard, opponentBoard = opponentBoard, allPlayerBoards = allPlayerBoards
                            ) ?: return
                            multiplayerSnapshotCheckpoint = application.checkpoint
                            application.checkpoint.surrenderedPlayerIds.forEach { id ->
                                multiplayerLifecyclePolicy?.recordSurrender(id)
                                if (id !in disconnectedPlayerIds) disconnectedPlayerIds.add(id)
                                consecutiveMissedTurns[id] = 3
                            }
                            playerBoard = application.playerBoard
                            opponentBoard = application.opponentBoard
                            allPlayerBoards = application.allPlayerBoards
                            pickedNumbersHistory.clear(); pickedNumbersHistory.addAll(application.picks.map { it.number })
                            pickedByPlayerHistory.clear(); pickedByPlayerHistory.addAll(application.picks.map { it.playerId })
                            pickedTurnIdsHistory.clear(); pickedTurnIdsHistory.addAll(application.picks.map { it.turn })
                            recentPick = application.picks.lastOrNull()?.let { RecentPick(it.number, it.playerId, it.turn) }

                            // The lifecycle policy accepts control revisions before any pause UI mutation.
                            val pauseSec = packet.pauseRemainingSeconds.coerceIn(0, 30)
                            val acceptedPause = multiplayerLifecyclePolicy?.syncRemotePause(
                                isPaused = packet.isPaused, remainingSeconds = pauseSec,
                                timestamp = packet.timestamp, remotePauseEpisode = packet.pauseEpisode
                            ) ?: (packet.timestamp == 0L || packet.timestamp > lastAppliedPauseTimestamp)
                            if (acceptedPause) {
                                lastAppliedPauseTimestamp = maxOf(lastAppliedPauseTimestamp, packet.timestamp)
                                if (packet.isPaused) {
                                    val wasPaused = isGamePaused
                                    isGamePaused = true
                                    pausedByPlayerName = packet.pauseOwnerId.ifBlank { "Opponent" }
                                    pauseRemainingSeconds = multiplayerLifecyclePolicy?.pauseRemainingSeconds
                                        ?: (if (wasPaused && pauseRemainingSeconds > 0) minOf(pauseRemainingSeconds, pauseSec) else pauseSec)
                                    val remMs = packet.remainingTurnMillis.takeIf { it > 0L } ?: (turnTimer * 1000L)
                                    frozenRemainingTurnMillis = remMs
                                    frozenRemainingTurnSeconds = ((remMs + 999L) / 1000L).toInt()
                                    turnTimer = frozenRemainingTurnSeconds ?: turnTimer
                                } else {
                                    isGamePaused = false
                                    pausedByPlayerName = ""
                                    pauseRemainingSeconds = 0
                                    frozenRemainingTurnMillis = null
                                    frozenRemainingTurnSeconds = null
                                    if (packet.remainingTurnMillis > 0L && !application.hasPendingPicks) {
                                        turnTimer = ((packet.remainingTurnMillis + 999L) / 1000L).toInt()
                                    }
                                }
                            }

                            // Reconcile eliminated players: host authority only
                            if (packet.eliminatedPlayerIds.isNotEmpty()) {
                                val newEliminated = packet.eliminatedPlayerIds.toSet()
                                if (newEliminated != eliminatedPlayerIds) {
                                    eliminatedPlayerIds = eliminatedPlayerIds + newEliminated
                                    newEliminated.forEach { elimId ->
                                        multiplayerLifecyclePolicy?.kickOrEliminatePlayer(elimId)
                                    }
                                }
                            }

                            // Evaluate win conditions from host
                            if (!isGameOver) {
                                val outcome = if (application.confirmedWinnerId.isNotBlank() &&
                                    MultiplayerResultAuthority.mayFinalize(gameplayContext(), packet)) {
                                    val isLocalWinner = isPlayerMe(packet.winnerPlayerId)
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
                                    if (MultiplayerResultAuthority.mayFinalize(gameplayContext())) evaluateMatchOutcome(lastPicker)
                                    else MatchOutcome(false, false, false)
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
                                } else if (!application.hasPendingPicks && packet.currentTurnPlayerId.isNotBlank()) {
                                    val changedTurn = packet.turnNumber != turnNumber || packet.currentTurnPlayerId != currentTurnPlayerId
                                    turnNumber = packet.turnNumber
                                    currentTurnPlayerId = packet.currentTurnPlayerId
                                    isMyTurn = (currentTurnPlayerId == myUid)
                                    if (changedTurn && !isGamePaused) {
                                        turnTimer = if (packet.remainingTurnMillis > 0L) ((packet.remainingTurnMillis + 999L) / 1000L).toInt() else 30
                                    }
                                    multiplayerLifecyclePolicy?.onTurnStarted(currentTurnPlayerId, turnNumber = turnNumber, forceReset = changedTurn)
                                }
                            }
                            if (packet.chatMessages.isNotEmpty()) {
                                val existingIds = matchChatHistory.map { it.id }.toSet()
                                val missing = packet.chatMessages.filter { it.id !in existingIds }
                                if (missing.isNotEmpty()) {
                                    matchChatHistory = (matchChatHistory + missing).sortedBy { it.timestamp }
                                }
                            }
                            awaitingHostStateSync = false
                            isReconcilingMatch = false
                            if (!isGameOver && currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                                OngoingMatchStore.updateMatchGameState(
                                    context = context, playerBoard = playerBoard, opponentBoard = opponentBoard,
                                    allPlayerBoards = allPlayerBoards, pickedNumbers = pickedNumbersHistory.toList(),
                                    pickedByPlayers = pickedByPlayerHistory.toList(), turnIds = pickedTurnIdsHistory.toList(),
                                    turnNumber = turnNumber, currentTurnPlayerId = currentTurnPlayerId, chatMessages = matchChatHistory,
                                    isPaused = isGamePaused, pauseRemainingSeconds = pauseRemainingSeconds,
                                    remainingTurnMillis = (if (isGamePaused) frozenRemainingTurnMillis else null) ?: (turnTimer * 1000L),
                                    pauseOwnerId = pausedByPlayerName,
                                    pauseDeadlineTimestamp = if (isGamePaused) multiplayerLifecyclePolicy?.let { System.currentTimeMillis() + it.pauseRemainingMillis } else null,
                                    eliminatedPlayerIds = eliminatedPlayerIds.toList(), accountUid = myUid,
                                    multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint,
                                    pauseControlRevision = multiplayerLifecyclePolicy?.pauseEpisode,
                                    versionedPauseControl = multiplayerLifecyclePolicy?.hasVersionedPauseControl,
                                    pauseControlTimestamp = multiplayerLifecyclePolicy?.lastAppliedPauseTimestamp
                                )
                            }
                        }
                    }
                    if (isHosting) isReconcilingMatch = false
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

            "PAUSE_EXTEND" -> {
                val hostPlayerId = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: (if (isHosting) myUid else opponentPlayerId)
                val matchesMatch = packet.seed == currentMatchSeed || packet.matchId == currentMatchSeed.toString()
                if (!isHosting && packet.playerId == hostPlayerId && matchesMatch) {
                    val applied = multiplayerLifecyclePolicy?.applyAuthoritativePauseExtension(
                        timestamp = packet.timestamp, remotePauseEpisode = packet.pauseEpisode
                    ) ?: (isGamePaused && (packet.timestamp == 0L || packet.timestamp > lastAppliedPauseTimestamp))
                    if (applied) {
                        lastAppliedPauseTimestamp = maxOf(lastAppliedPauseTimestamp, packet.timestamp)
                        isGamePaused = true
                        pauseRemainingSeconds = multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: 30
                        canExtendPause = false
                        persistMultiplayerControl()
                        Toast.makeText(context, "⏸️ Pause extended (+30s)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            "PLAYER_ELIMINATED" -> {
                val targetId = packet.targetPlayerId
                val senderId = packet.playerId
                val hostPlayerId = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: (if (isHosting) myUid else opponentPlayerId)
                val isSenderHost = (senderId == hostPlayerId)
                val matchesMatch = (packet.seed == currentMatchSeed || packet.matchId == currentMatchSeed.toString())
                if (isSenderHost && (currentMatchSeed == 0L || matchesMatch) && targetId.isNotBlank() && targetId != hostPlayerId) {
                    val kicked = multiplayerLifecyclePolicy?.kickOrEliminatePlayer(targetId) ?: true
                    if (kicked) {
                        eliminatedPlayerIds = eliminatedPlayerIds + targetId
                        val targetName = matchParticipants.find { it.id == targetId }?.displayName ?: "Player"
                        broadcastSystemChatMessage("🚫 $targetName was eliminated from the match")
                        if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                context = context,
                                eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                accountUid = myUid
                            )
                        }
                        if (currentTurnPlayerId == targetId && isHosting) {
                            val nextId = calculateNextTurnPlayerId(targetId)
                            currentTurnPlayerId = nextId
                            turnNumber += 1
                            turnTimer = 30
                            isMyTurn = (currentTurnPlayerId == myUid)
                            multiplayerLifecyclePolicy?.onTurnStarted(nextId, turnNumber = turnNumber, forceReset = true)
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "TURN_TIMEOUT",
                                    playerId = targetId,
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

            "HOST_HOLD" -> {
                // Group hold/return UI follows the locally verified heartbeat policy.
                if (!isHosting && multiplayerLifecyclePolicy == null) {
                    val secs = (packet.remainingTurnMillis / 1000L).toInt()
                    hostAbsenceHoldRemainingSeconds = secs
                }
            }

            "HOST_RESUMED" -> {
                if (!isHosting && multiplayerLifecyclePolicy == null) {
                    val secs = (packet.remainingTurnMillis / 1000L).toInt()
                    hostReturningCountdown = if (secs > 0) secs else null
                    hostAbsenceHoldRemainingSeconds = 0
                }
            }

            "GAME_PAUSED" -> {
                val hostPlayerId = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: (if (isHosting) myUid else opponentPlayerId)
                val matchesMatch = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)
                if (!isHosting && packet.playerId == hostPlayerId && matchesMatch) {
                    val accepted = multiplayerLifecyclePolicy?.syncRemotePause(
                        isPaused = true, remainingSeconds = 30,
                        timestamp = packet.timestamp, remotePauseEpisode = packet.pauseEpisode
                    ) ?: (packet.timestamp == 0L || packet.timestamp > lastAppliedPauseTimestamp)
                    if (!accepted) return
                    lastAppliedPauseTimestamp = maxOf(lastAppliedPauseTimestamp, packet.timestamp)
                    val policyRemSec = multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: 30
                    pauseRemainingSeconds = policyRemSec
                    canExtendPause = false
                    isGamePaused = true
                    pausedByPlayerName = packet.displayName.ifBlank { "Host" }
                    val remainderMs = if (packet.remainingTurnMillis > 0L) packet.remainingTurnMillis else (turnTimer * 1000L)
                    val remainderSec = ((remainderMs + 999L) / 1000L).toInt()
                    frozenRemainingTurnMillis = remainderMs
                    frozenRemainingTurnSeconds = remainderSec
                    turnTimer = remainderSec
                    if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                            context = context,
                            isPaused = true,
                            pauseRemainingSeconds = pauseRemainingSeconds,
                            pauseDeadlineTimestamp = System.currentTimeMillis() + (multiplayerLifecyclePolicy?.pauseRemainingMillis ?: (pauseRemainingSeconds * 1000L)),
                            remainingTurnMillis = remainderMs,
                            pauseOwnerId = pausedByPlayerName,
                            pauseControlRevision = multiplayerLifecyclePolicy?.pauseEpisode,
                            versionedPauseControl = multiplayerLifecyclePolicy?.hasVersionedPauseControl,
                            pauseControlTimestamp = multiplayerLifecyclePolicy?.lastAppliedPauseTimestamp,
                            eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                            accountUid = myUid
                        )
                    }
                }
            }

            "GAME_RESUMED" -> {
                val hostPlayerId = matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: onlineRoomSync.currentHostId ?: (if (isHosting) myUid else opponentPlayerId)
                val matchesMatch = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)
                if (!isHosting && packet.playerId == hostPlayerId && matchesMatch) {
                    val accepted = multiplayerLifecyclePolicy?.syncRemotePause(
                        isPaused = false, remainingSeconds = 0,
                        timestamp = packet.timestamp, remotePauseEpisode = packet.pauseEpisode
                    ) ?: (packet.timestamp == 0L || packet.timestamp > lastAppliedPauseTimestamp)
                    if (!accepted) return
                    lastAppliedPauseTimestamp = maxOf(lastAppliedPauseTimestamp, packet.timestamp)
                    pauseRemainingSeconds = 0
                    canExtendPause = false
                    hostReturningCountdown = null
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
                            pauseRemainingSeconds = 0,
                            pauseDeadlineTimestamp = null,
                            remainingTurnMillis = remainderMs,
                            pauseOwnerId = "",
                            pauseControlRevision = multiplayerLifecyclePolicy?.pauseEpisode,
                            versionedPauseControl = multiplayerLifecyclePolicy?.hasVersionedPauseControl,
                            pauseControlTimestamp = multiplayerLifecyclePolicy?.lastAppliedPauseTimestamp,
                            eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                            accountUid = myUid
                        )
                    }
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
                    val decision = MultiplayerGameplayReducer.result(packet, gameplayContext(), gameplayState())
                    if (!decision.accepted) {
                        requestGameplayCorrection()
                        return
                    }
                    run {
                        applyGameplayState(decision.state)
                        recentPick = decision.state.picks.lastOrNull()?.let { RecentPick(it.number, it.playerId, it.turn) }
                        val effectiveWinner = decision.winnerId

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
                            if (isHosting) broadcastAcceptedGameplayState()
                        }
                    }
                }
            }

            "SURRENDER" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    val rules = gameplayContext()
                    val decision = MultiplayerGameplayReducer.surrender(packet, rules, gameplayState())
                    if (!decision.accepted) return
                    applyGameplayState(decision.state)
                    multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(
                        turnNumber = maxOf(multiplayerSnapshotCheckpoint.turnNumber, turnNumber))
                    val surrenderName = packet.displayName.ifBlank { packet.username.ifBlank { "Opponent" } }

                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    val eligibleRemaining = participants.filter {
                        it.id.isNotBlank() &&
                        it.id !in decision.state.surrenderedPlayerIds &&
                        it.id !in eliminatedPlayerIds
                    }

                    if (eligibleRemaining.size <= 1) {
                        isGameOver = true
                        val winningPlayerId = eligibleRemaining.firstOrNull()?.id ?: myUid
                        val won = isPlayerMe(winningPlayerId)
                        didPlayerWin = won
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = winningPlayerId
                        activeWinReason = "Opponent Surrendered"
                        activeRunnerPlayerIds = emptyList()
                        recordFinishedMatch(won)
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.recordMatchResultTombstone(
                            context = context,
                            roomCode = roomCode,
                            matchSeed = currentMatchSeed,
                            winnerUid = winnerPlayerId,
                            winReason = activeWinReason,
                            runnerPlayerIds = activeRunnerPlayerIds,
                            episodeCount = interruptionEpisodeCount,
                            accountUid = myUid
                        )
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        opponentSurrenderMessage = if (won) "$surrenderName surrendered the match! You win!" else "$surrenderName surrendered the match."
                    } else {
                        // Group match continues among remaining active participants!
                        broadcastSystemChatMessage("🏳️ $surrenderName surrendered the match")
                    }
                    saveGameplayState()
                    if (isHosting) broadcastAcceptedGameplayState()
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
                                multiplayerLifecyclePolicy?.onTurnStarted(nextId, turnNumber = turnNumber, forceReset = true)
                                saveGameplayState()
                                persistMultiplayerControl()
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
                            // 1v1 Host Left: Intentional, permanent departure!
                            // Remaining participant wins by forfeit immediately!
                            isGameOver = true
                            didPlayerWin = true
                            isDrawMatch = false
                            isRunnerMatch = false
                            winnerPlayerId = myUid
                            activeWinReason = "Opponent Left"
                            activeRunnerPlayerIds = emptyList()
                            recordFinishedMatch(true)
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                            opponentDisconnectMessage = "Host left the game. You win by forfeit!"
                            return@handleIncomingPacket
                        }
                        isHostLeftGame = true
                        val hostId = packet.playerId.ifBlank {
                            matchParticipants.find { it.isHost }?.id ?: realTimePlayers.find { it.isHost }?.id ?: ""
                        }
                        if (hostId.isNotBlank()) {
                            markPlayerDisconnected(hostId)
                            consecutiveMissedTurns[hostId] = 3
                            eliminatedPlayerIds = eliminatedPlayerIds + hostId
                            multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(
                                surrenderedPlayerIds = multiplayerSnapshotCheckpoint.surrenderedPlayerIds + hostId
                            )
                        }
                        val participants = matchParticipants.ifEmpty { realTimePlayers }
                        val activeRemaining = participants.filter {
                            it.id.isNotBlank() && !isPlayerDisconnected(it.id) && it.id != hostId &&
                            it.id !in eliminatedPlayerIds && it.id !in multiplayerSnapshotCheckpoint.surrenderedPlayerIds
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
                            val actingHostId = activeRemaining.first().id
                            val isActingHost = (actingHostId == myUid)
                            if (isActingHost) {
                                isHosting = true
                            }
                            matchParticipants = participants.map { p ->
                                when (p.id) {
                                    actingHostId -> p.copy(isHost = true)
                                    hostId -> p.copy(isHost = false)
                                    else -> p.copy(isHost = false)
                                }
                            }
                            multiplayerLifecyclePolicy?.transferHostAuthority(actingHostId)

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
                            if (isActingHost) {
                                broadcastAcceptedGameplayState()
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
                        // 1v1 Guest Left: Intentional departure!
                        // Host wins by forfeit immediately!
                        isGameOver = true
                        didPlayerWin = true
                        isDrawMatch = false
                        isRunnerMatch = false
                        winnerPlayerId = myUid
                        activeWinReason = "Opponent Left"
                        activeRunnerPlayerIds = emptyList()
                        recordFinishedMatch(true)
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        opponentDisconnectMessage = "Opponent left the game. You win by forfeit!"
                        return@handleIncomingPacket
                    }
                    val participants = matchParticipants.ifEmpty { realTimePlayers }
                    markPlayerDisconnected(packet.playerId)
                    consecutiveMissedTurns[packet.playerId] = 3
                    eliminatedPlayerIds = eliminatedPlayerIds + packet.playerId
                    multiplayerSnapshotCheckpoint = multiplayerSnapshotCheckpoint.copy(
                        surrenderedPlayerIds = multiplayerSnapshotCheckpoint.surrenderedPlayerIds + packet.playerId
                    )
                    multiplayerLifecyclePolicy?.recordSurrender(packet.playerId)
                    val activeRemaining = participants.filter {
                        it.id.isNotBlank() && !isPlayerDisconnected(it.id) &&
                        it.id !in eliminatedPlayerIds && it.id !in multiplayerSnapshotCheckpoint.surrenderedPlayerIds
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
                val currentRoute = navController.currentDestination?.route
                val isLobbyPhase = currentRoute in setOf(Screen.Lobby.route, Screen.NearbyLobby.route)
                val isSenderHost = packet.isHost || realTimePlayers.find { it.id == packet.playerId }?.isHost == true || packet.playerId == onlineRoomSync.currentHostId
                if (!isHosting && isLobbyPhase && isSenderHost) {
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
                                pauseRemainingSeconds = if (isGamePaused) (multiplayerLifecyclePolicy?.pauseRemainingSeconds ?: pauseRemainingSeconds) else 0,
                                pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 0,
                                remainingTurnMillis = if (isGamePaused || isMultiplayerSuspended) (frozenRemainingTurnMillis ?: ((frozenRemainingTurnSeconds ?: turnTimer) * 1000L)) else (turnTimer * 1000L),
                                pauseOwnerId = pausedByPlayerName,
                                eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                claimEpisodeCount = interruptionEpisodeCount,
                                winnerPlayerId = if (isGameOver) winnerPlayerId else "",
                                winReason = if (isGameOver) activeWinReason else "",
                                runnerPlayerIds = if (isGameOver) activeRunnerPlayerIds else emptyList(),
                                chatMessages = matchChatHistory.toList()
                            )
                        )
                    }
                }
            }

            "KICK_PLAYER" -> {
                val currentRoute = navController.currentDestination?.route
                val isLobbyPhase = currentRoute in setOf(Screen.Lobby.route, Screen.NearbyLobby.route)
                val isSenderHost = packet.isHost || realTimePlayers.find { it.id == packet.playerId }?.isHost == true || packet.playerId == onlineRoomSync.currentHostId
                val isSameRoom = packet.roomCode.isBlank() || packet.roomCode == roomCode
                val targetId = packet.targetPlayerId.ifBlank { packet.playerId }
                if (targetId == myUid && isLobbyPhase && isSenderHost && isSameRoom) {
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
                        Toast.makeText(context, success.message, Toast.LENGTH_SHORT).show()
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode, invite)
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
                        Toast.makeText(context, success.message, Toast.LENGTH_SHORT).show()
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode, invite)
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
                        Toast.makeText(context, success.message, Toast.LENGTH_SHORT).show()
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode, invite)
                },
                notificationFriendUsername = notificationFriendTarget,
                notificationRequests = notificationRequestsTarget,
                onNotificationTargetConsumed = { notificationFriendTarget = null; notificationRequestsTarget = false },
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
                            val generatedTurnOrder = com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.generateDeterministicTurnOrder(
                                sanitizedList,
                                seed
                            )
                            randomizedTurnOrder = generatedTurnOrder
                            val chosenFirstTurnUid = generatedTurnOrder.firstOrNull()?.id ?: candidateUids.first()

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

            DisposableEffect(Unit) {
                onDispose {
                    manualStartJob?.cancel()
                }
            }

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
                        updatedBoards = updatedBoards + ("ai_bot" to aiBoard)
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
                    manualStartJob?.cancel()
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
            LaunchedEffect(turnNumber, isMyTurn, isGameOver, isGamePaused, isMultiplayerSuspended) {
                if (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2) return@LaunchedEffect
                if (!isGameOver && !isGamePaused && !isMultiplayerSuspended) {
                    val isTwoPlayerOnline = (currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2)
                    if (multiplayerLifecyclePolicy?.shouldInstantlySkipTurn(currentTurnPlayerId) == true && isHosting) {
                        val skippedId = currentTurnPlayerId
                        val closedTurn = turnNumber
                        val nextId = calculateNextTurnPlayerId(skippedId)
                        currentTurnPlayerId = nextId
                        turnNumber += 1
                        turnTimer = 30
                        frozenRemainingTurnSeconds = null
                        frozenRemainingTurnMillis = null
                        isMyTurn = (currentTurnPlayerId == getLocalUid())
                        multiplayerLifecyclePolicy?.onTurnStarted(
                            newTurnPlayerId = nextId,
                            turnNumber = turnNumber,
                            forceReset = true
                        )
                        broadcastPacket(
                            MultiplayerGameplayReducer.timeoutPacket(currentMatchSeed, getLocalUid(), skippedId, closedTurn, nextId).copy(
                                pickedHistory = pickedNumbersHistory.toList(),
                                pickedByHistory = pickedByPlayerHistory.toList(), turnIds = pickedTurnIdsHistory.toList()
                            )
                        )
                        return@LaunchedEffect
                    }
                    if (frozenRemainingTurnSeconds != null) {
                        if (frozenTurnNumber == turnNumber && frozenMatchSeed == currentMatchSeed) {
                            turnTimer = frozenRemainingTurnSeconds!!
                        }
                        frozenRemainingTurnSeconds = null
                        frozenRemainingTurnMillis = null
                        frozenTurnNumber = -1
                        frozenMatchSeed = 0L
                    } else if (turnTimer <= 0) {
                        turnTimer = 30
                    }
                    var skippedDueToDisconnect = false
                    while (turnTimer > 0 && !isGamePaused && !isGameOver && !isMultiplayerSuspended) {
                        delay(1000L)
                        if (!isGamePaused && !isGameOver && !isMultiplayerSuspended) {
                            val isForeground = com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value
                            if (!isForeground && isHosting && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                                continue
                            }
                            if (isTwoPlayerOnline && !onlineRoomSync.isConnected) {
                                // Suspend turn timer if local transport is disconnected
                                continue
                            }
                            turnTimer -= 1
                            // Check if current active player's connection is dead (> 10s of silence)
                            val now = System.currentTimeMillis()
                            val isWithinResumeGracePeriod = (now - lastForegroundResumeTimestamp) < 12_000L

                            if (multiplayerLifecyclePolicy == null && !isTwoPlayerOnline && isForeground && !isWithinResumeGracePeriod && !isMyTurn && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
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
                    if (skippedDueToDisconnect && !isGameOver && !isGamePaused && !isMultiplayerSuspended) {
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
                            val isAuthorizedSkipper = (isHosting || isLegacyHostTurn || isActingHost || activeRemaining.size <= 2) && !isMultiplayerSuspended
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
                    } else if (isMyTurn && !isGameOver && !isGamePaused && !isMultiplayerSuspended && turnTimer == 0) {
                        // Pass the turn cleanly with no auto-picked number
                        executePick(
                            number = -1,
                            isOwnPick = true,
                            pickerId = getLocalUid(),
                            shouldBroadcast = true
                        )
                    } else if (!isMyTurn && !isGameOver && !isGamePaused && !isMultiplayerSuspended && turnTimer == 0) {
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
                            if (!isMyTurn && !isGameOver && !isGamePaused && !isMultiplayerSuspended && turnTimer == 0) {
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

            LaunchedEffect(currentMatchSeed, multiplayerLifecyclePolicy) {
                if (multiplayerLifecyclePolicy == null) return@LaunchedEffect
                while (isActive && !isGameOver) {
                    if (com.bingo.multiplayer.domain.network.AppLifecycleObserver.isAppInForeground.value) {
                        val session = if (isUsingP2p) "nearby:${onlineRoomSync.instanceId}" else onlineRoomSync.roomSessionId
                        broadcastPacket(multiplayerPacketGate.beginProbe(gameplayContext(), roomCode, session))
                    }
                    delay(MultiplayerLifecyclePacketGate.PROBE_INTERVAL_MS)
                }
            }

            // Multiplayer Match Lifecycle & Watchdog Loop (3-8 players)
            LaunchedEffect(multiplayerLifecyclePolicy, isGameOver) {
                val policy = multiplayerLifecyclePolicy ?: return@LaunchedEffect
                if (isGameOver) return@LaunchedEffect
                while (!isGameOver && isActive) {
                    delay(500L)
                    if (isGameOver) break
                    val actions = policy.onTick()
                    for (action in actions) {
                        applyLifecycleAction(action)
                    }
                    val currentDisc = policy.disconnectedPlayerIds.toList()
                    if (disconnectedPlayerIds.toSet() != policy.disconnectedPlayerIds) {
                        disconnectedPlayerIds.clear()
                        disconnectedPlayerIds.addAll(currentDisc)
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
                    onlineRoomSync.matchHeartbeatPolicy?.let { policy ->
                        val initialSnap = policy.toSnapshot()
                        peerAvailability = initialSnap.state
                        peerStateSynchronized = initialSnap.isStateSynchronized
                        interruptionEpisodeCount = initialSnap.committedEpisodeCount
                        if (initialSnap.state == PeerAvailabilityState.UNREACHABLE && initialSnap.isLocalHealthy) {
                            isOpponentUnreachable = true
                            isClaimVictoryEligible = policy.canClaimVictory(getLocalUid()) && reliableClient?.isHost == true
                            val peerName = matchParticipants.find { it.id == policy.peerUid }?.displayName ?: "Opponent"
                            reportMatchStatus("disconnect:${initialSnap.currentEpisodeId}", "$peerName disconnected (warning ${initialSnap.committedEpisodeCount})")
                            if (reliableClient?.isHost == true) {
                                savePrompt(InterruptionPromptState.open(
                                    interruptionPrompt, initialSnap.currentEpisodeId.orEmpty(), initialSnap.committedEpisodeCount,
                                    System.currentTimeMillis(), isGamePaused
                                ))
                            } else {
                                reliableClient?.waitForHost()
                            }
                        }
                    }
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
            val isHostPlayer = isHosting || (onlineRoomSync.currentHostId != null && getLocalUid() == onlineRoomSync.currentHostId) || (matchParticipants.singleOrNull { it.isHost }?.id == getLocalUid()) || (realTimePlayers.singleOrNull { it.isHost }?.id == getLocalUid())
            val isCurrentHost = when (currentGameMode) {
                GameMode.AI_EASY, GameMode.AI_HARD -> true
                GameMode.ONLINE_ROOM -> if (reliable1v1) reliableClient?.isHost ?: (matchParticipants.singleOrNull { it.isHost }?.id == getLocalUid()) else isHostPlayer
                GameMode.NEARBY_NETWORK -> isHostPlayer
            }
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

            val displayedPlayerBoard = remember(playerBoard, optimisticPendingPickNumber) {
                val pending = optimisticPendingPickNumber
                if (pending != null && reliable1v1) {
                    playerBoard.copy(
                        cells = playerBoard.cells.map { cell ->
                            if (cell.number == pending) {
                                cell.copy(
                                    markState = com.bingo.multiplayer.domain.model.CellMarkState.Marked(
                                        pickedByPlayerId = getLocalUid(),
                                        isOwnPick = true,
                                        turnNumber = turnNumber
                                    ),
                                    isRecentPick = true
                                )
                            } else {
                                cell
                            }
                        }
                    )
                } else {
                    playerBoard
                }
            }

            val displayedRecentPick = remember(recentPick, optimisticPendingPickNumber) {
                val pending = optimisticPendingPickNumber
                if (pending != null && reliable1v1) {
                    RecentPick(
                        number = pending,
                        pickedByPlayerId = getLocalUid(),
                        turnNumber = turnNumber
                    )
                } else {
                    recentPick
                }
            }

            GameScreen(
                hostDisconnected = currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2 && isHostDisconnected && !isGameOver,
                gameplaySuspended = isMultiplayerSuspended,
                useAcceptedChatHistory = currentGameMode == GameMode.ONLINE_ROOM && matchParticipants.size == 2,
                canControlPause = isCurrentHost,
                canResumeGame = if (reliable1v1) canResumeReliableMatch() else isCurrentHost,
                opponentConnectionStatus = if (reliable1v1) peerConnectionText else "",
                hostName = matchParticipants.singleOrNull { it.isHost }?.displayName ?: "Host",
                interruptionPrompt = interruptionPrompt,
                onContinueCountdown = { savePrompt(interruptionPrompt.decide()) },
                board = displayedPlayerBoard,
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
                hostAbsenceHoldSeconds = hostAbsenceHoldRemainingSeconds,
                hostReturningCountdown = hostReturningCountdown,
                pauseRemainingSeconds = pauseRemainingSeconds,
                canExtendPause = canExtendPause,
                onExtendPause = {
                    if (isCurrentHost) {
                        val extended = multiplayerLifecyclePolicy?.extendPause() ?: true
                        if (extended) {
                            pauseRemainingSeconds = 30
                            canExtendPause = false
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "PAUSE_EXTEND",
                                    playerId = getLocalUid(),
                                    remainingTurnMillis = 30_000L,
                                    seed = currentMatchSeed,
                                    matchId = currentMatchSeed.toString(),
                                    timestamp = System.currentTimeMillis(),
                                    pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 2
                                )
                            )
                            if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                    context = context,
                                    isPaused = true,
                                    pauseRemainingSeconds = 30,
                                    pauseDeadlineTimestamp = System.currentTimeMillis() + 30_000L,
                                    eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                    accountUid = getLocalUid()
                                )
                            }
                            Toast.makeText(context, "⏸️ Pause extended (+30s)", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                turnInactivityPlayerId = turnInactivityPlayerId,
                turnInactivityPlayerName = matchParticipants.find { it.id == turnInactivityPlayerId }?.displayName ?: "Player",
                turnInactivityAutoSkipSeconds = turnInactivityAutoSkipSeconds,
                onHostSkipInactivePlayer = {
                    if (isCurrentHost) {
                        val inactiveId = turnInactivityPlayerId ?: currentTurnPlayerId
                        turnInactivityPlayerId = null
                        if (inactiveId.isNotBlank()) {
                            val inactiveName = matchParticipants.find { it.id == inactiveId }?.displayName ?: "Player"
                            val skipText = "📢 Turn skipped ($inactiveName unresponsive)"
                            broadcastSystemChatMessage(skipText)
                            val nextId = calculateNextTurnPlayerId(inactiveId)
                            currentTurnPlayerId = nextId
                            turnNumber += 1
                            turnTimer = 30
                            isMyTurn = (currentTurnPlayerId == getLocalUid())
                            multiplayerLifecyclePolicy?.onTurnStarted(nextId, turnNumber = turnNumber, forceReset = true)
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "TURN_TIMEOUT",
                                    playerId = inactiveId,
                                    turnNumber = turnNumber,
                                    currentTurnPlayerId = nextId,
                                    seed = currentMatchSeed,
                                    pickedHistory = pickedNumbersHistory.toList(),
                                    pickedByHistory = pickedByPlayerHistory.toList()
                                )
                            )
                        }
                    }
                },
                onHostPauseInactivePlayer = {
                    if (isCurrentHost) {
                        turnInactivityPlayerId = null
                        multiplayerLifecyclePolicy?.pauseMatch()
                        isGamePaused = true
                        pausedByPlayerName = getPlayerDisplayName()
                        pauseRemainingSeconds = 30
                        canExtendPause = false
                        val remMs = (turnTimer * 1000L)
                        frozenRemainingTurnMillis = remMs
                        frozenRemainingTurnSeconds = turnTimer
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "GAME_PAUSED",
                                playerId = getLocalUid(),
                                displayName = getPlayerDisplayName(),
                                remainingTurnMillis = remMs,
                                isPaused = true,
                                pauseOwnerId = getLocalUid(),
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString(),
                                timestamp = System.currentTimeMillis(),
                                pauseEpisode = multiplayerLifecyclePolicy?.pauseEpisode ?: 1
                            )
                        )
                        if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                            com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                context = context,
                                isPaused = true,
                                remainingTurnMillis = remMs,
                                pauseRemainingSeconds = 30,
                                pauseDeadlineTimestamp = System.currentTimeMillis() + 30_000L,
                                pauseOwnerId = getLocalUid(),
                                eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                accountUid = getLocalUid()
                            )
                        }
                    }
                },
                onKickPlayer = { targetId ->
                    if (isCurrentHost && targetId.isNotBlank()) {
                        val kicked = multiplayerLifecyclePolicy?.kickOrEliminatePlayer(targetId) ?: true
                        if (kicked) {
                            eliminatedPlayerIds = eliminatedPlayerIds + targetId
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "PLAYER_ELIMINATED",
                                    targetPlayerId = targetId,
                                    playerId = getLocalUid(),
                                    seed = currentMatchSeed,
                                    matchId = currentMatchSeed.toString(),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            val targetName = matchParticipants.find { p -> p.id == targetId }?.displayName ?: "Player"
                            broadcastSystemChatMessage("🚫 $targetName was eliminated from the match")
                            if (currentGameMode == GameMode.ONLINE_ROOM && roomCode.isNotBlank()) {
                                com.bingo.multiplayer.domain.network.OngoingMatchStore.updateMatchGameState(
                                    context = context,
                                    eliminatedPlayerIds = eliminatedPlayerIds.toList(),
                                    accountUid = getLocalUid()
                                )
                            }
                            if (currentTurnPlayerId == targetId) {
                                val nextId = calculateNextTurnPlayerId(targetId)
                                currentTurnPlayerId = nextId
                                turnNumber += 1
                                turnTimer = 30
                                isMyTurn = (currentTurnPlayerId == getLocalUid())
                                multiplayerLifecyclePolicy?.onTurnStarted(nextId, turnNumber = turnNumber, forceReset = true)
                                broadcastPacket(
                                    RoomMessagePacket(
                                        type = "TURN_TIMEOUT",
                                        playerId = targetId,
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
                },
                eliminatedPlayerIds = eliminatedPlayerIds,
                recentPick = displayedRecentPick,
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
                        val myUid = getLocalUid()
                        recordFinishedMatch(false)
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        coroutineScope.launch {
                            delay(200L)
                            disconnectRoom()
                        }
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.MainMenu.route) { inclusive = true }
                        }
                        return@surrender
                    }
                    val myUid = getLocalUid()
                    val isGroup = matchParticipants.size > 2
                    manualStartJob?.cancel()
                    isGameOver = false
                    didPlayerWin = false
                    latestIncomingEmote = null
                    latestIncomingEmoteTimestamp = 0L
                    recordFinishedMatch(false)
                    com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)

                    if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                        val packet = if (isHosting) {
                            RoomMessagePacket(
                                type = "HOST_LEFT",
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString(),
                                playerId = myUid,
                                displayName = getPlayerDisplayName(),
                                username = currentAuthUser?.username ?: "",
                                isHost = true
                            )
                        } else {
                            RoomMessagePacket(
                                type = "SURRENDER",
                                seed = currentMatchSeed,
                                matchId = currentMatchSeed.toString(),
                                playerId = myUid,
                                displayName = getPlayerDisplayName()
                            )
                        }
                        coroutineScope.launch {
                            onlineRoomSync.sendImmediateAndAcknowledge(packet)
                            if (isHosting && !isGroup && roomCode.isNotBlank()) {
                                com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                            }
                            disconnectRoom()
                        }
                    } else {
                        disconnectRoom()
                    }

                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = true }
                    }
                },
                onCellPicked = { cellNumber ->
                    if (!isReconcilingMatch && !awaitingHostStateSync) {
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
                    multiplayerLifecyclePolicy = null
                    hostAbsenceHoldRemainingSeconds = 0
                    hostReturningCountdown = null
                    pauseRemainingSeconds = 0
                    canExtendPause = false
                    turnInactivityPlayerId = null
                    turnInactivityAutoSkipSeconds = 10
                    eliminatedPlayerIds = emptySet()
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
                        multiplayerLifecyclePolicy = null
                        hostAbsenceHoldRemainingSeconds = 0
                        hostReturningCountdown = null
                        pauseRemainingSeconds = 0
                        canExtendPause = false
                        turnInactivityPlayerId = null
                        turnInactivityAutoSkipSeconds = 10
                        eliminatedPlayerIds = emptySet()
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

    val currentInvite = incomingInvite?.takeIf { invite ->
        val account = NotificationRuntime.account(context)
        com.bingo.multiplayer.core.designsystem.NotificationPreferences.canShowInAppInvite(context) &&
            NotificationPolicy.eligible(invite.toEvent(), account, System.currentTimeMillis()) &&
            account != null && !NotificationRuntime.ledger(context).handled(account.uid, invite.eventId)
    }
    if (currentInvite != null) {
        AlertDialog(
            onDismissRequest = {
                NotificationRuntime.finishInvite(context, currentInvite)
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
                        acceptAndJoinRoom(currentInvite.roomCode, currentInvite)
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
                        NotificationRuntime.finishInvite(context, currentInvite)
                        incomingInvite = null
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
                        manualStartJob?.cancel()
                        val myUid = getLocalUid()
                        val isGroup = matchParticipants.size > 2
                        com.bingo.multiplayer.domain.network.OngoingMatchStore.clearOngoingMatch(context, myUid)
                        isGameOver = false
                        didPlayerWin = false
                        isLocalBoardReady = false
                        isOpponentBoardReady = false
                        countdownSeconds = -1
                        firstTurnPlayerName = ""
                        recordFinishedMatch(false)
                        if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                            val packet = if (isHosting) {
                                RoomMessagePacket(
                                    type = "HOST_LEFT",
                                    seed = currentMatchSeed,
                                    matchId = currentMatchSeed.toString(),
                                    playerId = myUid,
                                    displayName = getPlayerDisplayName(),
                                    username = currentAuthUser?.username ?: "",
                                    isHost = true
                                )
                            } else {
                                RoomMessagePacket(
                                    type = "SURRENDER",
                                    seed = currentMatchSeed, matchId = currentMatchSeed.toString(),
                                    playerId = myUid,
                                    displayName = getPlayerDisplayName()
                                )
                            }
                            coroutineScope.launch {
                                onlineRoomSync.sendImmediateAndAcknowledge(packet)
                                if (isHosting && !isGroup && roomCode.isNotBlank()) {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.closeRoom(roomCode)
                                }
                                disconnectRoom()
                            }
                        } else {
                            disconnectRoom()
                        }
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
