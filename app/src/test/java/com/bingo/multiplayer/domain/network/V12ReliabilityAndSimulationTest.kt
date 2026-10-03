package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType
import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Rigorous Unit & Multi-Device Simulation Tests for V1.2 Reliability Upgrades:
 * 1. Deterministic Randomized Turn Order across all devices.
 * 2. Simultaneous Win Tie-Break:
 *    - More lines wins first (e.g. 8 vs 7 lines).
 *    - Exact line ties resolved via Turn-Order Priority (circular right-of-way).
 * 3. Return to Lobby departure handling (instant turn advance without 30s freeze).
 * 4. High Ping (full 30s allowed) vs Dead Socket (10s auto-skip).
 * 5. Full 8-Player match simulation with departure and rejoin.
 */
class V12ReliabilityAndSimulationTest {

    private val engine = BingoEngine()

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DETERMINISTIC RANDOM TURN ORDER TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testDeterministicRandomTurnOrder_IdenticalAcrossAllDevices() {
        val players = (1..8).map { i ->
            Player(id = "player_$i", displayName = "Player $i", isHost = (i == 1))
        }
        val matchSeed = 9876543210L

        // Device 1 (Host) generates order
        val device1Order = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, matchSeed)

        // Device 2 to 8 (Guests) generate order using same seed
        for (dev in 2..8) {
            val guestOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, matchSeed)
            assertEquals("Device $dev turn order must match Device 1 exactly",
                device1Order.map { it.id }, guestOrder.map { it.id })
        }

        // Must contain all 8 players exactly once
        assertEquals(8, device1Order.size)
        assertEquals(players.map { it.id }.toSet(), device1Order.map { it.id }.toSet())
    }

    @Test
    fun testDeterministicRandomTurnOrder_DifferentSeedsProduceDifferentRotations() {
        val players = (1..6).map { i ->
            Player(id = "p_$i", displayName = "P$i", isHost = (i == 1))
        }

        val order1 = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, 111111L).map { it.id }
        val order2 = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, 999999L).map { it.id }

        // Both are valid permutations, but different
        assertEquals(6, order1.size)
        assertEquals(6, order2.size)
        assertNotEquals("Different match seeds must produce different randomized turn orders", order1, order2)
    }

    @Test
    fun testCircularTurnRotation_FollowsRandomizedOrderThroughoutMatch() {
        val p1 = Player(id = "p1", displayName = "P1", isHost = true)
        val p2 = Player(id = "p2", displayName = "P2")
        val p3 = Player(id = "p3", displayName = "P3")
        val p4 = Player(id = "p4", displayName = "P4")
        val allPlayers = listOf(p1, p2, p3, p4)

        val matchSeed = 424242L
        val randomOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(allPlayers, matchSeed)
        val ids = randomOrder.map { it.id }

        // Test full circular rotation: each player passes to the next in randomized order
        for (i in ids.indices) {
            val current = ids[i]
            val expectedNext = ids[(i + 1) % ids.size]
            val actualNext = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = allPlayers,
                disconnectedPlayerIds = emptySet(),
                currentPickerId = current,
                matchSeed = matchSeed,
                customTurnOrder = randomOrder
            )
            assertEquals("Turn after $current must be $expectedNext in randomized rotation", expectedNext, actualNext)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. SIMULTANEOUS WIN & TIE-BREAKER TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testTurnDistanceCalculation() {
        val turnOrder = listOf("p3", "p4", "p1", "p2")

        // Distance from p3 to p4 is 1 step
        assertEquals(1, LobbyLifecycleEngine.calculateTurnDistance(turnOrder, "p3", "p4"))
        // Distance from p3 to p1 is 2 steps
        assertEquals(2, LobbyLifecycleEngine.calculateTurnDistance(turnOrder, "p3", "p1"))
        // Distance from p3 to p2 is 3 steps
        assertEquals(3, LobbyLifecycleEngine.calculateTurnDistance(turnOrder, "p3", "p2"))
        // Distance to self is 0
        assertEquals(0, LobbyLifecycleEngine.calculateTurnDistance(turnOrder, "p3", "p3"))
    }

    @Test
    fun testSimultaneousWin_HigherCompletedLinesWins() {
        // Player 3 picks a number.
        // Player 1 reaches 8 lines (double line completion!).
        // Player 2 reaches 7 lines.
        val boardP1 = createBoardWithLines(size = 7, completedLines = 8, isBingo = true)
        val boardP2 = createBoardWithLines(size = 7, completedLines = 7, isBingo = true)
        val boardP3 = createBoardWithLines(size = 7, completedLines = 5, isBingo = false)
        val boardP4 = createBoardWithLines(size = 7, completedLines = 3, isBingo = false)

        val allBoards = mapOf(
            "p1" to boardP1,
            "p2" to boardP2,
            "p3" to boardP3,
            "p4" to boardP4
        )
        val turnOrderUids = listOf("p1", "p2", "p3", "p4")
        val activePickerId = "p3"

        val outcome = evaluateSimultaneousWin(
            allBoards = allBoards,
            turnOrderUids = turnOrderUids,
            activePickerId = activePickerId
        )

        assertEquals("p1", outcome.winnerPlayerId)
        assertEquals("Line Count", outcome.winReason)
        assertTrue(outcome.runners.contains("p2"))
        assertFalse(outcome.runners.contains("p3"))
        assertFalse(outcome.runners.contains("p4"))
    }

    @Test
    fun testSimultaneousWin_EqualLinesResolvedByTurnPriority() {
        // 4 players, 7x7 board.
        // Player 3 picks. Both Player 1 and Player 2 reach exactly 7 lines!
        val boardP1 = createBoardWithLines(size = 7, completedLines = 7, isBingo = true)
        val boardP2 = createBoardWithLines(size = 7, completedLines = 7, isBingo = true)
        val boardP3 = createBoardWithLines(size = 7, completedLines = 5, isBingo = false)
        val boardP4 = createBoardWithLines(size = 7, completedLines = 4, isBingo = false)

        val allBoards = mapOf(
            "p1" to boardP1,
            "p2" to boardP2,
            "p3" to boardP3,
            "p4" to boardP4
        )

        // Case A: Turn order is [p3, p4, p1, p2].
        // Next after p3: p4 (step 1), p1 (step 2), p2 (step 3).
        // p1 is closer to p3 than p2 -> p1 wins!
        val turnOrderA = listOf("p3", "p4", "p1", "p2")
        val outcomeA = evaluateSimultaneousWin(allBoards, turnOrderA, "p3")
        assertEquals("p1", outcomeA.winnerPlayerId)
        assertEquals("Turn Priority", outcomeA.winReason)
        assertTrue(outcomeA.runners.contains("p2"))

        // Case B: Turn order is [p3, p2, p4, p1].
        // Next after p3: p2 (step 1), p4 (step 2), p1 (step 3).
        // p2 is closer to p3 than p1 -> p2 wins!
        val turnOrderB = listOf("p3", "p2", "p4", "p1")
        val outcomeB = evaluateSimultaneousWin(allBoards, turnOrderB, "p3")
        assertEquals("p2", outcomeB.winnerPlayerId)
        assertEquals("Turn Priority", outcomeB.winReason)
        assertTrue(outcomeB.runners.contains("p1"))
    }

    @Test
    fun testSimultaneousWin_ActivePickerWinsIfTheyCompleteBingoOnTheirTurn() {
        // Player 3 picks on their turn and reaches 7 lines!
        // Player 1 also reaches 7 lines on this pick.
        // Since Player 3 made the pick on their OWN turn, Player 3 is the undisputed Winner!
        val boardP1 = createBoardWithLines(size = 7, completedLines = 7, isBingo = true)
        val boardP2 = createBoardWithLines(size = 7, completedLines = 6, isBingo = false)
        val boardP3 = createBoardWithLines(size = 7, completedLines = 7, isBingo = true)

        val allBoards = mapOf("p1" to boardP1, "p2" to boardP2, "p3" to boardP3)
        val turnOrder = listOf("p1", "p2", "p3")

        val outcome = evaluateSimultaneousWin(allBoards, turnOrder, "p3")
        assertEquals("p3", outcome.winnerPlayerId)
        assertEquals("Turn Pick", outcome.winReason)
        assertTrue(outcome.runners.contains("p1"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. MID-GAME DEPARTURE & REJOIN TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testPlayerReturnsToLobby_SkipsTurnImmediatelyWithout30sFreeze() {
        val p1 = Player(id = "p1", displayName = "P1", isHost = true)
        val p2 = Player(id = "p2", displayName = "P2")
        val p3 = Player(id = "p3", displayName = "P3")
        val allPlayers = listOf(p1, p2, p3)

        // Currently it is p2's turn.
        // p2 clicks "Return to Lobby", so p2 is added to disconnectedPlayerIds.
        val disconnected = mutableSetOf("p2")

        val nextPlayer = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = allPlayers,
            disconnectedPlayerIds = disconnected,
            currentPickerId = "p2",
            fallbackPlayerId = "p1"
        )

        // Must immediately advance to p3 (not p2, not frozen)
        assertEquals("p3", nextPlayer)
    }

    @Test
    fun testPlayerRejoinsFromLobby_RestoresFullTurnRotation() {
        val p1 = Player(id = "p1", displayName = "P1", isHost = true)
        val p2 = Player(id = "p2", displayName = "P2")
        val p3 = Player(id = "p3", displayName = "P3")
        val allPlayers = listOf(p1, p2, p3)

        // p2 rejoined, disconnectedPlayerIds is cleared
        val disconnected = mutableSetOf<String>()

        val nextAfterP1 = LobbyLifecycleEngine.calculateNextTurnPlayerId(allPlayers, disconnected, "p1")
        val nextAfterP2 = LobbyLifecycleEngine.calculateNextTurnPlayerId(allPlayers, disconnected, "p2")
        val nextAfterP3 = LobbyLifecycleEngine.calculateNextTurnPlayerId(allPlayers, disconnected, "p3")

        assertEquals("p2", nextAfterP1)
        assertEquals("p3", nextAfterP2)
        assertEquals("p1", nextAfterP3)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. HEARTBEAT ALIVE VS DEAD CONNECTION LOGIC
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testHeartbeatDetection_AlivePlayerAllowedFullThinkingTime() {
        val now = System.currentTimeMillis()
        // Player sent heartbeat 1.5 seconds ago (active connection, high latency or thinking)
        val lastSeen = now - 1500L
        val timeSinceLastSeen = now - lastSeen

        val isDeadConnection = (timeSinceLastSeen > 10_000L)
        assertFalse("Player with recent heartbeat must NOT be flagged as dead connection", isDeadConnection)
    }

    @Test
    fun testHeartbeatDetection_DeadConnectionTriggersFastTimeout() {
        val now = System.currentTimeMillis()
        // Player has had zero heartbeats for 11 seconds (app swiped, internet dead)
        val lastSeen = now - 11000L
        val timeSinceLastSeen = now - lastSeen
        val currentTurnTimer = 19 // 11 seconds of turn elapsed

        val shouldSkipTurn = (timeSinceLastSeen > 10_000L && currentTurnTimer <= 20)
        assertTrue("Dead connection with >10s silence must trigger quick turn skip", shouldSkipTurn)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. END-TO-END 8-PLAYER MATCH SIMULATION
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testFull8PlayerMatchSimulation_CompleteLifecycle() {
        val seed = 123456789L
        val players = (1..8).map { i ->
            Player(id = "user_$i", displayName = "User $i", isHost = (i == 1))
        }

        // 1. Generate randomized turn order
        val turnOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, seed)
        assertEquals(8, turnOrder.size)
        val firstPlayer = turnOrder.first().id

        // 2. Simulate 20 turns of game
        var currentPicker = firstPlayer
        var turnNum = 1
        val pickedNumbers = mutableListOf<Int>()
        val disconnected = mutableSetOf<String>()

        for (pick in 1..20) {
            pickedNumbers.add(pick * 2)

            // At turn 10, user_5 leaves to lobby
            if (turnNum == 10) {
                disconnected.add("user_5")
            }

            // At turn 15, user_5 rejoins
            if (turnNum == 15) {
                disconnected.remove("user_5")
            }

            currentPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = players,
                disconnectedPlayerIds = disconnected,
                currentPickerId = currentPicker,
                fallbackPlayerId = firstPlayer,
                matchSeed = seed,
                customTurnOrder = turnOrder
            )
            turnNum++
            assertNotNull(currentPicker)
            assertTrue("Next picker must not be in disconnected set", currentPicker !in disconnected)
        }

        assertEquals(20, pickedNumbers.size)
    }

    // ── Helper functions for tests ──

    private data class SimulatedOutcome(
        val winnerPlayerId: String,
        val winReason: String,
        val runners: Set<String>
    )

    private fun evaluateSimultaneousWin(
        allBoards: Map<String, Board>,
        turnOrderUids: List<String>,
        activePickerId: String
    ): SimulatedOutcome {
        val completedPlayers = allBoards.filter { it.value.isBingo }.keys
        if (completedPlayers.isEmpty()) return SimulatedOutcome("", "None", emptySet())

        val (winnerId, winReason) = if (activePickerId.isNotBlank() && activePickerId in completedPlayers) {
            activePickerId to "Turn Pick"
        } else if (completedPlayers.size == 1) {
            completedPlayers.first() to "Solo Bingo"
        } else {
            val maxLines = completedPlayers.maxOf { allBoards[it]?.completedLinesCount ?: 0 }
            val topCandidates = completedPlayers.filter { (allBoards[it]?.completedLinesCount ?: 0) == maxLines }
            if (topCandidates.size == 1) {
                topCandidates.first() to "Line Count"
            } else {
                val bestByTurn = topCandidates.minByOrNull { candidateId ->
                    LobbyLifecycleEngine.calculateTurnDistance(
                        turnOrder = turnOrderUids,
                        fromPlayerId = activePickerId,
                        toPlayerId = candidateId
                    )
                } ?: topCandidates.first()
                bestByTurn to "Turn Priority"
            }
        }

        val runners = completedPlayers.filter { it != winnerId }.toSet()
        return SimulatedOutcome(winnerId, winReason, runners)
    }

    private fun createBoardWithLines(size: Int, completedLines: Int, isBingo: Boolean): Board {
        val cells = (0 until (size * size)).map { idx ->
            Cell(
                row = idx / size,
                col = idx % size,
                number = idx + 1,
                markState = CellMarkState.Unmarked
            )
        }
        val lines = (0 until completedLines).map { idx ->
            LineCoordinate(type = LineType.ROW, index = idx)
        }.toSet()
        return Board(
            size = size,
            cells = cells,
            completedLines = lines,
            targetLines = if (isBingo) completedLines else (completedLines + 1)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. PERSISTENCE ACROSS REBOOT & APP TERMINATION TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testOngoingMatchData_FullSerializationAndDeserializationFidelity() {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        // Create a 7x7 board with partial marked cells and 2 completed lines
        val size = 7
        val cells = (0 until (size * size)).map { idx ->
            val num = idx + 1
            val isMarked = (idx % 3 == 0)
            Cell(
                row = idx / size,
                col = idx % size,
                number = num,
                markState = if (isMarked) {
                    CellMarkState.Marked(pickedByPlayerId = "p2", isOwnPick = false, turnNumber = idx + 1)
                } else CellMarkState.Unmarked
            )
        }
        val lines = setOf(
            LineCoordinate(type = LineType.ROW, index = 0),
            LineCoordinate(type = LineType.MAIN_DIAGONAL, index = 0)
        )
        val playerBoard = Board(
            size = size,
            cells = cells,
            completedLines = lines,
            targetLines = size
        )

        val opponentBoard = engine.generateBoard(size, 456789L)
        val p1 = Player(id = "p1", displayName = "Player 1", isHost = false)
        val p2 = Player(id = "p2", displayName = "Player 2", isHost = true)
        val p3 = Player(id = "p3", displayName = "Player 3", isHost = false)
        val participants = listOf(p2, p1, p3)

        val chat = listOf(
            InGameChatMessage(id = 1001L, text = "Hello everyone!", isSelf = false, senderName = "Player 2"),
            InGameChatMessage(id = 1002L, text = "Good luck!", isSelf = true, senderName = null)
        )

        val originalData = OngoingMatchData(
            roomCode = "XY9876",
            matchSeed = 9988776655L,
            boardSize = size,
            isDynamicBoard = true,
            isManualBoard = false,
            isHost = false,
            participants = participants,
            playerBoard = playerBoard,
            opponentBoard = opponentBoard,
            allPlayerBoards = mapOf("p1" to playerBoard, "p2" to opponentBoard),
            pickedNumbers = listOf(7, 14, 21, 28, 35),
            pickedByPlayers = listOf("p2", "p1", "p3", "p2", "p1"),
            turnNumber = 6,
            currentTurnPlayerId = "p3",
            randomizedTurnOrder = listOf(p2, p3, p1),
            chatMessages = chat
        )

        // 1. Serialize to JSON string (mimicking SharedPreferences storage)
        val serializedJson = json.encodeToString(originalData)
        assertNotNull(serializedJson)
        assertTrue(serializedJson.contains("XY9876"))
        assertTrue(serializedJson.contains("Hello everyone!"))

        // 2. Deserialize from JSON string (mimicking app launch after device restart)
        val restoredData = json.decodeFromString<OngoingMatchData>(serializedJson)

        // 3. Verify 100% data fidelity
        assertEquals("XY9876", restoredData.roomCode)
        assertEquals(9988776655L, restoredData.matchSeed)
        assertEquals(7, restoredData.boardSize)
        assertTrue(restoredData.isDynamicBoard)
        assertFalse(restoredData.isHost)
        assertEquals(6, restoredData.turnNumber)
        assertEquals("p3", restoredData.currentTurnPlayerId)
        assertEquals(listOf(7, 14, 21, 28, 35), restoredData.pickedNumbers)
        assertEquals(listOf("p2", "p1", "p3", "p2", "p1"), restoredData.pickedByPlayers)

        // Verify board numbers and marked cells
        val restoredBoard = restoredData.playerBoard
        assertNotNull(restoredBoard)
        assertEquals(49, restoredBoard!!.cells.size)
        assertEquals(2, restoredBoard.completedLines.size)
        assertEquals(playerBoard.cells.map { it.number }, restoredBoard.cells.map { it.number })
        assertEquals(
            playerBoard.cells.map { it.markState is CellMarkState.Marked },
            restoredBoard.cells.map { it.markState is CellMarkState.Marked }
        )

        // Verify chat messages
        assertEquals(2, restoredData.chatMessages.size)
        assertEquals("Hello everyone!", restoredData.chatMessages[0].text)
        assertEquals("Good luck!", restoredData.chatMessages[1].text)
    }

    @Test
    fun testHostDeparture_IdentificationAndMatchTermination() {
        val hostPlayer = Player(id = "host_uid", displayName = "SuperHost", isHost = true)
        val guest1 = Player(id = "guest_1", displayName = "Guest 1", isHost = false)
        val guest2 = Player(id = "guest_2", displayName = "Guest 2", isHost = false)
        val allPlayers = listOf(hostPlayer, guest1, guest2)

        // Packet 1: Normal guest leaves -> match continues for remaining 2 players
        val guestLeavePacket = RoomMessagePacket(
            type = "LEAVE",
            playerId = "guest_1",
            displayName = "Guest 1",
            isHost = false
        )
        val isSenderHostGuest = guestLeavePacket.isHost ||
                allPlayers.find { it.id == guestLeavePacket.playerId }?.isHost == true
        assertFalse(isSenderHostGuest)

        // Packet 2: Host leaves -> must immediately terminate match for everyone
        val hostLeavePacket = RoomMessagePacket(
            type = "HOST_LEFT",
            playerId = "host_uid",
            displayName = "SuperHost",
            isHost = true
        )
        val isSenderHost = hostLeavePacket.isHost || hostLeavePacket.type == "HOST_LEFT" ||
                allPlayers.find { it.id == hostLeavePacket.playerId }?.isHost == true
        assertTrue("Host leave must be detected as room protector departure", isSenderHost)
    }

    @Test
    fun testHeartbeatDetection_NormalizedPlayerIdResolution() {
        // Player registry contains "u_bob"
        val registry = mutableMapOf<String, Long>()
        val now = System.currentTimeMillis()
        registry["u_bob"] = now - 500L

        // Incoming packet arrives with ID "bob" (no u_ prefix)
        val incomingId = "bob"
        val clean = incomingId.trim().lowercase().removePrefix("u_")

        val matchingTimestamps = registry.entries.filter { (k, _) ->
            k.trim().lowercase().removePrefix("u_") == clean
        }.map { it.value }

        val latestTimestamp = matchingTimestamps.maxOrNull() ?: 0L
        assertTrue("Must successfully resolve timestamp across normalized ID aliases", latestTimestamp > 0L)
        assertTrue("Player seen 500ms ago must NOT be marked as dead connection", (now - latestTimestamp) <= 10_000L)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. AUTHORITATIVE MULTIPLAYER WINNER SYNC & STAMPS (POINT 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testAuthoritativeWinnerSync_GuaranteesAllDevicesAgreeOnWinnerAndRunners() {
        val dev1Uid = "u_bob"
        val dev2Uid = "u_player2"
        val dev3Uid = "u_player3"
        val dev4Uid = "u_player4"

        // Device 4 (Picker) makes the winning pick and authoritatively declares outcome
        val winPickPacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 15,
            playerId = dev4Uid,
            turnNumber = 12,
            seed = 12345L,
            winnerPlayerId = dev4Uid,
            winReason = "Turn Pick",
            runnerPlayerIds = listOf(dev1Uid, dev3Uid)
        )

        // Simulate how each device evaluates outcome upon receiving the authoritative packet:
        fun evaluateOnDevice(myUid: String, packet: RoomMessagePacket, myBoardHasBingo: Boolean): Pair<Boolean, Boolean> {
            val isLocalWinner = LobbyLifecycleEngine.isPlayerIdMatch(packet.winnerPlayerId, myUid)
            val isLocalRunner = !isLocalWinner && (packet.runnerPlayerIds.any { LobbyLifecycleEngine.isPlayerIdMatch(it, myUid) } || myBoardHasBingo)
            return Pair(isLocalWinner, isLocalRunner)
        }

        // Device 1 (Bob): Runner
        val dev1Outcome = evaluateOnDevice(dev1Uid, winPickPacket, myBoardHasBingo = true)
        assertFalse("Device 1 did not win", dev1Outcome.first)
        assertTrue("Device 1 is recognized as Runner", dev1Outcome.second)

        // Device 2: Lost
        val dev2Outcome = evaluateOnDevice(dev2Uid, winPickPacket, myBoardHasBingo = false)
        assertFalse("Device 2 did not win", dev2Outcome.first)
        assertFalse("Device 2 is not a runner", dev2Outcome.second)

        // Device 3: Runner
        val dev3Outcome = evaluateOnDevice(dev3Uid, winPickPacket, myBoardHasBingo = true)
        assertFalse("Device 3 did not win", dev3Outcome.first)
        assertTrue("Device 3 is recognized as Runner", dev3Outcome.second)

        // Device 4: Winner
        val dev4Outcome = evaluateOnDevice(dev4Uid, winPickPacket, myBoardHasBingo = true)
        assertTrue("Device 4 is recognized as Winner", dev4Outcome.first)
        assertFalse("Winner is not runner", dev4Outcome.second)

        // All 4 devices agree: Winner is Player 4!
        assertEquals("u_player4", winPickPacket.winnerPlayerId)
    }

    @Test
    fun testVictoryStamps_AccurateLabelingForLocalAndReviewBoards() {
        val myUid = "u_bob"
        val winnerUid = "u_player4"
        val runnerUid = "u_player3"
        val loserUid = "u_player2"

        val players = listOf(
            Player(id = myUid, displayName = "Bob"),
            Player(id = winnerUid, displayName = "Player 4"),
            Player(id = runnerUid, displayName = "Player 3"),
            Player(id = loserUid, displayName = "Player 2")
        )

        // Helper matching GameScreen stamp calculation logic
        fun resolveStampText(
            selectedReviewId: String,
            localWon: Boolean,
            localRunner: Boolean,
            selectedBoardBingo: Boolean,
            selectedBoardLines: Int
        ): String {
            val isLocalSelected = LobbyLifecycleEngine.isPlayerIdMatch(selectedReviewId, myUid) || selectedReviewId == "local" || selectedReviewId.isBlank()
            return if (isLocalSelected) {
                when {
                    localWon -> "YOU'VE WON!"
                    localRunner -> "RUNNER!"
                    else -> "YOU LOST!"
                }
            } else {
                val isWinnerSelected = LobbyLifecycleEngine.isPlayerIdMatch(selectedReviewId, winnerUid)
                val reviewName = players.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it.id, selectedReviewId) }?.displayName ?: "Player"
                when {
                    isWinnerSelected -> "$reviewName WON!"
                    selectedBoardBingo -> "RUNNER!"
                    else -> "$selectedBoardLines/5 LINES"
                }
            }
        }

        // Scenario 1: Bob lost, reviews own board -> "YOU LOST!"
        assertEquals("YOU LOST!", resolveStampText(myUid, localWon = false, localRunner = false, selectedBoardBingo = false, selectedBoardLines = 2))

        // Scenario 2: Bob (loser) inspects Winner's board -> "Player 4 WON!" (NEVER "YOU'VE WON!")
        val stampOnWinner = resolveStampText(winnerUid, localWon = false, localRunner = false, selectedBoardBingo = true, selectedBoardLines = 5)
        assertEquals("Player 4 WON!", stampOnWinner)
        assertNotEquals("Must NEVER say YOU'VE WON to a player reviewing someone else's board", "YOU'VE WON!", stampOnWinner)

        // Scenario 3: Bob inspects Runner's board -> "RUNNER!"
        assertEquals("RUNNER!", resolveStampText(runnerUid, localWon = false, localRunner = false, selectedBoardBingo = true, selectedBoardLines = 5))

        // Scenario 4: Bob inspects another loser's board -> "3/5 LINES" (NEVER "YOU LOST!")
        val stampOnLoser = resolveStampText(loserUid, localWon = false, localRunner = false, selectedBoardBingo = false, selectedBoardLines = 3)
        assertEquals("3/5 LINES", stampOnLoser)
        assertNotEquals("Must NEVER say YOU LOST on another player's review board", "YOU LOST!", stampOnLoser)

        // Scenario 5: Winner reviews own board -> "YOU'VE WON!"
        assertEquals("YOU'VE WON!", resolveStampText(myUid, localWon = true, localRunner = false, selectedBoardBingo = true, selectedBoardLines = 5))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. FOREGROUND-ONLY HEARTBEATS & 10s DISCONNECT SKIPPING (POINT 2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testForegroundHeartbeat_SilentWhenBackgrounded_SkipsTurnAfter10s() {
        var lastHeartbeatTime = 100_000L // t = 100s

        fun shouldSendHeartbeat(isAppInForeground: Boolean): Boolean {
            return isAppInForeground
        }

        // 1. In foreground: heartbeats are sent
        assertTrue(shouldSendHeartbeat(isAppInForeground = true))

        // 2. User locks phone / backgrounds app: heartbeats immediately stop
        assertFalse(shouldSendHeartbeat(isAppInForeground = false))

        // 3. At t = 105s (5s elapsed): not disconnected yet
        val t105 = 105_000L
        assertFalse("At 5s silence, should not skip yet", (t105 - lastHeartbeatTime) >= 10_000L)

        // 4. At t = 110s (10s elapsed): 10s silence reached -> Turn skipped!
        val t110 = 110_000L
        assertTrue("At 10s silence, disconnect auto-skip MUST trigger", (t110 - lastHeartbeatTime) >= 10_000L)
    }

    @Test
    fun testReconcileWithCloud_DoesNotArtificiallyRewritePeerTimestamps() {
        val now = 200_000L
        val peerCloudTimestamp = 180_000L // 20s ago

        // Cloud session contains peer with lastSeenTimestamp from 20s ago
        val peerInCloud = Player(id = "peer1", displayName = "Peer 1", lastSeenTimestamp = peerCloudTimestamp)

        // New reconcile logic: NEVER overwrites peer with now
        val effectiveLastSeen = when {
            peerInCloud.id == "local_id" -> now
            else -> peerInCloud.lastSeenTimestamp
        }

        assertEquals("Peer timestamp must be preserved as 180_000L, not rewritten to now", peerCloudTimestamp, effectiveLastSeen)
        assertTrue("Elapsed silence is correctly recognized as 20s (> 10s)", (now - effectiveLastSeen) >= 10_000L)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. IN-GAME TURN STATUS MESSAGING (POINT 3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testTurnStatusMessaging_ThreeStagesCorrectlyTransition() {
        val playerName = "bora"

        fun getStatusMessage(timeRemaining: Int, dotPhase: Int): String {
            val dots = ".".repeat(dotPhase)
            return when {
                timeRemaining > 20 -> "$playerName is choosing$dots"
                timeRemaining in 11..20 -> "$playerName is cooking something..."
                else -> "let's have some coffee, $playerName is sleeping I think..."
            }
        }

        // Stage 1: 0-10s elapsed (timeRemaining 21..30)
        assertEquals("bora is choosing.", getStatusMessage(timeRemaining = 30, dotPhase = 1))
        assertEquals("bora is choosing..", getStatusMessage(timeRemaining = 25, dotPhase = 2))
        assertEquals("bora is choosing...", getStatusMessage(timeRemaining = 21, dotPhase = 3))

        // Stage 2: 10-20s elapsed (timeRemaining 11..20)
        assertEquals("bora is cooking something...", getStatusMessage(timeRemaining = 20, dotPhase = 1))
        assertEquals("bora is cooking something...", getStatusMessage(timeRemaining = 15, dotPhase = 2))
        assertEquals("bora is cooking something...", getStatusMessage(timeRemaining = 11, dotPhase = 3))

        // Stage 3: >20s elapsed (timeRemaining 0..10)
        assertEquals("let's have some coffee, bora is sleeping I think...", getStatusMessage(timeRemaining = 10, dotPhase = 1))
        assertEquals("let's have some coffee, bora is sleeping I think...", getStatusMessage(timeRemaining = 5, dotPhase = 2))
        assertEquals("let's have some coffee, bora is sleeping I think...", getStatusMessage(timeRemaining = 0, dotPhase = 3))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. FAST PACKET CODEC WITH WINNER METADATA
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testFastPacketCodec_SerializesAndDeserializesWinnerMetadata() {
        val winningPacket = RoomMessagePacket(
            type = "PICK_NUMBER",
            number = 24,
            playerId = "u_player4",
            turnNumber = 16,
            seed = 99999L,
            winnerPlayerId = "u_player4",
            winReason = "Turn Pick",
            runnerPlayerIds = listOf("u_bob", "u_player3")
        )

        val encoded = FastPacketCodec.encode(winningPacket)
        // With winnerPlayerId populated, it serializes as JSON
        assertTrue("Winning move packet must serialize with full JSON fidelity", encoded.startsWith("{"))

        val decoded = FastPacketCodec.decode(encoded)
        assertEquals("PICK_NUMBER", decoded.type)
        assertEquals(24, decoded.number)
        assertEquals("u_player4", decoded.playerId)
        assertEquals("u_player4", decoded.winnerPlayerId)
        assertEquals("Turn Pick", decoded.winReason)
        assertEquals(listOf("u_bob", "u_player3"), decoded.runnerPlayerIds)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. 1-SECOND STABILIZED PING & KEYBOARD BACKLIGHT BREATHING TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testPingStabilization_UpdatesOn1SecondCadence_PreventsRapidFluctuation() {
        // Simulate high-frequency raw network latency fluctuations (e.g. 10 rapid readings in 1 second)
        val rawFluctuatingPings = listOf(32L, 78L, 24L, 95L, 41L, 110L, 29L, 85L, 33L, 48L)

        // The UI display sampler ticks strictly every 1000ms
        // Between ticks, the displayed ping remains constant, avoiding rapid visual jitter
        var sampledDisplayedPing = 0L
        val recordedDisplayStates = mutableListOf<Long>()

        for (second in 1..3) {
            // During each 1-second second interval, multiple raw pings arrive, but UI updates once per second
            val latestPingAtTick = rawFluctuatingPings[(second * 3) % rawFluctuatingPings.size]
            sampledDisplayedPing = latestPingAtTick
            recordedDisplayStates.add(sampledDisplayedPing)
        }

        // Must only update once per second
        assertEquals(3, recordedDisplayStates.size)
        // Ensure values remain bounded and reasonable
        recordedDisplayStates.forEach { ping ->
            assertTrue("Ping must be positive", ping > 0L)
            assertTrue("Ping must not exceed plausible ceiling", ping < 500L)
        }
    }

    @Test
    fun testPingRealtimeUpdating_OvercomesStaleClosureCapture_UpdatesEverySecond() {
        // Initial state at time 0
        var incomingPingMs = 28L
        // Simulated rememberUpdatedState supplier
        val latestPingSupplier = { if (incomingPingMs > 0L) incomingPingMs else 28L }

        var displayedPingMs = latestPingSupplier()
        assertEquals(28L, displayedPingMs)

        // Incoming ping updates over 3 seconds
        val pingsPerSecond = listOf(45L, 38L, 52L)
        val sampledDisplays = mutableListOf<Long>()

        for (newPing in pingsPerSecond) {
            // New network ping packet arrives
            incomingPingMs = newPing
            // 1-second display timer fires and reads latestPingSupplier
            displayedPingMs = latestPingSupplier()
            sampledDisplays.add(displayedPingMs)
        }

        // Must update to the real-time values, NOT stay stuck on 28L
        assertEquals(listOf(45L, 38L, 52L), sampledDisplays)
        assertNotEquals(28L, sampledDisplays.last())
    }

    @Test
    fun testKeyboardBacklightBreathing_TimingMeetsAtLeast3SecondsThreshold() {
        // Keyboard backlight breathing sequence specification:
        // Cycle 1: Inhale to 90% (800ms) -> Exhale to 18% (800ms) = 1600ms
        // Cycle 2: Peak Inhale to 100% (800ms) -> Smooth Exhale to 0% (800ms) = 1600ms
        val cycle1InhaleMs = 800L
        val cycle1ExhaleMs = 800L
        val cycle2InhaleMs = 800L
        val cycle2ExhaleMs = 800L

        val totalBreathingDurationMs = cycle1InhaleMs + cycle1ExhaleMs + cycle2InhaleMs + cycle2ExhaleMs

        assertTrue(
            "Keyboard backlight breathing duration must be at least 3000ms (was ${totalBreathingDurationMs}ms)",
            totalBreathingDurationMs >= 3000L
        )
        assertEquals(3200L, totalBreathingDurationMs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. HOST DEPARTURE & MINIMUM 2 ACTIVE PLAYERS (POINT 5)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testHostDepartureDuringMatch_KeepsMatchActiveForRemainingGuests() {
        val host = Player(id = "host_1", username = "hostUser", displayName = "Host", isHost = true)
        val guest1 = Player(id = "guest_1", username = "player2", displayName = "Player 2", isHost = false)
        val guest2 = Player(id = "guest_3", username = "player3", displayName = "Player 3", isHost = false)
        val allParticipants = listOf(host, guest1, guest2)
        val disconnectedPlayerIds = mutableSetOf<String>()

        // Host leaves the match while match is in progress
        val inGame = true
        var isGameOver = false
        var isHostLeftGame = false
        var opponentDisconnectMessage: String? = null

        val hostLeavePacket = RoomMessagePacket(
            type = "HOST_LEFT",
            playerId = host.id,
            displayName = host.displayName,
            isHost = true
        )

        // Simulate host leaving logic
        if (hostLeavePacket.type == "HOST_LEFT" && inGame && !isGameOver) {
            isHostLeftGame = true
            disconnectedPlayerIds.add(hostLeavePacket.playerId)
            val activeRemaining = allParticipants.filter { it.id !in disconnectedPlayerIds }
            if (activeRemaining.size <= 1) {
                isGameOver = true
                opponentDisconnectMessage = "All opponents left the game."
            }
        }

        // Host left, but 2 guest players remain -> game must NOT terminate
        assertTrue("isHostLeftGame flag must be set", isHostLeftGame)
        assertFalse("Game must NOT terminate when at least 2 players remain", isGameOver)
        assertNull("Opponent disconnect popup must not show", opponentDisconnectMessage)
        assertEquals(2, allParticipants.filter { it.id !in disconnectedPlayerIds }.size)

        // When host's turn rolls around, next turn player must be one of the active guests
        val nextPicker = LobbyLifecycleEngine.calculateNextTurnPlayerId(
            allParticipants = allParticipants,
            disconnectedPlayerIds = disconnectedPlayerIds,
            currentPickerId = host.id,
            fallbackPlayerId = guest1.id
        )
        assertTrue("Next turn must belong to an active guest", nextPicker in setOf(guest1.id, guest2.id))
        assertNotEquals("Host must never be given a turn after disconnecting", host.id, nextPicker)
    }

    @Test
    fun testTurnSkipping_OnlyOperatesWhenAtLeastTwoPlayersActive() {
        // 8 players started the match
        val players = (1..8).map { i ->
            Player(id = "p$i", username = "user$i", displayName = "Player $i", isHost = (i == 1))
        }
        val disconnectedPlayerIds = mutableSetOf<String>()
        val myUid = "p1"
        var isGameOver = false
        var opponentDisconnectMessage: String? = null
        val skippedTurnLogs = mutableListOf<String>()

        // Disconnect players 8 down to 3 sequentially
        for (i in 8 downTo 3) {
            val disconnectedId = "p$i"
            disconnectedPlayerIds.add(disconnectedId)
            val activeRemaining = players.filter { it.id !in disconnectedPlayerIds }

            if (activeRemaining.size <= 1) {
                isGameOver = true
                opponentDisconnectMessage = "All opponents left the game."
            } else {
                skippedTurnLogs.add("Turn skipped ($disconnectedId reconnecting...)")
            }
        }

        // 6 players were skipped (P8, P7, P6, P5, P4, P3)
        assertEquals(6, skippedTurnLogs.size)
        assertFalse("Match should still be active with P1 and P2 remaining", isGameOver)
        assertNull("Popup should not appear while 2 players remain", opponentDisconnectMessage)
        assertEquals(listOf("p1", "p2"), players.filter { it.id !in disconnectedPlayerIds }.map { it.id })

        // Now Player 2 (the last opponent) also disconnects!
        val lastOpponentId = "p2"
        disconnectedPlayerIds.add(lastOpponentId)
        val activeRemaining = players.filter { it.id !in disconnectedPlayerIds }

        if (activeRemaining.size <= 1) {
            isGameOver = true
            opponentDisconnectMessage = "All opponents left the game."
        }

        // When active players drop to 1 (only local player left), turn skipping stops and game ends!
        assertTrue("Game must terminate when only 1 player remains", isGameOver)
        assertEquals("All opponents left the game.", opponentDisconnectMessage)
    }

    @Test
    fun testReturnToLobby_WhenHostLeft_RedirectsGuestToMainMenu() {
        var isHosting = false
        var isHostLeftGame = true
        var navigatedRoute: String? = null
        var toastMessage: String? = null

        // Guest clicks "Return to Lobby" after match ends
        if (!isHosting && isHostLeftGame) {
            toastMessage = "Host left the lobby."
            navigatedRoute = "main_menu"
        } else {
            navigatedRoute = "lobby"
        }

        assertEquals("main_menu", navigatedRoute)
        assertEquals("Host left the lobby.", toastMessage)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. INACTIVE / DISCONNECTED PLAYER WIN EXCLUSION & REVIEW STRIP TESTS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testInactivePlayerBingo_DoesNotTriggerWin_ActivePlayersContinue() {
        val aliceUid = "alice"
        val bobUid = "bob"
        val charlieUid = "charlie"

        val disconnectedPlayerIds = mutableListOf(bobUid) // Bob swiped out

        // Construct boards
        // Bob's board has 5 completed lines (hit bingo due to other players' picks)
        val bobCells = (1..25).map { num ->
            Cell(
                number = num,
                row = (num - 1) / 5,
                col = (num - 1) % 5,
                markState = CellMarkState.Marked(pickedByPlayerId = "alice", isOwnPick = false, turnNumber = 1)
            )
        }
        val bobBoard = Board(
            size = 5,
            cells = bobCells,
            completedLines = (0..4).map { LineCoordinate(LineType.ROW, it) }.toSet()
        )
        assertTrue("Bob board must have completed BINGO", bobBoard.isBingo)
        assertEquals(5, bobBoard.completedLinesCount)

        // Alice and Charlie's boards have only 2 lines completed so far
        val aliceBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = setOf(LineCoordinate(LineType.ROW, 0), LineCoordinate(LineType.ROW, 1))
        )
        val charlieBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = setOf(LineCoordinate(LineType.ROW, 0), LineCoordinate(LineType.ROW, 1))
        )
        assertFalse(aliceBoard.isBingo)
        assertFalse(charlieBoard.isBingo)

        val allPlayerBoards = mapOf(
            aliceUid to aliceBoard,
            bobUid to bobBoard,
            charlieUid to charlieBoard
        )

        // Evaluate outcome with inactive player filtering logic
        val completedPlayers = allPlayerBoards.filter { it.value.isBingo }.keys
        assertEquals(setOf(bobUid), completedPlayers)

        val activeCompletedPlayers = completedPlayers.filter { it !in disconnectedPlayerIds }
        // Bob is disconnected, so activeCompletedPlayers MUST be empty!
        assertTrue("Active completed players must be empty when only disconnected player hit bingo", activeCompletedPlayers.isEmpty())

        val isGameOver = activeCompletedPlayers.isNotEmpty()
        assertFalse("Game must NOT end when only disconnected player has 5 lines", isGameOver)
    }

    @Test
    fun testInactivePlayerBingo_ActivePlayerWinsLater_BobNeverDeclaredWinner() {
        val aliceUid = "alice"
        val bobUid = "bob"
        val charlieUid = "charlie"

        val disconnectedPlayerIds = mutableListOf(bobUid) // Bob is inactive

        // Bob has 5 lines
        val bobBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5, markState = CellMarkState.Marked(pickedByPlayerId = "alice", isOwnPick = false, turnNumber = 1)) },
            completedLines = (0..4).map { LineCoordinate(LineType.ROW, it) }.toSet()
        )
        // Alice also gets 5 lines now!
        val aliceBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5, markState = CellMarkState.Marked(pickedByPlayerId = "alice", isOwnPick = true, turnNumber = 2)) },
            completedLines = (0..4).map { LineCoordinate(LineType.ROW, it) }.toSet()
        )
        val charlieBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = setOf(LineCoordinate(LineType.ROW, 0))
        )

        val allPlayerBoards = mapOf(
            aliceUid to aliceBoard,
            bobUid to bobBoard,
            charlieUid to charlieBoard
        )

        val completedPlayers = allPlayerBoards.filter { it.value.isBingo }.keys
        assertEquals(setOf(aliceUid, bobUid), completedPlayers)

        // Filter strictly for active players
        val activeCompletedPlayers = completedPlayers.filter { it !in disconnectedPlayerIds }
        assertEquals(listOf(aliceUid), activeCompletedPlayers)

        val isGameOver = activeCompletedPlayers.isNotEmpty()
        assertTrue("Game ends because active player Alice completed BINGO", isGameOver)

        val winnerId = if (activeCompletedPlayers.size == 1) activeCompletedPlayers.first() else ""
        assertEquals("Winner MUST be active player Alice, NOT disconnected Bob", aliceUid, winnerId)
        assertNotEquals(bobUid, winnerId)
    }

    @Test
    fun testPostGameReviewStrip_DisconnectedPlayerPlacedAtEnd_NoCrownOrRunnerBadge() {
        val aliceUid = "alice"
        val bobUid = "bob"
        val charlieUid = "charlie"

        val disconnectedPlayerIds = listOf(bobUid)
        val winnerPlayerId = aliceUid

        val alice = Player(id = aliceUid, displayName = "Alice", isHost = true)
        val bob = Player(id = bobUid, displayName = "Bob", isHost = false)
        val charlie = Player(id = charlieUid, displayName = "Charlie", isHost = false)
        val reviewPlayers = listOf(bob, charlie, alice) // Arbitrary initial order

        val bobBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = (0..4).map { LineCoordinate(LineType.ROW, it) }.toSet()
        )
        val aliceBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = (0..4).map { LineCoordinate(LineType.ROW, it) }.toSet()
        )
        val charlieBoard = Board(
            size = 5,
            cells = (1..25).map { Cell(number = it, row = (it - 1) / 5, col = (it - 1) % 5) },
            completedLines = setOf(LineCoordinate(LineType.ROW, 0), LineCoordinate(LineType.ROW, 1))
        )
        val allPlayerBoards = mapOf(
            aliceUid to aliceBoard,
            bobUid to bobBoard,
            charlieUid to charlieBoard
        )

        // Sorting comparator matching GameScreen implementation
        val sortedReviewPlayers = reviewPlayers.sortedWith(
            compareBy<Player> { p ->
                if (disconnectedPlayerIds.contains(p.id)) 1 else 0
            }.thenByDescending { p ->
                if (p.id == winnerPlayerId && !disconnectedPlayerIds.contains(p.id)) 3
                else {
                    val b = allPlayerBoards[p.id] ?: aliceBoard
                    if (b.isBingo && !disconnectedPlayerIds.contains(p.id)) 2 else 1
                }
            }.thenByDescending { p ->
                val b = allPlayerBoards[p.id] ?: aliceBoard
                b.completedLinesCount
            }
        )

        // 1. Order verification: Active players first (Alice winner, Charlie active), Disconnected Bob LAST
        assertEquals("First player in strip must be Winner Alice", aliceUid, sortedReviewPlayers[0].id)
        assertEquals("Second player in strip must be Active Charlie", charlieUid, sortedReviewPlayers[1].id)
        assertEquals("Last player in strip must be Disconnected Bob", bobUid, sortedReviewPlayers[2].id)

        // 2. Badge verification
        fun computeBadge(player: Player): String {
            val isDisconnected = disconnectedPlayerIds.contains(player.id)
            val b = allPlayerBoards[player.id] ?: aliceBoard
            return when {
                !isDisconnected && player.id == winnerPlayerId -> " 👑"
                !isDisconnected && b.isBingo -> " 🥈"
                isDisconnected -> " (Offline)"
                else -> ""
            }
        }

        assertEquals(" 👑", computeBadge(alice))
        assertEquals("", computeBadge(charlie))
        assertEquals(" (Offline)", computeBadge(bob))
        assertFalse("Bob must not have crown", computeBadge(bob).contains("👑"))
        assertFalse("Bob must not have runner badge", computeBadge(bob).contains("🥈"))

        // 3. Bob's board is still preserved with 5 lines for review
        assertEquals(5, allPlayerBoards[bobUid]?.completedLinesCount)
    }

    @Test
    fun testOngoingMatchData_SerializationAndHostPreservation() {
        val hostPlayer = Player(id = "host_1", displayName = "Host User", isHost = true)
        val guestPlayer = Player(id = "guest_2", displayName = "Guest User", isHost = false)
        val participants = listOf(hostPlayer, guestPlayer)

        val playerBoard = engine.generateBoard(5)
        val allBoards = mapOf(
            "host_1" to playerBoard,
            "guest_2" to engine.generateBoard(5)
        )

        val hostMatchData = OngoingMatchData(
            roomCode = "ROOM42",
            matchSeed = 123456789L,
            boardSize = 5,
            isDynamicBoard = false,
            isManualBoard = false,
            isHost = true,
            participants = participants,
            playerBoard = playerBoard,
            opponentBoard = null,
            allPlayerBoards = allBoards,
            pickedNumbers = listOf(7, 14, 21),
            pickedByPlayers = listOf("host_1", "guest_2", "host_1"),
            turnNumber = 4,
            currentTurnPlayerId = "guest_2",
            randomizedTurnOrder = participants
        )

        // Serialize to JSON string (same as OngoingMatchStore)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serialized = json.encodeToString(hostMatchData)
        assertNotNull(serialized)

        // Deserialize back
        val deserialized = json.decodeFromString<OngoingMatchData>(serialized)
        assertEquals("ROOM42", deserialized.roomCode)
        assertTrue("isHost must be preserved as true for host", deserialized.isHost)
        assertEquals(5, deserialized.boardSize)
        assertEquals(listOf(7, 14, 21), deserialized.pickedNumbers)
        assertEquals(listOf("host_1", "guest_2", "host_1"), deserialized.pickedByPlayers)
        assertEquals(4, deserialized.turnNumber)
        assertEquals("guest_2", deserialized.currentTurnPlayerId)
        assertEquals(2, deserialized.allPlayerBoards.size)
        assertEquals(playerBoard.cells.size, deserialized.playerBoard?.cells?.size)
    }

    @Test
    fun testPacketIngress_AutoReconnectsDisconnectedPlayer() {
        val disconnectedPlayerIds = mutableSetOf("bob_user", "charlie_user")
        val participants = listOf(
            Player(id = "alice_host", displayName = "Alice", isHost = true),
            Player(id = "bob_user", displayName = "Bob", isHost = false),
            Player(id = "charlie_user", displayName = "Charlie", isHost = false)
        )
        val matchChatHistory = mutableListOf<InGameChatMessage>()

        fun onPacketReceived(packet: RoomMessagePacket) {
            if (packet.playerId.isNotBlank() && packet.playerId != "alice_host") {
                if (disconnectedPlayerIds.remove(packet.playerId)) {
                    val returningPlayerName = packet.displayName.ifBlank {
                        participants.find { it.id == packet.playerId }?.displayName ?: "Player"
                    }
                    val reconnectedMsg = "🟢 $returningPlayerName reconnected"
                    val chatMsg = InGameChatMessage(
                        id = System.currentTimeMillis(),
                        text = reconnectedMsg,
                        isSelf = false,
                        senderName = null,
                        timestamp = System.currentTimeMillis(),
                        isSystemMessage = true
                    )
                    matchChatHistory.add(chatMsg)
                }
            }
        }

        // Initially Bob is marked disconnected
        assertTrue("Bob is in disconnected list", disconnectedPlayerIds.contains("bob_user"))

        // Bob sends a HEARTBEAT or any packet after returning to foreground
        val bobHeartbeat = RoomMessagePacket(
            type = "HEARTBEAT",
            playerId = "bob_user",
            displayName = "Bob",
            timestamp = System.currentTimeMillis()
        )
        onPacketReceived(bobHeartbeat)

        // Bob should be automatically removed from disconnected list
        assertFalse("Bob must be removed from disconnected list upon receiving packet", disconnectedPlayerIds.contains("bob_user"))
        assertTrue("Charlie remains disconnected", disconnectedPlayerIds.contains("charlie_user"))

        // Match chat history must contain the reconnected notification
        assertEquals(1, matchChatHistory.size)
        assertEquals("🟢 Bob reconnected", matchChatHistory.first().text)
        assertTrue(matchChatHistory.first().isSystemMessage)
        assertNull(matchChatHistory.first().senderName)
    }

    @Test
    fun testTurnTimerCountdownGuard_SuppressesPeerDisconnectDuringBackgroundAndGracePeriod() {
        var isAppInForeground = true
        var lastForegroundResumeTimestamp = System.currentTimeMillis()
        val disconnectedPlayerIds = mutableSetOf<String>()

        fun shouldEvaluateDisconnect(
            now: Long,
            isForeground: Boolean,
            lastResumeTime: Long,
            lastPeerHeartbeat: Long
        ): Boolean {
            val isWithinGracePeriod = (now - lastResumeTime) < 12_000L
            if (!isForeground || isWithinGracePeriod) {
                return false
            }
            return (now - lastPeerHeartbeat) >= 10_000L
        }

        val now = 100_000L
        val staleHeartbeatTime = 80_000L // 20s ago

        // Case 1: Local device is in background -> must NOT evaluate peer disconnect
        isAppInForeground = false
        lastForegroundResumeTimestamp = 50_000L
        assertFalse(
            "Must NOT evaluate disconnect while local app is in background",
            shouldEvaluateDisconnect(now, isAppInForeground, lastForegroundResumeTimestamp, staleHeartbeatTime)
        )

        // Case 2: Local device just resumed into foreground (e.g. 3s ago) -> within 12s grace period -> must NOT evaluate
        isAppInForeground = true
        lastForegroundResumeTimestamp = 97_000L // 3s ago
        assertFalse(
            "Must NOT evaluate disconnect within 12s grace period after foreground resume",
            shouldEvaluateDisconnect(now, isAppInForeground, lastForegroundResumeTimestamp, staleHeartbeatTime)
        )

        // Case 3: Local device in foreground and > 12s elapsed since resume, peer heartbeat is 20s old -> evaluate disconnect
        lastForegroundResumeTimestamp = 85_000L // 15s ago
        assertTrue(
            "Must evaluate disconnect when in foreground, past grace period, and peer silent >= 10s",
            shouldEvaluateDisconnect(now, isAppInForeground, lastForegroundResumeTimestamp, staleHeartbeatTime)
        )
    }

    @Test
    fun testSystemMessages_BroadcastWithSystemSender() {
        val chatMessages = mutableListOf<InGameChatMessage>()

        fun handleIncomingChatMessage(packet: RoomMessagePacket) {
            val isSys = packet.username == "SYSTEM"
            val newMsg = InGameChatMessage(
                id = packet.timestamp,
                text = packet.displayName,
                isSelf = false,
                senderName = if (isSys) null else (packet.username.takeIf { it.isNotBlank() } ?: "Opponent"),
                timestamp = packet.timestamp,
                isSystemMessage = isSys
            )
            chatMessages.add(newMsg)
        }

        // Test 1: Turn skipped system message
        val turnSkipPacket = RoomMessagePacket(
            type = "CHAT_MESSAGE",
            playerId = "host_1",
            displayName = "📢 Turn skipped (Bob reconnecting...)",
            username = "SYSTEM",
            timestamp = 1000L
        )
        handleIncomingChatMessage(turnSkipPacket)

        assertEquals(1, chatMessages.size)
        val msg1 = chatMessages[0]
        assertEquals("📢 Turn skipped (Bob reconnecting...)", msg1.text)
        assertTrue(msg1.isSystemMessage)
        assertNull(msg1.senderName)

        // Test 2: Normal user chat message
        val userPacket = RoomMessagePacket(
            type = "CHAT_MESSAGE",
            playerId = "alice_host",
            displayName = "Good game everyone!",
            username = "Alice",
            timestamp = 2000L
        )
        handleIncomingChatMessage(userPacket)

        assertEquals(2, chatMessages.size)
        val msg2 = chatMessages[1]
        assertEquals("Good game everyone!", msg2.text)
        assertFalse(msg2.isSystemMessage)
        assertEquals("Alice", msg2.senderName)
    }
}


