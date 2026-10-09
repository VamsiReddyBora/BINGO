package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Cell
import com.bingo.multiplayer.domain.model.CellMarkState
import com.bingo.multiplayer.domain.model.LineCoordinate
import com.bingo.multiplayer.domain.model.LineType
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.presentation.game.StampResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * End-to-End Multi-Player Online Match Simulation & Audit Suite
 *
 * Simulates online matches across 2, 3, 4, 6, and 8 players:
 * 1. 2-Player Head-to-Head full match lifecycle.
 * 2. 3-Player match with simultaneous BINGO and "Turn Pick" resolution.
 * 3. 4-Player match with line-count tie-breaker resolution.
 * 4. 4-Player match with circular turn-distance tie-breaker resolution.
 * 5. 6-Player match with mid-match player disconnect, turn skip, and offline stamp.
 * 6. 8-Player match with consecutive timeouts, missed turn passes, and recovery.
 * 7. 8-Player match with packet drops, out-of-order delivery, and history reconciliation.
 * 8. Mid-match disconnect and re-join with GAME_SYNC board recovery.
 * 9. Post-game aggressive sync attack immunity (no winner flips).
 * 10. Multi-match rematch cycle via PLAY_AGAIN packet distribution.
 */
class OnlineMatchComprehensiveSimulationTest {

    private val engine = BingoEngine()

    data class ClientMatchOutcome(
        val isGameOver: Boolean,
        val didPlayerWin: Boolean,
        val isDraw: Boolean,
        val isRunner: Boolean = false,
        val winnerPlayerId: String = "",
        val runnerPlayerIds: List<String> = emptyList(),
        val winReason: String = ""
    )

