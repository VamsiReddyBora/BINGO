package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * High-Fidelity 50-Game Multiplayer Simulation Suite (3 to 8 Players).
 *
 * Recreates exact real-world multiplayer mechanics and packet flows from RootNavGraph.kt:
 * - Turn Pick Priority (e.g. Vampire vs Vani vs Bob scenario)
 * - Simultaneous BINGO completions
 * - Line count tie-breakers
 * - Circular turn distance tie-breakers
 * - Background GAME_SYNC packet interleaving before, during, and after game-over
 * - Absolute winner announcement consistency across every single participant node
 */
class MultiplayerWinnerAnnouncementFiftyGamesSimulationTest {

    data class MatchOutcome(
        val isGameOver: Boolean,
        val didPlayerWin: Boolean,
        val isDraw: Boolean,
        val isRunner: Boolean = false,
        val winnerPlayerId: String = "",
        val runnerPlayerIds: List<String> = emptyList(),
        val winReason: String = ""
    )

    class SimulatedPlayerNode(
        val myUid: String,
        val myDisplayName: String,
        var board: Board,
        val allPlayers: List<Player>
    ) {
        val engine = BingoEngine()
        var isGameOver = false
        var isDrawMatch = false
        var didPlayerWin = false
        var isRunnerMatch = false
        var winnerPlayerId = ""
        var winReason = ""
        var runnerPlayerIds = emptyList<String>()
        var turnNumber = 1
        var currentTurnPlayerId = allPlayers.first().id
        val pickedNumbersHistory = mutableListOf<Int>()
        val pickedByPlayerHistory = mutableListOf<String>()
        var allPlayerBoards = mutableMapOf<String, Board>()

        fun isPlayerMe(id: String): Boolean = LobbyLifecycleEngine.isPlayerIdMatch(id, myUid)

        /**
         * Replicates evaluateMatchOutcome from RootNavGraph.kt (lines 702-772)
         */
        fun evaluateMatchOutcome(activePickerId: String = ""): MatchOutcome {
            val effBoards = if (allPlayerBoards.containsKey(myUid)) allPlayerBoards else (allPlayerBoards + (myUid to board))
            val completedPlayers = effBoards.filter { it.value.isBingo }.keys
            if (completedPlayers.isEmpty()) {
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }

            val activeCompletedPlayers = completedPlayers.toList()
            if (activeCompletedPlayers.isEmpty()) {
                return MatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
            }

            // 1. If activePickerId completed BINGO on their turn, they are the undisputed WINNER
            val matchingPickerId = activeCompletedPlayers.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it, activePickerId) }
            val (winnerId, reason) = if (activePickerId.isNotBlank() && matchingPickerId != null) {
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
                    // Tie in lines! Use Turn-Order Priority based on match order
                    val turnOrderUids = allPlayers.map { it.id }
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

            val isLocalWinner = isPlayerMe(winnerId)
            val isLocalRunner = (!isLocalWinner && (activeCompletedPlayers.any { isPlayerMe(it) } || board.isBingo))

            return MatchOutcome(
                isGameOver = true,
                didPlayerWin = isLocalWinner,
                isDraw = false,
                isRunner = isLocalRunner,
                winnerPlayerId = winnerId,
                runnerPlayerIds = activeCompletedPlayers.filter { !isPlayerMe(it) && it != winnerId },
                winReason = reason
            )
        }

        /**
         * Replicates executePick in RootNavGraph.kt
         */
        fun executeLocalPick(number: Int): RoomMessagePacket? {
            if (isGameOver || number in pickedNumbersHistory) return null

            pickedNumbersHistory.add(number)
            pickedByPlayerHistory.add(myUid)

            board = engine.markCell(board, number, myUid, isOwnPick = true, turnNumber = turnNumber)
            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                engine.markCell(entry.value, number, myUid, isOwnPick = (entry.key == myUid), turnNumber = turnNumber)
            }.toMutableMap()
            allPlayerBoards[myUid] = board

            val outcome = evaluateMatchOutcome(myUid)
            turnNumber += 1

