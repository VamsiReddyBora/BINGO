package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.MatchPick
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class MultiplayerGameplayReducerTest {
    private val engine = BingoEngine()
    private val players = listOf(Player("host", "Kevin", isHost = true), Player("A", "Stuart"), Player("B", "Bob"))
    private val context = MultiplayerGameplayContext(42L, "B", "host", players, players)
    private val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 11L + i) }.toMap()
    private fun state(turn: Int = 2, player: String = "A") = MultiplayerGameplayState(
        emptyList(), boards.getValue("B"), boards.getValue("A"), boards, turn, player, 17_000L)
    private fun wire(packet: RoomMessagePacket) = FastPacketCodec.decode(FastPacketCodec.encode(packet))
    private fun pick(number: Int = 7, closed: Int = 2, picker: String = "A", origin: String = picker) = wire(
        MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "PICK_NUMBER", playerId = picker,
            number = number, turnNumber = closed + 1, seed = 42L, pickedHistory = listOf(number),
            pickedByHistory = listOf(picker), turnIds = listOf(closed)), 42L, origin))

    private fun unchanged(before: MultiplayerGameplayState, decision: MultiplayerGameplayDecision) {
        assertFalse(decision.accepted)
        assertSame("Rejected packets must return the untouched state", before, decision.state)
        assertEquals(17_000L, decision.state.remainingTurnMillis)
    }

    @Test fun `expired direct pick cannot mark a board via its history before rejection`() {
        val before = state(2, "host")
        val result = MultiplayerGameplayReducer.pick(pick(closed = 1), context, before)
        unchanged(before, result)
        assertTrue(result.state.picks.isEmpty())
        assertTrue(result.state.allPlayerBoards.values.all { b -> b.cells.none { it.isMarked } })
    }

    @Test fun `legal open turn pick is applied once with original turn and picker on all boards`() {
        val packet = pick()
        assertEquals("A", packet.senderPlayerId)
        assertEquals(2, packet.closedTurnNumber)
        val result = MultiplayerGameplayReducer.pick(packet, context, state())
        assertTrue(result.accepted)
        assertEquals(listOf(MatchPick(7, "A", 2)), result.state.picks)
        assertEquals(3, result.state.turnNumber)
        assertEquals("B", result.state.currentPlayerId)
        assertEquals(30_000L, result.state.remainingTurnMillis)
        assertTrue(result.state.allPlayerBoards.values.all { it.cells.single { c -> c.number == 7 }.isMarked })
        val later = result.state.copy(remainingTurnMillis = 17_000L)
        unchanged(later, MultiplayerGameplayReducer.pick(packet, context, later))
    }

    @Test fun `peer history cannot inject missed closed turn or future numbers alongside a legal pick`() {
        val before = state()
        val packet = pick().copy(pickedHistory = listOf(9, 7, 11), pickedByHistory = listOf("host", "A", "B"), turnIds = listOf(1, 2, 3))
        val result = MultiplayerGameplayReducer.pick(wire(packet), context, before)
        assertTrue(result.accepted)
        assertEquals(listOf(7), result.state.picks.map { it.number })
        assertTrue(result.state.allPlayerBoards.values.all { board -> board.cells.filter { it.number in listOf(9, 11) }.none { it.isMarked } })
    }

    @Test fun `missed earlier moves recover through the real host snapshot before future direct picks`() {
        val behind = state(1, "host")
        unchanged(behind, MultiplayerGameplayReducer.pick(pick(9, 2), context, behind))
        val host = RoomMessagePacket(type = "GAME_SYNC", playerId = "host", seed = 42L, sequence = 1,
            pickedHistory = listOf(7, 9), pickedByHistory = listOf("host", "A"), turnIds = listOf(1, 2),
            turnNumber = 3, currentTurnPlayerId = "B")
        val recovered = MultiplayerSnapshotReconciler.reconcile(wire(host), 42L, "host", "B", MultiplayerSnapshotCheckpoint(),
            behind.picks, behind.playerBoard, behind.opponentBoard, behind.allPlayerBoards)!!
        val ready = behind.copy(picks = recovered.picks, playerBoard = recovered.playerBoard,
            opponentBoard = recovered.opponentBoard, allPlayerBoards = recovered.allPlayerBoards,
            turnNumber = 3, currentPlayerId = "B")
        val result = MultiplayerGameplayReducer.pick(pick(11, 3, "B"), context, ready)
        assertTrue(result.accepted)
        assertEquals(listOf(7, 9, 11), result.state.picks.map { it.number })
        assertEquals(4, result.state.turnNumber)
    }

    @Test fun `wrong sender turn match and invalid numbers cannot change state`() {
        val before = state()
        val invalid = listOf(pick().copy(seed = 41), pick().copy(matchId = "41"), pick().copy(seed = 0),
            pick(picker = "stranger"), pick(picker = "B"), pick(origin = "B"), pick(closed = 3), pick(number = 0), pick(number = 26),
            pick().copy(closedTurnNumber = 1))
        invalid.forEach { unchanged(before, MultiplayerGameplayReducer.pick(wire(it), context, before)) }
        unchanged(before, MultiplayerGameplayReducer.pick(pick(), context.copy(excludedPlayerIds = setOf("A")), before))
    }

    @Test fun `picks and timeouts cannot change paused suspended or completed games`() {
        val timeout = wire(MultiplayerGameplayReducer.timeoutPacket(42L, "host", "A", 2, "B"))
        for (before in listOf(state().copy(isPaused = true), state().copy(isSuspended = true), state().copy(isGameOver = true))) {
            unchanged(before, MultiplayerGameplayReducer.pick(pick(), context, before))
            unchanged(before, MultiplayerGameplayReducer.timeout(timeout, context, before))
        }
    }

    @Test fun `timeout identifies the skipped player separately from sender and applies once`() {
        val packet = wire(MultiplayerGameplayReducer.timeoutPacket(42L, "host", "A", 2, "B"))
        assertEquals("host", packet.senderPlayerId)
        assertEquals("A", packet.playerId)
        assertEquals(2, packet.closedTurnNumber)
        assertEquals("B", packet.currentTurnPlayerId)
        val result = MultiplayerGameplayReducer.timeout(packet, context, state())
        assertTrue(result.accepted)
        assertEquals(3, result.state.turnNumber)
        assertEquals("B", result.state.currentPlayerId)
        assertEquals(setOf("A"), result.state.disconnectedPlayerIds)
        assertEquals(mapOf("A" to 1), result.state.missedTurns)
        assertEquals(30_000L, result.state.remainingTurnMillis)
        val later = result.state.copy(remainingTurnMillis = 17_000L)
        repeat(10) { unchanged(later, MultiplayerGameplayReducer.timeout(packet, context, later)) }
    }

    @Test fun `timeout cannot blame next player advance gaps or accept another guest controller`() {
        val packet = MultiplayerGameplayReducer.timeoutPacket(42L, "host", "A", 2, "B")
        val before = state()
        for (invalid in listOf(packet.copy(playerId = "B"), packet.copy(turnNumber = 8, closedTurnNumber = 7),
            packet.copy(currentTurnPlayerId = "host"), packet.copy(senderPlayerId = "B"), packet.copy(seed = 41))) {
            unchanged(before, MultiplayerGameplayReducer.timeout(wire(invalid), context, before))
        }
    }

    @Test fun `self pass and existing nearby peer timeout remain supported`() {
        val packet = MultiplayerGameplayReducer.timeoutPacket(42L, "A", "A", 2, "B")
        assertTrue(MultiplayerGameplayReducer.timeout(wire(packet), context, state()).accepted)
        val nearby = context.copy(allowPeerTimeouts = true)
        assertTrue(MultiplayerGameplayReducer.timeout(wire(packet.copy(senderPlayerId = "B")), nearby, state()).accepted)
    }

    @Test fun `duplicate timeout is rejected even when skipped and next player are the same`() {
        val onlyHost = context.copy(excludedPlayerIds = setOf("A", "B"))
        val before = state(8, "host")
        val packet = wire(MultiplayerGameplayReducer.timeoutPacket(42L, "host", "host", 8, "host"))
        val accepted = MultiplayerGameplayReducer.timeout(packet, onlyHost, before)
        assertTrue(accepted.accepted)
        assertEquals(9, accepted.state.turnNumber)
        val later = accepted.state.copy(remainingTurnMillis = 17_000L)
        unchanged(later, MultiplayerGameplayReducer.timeout(packet, onlyHost, later))
    }

    @Test fun `timeouts after several eliminated players keep one normal timer per remaining turn`() {
        val remaining = context.copy(excludedPlayerIds = setOf("A"))
        var current = state(3, "B")
        repeat(30) {
            val next = remaining.nextPlayer(current.currentPlayerId)
            val packet = wire(MultiplayerGameplayReducer.timeoutPacket(42L, "host", current.currentPlayerId, current.turnNumber, next))
            val applied = MultiplayerGameplayReducer.timeout(packet, remaining, current)
            assertTrue(applied.accepted)
            assertEquals(current.turnNumber + 1, applied.state.turnNumber)
            assertEquals(30_000L, applied.state.remainingTurnMillis)
            assertFalse(applied.state.currentPlayerId == "A")
            current = applied.state.copy(remainingTurnMillis = 17_000L)
            unchanged(current, MultiplayerGameplayReducer.timeout(packet, remaining, current))
        }
    }

    private fun winningScenario(): Pair<MultiplayerGameplayState, RoomMessagePacket> {
        val hostBoard = boards.getValue("host")
        var marked = hostBoard
        var last = 0
        for (n in 1..25) {
            marked = engine.markCell(marked, n, "host", false, n)
            if (marked.isBingo) { last = n; break }
        }
        assertTrue(last > 1)
        val prior = (1 until last).map { MatchPick(it, "host", it) }
        fun markedBoard(board: Board): Board {
            var b = board
            prior.forEach { b = engine.markCell(b, it.number, it.playerId, false, it.turn) }
            return b
        }
        val all = boards.mapValues { markedBoard(it.value) }
        val before = state(last, "host").copy(picks = prior, playerBoard = all.getValue("B"),
            opponentBoard = all.getValue("A"), allPlayerBoards = all)
        assertFalse(before.allPlayerBoards.getValue("host").isBingo)
        val result = RoomMessagePacket(type = "BINGO_CLAIMED", playerId = "host", winnerPlayerId = "host",
            number = last, turnNumber = last + 1, seed = 42L, pickedHistory = (1..last).toList(),
            pickedByHistory = (1..last).map { "host" }, turnIds = (1..last).toList())
        return before to wire(MultiplayerGameplayReducer.prepareOutgoing(result, 42L, "host"))
    }

    @Test fun `old win and game over from another match leave boards turn outcome and timer untouched`() {
        val (before, packet) = winningScenario()
        for (type in listOf("BINGO_CLAIMED", "GAME_OVER")) {
            unchanged(before, MultiplayerGameplayReducer.result(wire(packet.copy(type = type, seed = 41, matchId = "41")), context, before))
            assertFalse(before.allPlayerBoards.getValue("host").isBingo)
            assertFalse(before.isGameOver)
        }
    }

    @Test fun `legitimate winning packet recovers a missing current pick and finishes once`() {
        val (before, packet) = winningScenario()
        val result = MultiplayerGameplayReducer.result(packet, context, before)
        assertTrue(result.accepted)
        assertEquals("host", result.winnerId)
        assertTrue(result.state.allPlayerBoards.getValue("host").isBingo)
        assertTrue(result.state.isGameOver)
        assertEquals(before.turnNumber + 1, result.state.turnNumber)
        assertEquals(packet.number, result.state.picks.last().number)
        val replay = MultiplayerGameplayReducer.result(packet, context, result.state)
        assertFalse(replay.accepted)
        assertSame(result.state, replay.state)
    }

    @Test fun `winning claim without accepted bingo or with an expired final pick is rejected atomically`() {
        val (before, packet) = winningScenario()
        unchanged(before, MultiplayerGameplayReducer.result(packet.copy(number = 0), context, before))
        val expired = before.copy(turnNumber = before.turnNumber + 1, currentPlayerId = "A")
        unchanged(expired, MultiplayerGameplayReducer.result(packet, context, expired))
        unchanged(before, MultiplayerGameplayReducer.result(packet.copy(playerId = "stranger", senderPlayerId = "stranger"), context, before))
        unchanged(before, MultiplayerGameplayReducer.result(packet.copy(playerId = "A", senderPlayerId = "A"), context, before))
    }

    @Test fun `result for an already accepted board and host report of another winner still work`() {
        val (before, packet) = winningScenario()
        val acceptedMove = MultiplayerGameplayReducer.pick(packet.copy(type = "PICK_NUMBER"), context, before).state
        assertTrue(MultiplayerGameplayReducer.result(packet, context, acceptedMove).accepted)
        val hostBoard = acceptedMove.allPlayerBoards.getValue("host")
        val anotherWon = acceptedMove.copy(allPlayerBoards = acceptedMove.allPlayerBoards + ("A" to hostBoard))
        assertTrue(MultiplayerGameplayReducer.result(packet.copy(number = 0, winnerPlayerId = "A"), context, anotherWon).accepted)
        assertFalse(MultiplayerGameplayReducer.result(packet.copy(number = 0), context.copy(excludedPlayerIds = setOf("host")), acceptedMove).accepted)
    }

    @Test fun `old surrender wrong owner and unknown sender do not affect current match`() {
        val before = state()
        val packet = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "A"), 42L, "A")
        for (invalid in listOf(packet.copy(seed = 41, matchId = "41"), packet.copy(seed = 0),
            packet.copy(senderPlayerId = "B"), packet.copy(playerId = "stranger", senderPlayerId = "stranger"))) {
            unchanged(before, MultiplayerGameplayReducer.surrender(wire(invalid), context, before))
        }
    }

    @Test fun `explicit surrender remains available during pause and duplicates do not increase misses`() {
        val before = state().copy(isPaused = true)
        val packet = wire(MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "A"), 42L, "A"))
        val accepted = MultiplayerGameplayReducer.surrender(packet, context, before)
        assertTrue(accepted.accepted)
        assertEquals(3, accepted.state.turnNumber)
        assertEquals(30_000L, accepted.state.remainingTurnMillis)
        assertEquals(3, accepted.state.missedTurns["A"])
        assertEquals(setOf("A"), accepted.state.disconnectedPlayerIds)
        val replay = MultiplayerGameplayReducer.surrender(packet, context, accepted.state)
        assertFalse(replay.accepted)
        assertSame(accepted.state, replay.state)
    }

    @Test fun `delayed outgoing resend preserves its old match identity`() {
        val packet = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "BINGO_CLAIMED", playerId = "A"), 41L, "A")
        val delayed = wire(MultiplayerGameplayReducer.prepareOutgoing(packet, 42L, "A"))
        assertEquals(41L, delayed.seed)
        assertEquals("41", delayed.matchId)
        assertFalse(MultiplayerGameplayReducer.belongsToMatch(delayed, context))
    }

    @Test fun `legacy game over cannot bypass online 1v1 coordinator and inactive room rejects gameplay`() {
        assertTrue("GAME_OVER" in MultiplayerGameplayReducer.reliable1v1BlockedTypes)
        assertTrue("BINGO_CLAIMED" in MultiplayerGameplayReducer.reliable1v1BlockedTypes)
        assertFalse(MultiplayerGameplayReducer.belongsToMatch(pick(), context.copy(isActive = false)))
    }
    @Test fun `saved timeout turn and host fence reject replay after restoring the guest process`() {
        OngoingMatchStore.customPrefs = com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences()
        try {
            val packet = wire(MultiplayerGameplayReducer.timeoutPacket(42L, "host", "A", 2, "B"))
            val accepted = MultiplayerGameplayReducer.timeout(packet, context, state()).state
            val checkpoint = MultiplayerSnapshotCheckpoint(turnNumber = accepted.turnNumber)
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData(
                roomCode = "ROOM", matchSeed = 42L, boardSize = 5, accountUid = "B", participants = players,
                playerBoard = accepted.playerBoard, opponentBoard = accepted.opponentBoard,
                allPlayerBoards = accepted.allPlayerBoards, turnNumber = accepted.turnNumber,
                currentTurnPlayerId = accepted.currentPlayerId, remainingTurnMillis = 17_000L,
                multiplayerSnapshotCheckpoint = checkpoint)))
            val saved = OngoingMatchStore.getOngoingMatch(null, "B")!!
            val restored = accepted.copy(turnNumber = saved.turnNumber, currentPlayerId = saved.currentTurnPlayerId,
                remainingTurnMillis = saved.remainingTurnMillis!!)
            unchanged(restored, MultiplayerGameplayReducer.timeout(packet, context, restored))
            val oldSnapshot = RoomMessagePacket(type = "GAME_SYNC", playerId = "host", seed = 42L,
                sequence = 1, turnNumber = 2, currentTurnPlayerId = "A")
            assertNull(MultiplayerSnapshotReconciler.reconcile(oldSnapshot, 42L, "host", "B", saved.multiplayerSnapshotCheckpoint,
                emptyList(), restored.playerBoard, restored.opponentBoard, restored.allPlayerBoards))
        } finally { OngoingMatchStore.customPrefs = null }
    }

    @Test fun `missed turns are not surrender and explicit departure dedup survives restore and passive sync`() {
        val unreachable = state().copy(disconnectedPlayerIds = setOf("A"), missedTurns = mapOf("A" to 3))
        val packet = wire(MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "A"), 42L, "A"))
        val explicit = MultiplayerGameplayReducer.surrender(packet, context, unreachable)
        assertTrue("A player with missed turns can still explicitly surrender", explicit.accepted)
        assertEquals(setOf("A"), explicit.state.surrenderedPlayerIds)
        val checkpoint = MultiplayerSnapshotCheckpoint(turnNumber = explicit.state.turnNumber, surrenderedPlayerIds = explicit.state.surrenderedPlayerIds)
        OngoingMatchStore.customPrefs = com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences()
        try {
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData(roomCode = "ROOM", matchSeed = 42L,
                boardSize = 5, accountUid = "B", participants = players, multiplayerSnapshotCheckpoint = checkpoint)))
            val saved = OngoingMatchStore.getOngoingMatch(null, "B")!!
            val passive = RoomMessagePacket(type = "GAME_SYNC", playerId = "host", seed = 42L, sequence = 1,
                turnNumber = explicit.state.turnNumber, currentTurnPlayerId = explicit.state.currentPlayerId)
            val synced = MultiplayerSnapshotReconciler.reconcile(wire(passive), 42L, "host", "B", saved.multiplayerSnapshotCheckpoint,
                emptyList(), unreachable.playerBoard, unreachable.opponentBoard, unreachable.allPlayerBoards)!!
            assertEquals(setOf("A"), synced.checkpoint.surrenderedPlayerIds)
            // Fresh presence and process restoration can clear transient missed-turn counters.
            val restored = state().copy(surrenderedPlayerIds = synced.checkpoint.surrenderedPlayerIds)
            unchanged(restored, MultiplayerGameplayReducer.surrender(packet, context, restored))
            unchanged(restored, MultiplayerGameplayReducer.pick(pick(), context, restored))
        } finally { OngoingMatchStore.customPrefs = null }
    }

}
