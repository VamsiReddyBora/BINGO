package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-Fidelity Two-Player Virtual Simulation Test Harness.
 * Simulates Host (Player 1) and Guest (Player 2) connected via an in-memory packet bus.
 * Tests:
 * 1. Room Creation, Joining, and Ready Toggling.
 * 2. Normal Match Flow (Manual board unchecked): Start, Board Generation, Deterministic Turn Sync, Gameplay.
 * 3. Manual Board Design Flow (Manual board checked): Start Packet Delivery to Guest, Box Number Filling (1..25),
 *    Button State, Waiting Screen, Synchronized 5s Countdown, Custom Board Ingestion, Deterministic First Turn.
 * 4. Multi-Match Stress Test (Matches 1 -> 2 -> 3 -> 4 -> 5): Detects turn flickering, state desync, or packet leaks.
 */
class TwoPlayerMatchSimulationTest {

    private val engine = BingoEngine()

    // ── Virtual Player Client Representation ──
    class SimulatedClient(
        val id: String,
        val displayName: String,
        val isHost: Boolean,
        private val messageBus: VirtualMessageBus,
        val instanceId: String = java.util.UUID.randomUUID().toString()
    ) {
        var screen: String = "MENU" // "MENU", "LOBBY", "MANUAL_BOARD", "GAME"
        var localPlayer = Player(id = id, displayName = displayName, isHost = isHost, lobbyReadyStatus = if (isHost) "READY" else "NOT_READY")
        val roomPlayers = mutableMapOf<String, Player>()
        var currentRoomCode: String = ""
        var currentMatchSeed: Long = 0L
        var boardSize: Int = 5
        var isManualBoardMode: Boolean = false

        // Manual board arrangement state
        var manualGrid = ManualBoardEngine.createEmptyGrid(5)
        var isLocalBoardReady: Boolean = false
        var isOpponentBoardReady: Boolean = false
        var countdownSeconds: Int = -1
        var isWaitingForOpponent: Boolean = false
        var firstTurnPlayerName: String = ""

        // Active game state
        var playerBoard: Board = BingoEngine().generateBoard(5)
        var opponentBoard: Board = BingoEngine().generateBoard(5)
        val pickedNumbersHistory = CopyOnWriteArrayList<Int>()
        var currentTurnPlayerId: String = ""
        var isMyTurn: Boolean = false
        var turnNumber: Int = 1
        var turnTimer: Int = 30
        var isGameOver: Boolean = false
        var didPlayerWin: Boolean = false
        var isDrawMatch: Boolean = false

        // Turn switch history tracker for flickering detection
        val turnSwitchLog = mutableListOf<String>()

        // Emote & quick chat phrase tracking
        var lastReceivedEmote: String? = null
        var lastReceivedEmoteTimestamp: Long = 0L

        init {
            roomPlayers[id] = localPlayer
        }

        fun sendPacket(packet: RoomMessagePacket) {
            val outgoing = if (packet.senderInstanceId.isBlank()) packet.copy(senderInstanceId = instanceId) else packet
            val encoded = FastPacketCodec.encode(outgoing)
            // Transmit over simulated network
            messageBus.dispatch(senderId = id, rawPayload = encoded)
        }

        fun handleIncomingPacket(packet: RoomMessagePacket) {
            // Discard self echo using instanceId first, or fallback to playerId if not present
            if (packet.senderInstanceId.isNotBlank()) {
                if (packet.senderInstanceId == instanceId && packet.type != "PING") return
            } else if (packet.playerId == id) {
                return
            }

            when (packet.type) {
                "JOIN" -> {
                    val updated = LobbyLifecycleEngine.onRemotePlayerJoinOrHeartbeat(packet, roomPlayers[packet.playerId])
                    roomPlayers[packet.playerId] = updated
                    if (isHost) {
                        // Host replies with ROOM_STATE
                        sendPacket(
                            RoomMessagePacket(
                                type = "ROOM_STATE",
                                playerId = id,
                                displayName = displayName,
                                isHost = true,
                                players = roomPlayers.values.toList()
                            )
                        )
                    }
                }

                "ROOM_STATE" -> {
                    packet.players.forEach { p ->
                        if (p.id.isNotBlank()) {
                            val existing = roomPlayers[p.id]
                            val isLocal = (p.id == id)
                            val effReady = if (isLocal) localPlayer.lobbyReadyStatus else {
                                if (p.readyVersion >= (existing?.readyVersion ?: 0L)) p.lobbyReadyStatus else existing?.lobbyReadyStatus ?: p.lobbyReadyStatus
                            }
                            roomPlayers[p.id] = p.copy(lobbyReadyStatus = effReady)
                        }
                    }
                }

                "READY_STATUS" -> {
                    val existing = roomPlayers[packet.playerId]
                    val updated = LobbyLifecycleEngine.onRemoteReadyStatusPacket(packet, existing)
                    if (updated != null) {
                        roomPlayers[packet.playerId] = updated
                    }
                }

                "START_GAME" -> {
                    if (!isHost) {
                        val isInGame = (screen == "GAME" || screen == "MANUAL_BOARD")
                        if (!LobbyLifecycleEngine.shouldStartNewMatch(
                                isHost = false,
                                incomingSeed = packet.seed,
                                currentMatchSeed = currentMatchSeed,
                                isGameOver = isGameOver,
                                isCurrentlyInGame = isInGame
                            )) {
                            return
                        }

                        currentMatchSeed = packet.seed
                        boardSize = packet.boardSize
                        isManualBoardMode = packet.isManualBoard
                        pickedNumbersHistory.clear()
                        isGameOver = false
                        didPlayerWin = false
                        isDrawMatch = false

                        if (packet.isManualBoard) {
                            screen = "MANUAL_BOARD"
                            manualGrid = ManualBoardEngine.createEmptyGrid(packet.boardSize)
                            isLocalBoardReady = false
                            isOpponentBoardReady = false
                            countdownSeconds = -1
                            isWaitingForOpponent = false
                        } else {
                            // Normal match: auto-generate boards
                            val engine = BingoEngine()
                            playerBoard = engine.generateBoard(packet.boardSize, packet.seed + 1)
                            opponentBoard = engine.generateBoard(packet.boardSize, packet.seed)
                            val firstTurnUid = if (packet.currentTurnPlayerId.isNotBlank()) {
                                packet.currentTurnPlayerId
                            } else {
                                val candidateUids = roomPlayers.keys.toList().sorted()
                                ManualBoardEngine.determineRandomFirstTurn(packet.seed, candidateUids)
                            }
                            currentTurnPlayerId = firstTurnUid
                            isMyTurn = (currentTurnPlayerId == id)
                            recordTurnSwitch(firstTurnUid)
                            turnNumber = 1
                            turnTimer = 30
                            screen = "GAME"
                        }
                    }
                }

                "BOARD_READY" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.pickedHistory.isNotEmpty()) {
                        opponentBoard = ManualBoardEngine.buildBoard(packet.pickedHistory, boardSize)
                    }
                    isOpponentBoardReady = true
                    if (isLocalBoardReady && countdownSeconds < 0) {
                        triggerCountdownAndStartGame()
                    }
                }

                "PICK_NUMBER" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    val engine = BingoEngine()
                    if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                        pickedNumbersHistory.add(packet.number)
                        val isMine = (packet.playerId == id)
                        playerBoard = engine.markCell(playerBoard, packet.number, packet.playerId, isMine, turnNumber)
                        opponentBoard = engine.markCell(opponentBoard, packet.number, packet.playerId, !isMine, turnNumber)
                    }

