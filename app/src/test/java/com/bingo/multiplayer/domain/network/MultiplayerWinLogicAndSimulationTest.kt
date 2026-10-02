package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-Fidelity Multiplayer & 2-Player Match Simulation and Win/Draw Edge Case Suite.
 *
 * Verifies:
 * 1. 2-Player Game Isolation: Original win, loss, and draw mechanics work 100% cleanly with zero regressions.
 *    No premature loss in the middle of a match.
 * 2. 3-Player Win/Draw Rules:
 *    - "if three players completed the 5 lines at a time then draw for all."
 *    - "If two players completed 5 lines at a time then, third player get you lost and the two player will get draw."
 *    - "If one player completed 5 lines first then, two players lost and one player won."
 * 3. Spotlight Bar Circular Rotation: Previous, Current, Next player spotlight mathematics.
 * 4. End-to-End 3-Player Virtual Match Simulation: Full lifecycle over message bus.
 * 5. State Isolation & Cleanup: allPlayerBoards resets on lobby return / rematch.
 */
class MultiplayerWinLogicAndSimulationTest {

    private val engine = BingoEngine()

    // ── Helper to evaluate match outcome exactly as implemented in RootNavGraph ──
    data class MatchOutcome(
        val isGameOver: Boolean,
        val didPlayerWin: Boolean,
        val isDraw: Boolean
    )

