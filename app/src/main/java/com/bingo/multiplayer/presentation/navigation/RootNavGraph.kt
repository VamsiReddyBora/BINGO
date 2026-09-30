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
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.domain.model.UserProfile
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
import com.bingo.multiplayer.presentation.nearby.NearbyLobbyScreen
import com.bingo.multiplayer.presentation.online.JoinRoomScreen
import com.bingo.multiplayer.presentation.online.OnlineMatchChoiceScreen
import com.bingo.multiplayer.presentation.settings.SettingsScreen
import com.bingo.multiplayer.presentation.social.DashboardAndFriendsScreen
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.presentation.manual.ManualBoardDesignScreen
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
    data object NearbyLobby : Screen("nearby_lobby")
    data object ManualBoardDesign : Screen("manual_board_design")
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
    val lanP2pSync = remember { com.bingo.multiplayer.domain.network.LanP2pSessionManager() }
    var isUsingP2p by remember { androidx.compose.runtime.mutableStateOf(false) }
    val lanDiscovery = remember { LanDiscoveryManager(context) }
    val broadcastPacket: (RoomMessagePacket) -> Unit = { packet -> if (isUsingP2p) lanP2pSync.broadcastPacket(packet) else onlineRoomSync.broadcastPacket(packet) }
    val disconnectRoom: () -> Unit = { if (isUsingP2p) lanP2pSync.disconnect() else onlineRoomSync.disconnect() }
    val coroutineScope = rememberCoroutineScope()

    val onlineRealTimePlayers by onlineRoomSync.players.collectAsState()
    val p2pRealTimePlayers by lanP2pSync.players.collectAsState()
    val realTimePlayers = if (isUsingP2p) p2pRealTimePlayers else onlineRealTimePlayers
    val isRefreshing by onlineRoomSync.isRefreshing.collectAsState()
    val discoveredGames by lanDiscovery.discoveredGames.collectAsState()
    var joinedLanGame by remember { mutableStateOf<LanDiscoveredGame?>(null) }

    // Game Session State
    var currentGameMode by remember { mutableStateOf(GameMode.AI_EASY) }
    var currentAiDifficulty by remember { mutableStateOf(AiDifficulty.EASY) }
    var boardSize by remember { mutableIntStateOf(5) }
    var playerBoard by remember { mutableStateOf(engine.generateBoard(5)) }
    var opponentBoard by remember { mutableStateOf(engine.generateBoard(5)) }
    var isMyTurn by remember { mutableStateOf(true) }
    var turnNumber by remember { mutableIntStateOf(1) }
    var turnTimer by remember { mutableIntStateOf(30) }
    var isGamePaused by remember { mutableStateOf(false) }
    var pausedByPlayerName by remember { mutableStateOf("") }
    var recentPick by remember { mutableStateOf<RecentPick?>(null) }
    var isGameOver by remember { mutableStateOf(false) }
    var didPlayerWin by remember { mutableStateOf(false) }
    var isDrawMatch by remember { mutableStateOf(false) }
    var currentMatchSeed by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var isManualBoard by remember { mutableStateOf(false) }
    var isLocalBoardReady by remember { mutableStateOf(false) }
    var isOpponentBoardReady by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableIntStateOf(-1) }
    var firstTurnPlayerName by remember { mutableStateOf("") }

    // History and Turn Authority (reconciles network packets and prevents stalls)
    val pickedNumbersHistory = remember { mutableStateListOf<Int>() }
    var currentTurnPlayerId by remember { mutableStateOf("") }
    var isProcessingTurn by remember { mutableStateOf(false) }

    // Online match state
    var roomCode by remember { mutableStateOf("") }
    var isHosting by remember { mutableStateOf(false) }
    var wantsToPlayAgainPlayerName by remember { mutableStateOf<String?>(null) }
    var opponentDisconnectMessage by remember { mutableStateOf<String?>(null) }
    var opponentSurrenderMessage by remember { mutableStateOf<String?>(null) }
    val onlinePingMs by onlineRoomSync.pingMs.collectAsState()
    var lobbyInactivityResetToken by remember { mutableIntStateOf(0) }

    fun getLocalUid(): String {
        val state = authRepository.authState.value
        val user = (state as? AuthState.Authenticated)?.user
        return user?.uid?.takeIf { it.isNotBlank() && it != "local_player" }
            ?: user?.username?.takeIf { it.isNotBlank() }?.let { "u_$it" }
            ?: authRepository.deviceId
    }

    fun getPlayerDisplayName(): String {
        val state = authRepository.authState.value
        return (state as? AuthState.Authenticated)?.user?.displayName ?: "Player"
    }

    fun getPlayerAvatarUrl(): String? {
        val state = authRepository.authState.value
        val user = (state as? AuthState.Authenticated)?.user
        return user?.avatarBase64?.takeIf { it.isNotBlank() } ?: user?.avatarUrl
    }

    var incomingInvite by remember { mutableStateOf<com.bingo.multiplayer.domain.network.GameInvite?>(null) }
    var showLeaveMatchDialog by remember { mutableStateOf(false) }
    val authStateValue by authRepository.authState.collectAsState()
    val currentAuthUser = (authStateValue as? AuthState.Authenticated)?.user

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

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
                if (roomCode != invite.roomCode) {
                    incomingInvite = invite
                }
            }
            val requestListener = friendsRepository.startListeningForRequests(
                myUsername = username,
                currentUser = currentAuthUser,
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
            // Start presence — managed by AppLifecycleObserver (ProcessLifecycleOwner)
            // for foreground/background transitions. DO NOT stop on screen transitions.
            com.bingo.multiplayer.domain.network.PresenceManager.startPresence(username)

            onDispose {
                inviteListener.close()
                requestListener.close()
                // NOTE: Do NOT call PresenceManager.stopPresence() here!
                // Presence is managed by AppLifecycleObserver (app foreground/background).
                // stopPresence() is only called on actual sign-out.
            }
        }
    }

    // Dual-channel background reconciliation: polls cloud KeyValue storage for pending invites and friend requests
    LaunchedEffect(currentAuthUser?.username) {
        val u = currentAuthUser?.username?.trim()?.lowercase()?.removePrefix("@")
        if (!u.isNullOrBlank()) {
            friendsRepository.syncFriendsAndRequests(u)
            while (isActive) {
                delay(3000L)
                try {
                    val pendingInvites = com.bingo.multiplayer.domain.network.GameInviteManager.fetchInvitesForUser(u)
                    val validInvite = pendingInvites.firstOrNull { it.roomCode != roomCode }
                    if (validInvite != null && incomingInvite?.roomCode != validInvite.roomCode) {
                        incomingInvite = validInvite
                    }
                    friendsRepository.syncFriendsAndRequests(u)
                } catch (_: Exception) {}
            }
        }
    }

    fun acceptAndJoinRoom(targetRoomCode: String) {
        val cleanCode = targetRoomCode.trim().uppercase()
        val user = (authRepository.authState.value as? AuthState.Authenticated)?.user
        val joinerVersion = System.currentTimeMillis()
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
            lastSeenTimestamp = joinerVersion,
            readyVersion = joinerVersion
        )
        coroutineScope.launch {
            when (val result = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.validateAndJoinRoom(cleanCode, localJoiner)) {
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
        }
    }

    fun togglePause() {
        val newPaused = !isGamePaused
        isGamePaused = newPaused
        pausedByPlayerName = if (newPaused) getPlayerDisplayName() else ""
        if (currentGameMode == GameMode.ONLINE_ROOM) {
            broadcastPacket(
                RoomMessagePacket(
                    type = if (newPaused) "GAME_PAUSED" else "GAME_RESUMED",
                    playerId = getLocalUid(),
                    displayName = getPlayerDisplayName()
                )
            )
        }
    }

    fun openNearbyLobby() {
        navController.navigate(Screen.NearbyLobby.route)
    }

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        openNearbyLobby()
    }

    fun recordFinishedMatch(won: Boolean, isDraw: Boolean = false) {
        val myName = currentAuthUser?.username?.ifBlank { currentAuthUser?.displayName } ?: getPlayerDisplayName()
        val isGroup = realTimePlayers.size > 2

        val matchTitle = if (isGroup) {
            when {
                isDraw -> "Group play draw 🤝"
                won -> "Group play won 🏆"
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
    }


    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // Dynamic Board Sizing Formula: 2 players -> 5x5, 3 players -> 6x6, 4 players -> 7x7, 5+ players -> 8x8
    fun calculateBoardSize(playerCount: Int): Int {
        val count = playerCount.coerceAtLeast(2)
        return (3 + count).coerceAtMost(8)
    }

    fun calculateNextTurnPlayerId(currentPickerId: String): String {
        return when (currentGameMode) {
            GameMode.AI_EASY, GameMode.AI_HARD -> {
                if (currentPickerId == "ai_bot") getLocalUid() else "ai_bot"
            }
            GameMode.ONLINE_ROOM, GameMode.NEARBY_NETWORK -> {
                val activePlayers = realTimePlayers.filter { it.id.isNotBlank() }
                if (activePlayers.size >= 2) {
                    val currentIndex = activePlayers.indexOfFirst { it.id == currentPickerId }
                    val nextIndex = if (currentIndex != -1) {
                        (currentIndex + 1) % activePlayers.size
                    } else {
                        val localIndex = activePlayers.indexOfFirst { it.id == getLocalUid() }
                        if (localIndex != -1) (localIndex + 1) % activePlayers.size else 0
                    }
                    activePlayers[nextIndex].id
                } else {
                    if (currentPickerId == getLocalUid()) {
                        realTimePlayers.firstOrNull { it.id != getLocalUid() }?.id ?: "opponent"
                    } else {
                        getLocalUid()
                    }
                }
            }
        }
    }

    fun startNewGame(
        mode: GameMode,
        difficulty: AiDifficulty = AiDifficulty.EASY,
        size: Int = 5,
        firstTurnPlayerId: String? = null,
        hostSeed: Long? = null
    ) {
        currentGameMode = mode
        currentAiDifficulty = difficulty
        boardSize = size
        
        val baseSeed = hostSeed ?: kotlin.random.Random.nextLong()
        currentMatchSeed = baseSeed
        if (mode == GameMode.ONLINE_ROOM || mode == GameMode.NEARBY_NETWORK) {
            if (isHosting) {
                playerBoard = engine.generateBoard(size, baseSeed)
                opponentBoard = engine.generateBoard(size, baseSeed + 1)
            } else {
                playerBoard = engine.generateBoard(size, baseSeed + 1)
                opponentBoard = engine.generateBoard(size, baseSeed)
            }
        } else {
            playerBoard = engine.generateBoard(size, baseSeed)
            opponentBoard = engine.generateBoard(size, baseSeed + 1)
        }
        
        pickedNumbersHistory.clear()
        isProcessingTurn = false

        val myUid = getLocalUid()
        val firstTurnUid = if (!firstTurnPlayerId.isNullOrBlank()) {
            firstTurnPlayerId
        } else if (mode == GameMode.ONLINE_ROOM || mode == GameMode.NEARBY_NETWORK) {
            val candidateUids = realTimePlayers.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
            val effCandidates = if (candidateUids.size >= 2) candidateUids else {
                val other = realTimePlayers.firstOrNull { it.id != myUid }?.id ?: "opponent"
                listOf(myUid, other).sorted()
            }
            ManualBoardEngine.determineRandomFirstTurn(baseSeed, effCandidates)
        } else {
            myUid
        }
        currentTurnPlayerId = firstTurnUid
        isMyTurn = (currentTurnPlayerId == myUid)

        turnNumber = 1
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

        navController.navigate(Screen.Game.route)
    }

    fun startCountdownAndInitiateTurn() {
        coroutineScope.launch {
            val candidateUids = realTimePlayers.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
            val myUid = getLocalUid()
            val effCandidates = if (candidateUids.size >= 2) candidateUids else {
                val other = realTimePlayers.firstOrNull { it.id != myUid }?.id ?: "opponent"
                listOf(myUid, other).sorted()
            }
            val firstTurnUid = ManualBoardEngine.determineRandomFirstTurn(currentMatchSeed, effCandidates)
            val assignedPlayer = realTimePlayers.find { it.id == firstTurnUid }
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

            navController.navigate(Screen.Game.route) {
                popUpTo(Screen.ManualBoardDesign.route) { inclusive = true }
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
                pickedNumbersHistory.add(number)

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

                playerBoard = updatedPlayer
                opponentBoard = updatedOpponent
                recentPick = pick
            } else {
                recentPick = RecentPick(
                    number = -1,
                    pickedByPlayerId = pickerId,
                    turnNumber = turnNumber
                )
            }

            val pWon = playerBoard.isBingo
            val oWon = opponentBoard.isBingo
            val over = !isTimeoutPass && (pWon || oWon)

            turnNumber += 1
            turnTimer = 30
            isGameOver = over

            val nextPlayerId = calculateNextTurnPlayerId(pickerId)
            currentTurnPlayerId = nextPlayerId

            if (over) {
                if (pWon && oWon) {
                    isDrawMatch = true
                    didPlayerWin = false
                    recordFinishedMatch(won = false, isDraw = true)
                } else if (pWon) {
                    isDrawMatch = false
                    didPlayerWin = true
                    recordFinishedMatch(won = true, isDraw = false)
                } else {
                    isDrawMatch = false
                    didPlayerWin = false
                    recordFinishedMatch(won = false, isDraw = false)
                }
            } else {
                isMyTurn = (nextPlayerId == getLocalUid())
            }

            // Broadcast to peer with full picked history to guarantee reconciliation
            if (shouldBroadcast && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                broadcastPacket(
                    RoomMessagePacket(
                        type = if (isTimeoutPass) "TURN_TIMEOUT" else "PICK_NUMBER",
                        number = number,
                        playerId = pickerId,
                        turnNumber = turnNumber,
                        pickedHistory = pickedNumbersHistory.toList(),
                        currentTurnPlayerId = nextPlayerId,
                        seed = currentMatchSeed
                    )
                )
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
            val currentRoute = navController.currentDestination?.route
            val inActiveGameScreen = (currentRoute == Screen.Game.route)
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

        when (packet.type) {
            "START_GAME" -> {
                if (!isHosting) {
                    val currentDest = navController.currentDestination?.route
                    val isInGame = currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.shouldStartNewMatch(
                            isHost = false,
                            incomingSeed = packet.seed,
                            currentMatchSeed = currentMatchSeed,
                            isGameOver = isGameOver,
                            isCurrentlyInGame = isInGame
                        )) {
                        return
                    }
                    pickedNumbersHistory.clear()
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        onlineRoomSync.updateLocalReadyStatus("IN_GAME")
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus("IN_GAME")
                    }
                    if (packet.isManualBoard) {
                        isManualBoard = true
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
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
                            firstTurnPlayerId = null,
                            hostSeed = packet.seed
                        )
                    }
                }
            }

            "PLAY_AGAIN" -> {
                if (!isHosting && (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK)) {
                    val currentDest = navController.currentDestination?.route
                    val isInGame = currentDest == Screen.Game.route || currentDest == Screen.ManualBoardDesign.route
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.shouldStartNewMatch(
                            isHost = false,
                            incomingSeed = packet.seed,
                            currentMatchSeed = currentMatchSeed,
                            isGameOver = isGameOver,
                            isCurrentlyInGame = isInGame
                        )) {
                        return
                    }
                    pickedNumbersHistory.clear()
                    if (packet.isManualBoard || isManualBoard) {
                        isManualBoard = true
                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
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
                            hostSeed = packet.seed
                        )
                    }
                }
            }

            "BOARD_READY" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.pickedHistory.isNotEmpty()) {
                        opponentBoard = ManualBoardEngine.buildBoard(packet.pickedHistory, boardSize)
                    }
                    isOpponentBoardReady = true
                    if (isLocalBoardReady && countdownSeconds < 0) {
                        startCountdownAndInitiateTurn()
                    }
                }
            }

            "PICK_NUMBER" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    var anyNewPick = false

                    // 1. Reconcile any missing numbers from packet's history
                    packet.pickedHistory.forEach { num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            pickedNumbersHistory.add(num)
                            val isMine = (packet.playerId == myUid)
                            val isOpponentMine = (packet.playerId != myUid)
                            playerBoard = engine.markCell(
                                board = playerBoard,
                                number = num,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = isMine,
                                turnNumber = turnNumber
                            )
                            opponentBoard = engine.markCell(
                                board = opponentBoard,
                                number = num,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = isOpponentMine,
                                turnNumber = turnNumber
                            )
                            recentPick = RecentPick(num, packet.playerId, turnNumber)
                            anyNewPick = true
                        }
                    }

                    // 2. Direct single pick fallback
                    if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                        pickedNumbersHistory.add(packet.number)
                        val isMine = (packet.playerId == myUid)
                        val isOpponentMine = (packet.playerId != myUid)
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
                        recentPick = RecentPick(packet.number, packet.playerId, turnNumber)
                        anyNewPick = true
                    }

                    // 3. Evaluate win conditions
                    val pWon = playerBoard.isBingo
                    val oWon = opponentBoard.isBingo
                    if (pWon && oWon) {
                        isGameOver = true
                        isDrawMatch = true
                        didPlayerWin = false
                        recordFinishedMatch(won = false, isDraw = true)
                    } else if (pWon || oWon) {
                        isGameOver = true
                        isDrawMatch = false
                        didPlayerWin = pWon
                        recordFinishedMatch(won = pWon, isDraw = false)
                    } else {
                        if (packet.turnNumber >= turnNumber || anyNewPick) {
                            turnNumber = packet.turnNumber.coerceAtLeast(turnNumber + 1)
                            turnTimer = 30
                            val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                                packet.currentTurnPlayerId
                            } else {
                                calculateNextTurnPlayerId(packet.playerId)
                            }
                            currentTurnPlayerId = nextId
                            isMyTurn = (currentTurnPlayerId == myUid)
                        }
                    }
                }
            }

            "TURN_TIMEOUT" -> {
                if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    if (!com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.turnNumber >= turnNumber) {
                        turnNumber = packet.turnNumber.coerceAtLeast(turnNumber + 1)
                        turnTimer = 30
                        val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                            packet.currentTurnPlayerId
                        } else {
                            calculateNextTurnPlayerId(packet.playerId)
                        }
                        currentTurnPlayerId = nextId
                        isMyTurn = (currentTurnPlayerId == myUid)
                        recentPick = RecentPick(number = -1, pickedByPlayerId = packet.playerId, turnNumber = turnNumber)
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
                    packet.pickedHistory.forEach { num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            pickedNumbersHistory.add(num)
                            val isMine = (packet.playerId == myUid)
                            val isOpponentMine = (packet.playerId != myUid)
                            playerBoard = engine.markCell(
                                board = playerBoard,
                                number = num,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = isMine,
                                turnNumber = turnNumber
                            )
                            opponentBoard = engine.markCell(
                                board = opponentBoard,
                                number = num,
                                pickedByPlayerId = packet.playerId,
                                isOwnPick = isOpponentMine,
                                turnNumber = turnNumber
                            )
                            recentPick = RecentPick(num, packet.playerId, turnNumber)
                            anyNewPick = true
                        }
                    }

                    // 2. Evaluate win conditions
                    val pWon = playerBoard.isBingo
                    val oWon = opponentBoard.isBingo
                    if (pWon && oWon) {
                        isGameOver = true
                        isDrawMatch = true
                        didPlayerWin = false
                        recordFinishedMatch(won = false, isDraw = true)
                    } else if (pWon || oWon) {
                        isGameOver = true
                        isDrawMatch = false
                        didPlayerWin = pWon
                        recordFinishedMatch(won = pWon, isDraw = false)
                    } else if (packet.turnNumber > turnNumber || (anyNewPick && packet.turnNumber >= turnNumber)) {
                        // Strictly newer turn or reconciled missed turn! Reconcile turn authority
                        turnNumber = packet.turnNumber.coerceAtLeast(turnNumber + 1)
                        turnTimer = 30
                        if (packet.currentTurnPlayerId.isNotBlank()) {
                            currentTurnPlayerId = packet.currentTurnPlayerId
                            isMyTurn = (currentTurnPlayerId == myUid)
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

            "SURRENDER" -> {
                if ((currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) && !isGameOver) {
                    isGameOver = true
                    didPlayerWin = true
                    val opponent = packet.displayName.ifBlank { "Opponent" }
                    recordFinishedMatch(true)
                    opponentSurrenderMessage = "$opponent surrendered the match! You win!"
                }
            }

            "LEAVE", "HOST_LEFT" -> {
                val destinationRoute = navController.currentDestination?.route
                val inGame = (destinationRoute == Screen.Game.route)
                if (inGame && !isGameOver && packet.playerId.isNotBlank() && packet.playerId != myUid) {
                    isGameOver = true
                    didPlayerWin = true
                    val opponent = packet.displayName.ifBlank { "Opponent" }
                    recordFinishedMatch(true)
                    opponentDisconnectMessage = "$opponent went offline / left the match! You win by forfeit."
                } else if (!inGame) {
                    val isHostSender = packet.isHost || packet.type == "HOST_LEFT" || realTimePlayers.find { it.id == packet.playerId }?.isHost == true
                    if (isHostSender && !isHosting) {
                        Toast.makeText(context, "Host has left the lobby.", Toast.LENGTH_LONG).show()
                        disconnectRoom()
                        isHosting = false
                        navController.navigate(Screen.MainMenu.route) {
                            popUpTo(Screen.MainMenu.route) { inclusive = false }
                            launchSingleTop = true
                        }
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

    NavHost(
        navController = navController,
        startDestination = Screen.AuthGate.route
    ) {
        // 1. Splash / Auth Gate
        composable(Screen.AuthGate.route) {
            AuthGateScreen(
                authRepository = authRepository,
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.AuthGate.route) { inclusive = true }
                    }
                },
                onNavigateToMenu = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.AuthGate.route) { inclusive = true }
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

        // 3. Main Menu Screen
        composable(Screen.MainMenu.route) {
            MainMenuScreen(
                authRepository = authRepository,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToDashboard = {
                    navController.navigate(Screen.Dashboard.route)
                },
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
                        openNearbyLobby()
                    } else {
                        nearbyPermissionLauncher.launch(perms.toTypedArray())
                    }
                }
            )
        }

        // 3b. Settings Screen
        composable(Screen.Settings.route) {
            BackHandler {
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            SettingsScreen(
                authRepository = authRepository,
                onBack = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
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
                onNavigateToDashboard = {
                    navController.navigate(Screen.Dashboard.route)
                },
                friendsRepository = friendsRepository
            )
        }

        // 3c. Dashboard & Friends Social Screen
        composable(Screen.Dashboard.route) {
            BackHandler {
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
                ?: UserProfile(uid = "guest", displayName = "Player")
            DashboardAndFriendsScreen(
                user = user,
                authRepository = authRepository,
                friendsRepository = friendsRepository,
                onInviteFriendToMatch = { friend ->
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
                    val localHost = Player(
                        id = getLocalUid(),
                        displayName = getPlayerDisplayName(),
                        username = user.username,
                        isHost = true,
                        avatarUrl = getPlayerAvatarUrl(),
                        gamesPlayed = user.gamesPlayed,
                        gamesWon = user.gamesWon,
                        currentStreak = user.currentStreak,
                        level = user.level,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    coroutineScope.launch {
                        com.bingo.multiplayer.domain.network.OnlineRoomRegistry.createRoom(roomCode, localHost, 5)
                    }
                    onlineRoomSync.connectToRoom(roomCode, localHost)
                    val fromUser = user.username.ifBlank { getLocalUid() }
                    val fromName = user.displayName.ifBlank { getPlayerDisplayName() }
                    coroutineScope.launch {
                        val success = com.bingo.multiplayer.domain.network.GameInviteManager.sendInvite(
                            targetUsername = friend.username,
                            invite = com.bingo.multiplayer.domain.network.GameInvite(
                                fromUsername = fromUser,
                                fromDisplayName = fromName,
                                fromAvatarUrl = null,
                                roomCode = roomCode
                            )
                        )
                        if (success) {
                            Toast.makeText(context, "Inviting @${friend.username} to room $roomCode...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invite dispatched to @${friend.username}!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    navController.navigate(Screen.Lobby.route)
                },
                onAcceptInviteToMatch = { invite ->
                    acceptAndJoinRoom(invite.roomCode)
                },
                onBack = {
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
                        launchSingleTop = true
                    }
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
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
            OnlineMatchChoiceScreen(
                onHostGame = {
                    roomCode = generateRoomCode()
                    currentGameMode = GameMode.ONLINE_ROOM
                    isUsingP2p = false
                    isHosting = true
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
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
            JoinRoomScreen(
                onJoinRoom = { code ->
                    val cleanCode = code.trim().uppercase()
                    val joinerVersion = System.currentTimeMillis()
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
                        lastSeenTimestamp = joinerVersion,
                        readyVersion = joinerVersion
                    )
                    when (val result = com.bingo.multiplayer.domain.network.OnlineRoomRegistry.validateAndJoinRoom(cleanCode, localJoiner)) {
                        is com.bingo.multiplayer.domain.network.RoomJoinResult.Success -> {
                            roomCode = cleanCode
                            currentGameMode = GameMode.ONLINE_ROOM
                            isUsingP2p = false
                            isHosting = false
                            val returnedPlayer = result.room.players.find { it.id == localJoiner.id }
                            val effJoiner = if (returnedPlayer != null) {
                                localJoiner.copy(readyVersion = returnedPlayer.readyVersion)
                            } else localJoiner
                            onlineRoomSync.connectToRoom(cleanCode, effJoiner, initialPlayers = result.room.players)
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
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val user = (authRepository.authState.collectAsState().value as? AuthState.Authenticated)?.user
            LobbyScreen(
                roomCode = roomCode,
                players = realTimePlayers,
                isHost = isHosting,
                currentUser = user,
                currentUserId = getLocalUid(),
                friendsRepository = friendsRepository,
                isRefreshing = isRefreshing,
                inactivityResetToken = lobbyInactivityResetToken,
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
                    coroutineScope.launch {
                        onlineRoomSync.refreshNow()
                    }
                },
                onSearchPlayer = { username ->
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
                onManualBoardChange = { isManualBoard = it },
                onStartGame = {
                    val dynamicSize = calculateBoardSize(realTimePlayers.size)
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
                                boardSize = dynamicSize
                            )
                        }
                        onlineRoomSync.updateLocalReadyStatus("IN_GAME")
                    } else if (currentGameMode == GameMode.NEARBY_NETWORK) {
                        lanP2pSync.updateLocalReadyStatus("IN_GAME")
                    }

                    val startPacket = RoomMessagePacket(
                        type = "START_GAME",
                        boardSize = dynamicSize,
                        seed = seed,
                        playerId = myId,
                        isManualBoard = manualMode
                    )
                    broadcastPacket(startPacket)
                    coroutineScope.launch {
                        delay(150L)
                        broadcastPacket(startPacket)
                        delay(250L)
                        broadcastPacket(startPacket)
                    }

                    if (manualMode) {
                        boardSize = dynamicSize
                        currentMatchSeed = seed
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
                        navController.navigate(Screen.ManualBoardDesign.route)
                    } else {
                        startNewGame(
                            mode = if (isUsingP2p) GameMode.NEARBY_NETWORK else GameMode.ONLINE_ROOM,
                            difficulty = AiDifficulty.EASY,
                            size = dynamicSize,
                            firstTurnPlayerId = null,
                            hostSeed = seed
                        )
                    }
                },
                onBack = {
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
                navController.navigate(Screen.MainMenu.route) {
                    popUpTo(Screen.MainMenu.route) { inclusive = false }
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
                joinedGame = joinedLanGame,
                onStartBroadcasting = { selectedSize ->
                    isHosting = true
                    isUsingP2p = true
                    joinedLanGame = null
                    val internalCode = "P2P_${(1000..9999).random()}"
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
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    
                    lanP2pSync.connectAsHost(localHost)
                    lanDiscovery.startBroadcasting(localHost, selectedSize, internalCode)
                },
                onStopBroadcasting = {
                    isHosting = false
                    lanDiscovery.stopBroadcasting()
                    disconnectRoom()
                },
                onJoinDiscoveredGame = { game ->
                    isHosting = false
                    isUsingP2p = true
                    joinedLanGame = game
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
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    lanP2pSync.connectAsClient(game.hostIp, localJoiner)
                },
                onLeaveJoinedGame = {
                    joinedLanGame = null
                    disconnectRoom()
                },
                onStartGame = {
                    val seed = Random.nextLong()
                    val myId = getLocalUid()
                    lanP2pSync.updateLocalReadyStatus("IN_GAME")
                    realTimePlayers.filter { !it.isHost }.forEach {
                        lanP2pSync.updatePlayerReadyStatus(it.id, "IN_GAME")
                    }
                    broadcastPacket(
                        RoomMessagePacket(
                            type = "START_GAME",
                            boardSize = boardSize,
                            seed = seed,
                            playerId = myId
                        )
                    )
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    startNewGame(
                        mode = GameMode.ONLINE_ROOM,
                        difficulty = AiDifficulty.EASY,
                        size = boardSize,
                        firstTurnPlayerId = myId,
                        hostSeed = seed
                    )
                },
                onBack = {
                    lanDiscovery.stopBroadcasting()
                    lanDiscovery.stopDiscovering()
                    disconnectRoom()
                    isHosting = false
                    joinedLanGame = null
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = false }
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

            ManualBoardDesignScreen(
                boardSize = boardSize,
                roomCode = roomCode,
                opponentName = opponentDisplayName,
                isWaitingForOpponent = isLocalBoardReady && !isOpponentBoardReady,
                countdownSeconds = countdownSeconds,
                firstTurnPlayerName = firstTurnPlayerName,
                onBoardReady = { boardNumbers ->
                    playerBoard = ManualBoardEngine.buildBoard(boardNumbers, boardSize)
                    isLocalBoardReady = true

                    val readyPacket = RoomMessagePacket(
                        type = "BOARD_READY",
                        playerId = getLocalUid(),
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

                    if (isOpponentBoardReady && countdownSeconds < 0) {
                        startCountdownAndInitiateTurn()
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
                    currentMatchSeed = 0L
                    pickedNumbersHistory.clear()
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
                        val targetRoute = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.Lobby.route
                        if (isHosting) {
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                coroutineScope.launch {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING")
                                }
                                onlineRoomSync.updateLocalReadyStatus("READY")
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
                    while (turnTimer > 0 && !isGamePaused && !isGameOver) {
                        delay(1000L)
                        if (!isGamePaused && !isGameOver) {
                            turnTimer -= 1
                        }
                    }
                    if (isMyTurn && !isGameOver && !isGamePaused && turnTimer == 0) {
                        // Pass the turn cleanly with no auto-picked number
                        executePick(
                            number = -1,
                            isOwnPick = true,
                            pickerId = getLocalUid(),
                            shouldBroadcast = true
                        )
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
            val currentPing = if (currentGameMode == GameMode.ONLINE_ROOM) onlinePingMs else 0L

            GameScreen(
                board = playerBoard,
                opponentBoard = opponentBoard,
                isMyTurn = isMyTurn,
                turnTimeRemaining = turnTimer,
                isGamePaused = isGamePaused,
                pausedByPlayerName = pausedByPlayerName,
                onTogglePause = { togglePause() },
                recentPick = recentPick,
                opponentName = opponentDisplayName,
                isGameOver = isGameOver,
                didPlayerWin = didPlayerWin,
                isDraw = isDrawMatch,
                isHost = isCurrentHost,
                pingMs = currentPing,
                wantsToPlayAgainName = wantsToPlayAgainPlayerName,
                onRequestPlayAgain = {
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
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
                    if (currentGameMode == GameMode.ONLINE_ROOM) {
                        broadcastPacket(
                            RoomMessagePacket(
                                type = "SURRENDER",
                                playerId = getLocalUid(),
                                displayName = getPlayerDisplayName()
                            )
                        )
                    }
                    isGameOver = true
                    didPlayerWin = false
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
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                coroutineScope.launch {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(
                                        roomCode = roomCode,
                                        status = "PLAYING",
                                        seed = seed,
                                        isManualBoard = isManualBoard,
                                        boardSize = boardSize
                                    )
                                }
                            }
                            val playAgainPacket = RoomMessagePacket(
                                type = "PLAY_AGAIN",
                                boardSize = boardSize,
                                seed = seed,
                                playerId = getLocalUid(),
                                isManualBoard = isManualBoard
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
                                navController.navigate(Screen.ManualBoardDesign.route)
                            } else {
                                startNewGame(
                                    mode = currentGameMode,
                                    difficulty = currentAiDifficulty,
                                    size = boardSize,
                                    firstTurnPlayerId = null,
                                    hostSeed = seed
                                )
                            }
                        }
                    } else {
                        startNewGame(currentGameMode, currentAiDifficulty, boardSize)
                    }
                },
                onBackToMenu = {
                    isGameOver = false
                    didPlayerWin = false
                    isDrawMatch = false
                    currentMatchSeed = 0L
                    pickedNumbersHistory.clear()
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
                    disconnectRoom()
                    navController.navigate(Screen.MainMenu.route) {
                        popUpTo(Screen.MainMenu.route) { inclusive = true }
                    }
                },
                onReturnToLobby = if (currentGameMode == GameMode.ONLINE_ROOM || currentGameMode == GameMode.NEARBY_NETWORK) {
                    {
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false
                        currentMatchSeed = 0L
                        pickedNumbersHistory.clear()
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
                        val targetRoute = if (currentGameMode == GameMode.NEARBY_NETWORK) Screen.NearbyLobby.route else Screen.Lobby.route
                        if (isHosting) {
                            if (currentGameMode == GameMode.ONLINE_ROOM) {
                                coroutineScope.launch {
                                    com.bingo.multiplayer.domain.network.OnlineRoomRegistry.updateRoomStatus(roomCode, "WAITING")
                                }
                                onlineRoomSync.updateLocalReadyStatus("READY")
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

    val currentInvite = incomingInvite
    if (currentInvite != null) {
        AlertDialog(
            onDismissRequest = { incomingInvite = null },
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
                        coroutineScope.launch {
                            currentAuthUser?.let { u ->
                                com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(u.username, inviteToJoin.roomCode)
                            }
                        }
                        acceptAndJoinRoom(inviteToJoin.roomCode)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BingoTheme.colors.accentBrand)
                ) {
                    Text("Accept & Play", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val inviteToDecline = currentInvite
                        incomingInvite = null
                        coroutineScope.launch {
                            currentAuthUser?.let { u ->
                                com.bingo.multiplayer.domain.network.GameInviteManager.removeInvite(u.username, inviteToDecline.roomCode)
                            }
                        }
                    }
                ) {
                    Text("Decline")
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
                    colors = ButtonDefaults.buttonColors(containerColor = BingoTheme.colors.accentBrand)
                ) {
                    Text("Claim Victory", fontWeight = FontWeight.Bold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = BingoTheme.colors.accentBrand)
                ) {
                    Text("Back to Menu", fontWeight = FontWeight.Bold)
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
                            broadcastPacket(
                                RoomMessagePacket(
                                    type = "SURRENDER",
                                    playerId = getLocalUid(),
                                    displayName = getPlayerDisplayName()
                                )
                            )
                        }
                        isGameOver = true
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
                    onClick = { showLeaveMatchDialog = false }
                ) {
                    Text("Stay")
                }
            },
            containerColor = BingoTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