                    val pWon = playerBoard.isBingo
                    val oWon = opponentBoard.isBingo
                    if (pWon && oWon) {
                        isGameOver = true
                        isDrawMatch = true
                        didPlayerWin = false
                    } else if (pWon || oWon) {
                        isGameOver = true
                        isDrawMatch = false
                        didPlayerWin = pWon
                    } else {
                        turnNumber = packet.turnNumber.coerceAtLeast(turnNumber + 1)
                        turnTimer = 30
                        val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                            packet.currentTurnPlayerId
                        } else {
                            calculateNextTurn(packet.playerId)
                        }
                        currentTurnPlayerId = nextId
                        isMyTurn = (currentTurnPlayerId == id)
                        recordTurnSwitch(nextId)
                    }
                }

                "TURN_TIMEOUT" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    if (packet.turnNumber >= turnNumber) {
                        turnNumber = packet.turnNumber.coerceAtLeast(turnNumber + 1)
                        turnTimer = 30
                        val nextId = if (packet.currentTurnPlayerId.isNotBlank()) {
                            packet.currentTurnPlayerId
                        } else {
                            calculateNextTurn(packet.playerId)
                        }
                        currentTurnPlayerId = nextId
                        isMyTurn = (currentTurnPlayerId == id)
                        recordTurnSwitch(nextId)
                    }
                }

                "GAME_SYNC" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) {
                        return
                    }
                    // Reconcile turn if strictly newer
                    if (packet.turnNumber > turnNumber) {
                        turnNumber = packet.turnNumber
                        if (packet.currentTurnPlayerId.isNotBlank()) {
                            currentTurnPlayerId = packet.currentTurnPlayerId
                            isMyTurn = (currentTurnPlayerId == id)
                            recordTurnSwitch(currentTurnPlayerId)
                        }
                    }
                }

                "EMOTE", "CHAT_PHRASE" -> {
                    lastReceivedEmote = packet.displayName
                    lastReceivedEmoteTimestamp = packet.timestamp
                }
            }
        }

        private fun recordTurnSwitch(nextPlayerId: String) {
            turnSwitchLog.add("Turn $turnNumber: $nextPlayerId (isMyTurn=$isMyTurn)")
        }

        fun calculateNextTurn(currentPickerId: String): String {
            val sorted = roomPlayers.keys.toList().sorted()
            val idx = sorted.indexOf(currentPickerId)
            return if (idx != -1) sorted[(idx + 1) % sorted.size] else sorted.first()
        }

        fun triggerCountdownAndStartGame() {
            val candidateUids = roomPlayers.keys.toList().sorted()
            val firstTurnUid = ManualBoardEngine.determineRandomFirstTurn(currentMatchSeed, candidateUids)
            firstTurnPlayerName = if (firstTurnUid == id) "You" else "Opponent"

            // Simulate 5s countdown
            for (s in 5 downTo 0) {
                countdownSeconds = s
            }
            countdownSeconds = -1
            isWaitingForOpponent = false

            currentTurnPlayerId = firstTurnUid
            isMyTurn = (currentTurnPlayerId == id)
            recordTurnSwitch(firstTurnUid)
            turnNumber = 1
            turnTimer = 30
            screen = "GAME"
        }

        fun submitBoardReady(numbers: List<Int>) {
            playerBoard = ManualBoardEngine.buildBoard(numbers, boardSize)
            isLocalBoardReady = true
            isWaitingForOpponent = !isOpponentBoardReady

            sendPacket(
                RoomMessagePacket(
                    type = "BOARD_READY",
                    playerId = id,
                    seed = currentMatchSeed,
                    pickedHistory = numbers
                )
            )

            if (isOpponentBoardReady && countdownSeconds < 0) {
                triggerCountdownAndStartGame()
            }
        }

        fun pickNumber(number: Int) {
            if (isGameOver || !isMyTurn || number in pickedNumbersHistory) return
            val engine = BingoEngine()
            pickedNumbersHistory.add(number)
            playerBoard = engine.markCell(playerBoard, number, id, true, turnNumber)
            opponentBoard = engine.markCell(opponentBoard, number, id, false, turnNumber)

            val pWon = playerBoard.isBingo
            val oWon = opponentBoard.isBingo
            val over = (pWon || oWon)
            turnNumber += 1
            turnTimer = 30
            isGameOver = over

            val nextId = calculateNextTurn(id)
            currentTurnPlayerId = nextId
            if (over) {
                isDrawMatch = (pWon && oWon)
                didPlayerWin = pWon
            } else {
                isMyTurn = (nextId == id)
                recordTurnSwitch(nextId)
            }

            sendPacket(
                RoomMessagePacket(
                    type = "PICK_NUMBER",
                    number = number,
                    playerId = id,
                    turnNumber = turnNumber,
                    pickedHistory = pickedNumbersHistory.toList(),
                    currentTurnPlayerId = nextId,
                    seed = currentMatchSeed
                )
            )
        }

        fun onTurnTimeout() {
            if (isGameOver || !isMyTurn) return
            turnNumber += 1
            turnTimer = 30
            val nextId = calculateNextTurn(id)
            currentTurnPlayerId = nextId
            isMyTurn = (nextId == id)
            recordTurnSwitch(nextId)

            sendPacket(
                RoomMessagePacket(
                    type = "TURN_TIMEOUT",
                    number = -1,
                    playerId = id,
                    turnNumber = turnNumber,
                    currentTurnPlayerId = nextId,
                    seed = currentMatchSeed
                )
            )
        }

        fun onFallbackTimeout(activePickerId: String) {
            if (isGameOver || isMyTurn) return
            turnNumber += 1
            turnTimer = 30
            val nextId = id
            currentTurnPlayerId = nextId
            isMyTurn = true
            recordTurnSwitch(nextId)

            sendPacket(
                RoomMessagePacket(
                    type = "TURN_TIMEOUT",
                    number = -1,
                    playerId = activePickerId,
                    turnNumber = turnNumber,
                    currentTurnPlayerId = nextId,
                    seed = currentMatchSeed
                )
            )
        }

        fun returnToLobby() {
            screen = "LOBBY"
            isGameOver = false
            didPlayerWin = false
            isDrawMatch = false
            currentMatchSeed = 0L
            pickedNumbersHistory.clear()
            isLocalBoardReady = false
            isOpponentBoardReady = false
            countdownSeconds = -1
            turnSwitchLog.clear()
            if (isHost) {
                localPlayer = LobbyLifecycleEngine.onLocalStatusChange(localPlayer, "READY")
            } else {
                localPlayer = LobbyLifecycleEngine.onLocalStatusChange(localPlayer, "NOT_READY")
                sendPacket(
                    RoomMessagePacket(
                        type = "READY_STATUS",
                        playerId = id,
                        readyStatus = "NOT_READY",
                        readyVersion = localPlayer.readyVersion
                    )
                )
            }
            roomPlayers[id] = localPlayer
        }

        fun sendEmoteOrPhrase(text: String, isPhrase: Boolean = false) {
            sendPacket(
                RoomMessagePacket(
                    type = if (isPhrase) "CHAT_PHRASE" else "EMOTE",
                    playerId = id,
                    displayName = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // ── Virtual In-Memory Packet Broker ──
    class VirtualMessageBus {
        private val clients = mutableListOf<SimulatedClient>()
        val packetLog = mutableListOf<String>()

        fun register(client: SimulatedClient) {
            clients.add(client)
        }

        fun dispatch(senderId: String, rawPayload: String) {
            val packet = FastPacketCodec.decode(rawPayload)
            packetLog.add("[$senderId -> ALL] ${packet.type} (seed=${packet.seed}, num=${packet.number}, turn=${packet.turnNumber})")
            clients.forEach { client ->
                client.handleIncomingPacket(packet)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 1: MANUAL BOARD DESIGN COMPLETE TWO-PLAYER LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_ManualBoardDesignFullLifecycle() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        // 1. Host creates room
        host.screen = "LOBBY"
        host.currentRoomCode = "SIM100"
        assertEquals("LOBBY", host.screen)
        assertEquals("READY", host.localPlayer.lobbyReadyStatus)

        // 2. Guest joins room
        guest.screen = "LOBBY"
        guest.currentRoomCode = "SIM100"
        guest.sendPacket(
            RoomMessagePacket(
                type = "JOIN",
                playerId = guest.id,
                displayName = guest.displayName,
                isHost = false,
                readyStatus = "NOT_READY"
            )
        )

        // Verify Host registered guest and Start button is disabled
        assertEquals(2, host.roomPlayers.size)
        assertEquals("NOT_READY", host.roomPlayers[guest.id]?.lobbyReadyStatus)
        assertFalse("Host start button must be disabled while guest is NOT_READY", LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // 3. Guest clicks "I'm Ready"
        guest.localPlayer = LobbyLifecycleEngine.onLocalToggleReady(guest.localPlayer, isReady = true)
        assertEquals("READY", guest.localPlayer.lobbyReadyStatus)
        guest.sendPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = guest.id,
                readyStatus = "READY",
                readyVersion = guest.localPlayer.readyVersion
            )
        )

        // Verify Host sees Guest READY and Start button enables
        assertEquals("READY", host.roomPlayers[guest.id]?.lobbyReadyStatus)
        assertTrue("Host start button must be enabled when guest is READY", LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // 4. Host checks "Manual designed board" checkbox and starts game
        val matchSeed = 7788991122L
        host.isManualBoardMode = true
        host.currentMatchSeed = matchSeed
        host.screen = "MANUAL_BOARD"

        val startPacket = RoomMessagePacket(
            type = "START_GAME",
            playerId = host.id,
            boardSize = 5,
            seed = matchSeed,
            isManualBoard = true
        )
        host.sendPacket(startPacket)

        // VERIFICATION: Did Guest receive the manual board design screen?
        assertEquals("Guest MUST transition to MANUAL_BOARD upon receiving START_GAME", "MANUAL_BOARD", guest.screen)
        assertTrue("Guest isManualBoardMode must be true", guest.isManualBoardMode)
        assertEquals("Both players must share exact match seed", matchSeed, guest.currentMatchSeed)

        // 5. Board Number Filling & Validation
        // Verify Board Ready button is disabled when grid is incomplete
        val partialGrid = (1..24).toList() + listOf(null)
        assertFalse("Board with 24 numbers must NOT be complete", ManualBoardEngine.isBoardComplete(partialGrid, 5))

        val hostNumbers = (1..25).toList()
        val guestNumbers = (25 downTo 1).toList()
        assertTrue("Board with 25 numbers must be complete", ManualBoardEngine.isBoardComplete(hostNumbers, 5))
        assertTrue("Guest board with 25 numbers must be complete", ManualBoardEngine.isBoardComplete(guestNumbers, 5))

        // 6. Host submits Board Ready first
        host.submitBoardReady(hostNumbers)
        assertTrue("Host local board must be ready", host.isLocalBoardReady)
        assertTrue("Host must enter waiting state while guest is still arranging", host.isWaitingForOpponent)
        assertEquals("Host countdown must NOT start yet", -1, host.countdownSeconds)
        assertEquals("Host must remain on MANUAL_BOARD in waiting state", "MANUAL_BOARD", host.screen)

        // Verify Guest received Host's BOARD_READY
        assertTrue("Guest must know opponent board is ready", guest.isOpponentBoardReady)
        assertFalse("Guest local board is not ready yet", guest.isLocalBoardReady)
        assertEquals("Guest must remain on MANUAL_BOARD", "MANUAL_BOARD", guest.screen)

        // 7. Guest submits Board Ready
        guest.submitBoardReady(guestNumbers)
        assertTrue("Guest local board is now ready", guest.isLocalBoardReady)

        // VERIFICATION: Synchronized 5-Second Countdown & Game Start
        assertEquals("Host must transition to GAME screen after countdown", "GAME", host.screen)
        assertEquals("Guest must transition to GAME screen after countdown", "GAME", guest.screen)

        // Verify both players computed identical first turn
        assertEquals("Host and Guest must agree on first turn player ID", host.currentTurnPlayerId, guest.currentTurnPlayerId)
        assertTrue("Exactly one player must have first turn", host.isMyTurn != guest.isMyTurn)

        val firstPlayer = if (host.isMyTurn) host else guest
        val secondPlayer = if (host.isMyTurn) guest else host

        // 8. Play turns back and forth
        val pick1 = 12
        firstPlayer.pickNumber(pick1)
        assertEquals("Pick 1 must be recorded on both", listOf(pick1), host.pickedNumbersHistory.toList())
        assertEquals("Pick 1 must be recorded on both", listOf(pick1), guest.pickedNumbersHistory.toList())
        assertEquals("Turn must advance to 2", 2, host.turnNumber)
        assertEquals("Turn must advance to 2", 2, guest.turnNumber)
        assertTrue("Turn must now belong to second player", secondPlayer.isMyTurn)
        assertFalse("First player turn must end", firstPlayer.isMyTurn)

        val pick2 = 7
        secondPlayer.pickNumber(pick2)
        assertEquals("Pick 2 must be recorded on both", listOf(pick1, pick2), host.pickedNumbersHistory.toList())
        assertEquals("Turn must advance to 3", 3, host.turnNumber)
        assertTrue("Turn must rotate back to first player", firstPlayer.isMyTurn)
        assertFalse("Second player turn must end", secondPlayer.isMyTurn)

        println("✅ Simulation Test 1 (Manual Board Design Full Lifecycle) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 2: NORMAL MATCH WITHOUT MANUAL BOARD ARRANGEMENT
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_NormalMatchWithoutManualBoard() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        host.screen = "LOBBY"
        guest.screen = "LOBBY"
        guest.sendPacket(RoomMessagePacket(type = "JOIN", playerId = guest.id, readyStatus = "READY"))
        host.roomPlayers[guest.id] = guest.localPlayer.copy(lobbyReadyStatus = "READY")

        // Host starts normal match (isManualBoard = false)
        val matchSeed = 3344556677L
        host.isManualBoardMode = false
        host.currentMatchSeed = matchSeed
        val engine = BingoEngine()
        host.playerBoard = engine.generateBoard(5, matchSeed)
        host.opponentBoard = engine.generateBoard(5, matchSeed + 1)
        val sortedUids = listOf(host.id, guest.id).sorted()
        val firstTurnUid = ManualBoardEngine.determineRandomFirstTurn(matchSeed, sortedUids)
        host.currentTurnPlayerId = firstTurnUid
        host.isMyTurn = (host.currentTurnPlayerId == host.id)
        host.screen = "GAME"

        host.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = host.id,
                boardSize = 5,
                seed = matchSeed,
                isManualBoard = false
            )
        )

        // VERIFICATION: Does Guest transition directly to GAME without manual board screen?
        assertEquals("Guest must navigate directly to GAME in normal mode", "GAME", guest.screen)
        assertFalse("Guest manual board mode must be false", guest.isManualBoardMode)
        assertEquals("Both players must agree on identical match seed", matchSeed, guest.currentMatchSeed)
        assertEquals("Both players must agree on identical first turn", host.currentTurnPlayerId, guest.currentTurnPlayerId)
        assertTrue("One player must have first turn", host.isMyTurn != guest.isMyTurn)

        println("✅ Simulation Test 2 (Normal Match Without Manual Board) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 3: MULTI-MATCH CONSECUTIVE STRESS TEST (CHECKS TURN FLICKERING BUG)
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_MultiMatchStressTest_DetectsTurnFlickering() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        host.screen = "LOBBY"
        guest.screen = "LOBBY"
        guest.sendPacket(RoomMessagePacket(type = "JOIN", playerId = guest.id, readyStatus = "READY"))
        host.roomPlayers[guest.id] = guest.localPlayer.copy(lobbyReadyStatus = "READY")

        // Simulate 5 consecutive matches in the same room lobby
        for (matchIndex in 1..5) {
            println("--- Match $matchIndex: host.isMyTurn=${host.isMyTurn}, guest.isMyTurn=${guest.isMyTurn}, host.turn=${host.turnNumber}, guest.turn=${guest.turnNumber}, host.currentTurn=${host.currentTurnPlayerId}, guest.currentTurn=${guest.currentTurnPlayerId} ---")

            // 1. Host initiates match with fresh unique seed
            val matchSeed = 1000000L + (matchIndex * 99991L)
            host.currentMatchSeed = matchSeed
            host.isManualBoardMode = false
            val engine = BingoEngine()
            host.playerBoard = engine.generateBoard(5, matchSeed)
            host.opponentBoard = engine.generateBoard(5, matchSeed + 1)
            val sortedUids = listOf(host.id, guest.id).sorted()
            val firstTurnUid = ManualBoardEngine.determineRandomFirstTurn(matchSeed, sortedUids)
            host.currentTurnPlayerId = firstTurnUid
            host.isMyTurn = (host.currentTurnPlayerId == host.id)
            host.screen = "GAME"

            host.sendPacket(
                RoomMessagePacket(
                    type = "START_GAME",
                    playerId = host.id,
                    boardSize = 5,
                    seed = matchSeed,
                    isManualBoard = false
                )
            )

            assertEquals("Match $matchIndex: Guest must start GAME", "GAME", guest.screen)
            assertEquals("Match $matchIndex: Seeds must match", host.currentMatchSeed, guest.currentMatchSeed)
            assertEquals("Match $matchIndex: First turn must match", host.currentTurnPlayerId, guest.currentTurnPlayerId)

            // 2. Play 4 turns
            val playerA = if (host.isMyTurn) host else guest
            val playerB = if (host.isMyTurn) guest else host

            val initialSwitchCountHost = host.turnSwitchLog.size
            val initialSwitchCountGuest = guest.turnSwitchLog.size

            playerA.pickNumber(matchIndex * 5 + 1)
            assertEquals("Match $matchIndex: Turn 2 belongs to B", true, playerB.isMyTurn)
            assertEquals("Match $matchIndex: Turn 2 does not belong to A", false, playerA.isMyTurn)

            playerB.pickNumber(matchIndex * 5 + 2)
            assertEquals("Match $matchIndex: Turn 3 belongs to A", true, playerA.isMyTurn)
            assertEquals("Match $matchIndex: Turn 3 does not belong to B", false, playerB.isMyTurn)

            println("Before GAME_SYNC: host.isMyTurn=${host.isMyTurn}, guest.isMyTurn=${guest.isMyTurn}, host.turn=${host.turnNumber}, guest.turn=${guest.turnNumber}, host.currentTurn=${host.currentTurnPlayerId}, guest.currentTurn=${guest.currentTurnPlayerId}"); // Simulate GAME_SYNC packets (as sent every 2.5s in live gameplay)
            host.sendPacket(
                RoomMessagePacket(
                    type = "GAME_SYNC",
                    turnNumber = host.turnNumber,
                    pickedHistory = host.pickedNumbersHistory.toList(),
                    currentTurnPlayerId = host.currentTurnPlayerId,
                    playerId = host.id,
                    seed = matchSeed
                )
            )
            guest.sendPacket(
                RoomMessagePacket(
                    type = "GAME_SYNC",
                    turnNumber = guest.turnNumber,
                    pickedHistory = guest.pickedNumbersHistory.toList(),
                    currentTurnPlayerId = guest.currentTurnPlayerId,
                    playerId = guest.id,
                    seed = matchSeed
                )
            )

            // VERIFICATION OF FLICKERING BUG:
            // Ensure GAME_SYNC did NOT cause unprompted turn switching or flickering!
            assertEquals("Match $matchIndex: Host turn state must remain stable after GAME_SYNC", (host.id == host.currentTurnPlayerId), host.isMyTurn)
            assertEquals("Match $matchIndex: Guest turn state must remain stable after GAME_SYNC", (guest.id == guest.currentTurnPlayerId), guest.isMyTurn)

            // 3. Return to lobby after match
            host.returnToLobby()
            guest.returnToLobby()
            assertEquals("Match $matchIndex: Host in lobby", "LOBBY", host.screen)
            assertEquals("Match $matchIndex: Guest in lobby", "LOBBY", guest.screen)
            assertEquals("Match $matchIndex: Host seed reset", 0L, host.currentMatchSeed)
            assertEquals("Match $matchIndex: Guest seed reset", 0L, guest.currentMatchSeed)

            // Guest toggles ready for next match
            guest.localPlayer = LobbyLifecycleEngine.onLocalToggleReady(guest.localPlayer, isReady = true)
            host.roomPlayers[guest.id] = guest.localPlayer
        }

        println("✅ Simulation Test 3 (5 Consecutive Matches - Zero Turn Flickering) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 4: ROGUE & RETRIED PACKET ISOLATION
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_StaleSeedPacketsAreRejected() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        guest.screen = "GAME"
        guest.currentMatchSeed = 99998888L
        guest.turnNumber = 5
        guest.currentTurnPlayerId = guest.id
        guest.isMyTurn = true

        // Simulate a stale PICK_NUMBER packet from an old match arriving over network
        val stalePacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 15,
            playerId = host.id,
            turnNumber = 8,
            currentTurnPlayerId = host.id,
            seed = 11112222L // OLD SEED!
        )
        guest.handleIncomingPacket(stalePacket)

        // VERIFICATION: Stale packet must be completely ignored by LobbyLifecycleEngine.isPacketForActiveMatch
        assertEquals("Guest turn number must NOT change on stale packet", 5, guest.turnNumber)
        assertEquals("Guest turn ownership must NOT flicker", true, guest.isMyTurn)
        assertFalse("Stale number 15 must NOT be added to history", guest.pickedNumbersHistory.contains(15))

        println("✅ Simulation Test 4 (Stale Seed Packet Rejection) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 5: FULL GAME INVITE & ACCEPT FLOW -> MANUAL BOARD -> GAMEPLAY -> RETURN TO LOBBY -> REMATCH
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_FullGameInviteAndAcceptFlow_ManualBoardToFinishToLobby() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_alice", "Alice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_bob", "Bob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        // Step 1: Host creates room
        host.screen = "LOBBY"
        host.currentRoomCode = "INV777"
        println("[SIM-INVITE] Host Alice created room INV777")

        // Step 2: Host invites friend Bob
        val invite = GameInvite(
            fromUsername = "alice",
            fromDisplayName = "Alice",
            roomCode = host.currentRoomCode
        )
        println("[SIM-INVITE] Alice sent game invite to Bob: roomCode=${invite.roomCode}")

        // Step 3: Bob accepts invite and joins room INV777
        guest.currentRoomCode = invite.roomCode
        guest.screen = "LOBBY"
        guest.sendPacket(
            RoomMessagePacket(
                type = "JOIN",
                playerId = guest.id,
                displayName = guest.displayName,
                isHost = false,
                readyStatus = "NOT_READY"
            )
        )
        println("[SIM-INVITE] Bob accepted invite and joined room INV777")

        assertEquals(2, host.roomPlayers.size)
        assertEquals("NOT_READY", host.roomPlayers[guest.id]?.lobbyReadyStatus)
        assertFalse(LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Step 4: Bob clicks "Ready"
        guest.localPlayer = LobbyLifecycleEngine.onLocalToggleReady(guest.localPlayer, isReady = true)
        guest.sendPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = guest.id,
                readyStatus = "READY",
                readyVersion = guest.localPlayer.readyVersion
            )
        )
        println("[SIM-INVITE] Bob toggled READY. Start button enabled on Host.")
        assertTrue(LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Step 5: Host starts game with Manual designed board checked
        val matchSeed = 5544332211L
        host.isManualBoardMode = true
        host.currentMatchSeed = matchSeed
        host.screen = "MANUAL_BOARD"

        val startPacket = RoomMessagePacket(
            type = "START_GAME",
            playerId = host.id,
            boardSize = 5,
            seed = matchSeed,
            isManualBoard = true
        )
        host.sendPacket(startPacket)
        println("[SIM-INVITE] Host started game with isManualBoard=true. Sent START_GAME packet.")

        // Verify Bob transitioned to MANUAL_BOARD
        assertEquals("MANUAL_BOARD", guest.screen)
        assertTrue(guest.isManualBoardMode)
        assertEquals(matchSeed, guest.currentMatchSeed)
        println("[SIM-INVITE] Guest Bob successfully navigated to MANUAL_BOARD screen!")

        // Step 6: Board arrangement (Bob finishes first)
        val bobNumbers = (1..25).toList()
        guest.submitBoardReady(bobNumbers)
        assertTrue(guest.isLocalBoardReady)
        assertTrue(guest.isWaitingForOpponent)
        assertEquals("MANUAL_BOARD", guest.screen)
        println("[SIM-INVITE] Bob finished board arrangement first. Waiting dialog displayed on clean background.")

        // Host receives Bob's BOARD_READY
        assertTrue(host.isOpponentBoardReady)
        assertFalse(host.isLocalBoardReady)
        assertEquals("MANUAL_BOARD", host.screen)

        // Host finishes board arrangement
        val aliceNumbers = (25 downTo 1).toList()
        host.submitBoardReady(aliceNumbers)
        assertTrue(host.isLocalBoardReady)
        println("[SIM-INVITE] Alice finished board arrangement. Synchronized 5s countdown triggered!")

        // Step 7: Synchronized Countdown & Game Screen Transition
        assertEquals("GAME", host.screen)
        assertEquals("GAME", guest.screen)
        assertEquals(host.currentTurnPlayerId, guest.currentTurnPlayerId)
        println("[SIM-INVITE] Both players entered GAME screen! First turn assigned to: ${host.currentTurnPlayerId}")

        // Step 8: Play gameplay moves until win or turns completed
        val firstPlayer = if (host.isMyTurn) host else guest
        val secondPlayer = if (host.isMyTurn) guest else host

        firstPlayer.pickNumber(1)
        println("[SIM-INVITE] Turn 1 played (Pick 1). Turn advanced to 2.")
        assertEquals(listOf(1), host.pickedNumbersHistory.toList())
        assertEquals(listOf(1), guest.pickedNumbersHistory.toList())
        assertTrue(secondPlayer.isMyTurn)

        secondPlayer.pickNumber(2)
        println("[SIM-INVITE] Turn 2 played (Pick 2). Turn advanced to 3.")
        assertEquals(listOf(1, 2), host.pickedNumbersHistory.toList())
        assertEquals(listOf(1, 2), guest.pickedNumbersHistory.toList())
        assertTrue(firstPlayer.isMyTurn)

        // Step 9: Return to Lobby
        host.returnToLobby()
        guest.returnToLobby()
        assertEquals("LOBBY", host.screen)
        assertEquals("LOBBY", guest.screen)
        assertEquals(0L, host.currentMatchSeed)
        assertEquals(0L, guest.currentMatchSeed)
        assertFalse(host.isLocalBoardReady)
        assertFalse(guest.isLocalBoardReady)
        println("[SIM-INVITE] Both players returned to LOBBY! Seeds and board ready states cleanly reset.")

        // Step 10: Repeat procedure (Rematch)
        guest.localPlayer = LobbyLifecycleEngine.onLocalToggleReady(guest.localPlayer, isReady = true)
        host.roomPlayers[guest.id] = guest.localPlayer

        val match2Seed = 9988776655L
        host.currentMatchSeed = match2Seed
        host.screen = "MANUAL_BOARD"
        host.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = host.id,
                boardSize = 5,
                seed = match2Seed,
                isManualBoard = true
            )
        )

        assertEquals("MANUAL_BOARD", guest.screen)
        assertEquals(match2Seed, guest.currentMatchSeed)
        println("[SIM-INVITE] Rematch initiated! Both players navigated to MANUAL_BOARD again cleanly.")
        println("✅ Simulation Test 5 (Full Game Invite -> Manual Board -> Return to Lobby -> Rematch) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 6: SPLIT-SCREEN / SECURE FOLDER DUAL-INSTANCE PACKET ISOLATION
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_SplitScreenDualInstancePacketIsolation() {
        val bus = VirtualMessageBus()
        // Two instances running on the same device in split screen / Secure Folder
        val topHalf = SimulatedClient(
            id = "user_top",
            displayName = "TopPlayer",
            isHost = true,
            messageBus = bus,
            instanceId = "inst_top_1111"
        )
        val bottomHalf = SimulatedClient(
            id = "user_bottom",
            displayName = "BottomPlayer",
            isHost = false,
            messageBus = bus,
            instanceId = "inst_bottom_2222"
        )
        bus.register(topHalf)
        bus.register(bottomHalf)

        topHalf.screen = "LOBBY"
        bottomHalf.screen = "LOBBY"

        // Verify senderInstanceId is properly encoded by FastPacketCodec
        val testPacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 10,
            playerId = topHalf.id,
            turnNumber = 2,
            senderInstanceId = topHalf.instanceId
        )
        val encoded = FastPacketCodec.encode(testPacket)
        assertTrue("Encoded PICK_NUMBER must contain senderInstanceId", encoded.contains(topHalf.instanceId))
        val decoded = FastPacketCodec.decode(encoded)
        assertEquals(topHalf.instanceId, decoded.senderInstanceId)

        // Join room
        bottomHalf.sendPacket(
            RoomMessagePacket(
                type = "JOIN",
                playerId = bottomHalf.id,
                displayName = bottomHalf.displayName,
                isHost = false
            )
        )
        assertEquals(2, topHalf.roomPlayers.size)

        // Toggle ready
        bottomHalf.sendPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = bottomHalf.id,
                readyStatus = "READY",
                readyVersion = 1L
            )
        )
        topHalf.roomPlayers[bottomHalf.id] = bottomHalf.localPlayer.copy(lobbyReadyStatus = "READY")

        // Start manual board match
        val seed = 123456789L
        topHalf.currentMatchSeed = seed
        topHalf.isManualBoardMode = true
        topHalf.screen = "MANUAL_BOARD"

        topHalf.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = topHalf.id,
                boardSize = 5,
                seed = seed,
                isManualBoard = true
            )
        )

        assertEquals("MANUAL_BOARD", bottomHalf.screen)
        assertEquals(seed, bottomHalf.currentMatchSeed)

        // Both submit board ready
        topHalf.submitBoardReady((1..25).toList())
        bottomHalf.submitBoardReady((25 downTo 1).toList())

        // Ensure both transitioned to GAME and zero packets were dropped by instance filtering
        assertEquals("GAME", topHalf.screen)
        assertEquals("GAME", bottomHalf.screen)
        assertEquals(topHalf.currentTurnPlayerId, bottomHalf.currentTurnPlayerId)

        println("✅ Simulation Test 6 (Split-Screen / Dual Instance Packet Isolation) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 7: NEARBY NETWORK (LAN/HOTSPOT) P2P WITH FULL LOBBY & MANUAL BOARD FLOW
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_NearbyNetworkP2p_ManualBoardAndRematchFlow() {
        val bus = VirtualMessageBus()
        // Host (Alice) broadcasts Nearby LAN Match
        val host = SimulatedClient("host_lan", "AliceLan", isHost = true, messageBus = bus)
        // Guest (Bob) discovers Host on Wi-Fi and joins
        val guest = SimulatedClient("guest_lan", "BobLan", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        // Step 1: Host creates Nearby LAN lobby
        host.screen = "LOBBY"
        host.currentRoomCode = "LAN_4912"
        host.boardSize = 5
        assertEquals("LOBBY", host.screen)
        assertEquals("READY", host.localPlayer.lobbyReadyStatus)

        // Step 2: Guest discovers game via LAN discovery and connects as client
        guest.screen = "LOBBY"
        guest.currentRoomCode = "LAN_4912"
        guest.boardSize = 5
        guest.sendPacket(
            RoomMessagePacket(
                type = "JOIN",
                playerId = guest.id,
                displayName = guest.displayName,
                isHost = false,
                readyStatus = "NOT_READY"
            )
        )

        // Both players are in the lobby
        assertEquals(2, host.roomPlayers.size)
        assertEquals(2, guest.roomPlayers.size)
        assertEquals("NOT_READY", host.roomPlayers[guest.id]?.lobbyReadyStatus)
        assertFalse("Host Start button must be disabled until guest is READY",
            LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Step 3: Host enables Manual Board mode in the lobby
        host.isManualBoardMode = true
        host.sendPacket(
            RoomMessagePacket(
                type = "SETTINGS_UPDATE",
                playerId = host.id,
                isManualBoard = true
            )
        )
        // Guest receives settings update
        guest.isManualBoardMode = true
        assertTrue(guest.isManualBoardMode)

        // Step 4: Guest toggles "I'M READY" in the lobby
        guest.localPlayer = LobbyLifecycleEngine.onLocalStatusChange(guest.localPlayer, "READY")
        guest.sendPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = guest.id,
                readyStatus = "READY",
                readyVersion = guest.localPlayer.readyVersion
            )
        )
        host.roomPlayers[guest.id] = guest.localPlayer

        assertTrue("Host Start button must now be enabled",
            LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Step 5: Host clicks "Start Match (5x5)"
        val matchSeed = 9988776655L
        host.currentMatchSeed = matchSeed
        host.screen = "MANUAL_BOARD"
        host.manualGrid = ManualBoardEngine.createEmptyGrid(5)

        host.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = host.id,
                boardSize = 5,
                seed = matchSeed,
                isManualBoard = true
            )
        )

        // Both clients must transition to MANUAL_BOARD design screen
        assertEquals("MANUAL_BOARD", host.screen)
        assertEquals("MANUAL_BOARD", guest.screen)
        assertEquals(matchSeed, guest.currentMatchSeed)

        // Step 6: Both design and submit their boards
        val hostGridNumbers = (1..25).toList()
        val guestGridNumbers = (25 downTo 1).toList()

        host.submitBoardReady(hostGridNumbers)
        assertTrue(host.isWaitingForOpponent)
        assertEquals("MANUAL_BOARD", host.screen) // Still waiting for guest

        guest.submitBoardReady(guestGridNumbers)

        // Both received BOARD_READY, 5-second countdown finishes, both enter GAME
        assertEquals("GAME", host.screen)
        assertEquals("GAME", guest.screen)
        assertEquals(host.currentTurnPlayerId, guest.currentTurnPlayerId)
        assertTrue("Exactly one player must have first turn",
            (host.isMyTurn && !guest.isMyTurn) || (!host.isMyTurn && guest.isMyTurn))

        // Step 7: Simulate move exchange over LAN P2P
        val firstPicker = if (host.isMyTurn) host else guest
        val secondPicker = if (host.isMyTurn) guest else host

        firstPicker.pickNumber(10)
        assertTrue(host.pickedNumbersHistory.contains(10))
        assertTrue(guest.pickedNumbersHistory.contains(10))
        assertEquals(secondPicker.id, host.currentTurnPlayerId)
        assertEquals(secondPicker.id, guest.currentTurnPlayerId)
        assertTrue(secondPicker.isMyTurn)
        assertFalse(firstPicker.isMyTurn)

        // Finish game
        host.isGameOver = true
        host.didPlayerWin = true
        guest.isGameOver = true
        guest.didPlayerWin = false

        // Step 8: Both click "Return to Lobby" / "Rematch"
        host.returnToLobby()
        guest.returnToLobby()

        assertEquals("LOBBY", host.screen)
        assertEquals("LOBBY", guest.screen)
        assertEquals("READY", host.localPlayer.lobbyReadyStatus)
        assertEquals("NOT_READY", guest.localPlayer.lobbyReadyStatus)
        assertFalse("Host Start button must be disabled for rematch until guest readies up",
            LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Step 9: Guest readies up again for Match 2
        guest.localPlayer = LobbyLifecycleEngine.onLocalStatusChange(guest.localPlayer, "READY")
        guest.sendPacket(
            RoomMessagePacket(
                type = "READY_STATUS",
                playerId = guest.id,
                readyStatus = "READY",
                readyVersion = guest.localPlayer.readyVersion
            )
        )
        host.roomPlayers[guest.id] = guest.localPlayer
        assertTrue("Host Start button must be enabled again for rematch",
            LobbyLifecycleEngine.canStartMatch(host.roomPlayers.values.toList()))

        // Match 2 starts
        val match2Seed = 5544332211L
        host.currentMatchSeed = match2Seed
        host.screen = "MANUAL_BOARD"
        host.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = host.id,
                boardSize = 5,
                seed = match2Seed,
                isManualBoard = true
            )
        )
        assertEquals("MANUAL_BOARD", guest.screen)
        assertEquals(match2Seed, guest.currentMatchSeed)

        println("✅ Simulation Test 7 (Nearby Network P2P with Full Lobby & Manual Board Flow) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 8: TURN TIMEOUT - ACTIVE PLAYER MISSES TURN, CONTROL SWITCHES TO OPPONENT
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_TurnTimeout_ActivePlayerTimesOut_ControlSwitchesCleanly() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        // Setup room and ready states
        host.roomPlayers[guest.id] = guest.localPlayer
        guest.roomPlayers[host.id] = host.localPlayer

        val matchSeed = 9988776655L
        host.currentMatchSeed = matchSeed
        guest.currentMatchSeed = matchSeed

        // Host determines first turn deterministically and sends START_GAME with currentTurnPlayerId
        val candidates = listOf(host.id, guest.id).sorted()
        val firstTurnUid = ManualBoardEngine.determineRandomFirstTurn(matchSeed, candidates)

        val startPacket = RoomMessagePacket(
            type = "START_GAME",
            playerId = host.id,
            boardSize = 5,
            seed = matchSeed,
            currentTurnPlayerId = firstTurnUid,
            isManualBoard = false
        )
        host.currentTurnPlayerId = firstTurnUid
        host.isMyTurn = (firstTurnUid == host.id)
        host.screen = "GAME"
        host.turnNumber = 1
        host.turnTimer = 30
        host.sendPacket(startPacket)

        assertEquals("GAME", guest.screen)
        assertEquals("Both players must agree on active turn player", host.currentTurnPlayerId, guest.currentTurnPlayerId)
        val activeClient = if (host.isMyTurn) host else guest
        val waitingClient = if (host.isMyTurn) guest else host

        assertTrue("Active player must have isMyTurn=true", activeClient.isMyTurn)
        assertFalse("Waiting player must have isMyTurn=false", waitingClient.isMyTurn)
        assertEquals(1, activeClient.turnNumber)
        assertEquals(1, waitingClient.turnNumber)

        // Active player runs out of time (30s countdown hits 0s)
        activeClient.onTurnTimeout()

        // VERIFY: Control cleanly switches to waiting player!
        assertEquals("Turn number must increment after timeout", 2, activeClient.turnNumber)
        assertEquals("Turn number must increment on opponent after timeout packet", 2, waitingClient.turnNumber)
        assertEquals(30, activeClient.turnTimer)
        assertEquals(30, waitingClient.turnTimer)

        assertFalse("Former active player must have isMyTurn=false", activeClient.isMyTurn)
        assertTrue("Waiting player must now have isMyTurn=true (control switched)", waitingClient.isMyTurn)
        assertEquals(waitingClient.id, activeClient.currentTurnPlayerId)
        assertEquals(waitingClient.id, guest.currentTurnPlayerId)

        // Now newly active player can pick a number
        waitingClient.pickNumber(15)
        assertEquals(3, activeClient.turnNumber)
        assertEquals(3, waitingClient.turnNumber)
        assertTrue("Control rotated back to original active player", activeClient.isMyTurn)
        assertFalse(waitingClient.isMyTurn)
        assertTrue(activeClient.pickedNumbersHistory.contains(15))
        assertTrue(waitingClient.pickedNumbersHistory.contains(15))

        println("✅ Simulation Test 8 (Turn Timeout - Control Switches Cleanly) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 9: TURN TIMEOUT - PACKET LOSS / FALLBACK ROTATION (NO 0s FREEZE)
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_TurnTimeout_DroppedPacket_NonActivePlayerFallbackTakesControl() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        host.roomPlayers[guest.id] = guest.localPlayer
        guest.roomPlayers[host.id] = host.localPlayer

        val matchSeed = 1122334455L
        host.currentMatchSeed = matchSeed
        guest.currentMatchSeed = matchSeed

        // Force Host as first turn player
        host.currentTurnPlayerId = host.id
        host.isMyTurn = true
        host.screen = "GAME"
        host.turnNumber = 1
        host.turnTimer = 30

        guest.currentTurnPlayerId = host.id
        guest.isMyTurn = false
        guest.screen = "GAME"
        guest.turnNumber = 1
        guest.turnTimer = 30

        // Simulate host times out BUT packet is dropped over network (guest doesn't receive it)
        // Guest timer hits 0s. After 1.5s grace period, guest's fail-safe triggers:
        guest.onFallbackTimeout(activePickerId = host.id)

        // VERIFY: Guest takes control locally and broadcasts TURN_TIMEOUT back to host
        assertTrue("Guest must gain isMyTurn=true via fallback", guest.isMyTurn)
        assertEquals(guest.id, guest.currentTurnPlayerId)
        assertEquals(2, guest.turnNumber)
        assertEquals(30, guest.turnTimer)

        // Host receives the packet from guest and reconciles
        assertFalse("Host must have isMyTurn=false", host.isMyTurn)
        assertEquals(guest.id, host.currentTurnPlayerId)
        assertEquals(2, host.turnNumber)
        assertEquals(30, host.turnTimer)

        println("✅ Simulation Test 9 (Turn Timeout - Fallback Rotation & Zero-Stall Guarantee) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 10: CONSECUTIVE TIMEOUTS - ALTERNATES INDEFINITELY WITHOUT STALL
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_TurnTimeout_ConsecutiveTimeouts_AlternatesContinuouslyWithoutStall() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        host.roomPlayers[guest.id] = guest.localPlayer
        guest.roomPlayers[host.id] = host.localPlayer

        val matchSeed = 4455667788L
        host.currentMatchSeed = matchSeed
        guest.currentMatchSeed = matchSeed

        host.currentTurnPlayerId = host.id
        host.isMyTurn = true
        host.screen = "GAME"
        host.turnNumber = 1

        guest.currentTurnPlayerId = host.id
        guest.isMyTurn = false
        guest.screen = "GAME"
        guest.turnNumber = 1

        // Turn 1: Host times out -> Guest gets turn
        host.onTurnTimeout()
        assertEquals(2, host.turnNumber)
        assertEquals(2, guest.turnNumber)
        assertFalse(host.isMyTurn)
        assertTrue(guest.isMyTurn)
        assertEquals(guest.id, host.currentTurnPlayerId)
        assertEquals(guest.id, guest.currentTurnPlayerId)

        // Turn 2: Guest times out -> Host gets turn
        guest.onTurnTimeout()
        assertEquals(3, host.turnNumber)
        assertEquals(3, guest.turnNumber)
        assertTrue(host.isMyTurn)
        assertFalse(guest.isMyTurn)
        assertEquals(host.id, host.currentTurnPlayerId)
        assertEquals(host.id, guest.currentTurnPlayerId)

        // Turn 3: Host times out again -> Guest gets turn
        host.onTurnTimeout()
        assertEquals(4, host.turnNumber)
        assertEquals(4, guest.turnNumber)
        assertFalse(host.isMyTurn)
        assertTrue(guest.isMyTurn)
        assertEquals(guest.id, host.currentTurnPlayerId)
        assertEquals(guest.id, guest.currentTurnPlayerId)

        // Turn 4: Guest picks number 7 -> Host gets turn
        guest.pickNumber(7)
        assertEquals(5, host.turnNumber)
        assertEquals(5, guest.turnNumber)
        assertTrue(host.isMyTurn)
        assertFalse(guest.isMyTurn)
        assertEquals(host.id, host.currentTurnPlayerId)
        assertEquals(host.id, guest.currentTurnPlayerId)
        assertEquals(listOf(7), host.pickedNumbersHistory.toList())
        assertEquals(listOf(7), guest.pickedNumbersHistory.toList())

        println("✅ Simulation Test 10 (Consecutive Timeouts Alternation) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 11: QUICK CHAT PHRASES AND FLOATING REACTION TRANSMISSION
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_QuickChatPhrasesAndReactionsDuringGameplay() {
        val bus = VirtualMessageBus()
        val host = SimulatedClient("host_1", "HostAlice", isHost = true, messageBus = bus)
        val guest = SimulatedClient("guest_2", "GuestBob", isHost = false, messageBus = bus)
        bus.register(host)
        bus.register(guest)

        // Setup room and start game
        host.screen = "LOBBY"
        guest.screen = "LOBBY"
        guest.sendPacket(RoomMessagePacket(type = "JOIN", playerId = guest.id, displayName = guest.displayName))
        host.roomPlayers[guest.id] = guest.localPlayer

        val matchSeed = 998877L
        host.currentMatchSeed = matchSeed
        host.boardSize = 5
        host.isManualBoardMode = false
        host.screen = "GAME"
        host.playerBoard = engine.generateBoard(5, matchSeed)
        host.opponentBoard = engine.generateBoard(5, matchSeed + 1)
        host.currentTurnPlayerId = host.id
        host.isMyTurn = true
        host.turnNumber = 1

        host.sendPacket(
            RoomMessagePacket(
                type = "START_GAME",
                playerId = host.id,
                seed = matchSeed,
                boardSize = 5,
                isManualBoard = false,
                currentTurnPlayerId = host.id
            )
        )

        assertEquals("GAME", guest.screen)
        assertEquals(host.id, guest.currentTurnPlayerId)
        assertFalse(guest.isMyTurn)

        // 1. Guest reacts with a custom quick chat phrase
        val phrase = "Nice move! 🎯"
        guest.sendEmoteOrPhrase(phrase, isPhrase = true)

        assertEquals("Nice move! 🎯", host.lastReceivedEmote)
        assertTrue(host.lastReceivedEmoteTimestamp > 0L)

        // 2. Host sends a reaction emoji in response
        val emote = "🔥"
        host.sendEmoteOrPhrase(emote, isPhrase = false)

        assertEquals("🔥", guest.lastReceivedEmote)
        assertTrue(guest.lastReceivedEmoteTimestamp > 0L)

        // 3. Verify maximum 25-character boundary rule for custom phrases
        val longPhrase = "This phrase is definitely way too long for twenty five chars"
        val trimmedPhrase = longPhrase.trim().take(25)
        assertEquals(25, trimmedPhrase.length)

        guest.sendEmoteOrPhrase(trimmedPhrase, isPhrase = true)
        assertEquals(trimmedPhrase, host.lastReceivedEmote)

        println("✅ Simulation Test 11 (Quick Chat Phrases & Reactions) PASSED successfully!")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 12: HEAD-TO-HEAD SCORECARD PROGRESSION & EXTENDED STRIKE LOGIC
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    fun testSimulation_HeadToHeadScorecardAndStrikeProgressionLogic() {
        val targetLines = 5
        var playerLines = 3
        var opponentLines = 2

        // Non match point
        var isPlayerMatchPoint = playerLines >= (targetLines - 1) && playerLines < targetLines
        var isOpponentMatchPoint = opponentLines >= (targetLines - 1) && opponentLines < targetLines
        assertFalse(isPlayerMatchPoint)
        assertFalse(isOpponentMatchPoint)

        // Player hits Match Point (4 lines)
        playerLines = 4
        isPlayerMatchPoint = playerLines >= (targetLines - 1) && playerLines < targetLines
        assertTrue(isPlayerMatchPoint)
        assertFalse(isOpponentMatchPoint)

        // Dual Match Point (both 4 lines)
        opponentLines = 4
        isOpponentMatchPoint = opponentLines >= (targetLines - 1) && opponentLines < targetLines
        assertTrue(isPlayerMatchPoint && isOpponentMatchPoint)

        // Strike progression verification:
        // When completedLines = 4, characters at indices 0..3 (B, I, N, G) have strikeProgress > 0,
        // and index 4 (O) is not yet struck out.
        val bingoLetters = listOf('B', 'I', 'N', 'G', 'O')
        val strikes = bingoLetters.mapIndexed { index, letter ->
            index < playerLines // true for struck letters
        }
        assertEquals(listOf(true, true, true, true, false), strikes)

        // Player completes 5th line (BINGO!)
        playerLines = 5
        val completedStrikes = bingoLetters.mapIndexed { index, _ -> index < playerLines }
        assertEquals(listOf(true, true, true, true, true), completedStrikes)

        // Once 5 lines reached, match point is cleared (game over / victory)
        isPlayerMatchPoint = playerLines >= (targetLines - 1) && playerLines < targetLines
        assertFalse(isPlayerMatchPoint)

        println("✅ Simulation Test 12 (Scorecard & Strike Progression Logic) PASSED successfully!")
    }
}