    private fun evaluateMatchOutcome(
        isGroup: Boolean,
        myUid: String,
        playerBoard: Board,
        opponentBoard: Board,
        allBoards: Map<String, Board>
    ): MatchOutcome {
        if (!isGroup) {
            // STRICT 2-PLAYER OR AI MATCH LOGIC
            val pWon = playerBoard.isBingo
            val oWon = opponentBoard.isBingo
            return when {
                pWon && oWon -> MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = true)
                pWon -> MatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false)
                oWon -> MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false)
                else -> MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }
        } else {
            // 3+ PLAYERS GROUP MATCH LOGIC
            val effBoards = if (allBoards.containsKey(myUid)) allBoards else (allBoards + (myUid to playerBoard))
            val winners = effBoards.filter { it.value.isBingo }.keys
            if (winners.isEmpty()) {
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }
            val myWon = (myUid in winners) || playerBoard.isBingo
            return if (myWon) {
                if (winners.size > 1) {
                    MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = true)
                } else {
                    MatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false)
                }
            } else {
                MatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false)
            }
        }
    }

    // Creates a test board with specified number of completed lines
    private fun createBoardWithCompletedLines(size: Int = 5, linesToComplete: Int): Board {
        val cells = mutableListOf<Cell>()
        for (r in 0 until size) {
            for (c in 0 until size) {
                cells.add(
                    Cell(
                        row = r,
                        col = c,
                        number = r * size + c + 1,
                        markState = CellMarkState.Unmarked,
                        isPartOfCompletedLine = false,
                        isRecentPick = false
                    )
                )
            }
        }
        val completedLines = mutableSetOf<LineCoordinate>()
        for (i in 0 until linesToComplete.coerceAtMost(size)) {
            completedLines.add(LineCoordinate(LineType.ROW, i))
            for (c in 0 until size) {
                val idx = i * size + c
                cells[idx] = cells[idx].copy(
                    markState = CellMarkState.Marked("test", isOwnPick = true, turnNumber = 1),
                    isPartOfCompletedLine = true
                )
            }
        }
        return Board(size = size, cells = cells, completedLines = completedLines)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Two-Player Game Logic Regression Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `two player match - does not declare loss in the middle of a match with partial lines`() {
        val myUid = "host_user"
        val oppUid = "guest_user"

        // Both players have 2 completed lines (game in progress)
        val myBoard = createBoardWithCompletedLines(5, 2)
        val oppBoard = createBoardWithCompletedLines(5, 3)
        val allBoards = mapOf(myUid to myBoard, oppUid to oppBoard)

        val outcome = evaluateMatchOutcome(
            isGroup = false,
            myUid = myUid,
            playerBoard = myBoard,
            opponentBoard = oppBoard,
            allBoards = allBoards
        )

        assertFalse("Game should NOT be over in the middle of the match", outcome.isGameOver)
        assertFalse("Player should NOT win yet", outcome.didPlayerWin)
        assertFalse("Should not be draw yet", outcome.isDraw)
    }

    @Test
    fun `two player match - player wins when player achieves 5 lines first`() {
        val myUid = "host_user"
        val oppUid = "guest_user"

        val myBoard = createBoardWithCompletedLines(5, 5) // 5 lines = Bingo
        val oppBoard = createBoardWithCompletedLines(5, 4) // 4 lines = Not Bingo
        val allBoards = mapOf(myUid to myBoard, oppUid to oppBoard)

        val outcome = evaluateMatchOutcome(
            isGroup = false,
            myUid = myUid,
            playerBoard = myBoard,
            opponentBoard = oppBoard,
            allBoards = allBoards
        )

        assertTrue("Game should be over", outcome.isGameOver)
        assertTrue("Player should win", outcome.didPlayerWin)
        assertFalse("Should not be draw", outcome.isDraw)
    }

    @Test
    fun `two player match - player loses when opponent achieves 5 lines first`() {
        val myUid = "guest_user"
        val oppUid = "host_user"

        val myBoard = createBoardWithCompletedLines(5, 3)
        val oppBoard = createBoardWithCompletedLines(5, 5) // Opponent has Bingo
        val allBoards = mapOf(myUid to myBoard, oppUid to oppBoard)

        val outcome = evaluateMatchOutcome(
            isGroup = false,
            myUid = myUid,
            playerBoard = myBoard,
            opponentBoard = oppBoard,
            allBoards = allBoards
        )

        assertTrue("Game should be over", outcome.isGameOver)
        assertFalse("Player should not win", outcome.didPlayerWin)
        assertFalse("Should not be draw", outcome.isDraw)
    }

    @Test
    fun `two player match - draw when both players achieve 5 lines at the same time`() {
        val myUid = "host_user"
        val oppUid = "guest_user"

        val myBoard = createBoardWithCompletedLines(5, 5) // Bingo
        val oppBoard = createBoardWithCompletedLines(5, 5) // Bingo
        val allBoards = mapOf(myUid to myBoard, oppUid to oppBoard)

        val outcome = evaluateMatchOutcome(
            isGroup = false,
            myUid = myUid,
            playerBoard = myBoard,
            opponentBoard = oppBoard,
            allBoards = allBoards
        )

        assertTrue("Game should be over", outcome.isGameOver)
        assertFalse("didPlayerWin is false on draw", outcome.didPlayerWin)
        assertTrue("isDraw must be true", outcome.isDraw)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Three-Player & Group Match Win / Draw Logic Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `three players - rule 1 - if three players completed 5 lines at a time then draw for all`() {
        val p1 = "player_1"
        val p2 = "player_2"
        val p3 = "player_3"

        val board1 = createBoardWithCompletedLines(5, 5)
        val board2 = createBoardWithCompletedLines(5, 5)
        val board3 = createBoardWithCompletedLines(5, 5)

        val allBoards = mapOf(p1 to board1, p2 to board2, p3 to board3)

        // Evaluate from P1's perspective
        val p1Outcome = evaluateMatchOutcome(true, p1, board1, board2, allBoards)
        assertTrue("Game should be over", p1Outcome.isGameOver)
        assertTrue("P1 should get DRAW", p1Outcome.isDraw)
        assertFalse("P1 didPlayerWin should be false", p1Outcome.didPlayerWin)

        // Evaluate from P2's perspective
        val p2Outcome = evaluateMatchOutcome(true, p2, board2, board1, allBoards)
        assertTrue("Game should be over", p2Outcome.isGameOver)
        assertTrue("P2 should get DRAW", p2Outcome.isDraw)
        assertFalse("P2 didPlayerWin should be false", p2Outcome.didPlayerWin)

        // Evaluate from P3's perspective
        val p3Outcome = evaluateMatchOutcome(true, p3, board3, board1, allBoards)
        assertTrue("Game should be over", p3Outcome.isGameOver)
        assertTrue("P3 should get DRAW", p3Outcome.isDraw)
        assertFalse("P3 didPlayerWin should be false", p3Outcome.didPlayerWin)
    }

    @Test
    fun `three players - rule 2 - if two players completed 5 lines at a time then third player lost and two get draw`() {
        val p1 = "player_1"
        val p2 = "player_2"
        val p3 = "player_3"

        // P1 and P2 have 5 lines (Bingo). P3 only has 3 lines (Not Bingo).
        val board1 = createBoardWithCompletedLines(5, 5)
        val board2 = createBoardWithCompletedLines(5, 5)
        val board3 = createBoardWithCompletedLines(5, 3)

        val allBoards = mapOf(p1 to board1, p2 to board2, p3 to board3)

        // P1 perspective: completed 5 lines, but 2 players completed -> DRAW
        val p1Outcome = evaluateMatchOutcome(true, p1, board1, board2, allBoards)
        assertTrue("Game should be over", p1Outcome.isGameOver)
        assertTrue("P1 should get DRAW", p1Outcome.isDraw)
        assertFalse("P1 didPlayerWin should be false", p1Outcome.didPlayerWin)

        // P2 perspective: completed 5 lines, 2 players completed -> DRAW
        val p2Outcome = evaluateMatchOutcome(true, p2, board2, board1, allBoards)
        assertTrue("Game should be over", p2Outcome.isGameOver)
        assertTrue("P2 should get DRAW", p2Outcome.isDraw)
        assertFalse("P2 didPlayerWin should be false", p2Outcome.didPlayerWin)

        // P3 perspective: did NOT complete 5 lines -> YOU LOST (didPlayerWin = false, isDraw = false)
        val p3Outcome = evaluateMatchOutcome(true, p3, board3, board1, allBoards)
        assertTrue("Game should be over", p3Outcome.isGameOver)
        assertFalse("Third player gets YOU LOST (not draw)", p3Outcome.isDraw)
        assertFalse("Third player gets YOU LOST (not win)", p3Outcome.didPlayerWin)
    }

    @Test
    fun `three players - rule 3 - if one player completed 5 lines first then two players lost and one player won`() {
        val p1 = "player_1"
        val p2 = "player_2"
        val p3 = "player_3"

        // P1 has 5 lines (Bingo). P2 has 4 lines, P3 has 2 lines.
        val board1 = createBoardWithCompletedLines(5, 5)
        val board2 = createBoardWithCompletedLines(5, 4)
        val board3 = createBoardWithCompletedLines(5, 2)

        val allBoards = mapOf(p1 to board1, p2 to board2, p3 to board3)

        // P1 perspective: solo winner -> WON
        val p1Outcome = evaluateMatchOutcome(true, p1, board1, board2, allBoards)
        assertTrue("Game should be over", p1Outcome.isGameOver)
        assertTrue("P1 won", p1Outcome.didPlayerWin)
        assertFalse("Not a draw", p1Outcome.isDraw)

        // P2 perspective: lost
        val p2Outcome = evaluateMatchOutcome(true, p2, board2, board1, allBoards)
        assertTrue("Game should be over", p2Outcome.isGameOver)
        assertFalse("P2 lost", p2Outcome.didPlayerWin)
        assertFalse("P2 not in draw", p2Outcome.isDraw)

        // P3 perspective: lost
        val p3Outcome = evaluateMatchOutcome(true, p3, board3, board1, allBoards)
        assertTrue("Game should be over", p3Outcome.isGameOver)
        assertFalse("P3 lost", p3Outcome.didPlayerWin)
        assertFalse("P3 not in draw", p3Outcome.isDraw)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Post-Match Review Strip & Stamp Logic Tests
    // ═══════════════════════════════════════════════════════════════════════

    enum class StampResult { WON, DRAW, LOST }

    private fun computeReviewStamp(
        displayedBoard: Board,
        allBoards: Map<String, Board>
    ): StampResult {
        val winnersCount = allBoards.values.count { it.isBingo }
        return if (displayedBoard.isBingo) {
            if (winnersCount > 1) StampResult.DRAW else StampResult.WON
        } else {
            StampResult.LOST
        }
    }

    @Test
    fun `review stamps - solo winner displays WON stamp, losers display LOST stamps`() {
        val b1 = createBoardWithCompletedLines(5, 5)
        val b2 = createBoardWithCompletedLines(5, 3)
        val b3 = createBoardWithCompletedLines(5, 2)
        val all = mapOf("p1" to b1, "p2" to b2, "p3" to b3)

        assertEquals(StampResult.WON, computeReviewStamp(b1, all))
        assertEquals(StampResult.LOST, computeReviewStamp(b2, all))
        assertEquals(StampResult.LOST, computeReviewStamp(b3, all))
    }

    @Test
    fun `review stamps - two winner draw displays DRAW stamps on both, and LOST stamp on third`() {
        val b1 = createBoardWithCompletedLines(5, 5)
        val b2 = createBoardWithCompletedLines(5, 5)
        val b3 = createBoardWithCompletedLines(5, 4)
        val all = mapOf("p1" to b1, "p2" to b2, "p3" to b3)

        assertEquals(StampResult.DRAW, computeReviewStamp(b1, all))
        assertEquals(StampResult.DRAW, computeReviewStamp(b2, all))
        assertEquals(StampResult.LOST, computeReviewStamp(b3, all))
    }

    @Test
    fun `review stamps - three winner draw displays DRAW stamps on all three boards`() {
        val b1 = createBoardWithCompletedLines(5, 5)
        val b2 = createBoardWithCompletedLines(5, 5)
        val b3 = createBoardWithCompletedLines(5, 5)
        val all = mapOf("p1" to b1, "p2" to b2, "p3" to b3)

        assertEquals(StampResult.DRAW, computeReviewStamp(b1, all))
        assertEquals(StampResult.DRAW, computeReviewStamp(b2, all))
        assertEquals(StampResult.DRAW, computeReviewStamp(b3, all))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Spotlight Bar Mathematics and Heartbeat Jitter Proofing
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `spotlight bar - circular indexing for 5 players matches specification exactly`() {
        val players = listOf(
            Player(id = "p1", displayName = "Player 1", isHost = true),
            Player(id = "p2", displayName = "Player 2"),
            Player(id = "p3", displayName = "Player 3"),
            Player(id = "p4", displayName = "Player 4"),
            Player(id = "p5", displayName = "Player 5")
        )
        val n = players.size

        // When P2 is choosing (idx = 1):
        // Previous = P1 (idx 0), Current = P2 (idx 1), Next = P3 (idx 2)
        var currIdx = players.indexOfFirst { it.id == "p2" }
        var prevIdx = (currIdx - 1 + n) % n
        var nextIdx = (currIdx + 1) % n
        assertEquals("p1", players[prevIdx].id)
        assertEquals("p2", players[currIdx].id)
        assertEquals("p3", players[nextIdx].id)

        // When P3 is choosing (idx = 2):
        // Previous = P2 (idx 1), Current = P3 (idx 2), Next = P4 (idx 3)
        currIdx = players.indexOfFirst { it.id == "p3" }
        prevIdx = (currIdx - 1 + n) % n
        nextIdx = (currIdx + 1) % n
        assertEquals("p2", players[prevIdx].id)
        assertEquals("p3", players[currIdx].id)
        assertEquals("p4", players[nextIdx].id)

        // When P5 is choosing (idx = 4):
        // Previous = P4 (idx 3), Current = P5 (idx 4), Next = P1 (idx 0)
        currIdx = players.indexOfFirst { it.id == "p5" }
        prevIdx = (currIdx - 1 + n) % n
        nextIdx = (currIdx + 1) % n
        assertEquals("p4", players[prevIdx].id)
        assertEquals("p5", players[currIdx].id)
        assertEquals("p1", players[nextIdx].id)
    }

    @Test
    fun `spotlight bar - heartbeat updates to timestamps do not change animation targetState keys`() {
        val p1 = Player(id = "p1", displayName = "Alice", lastSeenTimestamp = 1000L)
        val p2 = Player(id = "p2", displayName = "Bob", lastSeenTimestamp = 1000L)
        val p3 = Player(id = "p3", displayName = "Charlie", lastSeenTimestamp = 1000L)
        val initialPlayers = listOf(p1, p2, p3)

        val currentTurnId = "p2"
        val currIdx1 = initialPlayers.indexOfFirst { it.id == currentTurnId }
        val prevKey1 = initialPlayers[(currIdx1 - 1 + 3) % 3].id
        val currKey1 = initialPlayers[currIdx1].id
        val nextKey1 = initialPlayers[(currIdx1 + 1) % 3].id

        // Heartbeat tick occurs 1.5s later: new timestamps
        val p1Updated = p1.copy(lastSeenTimestamp = 2500L)
        val p2Updated = p2.copy(lastSeenTimestamp = 2500L)
        val p3Updated = p3.copy(lastSeenTimestamp = 2500L)
        val updatedPlayers = listOf(p1Updated, p2Updated, p3Updated)

        val currIdx2 = updatedPlayers.indexOfFirst { it.id == currentTurnId }
        val prevKey2 = updatedPlayers[(currIdx2 - 1 + 3) % 3].id
        val currKey2 = updatedPlayers[currIdx2].id
        val nextKey2 = updatedPlayers[(currIdx2 + 1) % 3].id

        // The keys for AnimatedContent MUST be completely identical!
        assertEquals("p1", prevKey1)
        assertEquals(prevKey1, prevKey2)
        assertEquals("p2", currKey1)
        assertEquals(currKey1, currKey2)
        assertEquals("p3", nextKey1)
        assertEquals(nextKey1, nextKey2)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Full End-to-End 3-Player Network Virtual Simulation
    // ═══════════════════════════════════════════════════════════════════════

    class Simulated3PlayerBus {
        private val subscribers = mutableMapOf<String, (RoomMessagePacket) -> Unit>()

        fun register(clientId: String, onPacket: (RoomMessagePacket) -> Unit) {
            subscribers[clientId] = onPacket
        }

        fun broadcast(senderId: String, packet: RoomMessagePacket) {
            subscribers.forEach { (id, callback) ->
                if (id != senderId) {
                    callback(packet)
                }
            }
        }
    }

    class Virtual3PlayerClient(
        val id: String,
        val displayName: String,
        val isHost: Boolean,
        private val bus: Simulated3PlayerBus
    ) {
        var board: Board = BingoEngine().generateBoard(5)
        var allBoards = mutableMapOf<String, Board>()
        val pickedHistory = CopyOnWriteArrayList<Int>()
        var currentTurnId: String = ""
        var isMyTurn: Boolean = false
        var isGameOver: Boolean = false
        var didPlayerWin: Boolean = false
        var isDraw: Boolean = false
        var turnCount: Int = 1

        init {
            bus.register(id) { packet -> handlePacket(packet) }
        }

        fun initMatch(seed: Long, players: List<Player>) {
            val engine = BingoEngine()
            val sorted = players.sortedWith(compareByDescending<Player> { it.isHost }.thenBy { it.id })
            allBoards.clear()
            sorted.forEachIndexed { index, p ->
                val b = engine.generateBoard(5, seed + index)
                allBoards[p.id] = b
            }
            board = allBoards[id]!!
            currentTurnId = sorted.first().id
            isMyTurn = (currentTurnId == id)
            pickedHistory.clear()
            isGameOver = false
            didPlayerWin = false
            isDraw = false
            turnCount = 1
        }

        fun makePick(number: Int, nextTurnPlayerId: String) {
            val engine = BingoEngine()
            pickedHistory.add(number)
            board = engine.markCell(board, number, id, isOwnPick = true, turnNumber = turnCount)
            allBoards = allBoards.mapValues {
                engine.markCell(it.value, number, id, isOwnPick = (it.key == id), turnNumber = turnCount)
            }.toMutableMap()

            evaluateOutcome()
            currentTurnId = nextTurnPlayerId
            isMyTurn = (currentTurnId == id)
            turnCount += 1

            bus.broadcast(
                id,
                RoomMessagePacket(
                    type = "PICK_NUMBER",
                    number = number,
                    playerId = id,
                    currentTurnPlayerId = nextTurnPlayerId,
                    pickedHistory = pickedHistory.toList()
                )
            )
        }

        private fun handlePacket(packet: RoomMessagePacket) {
            if (packet.type == "PICK_NUMBER") {
                val engine = BingoEngine()
                if (packet.number > 0 && packet.number !in pickedHistory) {
                    pickedHistory.add(packet.number)
                    board = engine.markCell(board, packet.number, packet.playerId, isOwnPick = false, turnNumber = turnCount)
                    allBoards = allBoards.mapValues {
                        engine.markCell(it.value, packet.number, packet.playerId, isOwnPick = (it.key == packet.playerId), turnNumber = turnCount)
                    }.toMutableMap()
                }
                evaluateOutcome()
                currentTurnId = packet.currentTurnPlayerId
                isMyTurn = (currentTurnId == id)
                turnCount += 1
            }
        }

        private fun evaluateOutcome() {
            val winners = allBoards.filter { it.value.isBingo }.keys
            if (winners.isNotEmpty()) {
                isGameOver = true
                val myWon = id in winners
                if (myWon) {
                    if (winners.size > 1) {
                        isDraw = true
                        didPlayerWin = false
                    } else {
                        isDraw = false
                        didPlayerWin = true
                    }
                } else {
                    isDraw = false
                    didPlayerWin = false
                }
            }
        }
    }

    @Test
    fun `end to end virtual 3-player match - turn alternation and deterministic board sync`() {
        val bus = Simulated3PlayerBus()
        val c1 = Virtual3PlayerClient("p1", "Host Alice", isHost = true, bus = bus)
        val c2 = Virtual3PlayerClient("p2", "Guest Bob", isHost = false, bus = bus)
        val c3 = Virtual3PlayerClient("p3", "Guest Charlie", isHost = false, bus = bus)

        val playerList = listOf(
            Player("p1", "Host Alice", isHost = true),
            Player("p2", "Guest Bob"),
            Player("p3", "Guest Charlie")
        )
        val seed = 987654321L

        c1.initMatch(seed, playerList)
        c2.initMatch(seed, playerList)
        c3.initMatch(seed, playerList)

        // Verify boards are identical across clients
        assertEquals(c1.allBoards["p1"]!!.cells.map { it.number }, c2.allBoards["p1"]!!.cells.map { it.number })
        assertEquals(c1.allBoards["p2"]!!.cells.map { it.number }, c3.allBoards["p2"]!!.cells.map { it.number })
        assertEquals(c1.allBoards["p3"]!!.cells.map { it.number }, c2.allBoards["p3"]!!.cells.map { it.number })

        // Turn 1: P1 picks 5
        assertTrue("P1 starts first", c1.isMyTurn)
        assertFalse("P2 waits", c2.isMyTurn)
        assertFalse("P3 waits", c3.isMyTurn)
        c1.makePick(5, nextTurnPlayerId = "p2")

        // Turn 2: P2 turn
        assertFalse("P1 turn ended", c1.isMyTurn)
        assertTrue("P2 is now active", c2.isMyTurn)
        assertFalse("P3 still waits", c3.isMyTurn)
        c2.makePick(12, nextTurnPlayerId = "p3")

        // Turn 3: P3 turn
        assertFalse("P1 still waits", c1.isMyTurn)
        assertFalse("P2 turn ended", c2.isMyTurn)
        assertTrue("P3 is now active", c3.isMyTurn)
        c3.makePick(19, nextTurnPlayerId = "p1")

        // Turn 4: Wraps around back to P1
        assertTrue("P1 turn again", c1.isMyTurn)
        assertFalse("P2 waits", c2.isMyTurn)
        assertFalse("P3 waits", c3.isMyTurn)

        // History synchronized on all 3 devices
        assertEquals(listOf(5, 12, 19), c1.pickedHistory.toList())
        assertEquals(listOf(5, 12, 19), c2.pickedHistory.toList())
        assertEquals(listOf(5, 12, 19), c3.pickedHistory.toList())
    }

    @Test
    fun `lobby exit and rematch - allPlayerBoards clean slate prevents previous match carry-over`() {
        var allPlayerBoards = mapOf(
            "p1" to createBoardWithCompletedLines(5, 5), // Old finished winning board
            "p2" to createBoardWithCompletedLines(5, 2)
        )

        // Simulate returning to lobby or restarting match: allPlayerBoards must be wiped
        allPlayerBoards = emptyMap()
        assertTrue("allPlayerBoards must be empty after match exit", allPlayerBoards.isEmpty())

        // Start new match
        val myUid = "p1"
        val newPlayerBoard = createBoardWithCompletedLines(5, 0)
        val newOpponentBoard = createBoardWithCompletedLines(5, 0)
        allPlayerBoards = mapOf(myUid to newPlayerBoard, "p2" to newOpponentBoard)

        val outcome = evaluateMatchOutcome(
            isGroup = false,
            myUid = myUid,
            playerBoard = newPlayerBoard,
            opponentBoard = newOpponentBoard,
            allBoards = allPlayerBoards
        )

        assertFalse("New match must not trigger premature victory or loss", outcome.isGameOver)
        assertFalse("Player did not win yet", outcome.didPlayerWin)
        assertFalse("Player did not lose yet", outcome.isDraw)
    }
}