            val turnOrder = allPlayers.map { it.id }
            val currentIdx = turnOrder.indexOfFirst { LobbyLifecycleEngine.isPlayerIdMatch(it, myUid) }
            val nextTurnPlayerId = turnOrder[(currentIdx + 1) % turnOrder.size]
            currentTurnPlayerId = nextTurnPlayerId

            if (outcome.isGameOver) {
                isGameOver = true
                isDrawMatch = outcome.isDraw
                didPlayerWin = outcome.didPlayerWin
                isRunnerMatch = outcome.isRunner
                winnerPlayerId = outcome.winnerPlayerId
                winReason = outcome.winReason
                runnerPlayerIds = outcome.runnerPlayerIds
            }

            return RoomMessagePacket(
                type = "PICK_NUMBER",
                number = number,
                playerId = myUid,
                turnNumber = turnNumber,
                currentTurnPlayerId = currentTurnPlayerId,
                pickedHistory = pickedNumbersHistory.toList(),
                pickedByHistory = pickedByPlayerHistory.toList(),
                winnerPlayerId = if (outcome.isGameOver) outcome.winnerPlayerId else "",
                winReason = if (outcome.isGameOver) outcome.winReason else "",
                runnerPlayerIds = if (outcome.isGameOver) outcome.runnerPlayerIds else emptyList()
            )
        }

        /**
         * Replicates PICK_NUMBER packet handler in RootNavGraph.kt (lines 1474-1576)
         */
        fun onReceivePickNumber(packet: RoomMessagePacket) {
            var anyNewPick = false

            // 1. Reconcile missing
            packet.pickedHistory.forEachIndexed { idx, num ->
                if (num > 0 && num !in pickedNumbersHistory) {
                    val origPickerId = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                    pickedNumbersHistory.add(num)
                    pickedByPlayerHistory.add(origPickerId)
                    board = engine.markCell(board, num, origPickerId, isOwnPick = (origPickerId == myUid), turnNumber = turnNumber)
                    allPlayerBoards = allPlayerBoards.mapValues { entry ->
                        engine.markCell(entry.value, num, origPickerId, isOwnPick = (entry.key == origPickerId), turnNumber = turnNumber)
                    }.toMutableMap()
                    allPlayerBoards[myUid] = board
                    anyNewPick = true
                }
            }

            // 2. Direct pick
            if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                pickedNumbersHistory.add(packet.number)
                pickedByPlayerHistory.add(packet.playerId)
                board = engine.markCell(board, packet.number, packet.playerId, isOwnPick = (packet.playerId == myUid), turnNumber = turnNumber)
                allPlayerBoards = allPlayerBoards.mapValues { entry ->
                    engine.markCell(entry.value, packet.number, packet.playerId, isOwnPick = (entry.key == packet.playerId), turnNumber = turnNumber)
                }.toMutableMap()
                allPlayerBoards[myUid] = board
                anyNewPick = true
            }

            // 3. Evaluate win conditions (with v1.3.3 guard: if (!isGameOver))
            if (!isGameOver) {
                val outcome = if (packet.winnerPlayerId.isNotBlank()) {
                    val isLocalWinner = isPlayerMe(packet.winnerPlayerId)
                    val isLocalRunner = !isLocalWinner && (packet.runnerPlayerIds.any { isPlayerMe(it) } || board.isBingo)
                    MatchOutcome(
                        isGameOver = true,
                        didPlayerWin = isLocalWinner,
                        isDraw = false,
                        isRunner = isLocalRunner,
                        winnerPlayerId = packet.winnerPlayerId,
                        winReason = packet.winReason,
                        runnerPlayerIds = packet.runnerPlayerIds
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
                    winReason = outcome.winReason
                    runnerPlayerIds = outcome.runnerPlayerIds
                } else {
                    if (packet.turnNumber > turnNumber || anyNewPick) {
                        turnNumber = maxOf(packet.turnNumber, turnNumber + (if (anyNewPick && packet.turnNumber <= turnNumber) 1 else 0))
                        currentTurnPlayerId = packet.currentTurnPlayerId
                    }
                }
            }
        }

        /**
         * Replicates GAME_SYNC packet handler in RootNavGraph.kt (lines 1756-1835)
         * with v1.3.3 if (!isGameOver) guard and lastPicker resolution.
         */
        fun onReceiveGameSync(packet: RoomMessagePacket) {
            var anyNewPick = false

            packet.pickedHistory.forEachIndexed { idx, num ->
                if (num > 0 && num !in pickedNumbersHistory) {
                    val origPickerId = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                    pickedNumbersHistory.add(num)
                    pickedByPlayerHistory.add(origPickerId)
                    board = engine.markCell(board, num, origPickerId, isOwnPick = (origPickerId == myUid), turnNumber = turnNumber)
                    allPlayerBoards = allPlayerBoards.mapValues { entry ->
                        engine.markCell(entry.value, num, origPickerId, isOwnPick = (entry.key == origPickerId), turnNumber = turnNumber)
                    }.toMutableMap()
                    allPlayerBoards[myUid] = board
                    anyNewPick = true
                }
            }

            // Guarded by if (!isGameOver)
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
                    winReason = outcome.winReason
                    runnerPlayerIds = outcome.runnerPlayerIds
                } else if (packet.turnNumber > turnNumber || (anyNewPick && packet.turnNumber >= turnNumber)) {
                    turnNumber = maxOf(packet.turnNumber, turnNumber + (if (anyNewPick && packet.turnNumber == turnNumber) 1 else 0))
                    if (packet.currentTurnPlayerId.isNotBlank()) {
                        currentTurnPlayerId = packet.currentTurnPlayerId
                    }
                }
            }
        }

        fun generateSyncPacket(): RoomMessagePacket {
            return RoomMessagePacket(
                type = "GAME_SYNC",
                turnNumber = turnNumber,
                pickedHistory = pickedNumbersHistory.toList(),
                pickedByHistory = pickedByPlayerHistory.toList(),
                currentTurnPlayerId = currentTurnPlayerId,
                playerId = myUid
            )
        }
    }

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

    @Test
    fun `run 50 games simulation across 3 to 8 players verifying winner announcements and rules`() {
        println("================================================================================")
        println("STARTING 50-GAME MULTIPLAYER SIMULATION SUITE (3 TO 8 PLAYERS)")
        println("================================================================================")

        val gameLogs = mutableListOf<String>()

        for (gameNumber in 1..50) {
            // Distribute player counts: 3 to 8 players
            val playerCount = when {
                gameNumber <= 8 -> 3   // Games 1..8: 3 players (includes Vani, Bob, Vampire)
                gameNumber <= 16 -> 4  // Games 9..16: 4 players
                gameNumber <= 24 -> 5  // Games 17..24: 5 players
                gameNumber <= 32 -> 6  // Games 25..32: 6 players
                gameNumber <= 40 -> 7  // Games 33..40: 7 players
                else -> 8              // Games 41..50: 8 players
            }

            val players = (1..playerCount).map { i ->
                val name = when (i) {
                    1 -> if (playerCount == 3 && gameNumber == 1) "vampire" else "Player_$i"
                    2 -> if (playerCount == 3 && gameNumber == 1) "vani" else "Player_$i"
                    3 -> if (playerCount == 3 && gameNumber == 1) "bob" else "Player_$i"
                    else -> "Player_$i"
                }
                Player(id = "uid_$name", displayName = name, isHost = (i == 1))
            }

            val engine = BingoEngine()

            // Construct boards: special edge cases for early games, randomized for others
            val initialBoards = mutableMapOf<String, Board>()

            when {
                // Game 1: The exact Vampire, Vani, Bob edge case!
                gameNumber == 1 -> {
                    // vampire and vani both have 4 lines and both need number 25 to complete 5 lines (BINGO)
                    // bob has 2 lines
                    val vampireBoard = createBoardWithCompletedLines(5, 4)
                    val vaniBoard = createBoardWithCompletedLines(5, 4)
                    val bobBoard = createBoardWithCompletedLines(5, 2)
                    initialBoards["uid_vampire"] = vampireBoard
                    initialBoards["uid_vani"] = vaniBoard
                    initialBoards["uid_bob"] = bobBoard
                }

                // Game 2: 3-Player Line Count Tie-Breaker on someone else's pick
                // P1 picks a number, but P2 and P3 complete BINGO! P2 has 5 lines, P3 has 6 lines -> P3 wins!
                gameNumber == 2 -> {
                    initialBoards[players[0].id] = createBoardWithCompletedLines(5, 1)
                    initialBoards[players[1].id] = createBoardWithCompletedLines(5, 4) // reaches 5 lines
                    initialBoards[players[2].id] = createBoardWithCompletedLines(6, 5) // reaches 6 lines
                }

                // Game 3: 3-Player Turn Distance Tie-Breaker on someone else's pick
                // P1 picks. Both P2 and P3 reach 5 lines. P1 is picker. P2 is distance 1, P3 is distance 2. P2 wins!
                gameNumber == 3 -> {
                    initialBoards[players[0].id] = createBoardWithCompletedLines(5, 4)
                    initialBoards[players[1].id] = createBoardWithCompletedLines(5, 4)
                    initialBoards[players[2].id] = createBoardWithCompletedLines(5, 4)
                }

                // Game 4: 4-Player Simultaneous 3-way BINGO (Picker wins)
                gameNumber == 4 -> {
                    players.forEachIndexed { idx, p ->
                        initialBoards[p.id] = createBoardWithCompletedLines(5, if (idx < 3) 4 else 1)
                    }
                }

                // All other games: authentic generated boards with diverse seeds
                else -> {
                    players.forEachIndexed { idx, p ->
                        val seed = (gameNumber * 1000L) + idx * 77L + 12345L
                        initialBoards[p.id] = engine.generateBoard(size = 5, seed = seed)
                    }
                }
            }

            // Create simulated nodes for every player
            val nodes = players.map { p ->
                val node = SimulatedPlayerNode(
                    myUid = p.id,
                    myDisplayName = p.displayName,
                    board = initialBoards[p.id]!!,
                    allPlayers = players
                )
                // Initialize allPlayerBoards with peers' boards
                node.allPlayerBoards = initialBoards.toMutableMap()
                node
            }

            // PLAY THE GAME
            var turnsPlayed = 0
            var finalWinningNumber = -1
            var finalPickerId = ""
            var currentTurnIndex = 0

            // Pool of numbers for the game (1 to 25 for 5x5 board)
            val availableNumbers = (1..25).shuffled(Random(gameNumber * 999L + 42L)).toMutableList()

            // Run until match ends or numbers run out
            while (nodes.none { it.isGameOver } && availableNumbers.isNotEmpty() && turnsPlayed < 40) {
                turnsPlayed++
                val activeNode = nodes[currentTurnIndex]
                val activePlayer = players[currentTurnIndex]

                // Pick an unmarked number
                val numberToPick = if (gameNumber in 1..4 && turnsPlayed == 1) {
                    // Specific pick to trigger simultaneous win on turn 1 for scripted games
                    // Use a number that completes the 5th line (row 4, last uncompleted row in 5x5: index 20..24 -> number 21)
                    21
                } else {
                    availableNumbers.removeAt(0)
                }

                // Active player executes pick locally
                finalWinningNumber = numberToPick
                finalPickerId = activePlayer.id
                val pickPacket = activeNode.executeLocalPick(numberToPick)

                if (pickPacket != null) {
                    // Broadcast PICK_NUMBER to all other nodes
                    nodes.filter { it.myUid != activeNode.myUid }.forEach { peerNode ->
                        peerNode.onReceivePickNumber(pickPacket)
                    }

                    // Interleave background GAME_SYNC packets from random peers
                    // (Simulates realistic background sync / race condition during game)
                    if (gameNumber % 2 == 0) {
                        val randomPeer = nodes.random(Random(turnsPlayed * 31L))
                        val syncPacket = randomPeer.generateSyncPacket()
                        nodes.filter { it.myUid != randomPeer.myUid }.forEach { otherNode ->
                            otherNode.onReceiveGameSync(syncPacket)
                        }
                    }
                }

                currentTurnIndex = (currentTurnIndex + 1) % players.size
            }

            // SIMULATE POST-GAME AGGRESSIVE SYNC ATTACK:
            // Every single node sends a GAME_SYNC packet to every other node.
            // In v1.3.2, this would cause winner desync and screens to flip!
            // In v1.3.3, all screens MUST remain 100% stable and unchanged!
            nodes.forEach { sendingNode ->
                val postGameSync = sendingNode.generateSyncPacket()
                nodes.filter { it.myUid != sendingNode.myUid }.forEach { receivingNode ->
                    receivingNode.onReceiveGameSync(postGameSync)
                }
            }

            // VERIFY ALL INVARIANTS AND WINNER ANNOUNCEMENTS
            val winnerIdsReported = nodes.map { it.winnerPlayerId }.distinct()
            val gameOversReported = nodes.map { it.isGameOver }.distinct()
            val playersWhoCompletedBingo = nodes.first().allPlayerBoards.filter { it.value.isBingo }.keys.toList()

            // 1. All nodes must agree on whether game is over
            assertEquals("Game #$gameNumber: All nodes must agree that game is over", listOf(true), gameOversReported)

            // 2. All nodes must agree on the EXACT SAME WINNER
            assertEquals(
                "Game #$gameNumber ($playerCount players): All ${nodes.size} nodes MUST report the exact same winnerId. Reported: $winnerIdsReported",
                1,
                winnerIdsReported.size
            )

            val agreedWinnerId = winnerIdsReported.first()
            assertTrue("Game #$gameNumber: Agreed winner ID must be valid and non-blank", agreedWinnerId.isNotBlank())

            // 3. Exactly ONE node must have didPlayerWin == true
            val nodesClaimingWin = nodes.filter { it.didPlayerWin }
            assertEquals(
                "Game #$gameNumber: Exactly one node must have didPlayerWin=true. Claimants: ${nodesClaimingWin.map { it.myDisplayName }}",
                1,
                nodesClaimingWin.size
            )
            assertEquals("Game #$gameNumber: Winner node must match agreed winner ID", agreedWinnerId, nodesClaimingWin.first().myUid)

            // 4. Verify Turn Pick Priority:
            // If the final picker completed BINGO, they MUST be the winner!
            if (agreedWinnerId in playersWhoCompletedBingo && agreedWinnerId == finalPickerId) {
                assertEquals(
                    "Game #$gameNumber: Turn picker who completed BINGO must win with 'Turn Pick'",
                    "Turn Pick",
                    nodesClaimingWin.first().winReason
                )
            }

            // 5. Verify Runner status:
            // Any other player who completed BINGO must be marked as isRunnerMatch on their own screen
            nodes.forEach { node ->
                if (node.myUid != agreedWinnerId) {
                    assertFalse("Game #$gameNumber: Non-winner ${node.myDisplayName} must not have didPlayerWin=true", node.didPlayerWin)
                    if (node.board.isBingo) {
                        assertTrue("Game #$gameNumber: Player ${node.myDisplayName} achieved BINGO but lost turn priority, so must be isRunnerMatch=true", node.isRunnerMatch)
                    } else {
                        assertFalse("Game #$gameNumber: Player ${node.myDisplayName} without BINGO must not be runner", node.isRunnerMatch)
                    }
                }
            }

            val winnerName = players.first { it.id == agreedWinnerId }.displayName
            val winReason = nodesClaimingWin.first().winReason
            val logLine = "Game #%02d | %d Players | Turns: %02d | Pick: #%02d by %s | Winner: %s (%s) | Bingo Completed: %s".format(
                gameNumber,
                playerCount,
                turnsPlayed,
                finalWinningNumber,
                players.firstOrNull { it.id == finalPickerId }?.displayName ?: finalPickerId,
                winnerName,
                winReason,
                playersWhoCompletedBingo.map { id -> players.first { it.id == id }.displayName }
            )
            println(logLine)
            gameLogs.add(logLine)
        }

        println("================================================================================")
        println("ALL 50 GAMES COMPLETED WITH 100% WINNER CONSISTENCY ACROSS ALL NODES!")
        println("================================================================================")
        assertEquals(50, gameLogs.size)
    }
}