    /**
     * Simulated Online Game Node replicating RootNavGraph.kt state and network message flows.
     */
    class VirtualGameNode(
        val id: String,
        val displayName: String,
        val isHost: Boolean,
        val bus: VirtualNetworkBus
    ) {
        val engine = BingoEngine()
        var currentMatchSeed: Long = 0L
        var boardSize: Int = 5
        var playerBoard: Board = engine.generateBoard(5)
        var opponentBoard: Board = engine.generateBoard(5)
        var allPlayerBoards = mutableMapOf<String, Board>()

        var matchParticipants = mutableListOf<Player>()
        var randomizedTurnOrder = mutableListOf<Player>()
        val disconnectedPlayerIds = mutableSetOf<String>()
        val consecutiveMissedTurns = mutableMapOf<String, Int>()

        val pickedNumbersHistory = CopyOnWriteArrayList<Int>()
        val pickedByPlayerHistory = CopyOnWriteArrayList<String>()

        var turnNumber: Int = 1
        var turnTimer: Int = 30
        var currentTurnPlayerId: String = ""
        var isMyTurn: Boolean = false

        var isGameOver: Boolean = false
        var didPlayerWin: Boolean = false
        var isDrawMatch: Boolean = false
        var isRunnerMatch: Boolean = false
        var winnerPlayerId: String = ""
        var winReason: String = ""
        var runnerPlayerIds = emptyList<String>()

        val chatLog = mutableListOf<String>()

        fun isPlayerMe(uid: String): Boolean = LobbyLifecycleEngine.isPlayerIdMatch(uid, id)
        fun isPlayerDisconnected(uid: String): Boolean = disconnectedPlayerIds.any { LobbyLifecycleEngine.isPlayerIdMatch(it, uid) }

        fun markPlayerDisconnected(uid: String) {
            if (uid.isNotBlank() && !isPlayerDisconnected(uid)) {
                disconnectedPlayerIds.add(uid)
            }
        }

        fun markPlayerReconnected(uid: String) {
            consecutiveMissedTurns.remove(uid)
            val toRemove = disconnectedPlayerIds.filter { LobbyLifecycleEngine.isPlayerIdMatch(it, uid) }
            if (toRemove.isNotEmpty()) {
                disconnectedPlayerIds.removeAll(toRemove.toSet())
            }
        }

        fun isGroupMatch(): Boolean = matchParticipants.size > 2 || allPlayerBoards.size > 2

        /**
         * Replicates evaluateMatchOutcome from RootNavGraph.kt (lines 769-873)
         */
        fun evaluateMatchOutcome(activePickerId: String = ""): ClientMatchOutcome {
            val isGroup = isGroupMatch()
            if (!isGroup) {
                val opp = matchParticipants.firstOrNull { !isPlayerMe(it.id) }?.id ?: "opponent"
                val pWon = playerBoard.isBingo && !isPlayerDisconnected(id)
                val oWon = opponentBoard.isBingo && !isPlayerDisconnected(opp)
                return when {
                    pWon && oWon -> {
                        if (isPlayerMe(activePickerId)) {
                            ClientMatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false, isRunner = false, winnerPlayerId = id, winReason = "Turn Pick")
                        } else {
                            ClientMatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false, isRunner = true, winnerPlayerId = opp, winReason = "Turn Pick")
                        }
                    }
                    pWon -> ClientMatchOutcome(isGameOver = true, didPlayerWin = true, isDraw = false, isRunner = false, winnerPlayerId = id, winReason = "Bingo")
                    oWon -> ClientMatchOutcome(isGameOver = true, didPlayerWin = false, isDraw = false, isRunner = false, winnerPlayerId = opp, winReason = "Bingo")
                    else -> ClientMatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
                }
            } else {
                val effBoards = if (allPlayerBoards.containsKey(id)) allPlayerBoards else (allPlayerBoards + (id to playerBoard))
                val completedPlayers = effBoards.filter { it.value.isBingo }.keys
                if (completedPlayers.isEmpty()) {
                    return ClientMatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
                }

                val activeCompleted = completedPlayers.filter { !isPlayerDisconnected(it) }
                if (activeCompleted.isEmpty()) {
                    return ClientMatchOutcome(isGameOver = false, didPlayerWin = false, isDraw = false)
                }

                val matchingPickerId = activeCompleted.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it, activePickerId) }
                val (winnerId, reason) = if (activePickerId.isNotBlank() && matchingPickerId != null) {
                    matchingPickerId to "Turn Pick"
                } else if (activeCompleted.size == 1) {
                    activeCompleted.first() to "Solo Bingo"
                } else {
                    val maxLines = activeCompleted.maxOf { effBoards[it]?.completedLinesCount ?: 0 }
                    val topCandidates = activeCompleted.filter { (effBoards[it]?.completedLinesCount ?: 0) == maxLines }
                    if (topCandidates.size == 1) {
                        topCandidates.first() to "Line Count"
                    } else {
                        val turnOrderUids = if (randomizedTurnOrder.isNotEmpty()) {
                            randomizedTurnOrder.map { it.id }
                        } else {
                            matchParticipants.map { it.id }
                        }
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

                val isLocalWinner = isPlayerMe(winnerId) && !isPlayerDisconnected(id)
                val isLocalRunner = (!isLocalWinner && !isPlayerDisconnected(id) && (activeCompleted.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(id))))

                return ClientMatchOutcome(
                    isGameOver = true,
                    didPlayerWin = isLocalWinner,
                    isDraw = false,
                    isRunner = isLocalRunner,
                    winnerPlayerId = winnerId,
                    runnerPlayerIds = activeCompleted.filter { !isPlayerMe(it) && it != winnerId },
                    winReason = reason
                )
            }
        }

        fun calculateNextTurnPlayerId(currentPickerId: String): String {
            return LobbyLifecycleEngine.calculateNextTurnPlayerId(
                allParticipants = matchParticipants,
                disconnectedPlayerIds = disconnectedPlayerIds,
                currentPickerId = currentPickerId,
                fallbackPlayerId = id,
                matchSeed = currentMatchSeed,
                customTurnOrder = randomizedTurnOrder
            )
        }

        fun startMatch(seed: Long, players: List<Player>, size: Int = 5, firstTurnUid: String = "") {
            currentMatchSeed = seed
            boardSize = size
            matchParticipants = players.toMutableList()
            disconnectedPlayerIds.clear()
            consecutiveMissedTurns.clear()
            pickedNumbersHistory.clear()
            pickedByPlayerHistory.clear()
            chatLog.clear()

            isGameOver = false
            didPlayerWin = false
            isDrawMatch = false
            isRunnerMatch = false
            winnerPlayerId = ""
            winReason = ""
            runnerPlayerIds = emptyList()

            // Deterministic board generation per player
            val newBoards = mutableMapOf<String, Board>()
            players.forEachIndexed { index, p ->
                val pSeed = LobbyLifecycleEngine.resolvePlayerBoardSeed(seed, p, index)
                val b = engine.generateBoard(size, pSeed)
                newBoards[p.id] = b
                if (isPlayerMe(p.id)) {
                    playerBoard = b
                }
            }
            allPlayerBoards = newBoards
            val otherPlayer = players.firstOrNull { !isPlayerMe(it.id) }
            opponentBoard = if (otherPlayer != null) newBoards[otherPlayer.id] ?: engine.generateBoard(size) else engine.generateBoard(size)

            val order = LobbyLifecycleEngine.generateDeterministicTurnOrder(players, seed)
            randomizedTurnOrder = order.toMutableList()

            val initialTurnUid = firstTurnUid.ifBlank { order.first().id }
            currentTurnPlayerId = initialTurnUid
            isMyTurn = (currentTurnPlayerId == id)
            turnNumber = 1
            turnTimer = 30
        }

        fun executePick(number: Int, isTimeoutPass: Boolean = false) {
            if (isGameOver) return

            val pickerId = if (isTimeoutPass) currentTurnPlayerId else id
            val isOwnPick = isPlayerMe(pickerId)

            if (!isTimeoutPass) {
                consecutiveMissedTurns.remove(pickerId)
                markPlayerReconnected(pickerId)
                pickedNumbersHistory.add(number)
                pickedByPlayerHistory.add(pickerId)

                playerBoard = engine.markCell(playerBoard, number, pickerId, isOwnPick, turnNumber)
                opponentBoard = engine.markCell(opponentBoard, number, pickerId, !isOwnPick, turnNumber)
                allPlayerBoards = allPlayerBoards.mapValues { entry ->
                    engine.markCell(entry.value, number, pickerId, entry.key == pickerId, turnNumber)
                }.toMutableMap()
            } else {
                val misses = (consecutiveMissedTurns[pickerId] ?: 0) + 1
                consecutiveMissedTurns[pickerId] = misses
            }

            val outcome = if (!isTimeoutPass) evaluateMatchOutcome(pickerId) else ClientMatchOutcome(false, false, false)
            turnNumber += 1
            turnTimer = 30
            isGameOver = outcome.isGameOver

            val nextPlayerId = calculateNextTurnPlayerId(pickerId)
            currentTurnPlayerId = nextPlayerId
            isMyTurn = (currentTurnPlayerId == id)

            if (outcome.isGameOver) {
                isDrawMatch = outcome.isDraw
                didPlayerWin = outcome.didPlayerWin
                isRunnerMatch = outcome.isRunner
                winnerPlayerId = outcome.winnerPlayerId
                winReason = outcome.winReason
                runnerPlayerIds = outcome.runnerPlayerIds
            }

            // Broadcast packet
            val packet = RoomMessagePacket(
                type = if (isTimeoutPass) "TURN_TIMEOUT" else "PICK_NUMBER",
                number = number,
                playerId = pickerId,
                turnNumber = turnNumber,
                pickedHistory = pickedNumbersHistory.toList(),
                pickedByHistory = pickedByPlayerHistory.toList(),
                currentTurnPlayerId = nextPlayerId,
                seed = currentMatchSeed,
                winnerPlayerId = if (outcome.isGameOver) outcome.winnerPlayerId else "",
                winReason = if (outcome.isGameOver) outcome.winReason else "",
                runnerPlayerIds = if (outcome.isGameOver) outcome.runnerPlayerIds else emptyList()
            )
            bus.broadcast(id, packet)
        }

        fun handleIncomingPacket(packet: RoomMessagePacket) {
            if (packet.playerId == id && packet.type != "START_GAME" && packet.type != "PLAY_AGAIN") return

            when (packet.type) {
                "START_GAME", "PLAY_AGAIN" -> {
                    if (!isHost) {
                        startMatch(
                            seed = packet.seed,
                            players = packet.players,
                            size = packet.boardSize,
                            firstTurnUid = packet.currentTurnPlayerId
                        )
                    }
                }

                "PICK_NUMBER" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) return
                    if (packet.playerId.isNotBlank() && packet.playerId != id) {
                        markPlayerReconnected(packet.playerId)
                    }

                    var anyNewPick = false

                    // 1. Reconcile missing
                    packet.pickedHistory.forEachIndexed { idx, num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            val origPicker = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            val histTurn = idx + 1
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPicker)
                            val isMine = isPlayerMe(origPicker)
                            playerBoard = engine.markCell(playerBoard, num, origPicker, isMine, histTurn)
                            opponentBoard = engine.markCell(opponentBoard, num, origPicker, !isMine, histTurn)
                            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                                engine.markCell(entry.value, num, origPicker, entry.key == origPicker, histTurn)
                            }.toMutableMap()
                            anyNewPick = true
                        }
                    }

                    // 2. Direct pick
                    val isExpectedPicker = LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, currentTurnPlayerId) ||
                            (packet.isHost && packet.number <= 0) || currentTurnPlayerId.isBlank()
                    if (!isExpectedPicker && (packet.turnNumber < turnNumber)) {
                        return
                    }

                    if (packet.number > 0 && packet.number !in pickedNumbersHistory) {
                        pickedNumbersHistory.add(packet.number)
                        pickedByPlayerHistory.add(packet.playerId)
                        val isMine = isPlayerMe(packet.playerId)
                        playerBoard = engine.markCell(playerBoard, packet.number, packet.playerId, isMine, turnNumber)
                        opponentBoard = engine.markCell(opponentBoard, packet.number, packet.playerId, !isMine, turnNumber)
                        allPlayerBoards = allPlayerBoards.mapValues { entry ->
                            engine.markCell(entry.value, packet.number, packet.playerId, entry.key == packet.playerId, turnNumber)
                        }.toMutableMap()
                        anyNewPick = true
                    }

                    // 3. Win check
                    if (!isGameOver) {
                        val outcome = if (packet.winnerPlayerId.isNotBlank() && !isPlayerDisconnected(packet.winnerPlayerId)) {
                            val isLocalWinner = isPlayerMe(packet.winnerPlayerId) && !isPlayerDisconnected(id)
                            val isLocalRunner = !isLocalWinner && !isPlayerDisconnected(id) &&
                                    (packet.runnerPlayerIds.filter { !isPlayerDisconnected(it) }.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(id)))
                            ClientMatchOutcome(
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
                                turnTimer = 30
                                val nextId = if (packet.currentTurnPlayerId.isNotBlank()) packet.currentTurnPlayerId else calculateNextTurnPlayerId(packet.playerId)
                                currentTurnPlayerId = nextId
                                isMyTurn = (currentTurnPlayerId == id)
                            }
                        }
                    }
                }

                "TURN_TIMEOUT" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) return
                    val shouldAdvance = (packet.turnNumber > turnNumber) ||
                            (packet.turnNumber == turnNumber && packet.playerId.isNotBlank() && LobbyLifecycleEngine.isPlayerIdMatch(packet.playerId, currentTurnPlayerId))
                    if (shouldAdvance) {
                        turnNumber = maxOf(packet.turnNumber, turnNumber + 1)
                        turnTimer = 30
                        val nextId = if (packet.currentTurnPlayerId.isNotBlank()) packet.currentTurnPlayerId else calculateNextTurnPlayerId(packet.playerId)
                        currentTurnPlayerId = nextId
                        isMyTurn = (currentTurnPlayerId == id)
                    }
                }

                "REJOIN_GAME" -> {
                    if (packet.playerId.isNotBlank() && packet.playerId != id) {
                        markPlayerReconnected(packet.playerId)
                        // If we are host or active turn, send GAME_SYNC
                        if (isHost || isMyTurn) {
                            bus.sendDirect(
                                fromId = id,
                                toId = packet.playerId,
                                packet = RoomMessagePacket(
                                    type = "GAME_SYNC",
                                    turnNumber = turnNumber,
                                    pickedHistory = pickedNumbersHistory.toList(),
                                    pickedByHistory = pickedByPlayerHistory.toList(),
                                    currentTurnPlayerId = currentTurnPlayerId,
                                    seed = currentMatchSeed,
                                    playerId = id,
                                    winnerPlayerId = if (isGameOver) winnerPlayerId else "",
                                    winReason = if (isGameOver) winReason else "",
                                    runnerPlayerIds = if (isGameOver) runnerPlayerIds else emptyList()
                                )
                            )
                        }
                    }
                }

                "GAME_SYNC" -> {
                    if (!LobbyLifecycleEngine.isPacketForActiveMatch(packet.seed, currentMatchSeed)) return
                    var anyNewPick = false

                    packet.pickedHistory.forEachIndexed { idx, num ->
                        if (num > 0 && num !in pickedNumbersHistory) {
                            val origPicker = packet.pickedByHistory.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: packet.playerId
                            val histTurn = idx + 1
                            pickedNumbersHistory.add(num)
                            pickedByPlayerHistory.add(origPicker)
                            val isMine = isPlayerMe(origPicker)
                            playerBoard = engine.markCell(playerBoard, num, origPicker, isMine, histTurn)
                            opponentBoard = engine.markCell(opponentBoard, num, origPicker, !isMine, histTurn)
                            allPlayerBoards = allPlayerBoards.mapValues { entry ->
                                engine.markCell(entry.value, num, origPicker, entry.key == origPicker, histTurn)
                            }.toMutableMap()
                            anyNewPick = true
                        }
                    }

                    if (!isGameOver) {
                        val lastPicker = pickedByPlayerHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                            ?: packet.pickedByHistory.lastOrNull()?.takeIf { it.isNotBlank() }
                            ?: currentTurnPlayerId
                        val outcome = if (packet.winnerPlayerId.isNotBlank()) {
                            val isLocalWinner = isPlayerMe(packet.winnerPlayerId) && !isPlayerDisconnected(id)
                            val isLocalRunner = !isLocalWinner && !isPlayerDisconnected(id) &&
                                    (packet.runnerPlayerIds.filter { !isPlayerDisconnected(it) }.any { isPlayerMe(it) } || (playerBoard.isBingo && !isPlayerDisconnected(id)))
                            ClientMatchOutcome(
                                isGameOver = true,
                                didPlayerWin = isLocalWinner,
                                isDraw = false,
                                isRunner = isLocalRunner,
                                winnerPlayerId = packet.winnerPlayerId,
                                winReason = packet.winReason,
                                runnerPlayerIds = packet.runnerPlayerIds
                            )
                        } else {
                            evaluateMatchOutcome(lastPicker)
                        }

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
                                isMyTurn = (currentTurnPlayerId == id)
                            }
                        }
                    }
                }
            }
        }

        fun resolveStamp(selectedPlayerId: String): Pair<StampResultType, String> {
            val isLocalSelected = (selectedPlayerId == id)
            val isLocalDisconnected = isPlayerDisconnected(id)

            return if (isLocalSelected) {
                when {
                    isLocalDisconnected -> StampResultType.OFFLINE to "OFFLINE"
                    didPlayerWin -> StampResultType.WON to "YOU'VE WON!"
                    isRunnerMatch -> StampResultType.RUNNER to "RUNNER!"
                    else -> StampResultType.LOST to "YOU LOST!"
                }
            } else {
                val isSelectedDisconnected = isPlayerDisconnected(selectedPlayerId)
                val isWinnerSelected = !isSelectedDisconnected && (selectedPlayerId == winnerPlayerId)
                val reviewName = matchParticipants.firstOrNull { it.id == selectedPlayerId }?.displayName ?: "Player"
                val board = allPlayerBoards[selectedPlayerId] ?: opponentBoard
                when {
                    isSelectedDisconnected -> StampResultType.OFFLINE to "OFFLINE"
                    isWinnerSelected -> StampResultType.WON to "$reviewName WON!"
                    board.isBingo -> StampResultType.RUNNER to "RUNNER!"
                    else -> StampResultType.LOST to "LOST!"
                }
            }
        }
    }

    /**
     * Simulated network message bus supporting broadcasts, direct sends, latency delay, and packet drop.
     */
    class VirtualNetworkBus {
        private val nodes = mutableMapOf<String, VirtualGameNode>()
        var dropRatePercent: Int = 0 // 0 to 100
        val eventLog = mutableListOf<String>()

        fun register(node: VirtualGameNode) {
            nodes[node.id] = node
        }

        fun unregister(node: VirtualGameNode) {
            nodes.remove(node.id)
        }

        fun broadcast(senderId: String, packet: RoomMessagePacket) {
            eventLog.add("[$senderId -> ALL] ${packet.type} (num=${packet.number}, turn=${packet.turnNumber}, next=${packet.currentTurnPlayerId})")
            nodes.values.forEach { target ->
                if (target.id != senderId) {
                    if (dropRatePercent > 0 && packet.type == "PICK_NUMBER" && Random.nextInt(100) < dropRatePercent) {
                        eventLog.add("  -- [DROPPED] to ${target.id}")
                    } else {
                        target.handleIncomingPacket(packet)
                    }
                }
            }
        }

        fun sendDirect(fromId: String, toId: String, packet: RoomMessagePacket) {
            eventLog.add("[$fromId -> $toId DIRECT] ${packet.type}")
            nodes[toId]?.handleIncomingPacket(packet)
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

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 1: 2-PLAYER HEAD-TO-HEAD MATCH LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 2-player head to head complete match with turn alternation and clean win`() {
        val bus = VirtualNetworkBus()
        val p1 = VirtualGameNode("p1", "HostAlice", true, bus)
        val p2 = VirtualGameNode("p2", "GuestBob", false, bus)
        bus.register(p1)
        bus.register(p2)

        val players = listOf(
            Player("p1", "HostAlice", isHost = true),
            Player("p2", "GuestBob", isHost = false)
        )
        val matchSeed = 1001L

        p1.startMatch(matchSeed, players)
        p2.startMatch(matchSeed, players)

        assertEquals("Both players must agree on active picker", p1.currentTurnPlayerId, p2.currentTurnPlayerId)
        assertTrue(p1.isMyTurn || p2.isMyTurn)
        assertFalse(p1.isMyTurn && p2.isMyTurn)

        // Play turns until one completes BINGO
        var turns = 0
        val available = (1..25).shuffled(Random(matchSeed)).toMutableList()

        while (!p1.isGameOver && !p2.isGameOver && available.isNotEmpty() && turns < 25) {
            turns++
            val activeNode = if (p1.isMyTurn) p1 else p2
            val num = available.removeAt(0)
            activeNode.executePick(num)
        }

        assertTrue("Game must conclude", p1.isGameOver && p2.isGameOver)
        assertEquals("Both players must agree on winner", p1.winnerPlayerId, p2.winnerPlayerId)
        assertTrue("Agreed winner must be p1 or p2", p1.winnerPlayerId in listOf("p1", "p2"))
        assertTrue("Exactly one player won", (p1.didPlayerWin && !p2.didPlayerWin) || (!p1.didPlayerWin && p2.didPlayerWin))
        assertFalse("Draw is never allowed", p1.isDrawMatch || p2.isDrawMatch)

        val winnerNode = if (p1.didPlayerWin) p1 else p2
        val loserNode = if (p1.didPlayerWin) p2 else p1

        assertEquals(StampResultType.WON, winnerNode.resolveStamp(winnerNode.id).first)
        assertEquals(StampResultType.LOST, loserNode.resolveStamp(loserNode.id).first)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 2: 3-PLAYER MATCH WITH SIMULTANEOUS BINGO AND TURN-PICK PRIORITY
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 3-player match with simultaneous bingo completion - turn picker wins as undisputed winner`() {
        val bus = VirtualNetworkBus()
        val p1 = VirtualGameNode("p1", "Player1", true, bus)
        val p2 = VirtualGameNode("p2", "Player2", false, bus)
        val p3 = VirtualGameNode("p3", "Player3", false, bus)
        listOf(p1, p2, p3).forEach { bus.register(it) }

        val players = listOf(
            Player("p1", "Player1", isHost = true),
            Player("p2", "Player2", isHost = false),
            Player("p3", "Player3", isHost = false)
        )
        val matchSeed = 2002L
        listOf(p1, p2, p3).forEach { it.startMatch(matchSeed, players) }

        // Setup: P1 (turn picker) and P2 both have 4 lines complete, needing number 21 for 5th line.
        // P3 has 2 lines.
        fun craftBoardWithRows(lines: Int): Board {
            val cells = (0 until 25).map { idx ->
                val r = idx / 5
                val c = idx % 5
                val num = idx + 1
                Cell(row = r, col = c, number = num, markState = CellMarkState.Unmarked)
            }.toMutableList()
            val completedLines = mutableSetOf<LineCoordinate>()
            for (i in 0 until lines) {
                completedLines.add(LineCoordinate(LineType.ROW, i))
                for (c in 0 until 5) {
                    val idx = i * 5 + c
                    cells[idx] = cells[idx].copy(markState = CellMarkState.Marked("test", isOwnPick = true, turnNumber = 1))
                }
            }
            return Board(size = 5, cells = cells, completedLines = completedLines)
        }

        val b4 = craftBoardWithRows(4)
        val b2 = craftBoardWithRows(2)

        listOf(p1, p2, p3).forEach { node ->
            node.playerBoard = if (node.id == "p1" || node.id == "p2") b4 else b2
            node.allPlayerBoards["p1"] = b4
            node.allPlayerBoards["p2"] = b4
            node.allPlayerBoards["p3"] = b2
            node.currentTurnPlayerId = "p1"
            node.isMyTurn = (node.id == "p1")
        }

        // P1 picks number 21 (first number of row 4) which completes 5th line on both P1 and P2!
        p1.executePick(21)

        // Verify across ALL 3 nodes:
        listOf(p1, p2, p3).forEach { node ->
            assertTrue("Node ${node.id} must be in game-over state", node.isGameOver)
            assertEquals("Node ${node.id} must recognize p1 as winner", "p1", node.winnerPlayerId)
            assertEquals("Win reason must be Turn Pick", "Turn Pick", node.winReason)
        }

        // Winner check
        assertTrue("P1 must have didPlayerWin=true", p1.didPlayerWin)
        assertFalse("P2 must have didPlayerWin=false", p2.didPlayerWin)
        assertFalse("P3 must have didPlayerWin=false", p3.didPlayerWin)

        // Runner check
        assertFalse("P1 is winner, not runner", p1.isRunnerMatch)
        assertTrue("P2 achieved Bingo on someone else's pick, so must be RUNNER", p2.isRunnerMatch)
        assertFalse("P3 has 2 lines, cannot be runner", p3.isRunnerMatch)

        // Stamp check
        assertEquals(StampResultType.WON, p1.resolveStamp("p1").first)
        assertEquals(StampResultType.RUNNER, p2.resolveStamp("p2").first)
        assertEquals(StampResultType.LOST, p3.resolveStamp("p3").first)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 3: 4-PLAYER MATCH WITH LINE-COUNT TIE BREAKER
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 4-player match line count tie-breaker on passive pick`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..4).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..4).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 3003L
        nodes.forEach { it.startMatch(matchSeed, players) }

        // Setup: P1 is picker.
        // P1 has 1 line.
        // P2 reaches 5 lines.
        // P3 reaches 6 lines!
        // P4 has 2 lines.
        fun craftBoard(lines: Int): Board {
            val cells = (0 until 25).map { idx ->
                Cell(row = idx / 5, col = idx % 5, number = idx + 1, markState = CellMarkState.Unmarked)
            }.toMutableList()
            val completedLines = mutableSetOf<LineCoordinate>()
            for (i in 0 until lines.coerceAtMost(5)) {
                completedLines.add(LineCoordinate(LineType.ROW, i))
                for (c in 0 until 5) {
                    val idx = i * 5 + c
                    cells[idx] = cells[idx].copy(markState = CellMarkState.Marked("test", isOwnPick = true, turnNumber = 1))
                }
            }
            if (lines > 5) {
                // Add column 0
                completedLines.add(LineCoordinate(LineType.COLUMN, 0))
                for (r in 0 until 5) {
                    val idx = r * 5
                    cells[idx] = cells[idx].copy(markState = CellMarkState.Marked("test", isOwnPick = true, turnNumber = 1))
                }
            }
            return Board(size = 5, cells = cells, completedLines = completedLines)
        }

        val bP1 = craftBoard(1)
        val bP2 = craftBoard(4) // will reach 5
        val bP3 = craftBoard(6) // already 6 lines
        val bP4 = craftBoard(2)

        nodes.forEach { n ->
            n.allPlayerBoards["p1"] = bP1
            n.allPlayerBoards["p2"] = bP2
            n.allPlayerBoards["p3"] = bP3
            n.allPlayerBoards["p4"] = bP4
            n.playerBoard = when (n.id) {
                "p1" -> bP1; "p2" -> bP2; "p3" -> bP3; else -> bP4
            }
            n.currentTurnPlayerId = "p1"
            n.isMyTurn = (n.id == "p1")
        }

        // P1 picks number 21: P2 completes 5th line (5 lines total). P3 already has 6 lines.
        nodes.first().executePick(21)

        // All nodes must declare P3 as WINNER by "Line Count"!
        nodes.forEach { n ->
            assertTrue("Node ${n.id} game over", n.isGameOver)
            assertEquals("P3 must be winner by higher line count (6 vs 5)", "p3", n.winnerPlayerId)
            assertEquals("Win reason must be Line Count", "Line Count", n.winReason)
        }

        assertEquals(StampResultType.WON, nodes[2].resolveStamp("p3").first) // P3 is winner
        assertEquals(StampResultType.RUNNER, nodes[1].resolveStamp("p2").first) // P2 is runner (5 lines)
        assertEquals(StampResultType.LOST, nodes[0].resolveStamp("p1").first) // P1 is loser
        assertEquals(StampResultType.LOST, nodes[3].resolveStamp("p4").first) // P4 is loser
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 4: 4-PLAYER MATCH WITH TURN-DISTANCE TIE BREAKER
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 4-player match circular turn distance tie-breaker when lines are tied`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..4).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..4).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 4004L
        nodes.forEach { it.startMatch(matchSeed, players) }

        // Setup: P1 picks. Both P2 and P4 reach 5 lines (tied on line count).
        // Turn order: [p1, p2, p3, p4].
        // Distance from p1 to p2: (1 - 0) % 4 = 1.
        // Distance from p1 to p4: (3 - 0) % 4 = 3.
        // P2 has shorter turn distance (distance 1 < distance 3), so P2 wins!
        val bP1 = BingoEngine().generateBoard(5)
        val bP2 = createBoardWithCompletedLines(5, 5)
        val bP3 = BingoEngine().generateBoard(5)
        val bP4 = createBoardWithCompletedLines(5, 5)

        nodes.forEach { n ->
            n.allPlayerBoards["p1"] = bP1
            n.allPlayerBoards["p2"] = bP2
            n.allPlayerBoards["p3"] = bP3
            n.allPlayerBoards["p4"] = bP4
            n.playerBoard = when (n.id) {
                "p1" -> bP1; "p2" -> bP2; "p3" -> bP3; else -> bP4
            }
            n.randomizedTurnOrder = players.toMutableList()
            n.currentTurnPlayerId = "p1"
            n.isMyTurn = (n.id == "p1")
        }

        nodes.first().executePick(15)

        nodes.forEach { n ->
            assertTrue("Game over on ${n.id}", n.isGameOver)
            assertEquals("P2 must win via Turn Priority (closer to P1)", "p2", n.winnerPlayerId)
            assertEquals("Win reason must be Turn Priority", "Turn Priority", n.winReason)
        }

        assertEquals(StampResultType.WON, nodes[1].resolveStamp("p2").first)
        assertEquals(StampResultType.RUNNER, nodes[3].resolveStamp("p4").first)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 5: 6-PLAYER MATCH WITH MID-MATCH DISCONNECTION & OFFLINE STAMP
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 6-player match with mid-match player disconnection and offline stamp`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..6).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..6).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 5005L
        nodes.forEach { it.startMatch(matchSeed, players) }

        // P3 disconnects mid-match
        val disconnectedId = "p3"
        nodes.forEach { it.markPlayerDisconnected(disconnectedId) }

        // Find who is directly before P3 in randomizedTurnOrder
        val turnOrderIds = nodes.first().randomizedTurnOrder.map { it.id }
        val p3Idx = turnOrderIds.indexOf("p3")
        val prevPlayerId = turnOrderIds[(p3Idx - 1 + turnOrderIds.size) % turnOrderIds.size]
        val expectedNextPlayerId = turnOrderIds[(p3Idx + 1) % turnOrderIds.size]

        // Turn rotation must skip P3
        val nextAfterPrev = nodes.first().calculateNextTurnPlayerId(prevPlayerId)
        assertEquals("Turn must skip disconnected P3 and go to next active player", expectedNextPlayerId, nextAfterPrev)

        // Complete match: P1 achieves BINGO
        val winBoard = createBoardWithCompletedLines(5, 5)
        nodes.forEach { n ->
            n.allPlayerBoards["p1"] = winBoard
            if (n.id == "p1") n.playerBoard = winBoard
            n.currentTurnPlayerId = "p1"
            n.isMyTurn = (n.id == "p1")
        }

        nodes.first().executePick(10)

        nodes.forEach { n ->
            assertTrue("Game over on ${n.id}", n.isGameOver)
            assertEquals("P1 must be the winner", "p1", n.winnerPlayerId)
        }

        // Offline stamp verification: P3 must get OFFLINE stamp, not LOST or DRAW
        assertEquals(StampResultType.OFFLINE, nodes.first().resolveStamp("p3").first)
        assertEquals("OFFLINE", nodes.first().resolveStamp("p3").second)

        // Winner gets WON stamp
        assertEquals(StampResultType.WON, nodes.first().resolveStamp("p1").first)

        // Other active losers get LOST stamp
        assertEquals(StampResultType.LOST, nodes.first().resolveStamp("p2").first)
        assertEquals(StampResultType.LOST, nodes.first().resolveStamp("p4").first)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 6: 8-PLAYER MATCH WITH TURN TIMEOUTS (NO AUTO-PICK)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 8-player match with turn timeouts cleanly rotates without number marks`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..8).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..8).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 6006L
        nodes.forEach { it.startMatch(matchSeed, players) }

        val initialTurnNumber = nodes.first().turnNumber
        val initialPicker = nodes.first().currentTurnPlayerId

        // Active picker times out (isTimeoutPass = true, number = -1)
        val activeNode = nodes.first { it.id == initialPicker }
        activeNode.executePick(number = -1, isTimeoutPass = true)

        // Verify on ALL nodes:
        nodes.forEach { n ->
            assertEquals("Turn number must increment by 1", initialTurnNumber + 1, n.turnNumber)
            assertFalse("Current turn must rotate away from initial picker", n.currentTurnPlayerId == initialPicker)
            assertTrue("No number must be marked on timeout pass", n.pickedNumbersHistory.isEmpty())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 7: 8-PLAYER MATCH WITH PACKET DROPS & HISTORY RECONCILIATION
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test 8-player match with packet drops reconciles all historical picks from packet history`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..8).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..8).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 7007L
        nodes.forEach { it.startMatch(matchSeed, players) }

        // Play 8 turns in order of randomizedTurnOrder
        val turnOrder = nodes.first().randomizedTurnOrder
        val available = (1..25).shuffled(Random(matchSeed)).toMutableList()

        for (turnIndex in 0 until 8) {
            val activePlayer = turnOrder[turnIndex]
            val activeNode = nodes.first { it.id == activePlayer.id }
            val num = available.removeAt(0)

            // Simulate packet drop for 2 nodes on even turns, and 2 other nodes on odd turns
            val droppedNodes = if (turnIndex % 2 == 0) listOf("p3", "p7") else listOf("p2", "p6")

            // Temporarily unregister dropped nodes so they miss this specific PICK_NUMBER packet
            val unreg = droppedNodes.mapNotNull { dId -> nodes.firstOrNull { it.id == dId && it.id != activeNode.id } }
            unreg.forEach { bus.unregister(it) }

            // Active node executes pick and broadcasts to available peers
            activeNode.executePick(num)

            // Re-register dropped nodes immediately (they missed ONLY this in-flight packet)
            unreg.forEach { bus.register(it) }
        }

        // On the 9th turn, the first player picks again, broadcasting the full cumulative history of all picks!
        val lastPicker = turnOrder[0]
        val lastNode = nodes.first { it.id == lastPicker.id }
        val num9 = available.removeAt(0)
        lastNode.executePick(num9)

        // All 8 nodes must have received the 9th packet and reconciled EVERY single dropped pick!
        val expectedPicks = lastNode.pickedNumbersHistory.toList()
        assertEquals("Total 9 picks must have occurred", 9, expectedPicks.size)

        nodes.forEach { n ->
            assertEquals(
                "Node ${n.id} must reconcile all 9 picks despite dropping intermediate packets",
                expectedPicks.toSet(),
                n.pickedNumbersHistory.toSet()
            )
            assertEquals(
                "Node ${n.id} must have exactly 9 picks",
                9,
                n.pickedNumbersHistory.size
            )
            // Verify board marks: every single picked number must be marked on every node's playerBoard
            expectedPicks.forEach { pickedNum ->
                val cell = n.playerBoard.cells.first { it.number == pickedNum }
                assertTrue(
                    "Cell $pickedNum must be marked on Node ${n.id}'s board",
                    cell.markState != CellMarkState.Unmarked
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 8: MID-MATCH RECONNECT & GAME_SYNC CATCH-UP
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test disconnected player reconnects and catches up via GAME_SYNC packet`() {
        val bus = VirtualNetworkBus()
        val p1 = VirtualGameNode("p1", "Host", true, bus)
        val p2 = VirtualGameNode("p2", "Guest1", false, bus)
        val p3 = VirtualGameNode("p3", "Guest2", false, bus)
        listOf(p1, p2, p3).forEach { bus.register(it) }

        val players = listOf(
            Player("p1", "Host", isHost = true),
            Player("p2", "Guest1", isHost = false),
            Player("p3", "Guest2", isHost = false)
        )
        val matchSeed = 8008L
        listOf(p1, p2, p3).forEach { it.startMatch(matchSeed, players) }

        // Turn 1: 5 picked
        p1.executePick(5)

        // P3 disconnects (unregistered from message bus)
        bus.unregister(p3)

        // P1 and P2 continue playing turns 2, 3, 4:
        p2.executePick(10)
        p1.executePick(15)
        p2.executePick(20)

        assertEquals(4, p1.pickedNumbersHistory.size)
        assertEquals(1, p3.pickedNumbersHistory.size) // P3 is behind!

        // P3 reconnects! Re-registers on message bus and sends REJOIN_GAME:
        bus.register(p3)
        bus.broadcast(
            "p3",
            RoomMessagePacket(
                type = "REJOIN_GAME",
                playerId = "p3",
                displayName = "Guest2",
                seed = matchSeed
            )
        )

        // P1 receives REJOIN_GAME and replies with direct GAME_SYNC to P3!
        // P3 receives GAME_SYNC and reconciles missing picks:
        assertEquals("P3 must catch up to all 4 picks", listOf(5, 10, 15, 20), p3.pickedNumbersHistory.toList())
        assertEquals("P3 must catch up to current turn number", p1.turnNumber, p3.turnNumber)
        assertEquals("P3 must catch up to current active turn picker", p1.currentTurnPlayerId, p3.currentTurnPlayerId)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 9: POST-GAME AGGRESSIVE SYNC ATTACK IMMUNITY
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test post-game flood of GAME_SYNC packets never flips winner or resurrects game`() {
        val bus = VirtualNetworkBus()
        val nodes = (1..4).map { i -> VirtualGameNode("p$i", "Player$i", i == 1, bus) }
        nodes.forEach { bus.register(it) }

        val players = (1..4).map { i -> Player("p$i", "Player$i", isHost = (i == 1)) }
        val matchSeed = 9009L
        nodes.forEach { it.startMatch(matchSeed, players) }

        // P1 wins cleanly with 5 lines
        val bP1 = createBoardWithCompletedLines(5, 5)
        nodes.forEach { n ->
            n.allPlayerBoards["p1"] = bP1
            if (n.id == "p1") n.playerBoard = bP1
            n.currentTurnPlayerId = "p1"
            n.isMyTurn = (n.id == "p1")
        }

        nodes.first().executePick(1)

        val originalWinner = nodes.first().winnerPlayerId
        assertEquals("p1", originalWinner)
        assertTrue(nodes.all { it.isGameOver })

        // Attack: P4 aggressively sends out-of-date GAME_SYNC with different picked history and old turn
        val attackSync = RoomMessagePacket(
            type = "GAME_SYNC",
            turnNumber = 2,
            pickedHistory = listOf(99),
            pickedByHistory = listOf("p4"),
            currentTurnPlayerId = "p4",
            seed = matchSeed,
            playerId = "p4"
        )

        nodes.forEach { n -> n.handleIncomingPacket(attackSync) }

        // Verify 100% stability
        nodes.forEach { n ->
            assertTrue("Game must remain over on ${n.id}", n.isGameOver)
            assertEquals("Winner must remain p1 on ${n.id}", "p1", n.winnerPlayerId)
            assertFalse("Game must not be draw on ${n.id}", n.isDrawMatch)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 10: MULTI-MATCH REMATCH CYCLE (PLAY_AGAIN)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `test multi-match rematch cycle resets state and plays subsequent match cleanly`() {
        val bus = VirtualNetworkBus()
        val p1 = VirtualGameNode("p1", "Host", true, bus)
        val p2 = VirtualGameNode("p2", "Guest", false, bus)
        bus.register(p1)
        bus.register(p2)

        val players = listOf(
            Player("p1", "Host", isHost = true),
            Player("p2", "Guest", isHost = false)
        )

        // MATCH 1
        p1.startMatch(1111L, players)
        p2.startMatch(1111L, players)
        p1.executePick(5)
        p2.executePick(10)
        assertEquals(2, p1.pickedNumbersHistory.size)

        // HOST INITIATES REMATCH VIA PLAY_AGAIN (Match 2 with new seed)
        val match2Seed = 2222L
        p1.startMatch(match2Seed, players)
        val playAgainPacket = RoomMessagePacket(
            type = "PLAY_AGAIN",
            seed = match2Seed,
            boardSize = 5,
            playerId = "p1",
            players = players,
            currentTurnPlayerId = p1.currentTurnPlayerId
        )
        bus.broadcast("p1", playAgainPacket)

        // Both nodes must have clean state for Match 2
        assertEquals(match2Seed, p1.currentMatchSeed)
        assertEquals(match2Seed, p2.currentMatchSeed)
        assertEquals(0, p1.pickedNumbersHistory.size)
        assertEquals(0, p2.pickedNumbersHistory.size)
        assertEquals(1, p1.turnNumber)
        assertEquals(1, p2.turnNumber)
        assertFalse(p1.isGameOver)
        assertFalse(p2.isGameOver)
    }
}
