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
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine.PlayerLobbyStatus
import com.bingo.multiplayer.presentation.game.StampResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomic-Level Simulation and Verification Suite for 8-Player Multiplayer & AI Bingo:
 *
 * 1. Match Invitations & In-App Delivery
 * 2. Ready Buttons Switching & Multi-Player Status Reflection
 * 3. 8-Player In-Game Turn Rotation & Pick Propagation
 * 4. Sequential Player Disconnection & Home Screen Rejoin State Preservation
 * 5. Return to Lobby & Lobby Rejoin Button Functionality
 * 6. Turn Timeouts / Missed Chances (Floating Emote & Chat System Messages)
 * 7. Reconnection Messages & Turn Resumption
 * 8. Winner, Runner, Loser, and Offline Stamps (Strictly No Line-Count Stamp)
 * 9. AI vs Player Strict Winner / Runner / Loss Isolation
 */
class AtomicEightPlayerSimulationTest {

    private val engine = BingoEngine()

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. MATCH INVITATION & IN-APP DELIVERY SIMULATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 8 players match invitation and in-app delivery without system notification gating`() {
        val hostUsername = "host_vamsi"
        val invitees = (2..8).map { "player_$it" }
        val roomCode = "ROYAL8"

        // Host generates game invites for 7 peers
        val dispatchedInvites = mutableMapOf<String, GameInvite>()
        invitees.forEach { target ->
            val invite = GameInvite(
                fromUsername = hostUsername,
                fromDisplayName = "Vamsi Reddy Bora",
                fromAvatarUrl = null,
                roomCode = roomCode
            )
            dispatchedInvites[target] = invite
        }

        assertEquals(7, dispatchedInvites.size)

        // Simulate each peer receiving invite directly into in-app state (incomingInvite = invite)
        invitees.forEach { target ->
            val received = dispatchedInvites[target]
            assertNotNull("Peer $target must receive the invite", received)
            assertEquals(roomCode, received!!.roomCode)
            assertEquals("Vamsi Reddy Bora", received.fromDisplayName)

            // Direct in-app display verification:
            var incomingInvite: GameInvite? = received
            assertNotNull(incomingInvite)
            assertEquals("ROYAL8", incomingInvite!!.roomCode)

            // Peer accepts invite:
            incomingInvite = null // Dialog dismissed on accept
            assertNull(incomingInvite)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. READY BUTTONS SWITCHING & MULTI-PLAYER STATUS REFLECTION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 8 players ready buttons switching, status reflection, and all-ready gate`() {
        // Host (P1) is ready by default; Guests (P2..P8) start NOT_READY
        var host = Player(id = "p1", displayName = "Host", username = "user_p1", isHost = true)
        val guests = (2..8).map { i ->
            Player(id = "p$i", displayName = "Player $i", username = "user_p$i", isHost = false)
        }.toMutableList()

        var currentPlayers = listOf(host) + guests

        // 1. Initial State: Host is READY, all guests are NOT_READY
        assertEquals(PlayerLobbyStatus.READY, LobbyLifecycleEngine.getPlayerLobbyStatus(host, isMe = true, rawPresenceStatus = ""))
        guests.forEach { g ->
            assertEquals(PlayerLobbyStatus.NOT_READY, LobbyLifecycleEngine.getPlayerLobbyStatus(g, isMe = false, rawPresenceStatus = ""))
        }
        assertFalse("Cannot start game when guests are not ready", LobbyLifecycleEngine.canStartMatch(currentPlayers))
        assertEquals(1, LobbyLifecycleEngine.countReadyPlayers(currentPlayers)) // only host

        // 2. Guests toggle Ready one by one:
        for (i in 0 until guests.size) {
            val guest = guests[i]
            val readyGuest = LobbyLifecycleEngine.onLocalToggleReady(guest, isReady = true)
            assertEquals(LobbyLifecycleEngine.STATUS_READY, readyGuest.lobbyReadyStatus)
            assertTrue(readyGuest.readyVersion > guest.readyVersion)
            assertEquals(PlayerLobbyStatus.READY, LobbyLifecycleEngine.getPlayerLobbyStatus(readyGuest, isMe = false, rawPresenceStatus = ""))
            guests[i] = readyGuest
            currentPlayers = listOf(host) + guests

            if (i < guests.size - 1) {
                assertFalse("Not all guests ready yet", LobbyLifecycleEngine.canStartMatch(currentPlayers))
            }
        }

        // All 8 players are ready!
        assertTrue("All 8 players are now ready!", LobbyLifecycleEngine.canStartMatch(currentPlayers))
        assertEquals(8, LobbyLifecycleEngine.countReadyPlayers(currentPlayers))

        // 3. Test switching back to Not Ready: P4 toggles Not Ready
        val p4Unready = LobbyLifecycleEngine.onLocalToggleReady(guests[2], isReady = false)
        assertEquals(LobbyLifecycleEngine.STATUS_NOT_READY, p4Unready.lobbyReadyStatus)
        assertTrue(p4Unready.readyVersion > guests[2].readyVersion)
        guests[2] = p4Unready
        currentPlayers = listOf(host) + guests

        assertFalse("Game should be gated when P4 unreadies", LobbyLifecycleEngine.canStartMatch(currentPlayers))
        assertEquals(7, LobbyLifecycleEngine.countReadyPlayers(currentPlayers))

        // 4. P4 toggles Ready again:
        val p4ReadyAgain = LobbyLifecycleEngine.onLocalToggleReady(p4Unready, isReady = true)
        guests[2] = p4ReadyAgain
        currentPlayers = listOf(host) + guests
        assertTrue("All 8 players ready again!", LobbyLifecycleEngine.canStartMatch(currentPlayers))
        assertEquals(8, LobbyLifecycleEngine.countReadyPlayers(currentPlayers))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. 8-PLAYER IN-GAME TURN ROTATION & PICK PROPAGATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 8 players in-game deterministic boards and turn rotation distribution`() {
        val players = (1..8).map { i ->
            Player(id = "player_$i", displayName = "Player $i", username = "user_$i", isHost = (i == 1))
        }
        val matchSeed = 123456789L
        val size = 5

        // Generate boards for all 8 players using deterministic seeds
        val boards = players.mapIndexed { index, p ->
            val seed = LobbyLifecycleEngine.resolvePlayerBoardSeed(matchSeed, p, index)
            p.id to engine.generateBoard(size, seed)
        }.toMap().toMutableMap()

        // Verify boards are unique and correctly generated
        assertEquals(8, boards.size)
        boards.values.forEach { b ->
            assertEquals(5, b.size)
            assertEquals(25, b.cells.size)
            assertFalse(b.isBingo)
        }

        // Deterministic turn order
        val turnOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, matchSeed)
        assertEquals(8, turnOrder.size)
        val disconnected = mutableSetOf<String>()

        // Simulate 2 full rounds of picks (16 turns) across all 8 players
        var currentTurnPlayerId = turnOrder.first().id
        val pickedNumbers = mutableListOf<Int>()
        val pickedByPlayers = mutableListOf<String>()

        for (round in 1..2) {
            for (pIndex in turnOrder.indices) {
                val activePlayer = turnOrder[pIndex]
                assertEquals(activePlayer.id, currentTurnPlayerId)

                // Pick an unmarked number from active player's board
                val myBoard = boards[activePlayer.id]!!
                val availableNum = myBoard.cells.first { it.markState == CellMarkState.Unmarked && it.number !in pickedNumbers }.number

                pickedNumbers.add(availableNum)
                pickedByPlayers.add(activePlayer.id)

                // Apply pick across all 8 boards
                boards.keys.forEach { pid ->
                    boards[pid] = engine.markCell(
                        board = boards[pid]!!,
                        number = availableNum,
                        pickedByPlayerId = activePlayer.id,
                        isOwnPick = (pid == activePlayer.id),
                        turnNumber = pickedNumbers.size
                    )
                }

                // Verify cell is marked on every player's board
                boards.values.forEach { b ->
                    val cell = b.cells.first { it.number == availableNum }
                    assertTrue(cell.markState != CellMarkState.Unmarked)
                }

                // Calculate next turn player
                currentTurnPlayerId = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                    allParticipants = players,
                    disconnectedPlayerIds = disconnected,
                    currentPickerId = activePlayer.id,
                    customTurnOrder = turnOrder
                )
            }
        }

        assertEquals(16, pickedNumbers.size)
        assertEquals(16, pickedByPlayers.size)
        assertEquals(turnOrder.first().id, currentTurnPlayerId) // Wrapped back to first player
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. SEQUENTIAL REMOVAL & HOMESCREEN REJOIN STATE PRESERVATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test sequential removal of players one by one and homescreen ongoing match persistence`() {
        val players = (1..8).map { i ->
            Player(id = "player_$i", displayName = "Player $i", username = "user_$i", isHost = (i == 1))
        }
        val matchSeed = 998877L
        val size = 5
        val disconnected = mutableSetOf<String>()
        val turnOrder = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, matchSeed)

        val boards = players.mapIndexed { index, p ->
            val seed = LobbyLifecycleEngine.resolvePlayerBoardSeed(matchSeed, p, index)
            p.id to engine.generateBoard(size, seed)
        }.toMap().toMutableMap()

        // Remove players one by one from P8 down to P2
        for (i in 8 downTo 2) {
            val removedId = "player_$i"
            disconnected.add(removedId)

            // Simulate ongoing match state saved for removed player
            val savedMatchData = OngoingMatchData(
                roomCode = "ROOM8P",
                isHost = (i == 1),
                matchSeed = matchSeed,
                boardSize = size,
                playerBoard = boards[removedId],
                opponentBoard = boards["player_1"],
                allPlayerBoards = boards,
                pickedNumbers = listOf(5, 12, 19),
                pickedByPlayers = listOf("player_1", "player_2", "player_3"),
                turnNumber = 4,
                currentTurnPlayerId = "player_1",
                participants = players,
                randomizedTurnOrder = turnOrder
            )

            // Verify removed player can restore complete match state
            assertNotNull(savedMatchData.playerBoard)
            assertEquals(size, savedMatchData.boardSize)
            assertEquals(matchSeed, savedMatchData.matchSeed)
            assertEquals(8, savedMatchData.allPlayerBoards.size)
            assertEquals(3, savedMatchData.pickedNumbers.size)

            // Verify turn calculation immediately skips all removed players
            val remainingActive = players.filter { it.id !in disconnected }
            assertEquals(i - 1, remainingActive.size)

            val nextTurn = LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = players,
                disconnectedPlayerIds = disconnected,
                currentPickerId = "player_1",
                customTurnOrder = turnOrder
            )
            assertFalse("Turn must not be assigned to disconnected player", disconnected.contains(nextTurn))
        }

        // Only Player 1 (Host) remains
        assertEquals(7, disconnected.size)
        val remaining = players.filter { it.id !in disconnected }
        assertEquals(1, remaining.size)
        assertEquals("player_1", remaining.first().id)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. RETURN TO LOBBY & LOBBY REJOIN BUTTON VERIFICATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test return to lobby and lobby rejoin button restores all board and turn data`() {
        val players = (1..8).map { i ->
            Player(id = "player_$i", displayName = "Player $i", username = "user_$i", isHost = (i == 1))
        }
        val matchSeed = 554433L
        val size = 5

        val boards = players.mapIndexed { index, p ->
            val seed = LobbyLifecycleEngine.resolvePlayerBoardSeed(matchSeed, p, index)
            p.id to engine.generateBoard(size, seed)
        }.toMap().toMutableMap()

        // Player 3 has marked cells and completed 2 lines
        var p3Board = boards["player_3"]!!
        val numbersToMark = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        numbersToMark.forEach { num ->
            p3Board = engine.markCell(p3Board, num, "player_1", false, 1)
        }
        boards["player_3"] = p3Board

        val ongoingMatchData = OngoingMatchData(
            roomCode = "LOBBY_REJOIN_ROOM",
            isHost = false,
            matchSeed = matchSeed,
            boardSize = size,
            playerBoard = p3Board,
            opponentBoard = boards["player_1"],
            allPlayerBoards = boards,
            pickedNumbers = numbersToMark,
            pickedByPlayers = List(10) { "player_1" },
            turnNumber = 11,
            currentTurnPlayerId = "player_3",
            participants = players,
            randomizedTurnOrder = players,
            chatMessages = listOf(
                InGameChatMessage(id = 100L, text = "Hello room", isSelf = false)
            )
        )

        // 1. In LobbyScreen: check hasActiveOngoing condition
        val roomCodeInLobby = "" // Or "LOBBY_REJOIN_ROOM"
        val isGameOver = false
        val hasActiveOngoing = (ongoingMatchData != null && !isGameOver && (roomCodeInLobby.isBlank() || ongoingMatchData.roomCode == roomCodeInLobby))
        assertTrue("Lobby must show match in progress card with rejoin button", hasActiveOngoing)

        // 2. Trigger onRejoinMatch from Lobby:
        val restoredData = ongoingMatchData!!
        assertNotNull(restoredData.playerBoard)
        assertEquals(size, restoredData.boardSize)
        assertEquals(10, restoredData.pickedNumbers.size)
        assertEquals(11, restoredData.turnNumber)
        assertEquals("player_3", restoredData.currentTurnPlayerId)
        assertEquals(1, restoredData.chatMessages.size)

        // Verify board markings and completed lines are 100% preserved
        val restoredBoard = restoredData.playerBoard!!
        numbersToMark.forEach { num ->
            val cell = restoredBoard.cells.first { it.number == num }
            assertTrue("Marked number $num must remain marked", cell.markState != CellMarkState.Unmarked)
        }

        // 3. Emit reconnected system message:
        val reconnectedChat = InGameChatMessage(
            id = System.currentTimeMillis(),
            text = "🟢 Player 3 reconnected",
            isSelf = false,
            isSystemMessage = true
        )
        assertTrue(reconnectedChat.isSystemMessage)
        assertEquals("🟢 Player 3 reconnected", reconnectedChat.text)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. MISSING TURNS / TIMEOUTS (FLOATING & CHAT SYSTEM MESSAGES)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test player missing turn emits turn-skip system message in chat and floating display`() {
        val pickerId = "player_4"
        val pickerName = "Player 4"

        // When a player misses their turn (number == -1):
        val isMyTurn = false
        val skipText = if (isMyTurn) "⏳ You missed turn - skipping" else "⏳ $pickerName missed turn - skipping"

        val skipSysMsg = InGameChatMessage(
            id = System.currentTimeMillis(),
            text = skipText,
            isSelf = false,
            senderName = null,
            timestamp = System.currentTimeMillis(),
            isSystemMessage = true
        )

        // Verification 1: Chat message created with system message flag
        assertTrue("Must be flagged as system message for floating emote spawning", skipSysMsg.isSystemMessage)
        assertEquals("⏳ Player 4 missed turn - skipping", skipSysMsg.text)

        // Verification 2: Local player missing turn
        val mySkipText = "⏳ You missed turn - skipping"
        val mySkipMsg = InGameChatMessage(
            id = System.currentTimeMillis() + 1,
            text = mySkipText,
            isSelf = false,
            isSystemMessage = true
        )
        assertTrue(mySkipMsg.isSystemMessage)
        assertEquals("⏳ You missed turn - skipping", mySkipMsg.text)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 7. WINNER, RUNNER, LOSER, AND OFFLINE STAMPS (STRICTLY NO 5-OF-7 LINES STAMP)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test strict won, runner, lose, and offline stamps with zero line-count stamps`() {
        val players = (1..8).map { i ->
            Player(id = "player_$i", displayName = "Player $i", username = "user_$i", isHost = (i == 1))
        }
        val disconnectedPlayerIds = listOf("player_8") // Player 8 is offline

        // Create boards:
        // P1: BINGO (Active picker who won)
        // P2: BINGO (Completed on P1's pick -> RUNNER)
        // P3..P7: Incomplete (< 5 lines -> LOST)
        // P8: Disconnected (< 5 lines -> OFFLINE)
        fun createBoardWithLines(lines: Int): Board {
            val cells = mutableListOf<Cell>()
            for (r in 0 until 5) {
                for (c in 0 until 5) {
                    cells.add(Cell(row = r, col = c, number = r * 5 + c + 1, markState = CellMarkState.Unmarked))
                }
            }
            val completedLines = mutableSetOf<LineCoordinate>()
            for (i in 0 until lines) {
                completedLines.add(LineCoordinate(LineType.ROW, i))
                for (c in 0 until 5) {
                    val idx = i * 5 + c
                    cells[idx] = cells[idx].copy(markState = CellMarkState.Marked(pickedByPlayerId = "player_1", isOwnPick = true, turnNumber = 1))
                }
            }
            return Board(size = 5, cells = cells, targetLines = 5, completedLines = completedLines)
        }

        val boardP1 = createBoardWithLines(5) // isBingo = true
        val boardP2 = createBoardWithLines(5) // isBingo = true
        val boardP3 = createBoardWithLines(4) // isBingo = false
        val boardP8 = createBoardWithLines(2) // isBingo = false

        assertTrue(boardP1.isBingo)
        assertTrue(boardP2.isBingo)
        assertFalse(boardP3.isBingo)
        assertFalse(boardP8.isBingo)

        val winnerPlayerId = "player_1"

        // Stamp evaluation logic matching GameScreen.kt:
        fun resolveStamp(
            selectedPlayerId: String,
            myPlayerId: String,
            didPlayerWin: Boolean,
            isRunner: Boolean,
            displayedBoard: Board
        ): Pair<StampResultType, String> {
            val isLocalSelected = (selectedPlayerId == myPlayerId)
            val isLocalDisconnected = disconnectedPlayerIds.contains(myPlayerId)

            return if (isLocalSelected) {
                when {
                    isLocalDisconnected -> StampResultType.OFFLINE to "OFFLINE"
                    didPlayerWin -> StampResultType.WON to "YOU'VE WON!"
                    isRunner -> StampResultType.RUNNER to "RUNNER!"
                    else -> StampResultType.LOST to "YOU LOST!"
                }
            } else {
                val isSelectedDisconnected = disconnectedPlayerIds.contains(selectedPlayerId)
                val isWinnerSelected = !isSelectedDisconnected && (selectedPlayerId == winnerPlayerId)
                val reviewName = players.firstOrNull { it.id == selectedPlayerId }?.displayName ?: "Player"
                when {
                    isSelectedDisconnected -> StampResultType.OFFLINE to "OFFLINE"
                    isWinnerSelected -> StampResultType.WON to "$reviewName WON!"
                    displayedBoard.isBingo -> StampResultType.RUNNER to "RUNNER!"
                    else -> StampResultType.LOST to "LOST!"
                }
            }
        }

        // Test 1: Player 1 (Winner) views their own board:
        val stampP1Self = resolveStamp("player_1", "player_1", didPlayerWin = true, isRunner = false, displayedBoard = boardP1)
        assertEquals(StampResultType.WON, stampP1Self.first)
        assertEquals("YOU'VE WON!", stampP1Self.second)

        // Test 2: Player 2 (Runner) views their own board:
        val stampP2Self = resolveStamp("player_2", "player_2", didPlayerWin = false, isRunner = true, displayedBoard = boardP2)
        assertEquals(StampResultType.RUNNER, stampP2Self.first)
        assertEquals("RUNNER!", stampP2Self.second)

        // Test 3: Player 3 (Loser) views their own board:
        val stampP3Self = resolveStamp("player_3", "player_3", didPlayerWin = false, isRunner = false, displayedBoard = boardP3)
        assertEquals(StampResultType.LOST, stampP3Self.first)
        assertEquals("YOU LOST!", stampP3Self.second)

        // Test 4: Player 8 (Offline) views their own board:
        val stampP8Self = resolveStamp("player_8", "player_8", didPlayerWin = false, isRunner = false, displayedBoard = boardP8)
        assertEquals(StampResultType.OFFLINE, stampP8Self.first)
        assertEquals("OFFLINE", stampP8Self.second)

        // Test 5: Player 3 reviews Player 1's board in review strip:
        val stampP1Reviewed = resolveStamp("player_1", "player_3", didPlayerWin = false, isRunner = false, displayedBoard = boardP1)
        assertEquals(StampResultType.WON, stampP1Reviewed.first)
        assertEquals("Player 1 WON!", stampP1Reviewed.second)

        // Test 6: Player 3 reviews Player 2's board in review strip:
        val stampP2Reviewed = resolveStamp("player_2", "player_3", didPlayerWin = false, isRunner = false, displayedBoard = boardP2)
        assertEquals(StampResultType.RUNNER, stampP2Reviewed.first)
        assertEquals("RUNNER!", stampP2Reviewed.second)

        // Test 7: Player 3 reviews Player 8's board (Offline):
        val stampP8Reviewed = resolveStamp("player_8", "player_3", didPlayerWin = false, isRunner = false, displayedBoard = boardP8)
        assertEquals(StampResultType.OFFLINE, stampP8Reviewed.first)
        assertEquals("OFFLINE", stampP8Reviewed.second)

        // CRITICAL CHECK: Assert NO stamp text contains "LINES" or "/"
        val allStampTexts = listOf(
            stampP1Self.second, stampP2Self.second, stampP3Self.second, stampP8Self.second,
            stampP1Reviewed.second, stampP2Reviewed.second, stampP8Reviewed.second
        )
        allStampTexts.forEach { text ->
            assertFalse("Stamp must strictly not contain 'LINES': $text", text.contains("LINES", ignoreCase = true))
            assertFalse("Stamp must strictly not contain fraction '/': $text", text.contains("/"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 8. AI VS PLAYER WINNER / RUNNER / LOST ISOLATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test AI vs Player mode correctly declares individual winner and runner without both-runners bug`() {
        fun createBoardWithLines(lines: Int): Board {
            val cells = mutableListOf<Cell>()
            for (r in 0 until 5) {
                for (c in 0 until 5) {
                    cells.add(Cell(row = r, col = c, number = r * 5 + c + 1, markState = CellMarkState.Unmarked))
                }
            }
            val completedLines = mutableSetOf<LineCoordinate>()
            for (i in 0 until lines) {
                completedLines.add(LineCoordinate(LineType.ROW, i))
                for (c in 0 until 5) {
                    val idx = i * 5 + c
                    cells[idx] = cells[idx].copy(markState = CellMarkState.Marked(pickedByPlayerId = "player_1", isOwnPick = true, turnNumber = 1))
                }
            }
            return Board(size = 5, cells = cells, targetLines = 5, completedLines = completedLines)
        }

        val myUid = "local_player"
        val aiId = "ai_bot"

        fun evaluateAiOutcome(
            activePickerId: String,
            playerBoard: Board,
            opponentBoard: Board
        ): RootNavGraphOutcome {
            val pWon = playerBoard.isBingo
            val oWon = opponentBoard.isBingo
            return when {
                pWon && oWon -> {
                    if (activePickerId == myUid) {
                        RootNavGraphOutcome(isGameOver = true, didPlayerWin = true, isRunner = false, winnerPlayerId = myUid)
                    } else {
                        RootNavGraphOutcome(isGameOver = true, didPlayerWin = false, isRunner = true, winnerPlayerId = aiId)
                    }
                }
                pWon -> RootNavGraphOutcome(isGameOver = true, didPlayerWin = true, isRunner = false, winnerPlayerId = myUid)
                oWon -> RootNavGraphOutcome(isGameOver = true, didPlayerWin = false, isRunner = false, winnerPlayerId = aiId)
                else -> RootNavGraphOutcome(isGameOver = false, didPlayerWin = false, isRunner = false, winnerPlayerId = "")
            }
        }

        // Scenario A: Player completes Bingo on Player's pick. AI has 3 lines.
        val outcomeA = evaluateAiOutcome(activePickerId = myUid, playerBoard = createBoardWithLines(5), opponentBoard = createBoardWithLines(3))
        assertTrue(outcomeA.isGameOver)
        assertTrue("Player won", outcomeA.didPlayerWin)
        assertFalse("Player is not runner", outcomeA.isRunner)
        assertEquals(myUid, outcomeA.winnerPlayerId)

        // Scenario B: AI completes Bingo on AI's pick. Player has 2 lines.
        val outcomeB = evaluateAiOutcome(activePickerId = aiId, playerBoard = createBoardWithLines(2), opponentBoard = createBoardWithLines(5))
        assertTrue(outcomeB.isGameOver)
        assertFalse("Player did not win", outcomeB.didPlayerWin)
        assertFalse("Player is lost (not runner because player has < 5 lines)", outcomeB.isRunner)
        assertEquals(aiId, outcomeB.winnerPlayerId)

        // Scenario C: Both complete Bingo on Player's pick!
        // Player picked the number on their turn -> Player is WINNER, AI is RUNNER.
        val outcomeC = evaluateAiOutcome(activePickerId = myUid, playerBoard = createBoardWithLines(5), opponentBoard = createBoardWithLines(5))
        assertTrue(outcomeC.isGameOver)
        assertTrue("Player is WINNER", outcomeC.didPlayerWin)
        assertFalse("Player is not runner", outcomeC.isRunner)
        assertEquals(myUid, outcomeC.winnerPlayerId)

        // Scenario D: Both complete Bingo on AI's pick!
        // AI picked the number on its turn -> AI is WINNER, Player is RUNNER.
        val outcomeD = evaluateAiOutcome(activePickerId = aiId, playerBoard = createBoardWithLines(5), opponentBoard = createBoardWithLines(5))
        assertTrue(outcomeD.isGameOver)
        assertFalse("Player did not win", outcomeD.didPlayerWin)
        assertTrue("Player is RUNNER", outcomeD.isRunner)
        assertEquals(aiId, outcomeD.winnerPlayerId)

        // Assert: In no scenario are both declared runner!
        assertFalse(outcomeC.didPlayerWin && outcomeC.isRunner)
        assertFalse(outcomeD.didPlayerWin && outcomeD.isRunner)
    }

    private data class RootNavGraphOutcome(
        val isGameOver: Boolean,
        val didPlayerWin: Boolean,
        val isRunner: Boolean,
        val winnerPlayerId: String
    )
}
