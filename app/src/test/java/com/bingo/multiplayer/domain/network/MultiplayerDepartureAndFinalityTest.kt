package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.Assert.*
import org.junit.Test

class MultiplayerDepartureAndFinalityTest {
    private class FakeClock(var now: Long = 100_000L) : Clock { override fun elapsedRealtime() = now }
    private val players = listOf(Player("host", "Kevin", isHost = true), Player("A", "Stuart"), Player("B", "Bob"))
    private val engine = BingoEngine()
    private val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 11L + i) }.toMap()
    private val context = MultiplayerGameplayContext(42, "host", "host", players, players, requiresHostResultConfirmation = true)
    private fun state(turn: Int = 2, current: String = "A") = MultiplayerGameplayState(emptyList(), boards.getValue("host"),
        boards.getValue("A"), boards, turn, current, 17_000L)
    private fun surrender(id: String = "A") = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = id), 42, id)
    private fun wire(p: RoomMessagePacket) = FastPacketCodec.decode(FastPacketCodec.encode(p))
    private fun sync(picks: List<MatchPick>, turn: Int, sequence: Long = 1, winner: String = "") = wire(RoomMessagePacket(type = "GAME_SYNC", seed = 42,
        playerId = "host", sequence = sequence, pickedHistory = picks.map { it.number }, pickedByHistory = picks.map { it.playerId },
        turnIds = picks.map { it.turn }, turnNumber = turn, currentTurnPlayerId = "B", winnerPlayerId = winner))
    private fun reconcile(p: RoomMessagePacket, s: MultiplayerGameplayState, cp: MultiplayerSnapshotCheckpoint = MultiplayerSnapshotCheckpoint()) =
        MultiplayerSnapshotReconciler.reconcile(p, 42, "host", "host", cp, s.picks, s.playerBoard, s.opponentBoard, s.allPlayerBoards)

    @Test fun `current surrender advances once removes future turns and rejects the old watcher action`() {
        val clock = FakeClock(); val policy = MultiplayerMatchLifecyclePolicy("42", "host", true, "host", players.map { it.id }, clock)
        policy.onTurnStarted("A", 2); clock.now += 10_000
        val queued = policy.onTick().filterIsInstance<LifecycleAction.TurnPlayerInactive>().single()
        val result = MultiplayerGameplayReducer.surrender(wire(surrender()), context, state())
        assertTrue(result.accepted); assertEquals(3, result.state.turnNumber); assertEquals("B", result.state.currentPlayerId)
        assertEquals(30_000L, result.state.remainingTurnMillis)
        result.state.surrenderedPlayerIds.forEach(policy::recordSurrender)
        policy.onTurnStarted(result.state.currentPlayerId, result.state.turnNumber, forceReset = true)
        assertFalse(policy.isCurrentTurnAction(queued, "B", 3))
        assertFalse(policy.activeParticipants.contains("A"))
        policy.onPeerHeartbeat("A"); assertTrue(policy.isPlayerDisconnected("A"))
        clock.now += 10_000
        assertTrue(policy.onTick().filterIsInstance<LifecycleAction.TurnAutoSkipped>().none { it.playerId == "A" })
        var next = result.state
        repeat(10) {
            assertNotEquals("A", next.currentPlayerId)
            val packet = MultiplayerGameplayReducer.timeoutPacket(42, "host", next.currentPlayerId, next.turnNumber,
                context.nextPlayer(next.currentPlayerId, next.surrenderedPlayerIds))
            next = MultiplayerGameplayReducer.timeout(wire(packet), context, next).also { assertTrue(it.accepted) }.state
            assertEquals(30_000L, next.remainingTurnMillis)
        }
    }

    @Test fun `surrender during another turn preserves its countdown and duplicate cannot reset it`() {
        val before = state(current = "B")
        val result = MultiplayerGameplayReducer.surrender(surrender(), context, before)
        assertTrue(result.accepted); assertEquals(2, result.state.turnNumber); assertEquals("B", result.state.currentPlayerId)
        assertEquals(17_000L, result.state.remainingTurnMillis)
        val replay = MultiplayerGameplayReducer.surrender(surrender(), context, result.state)
        assertFalse(replay.accepted); assertSame(result.state, replay.state)
        assertEquals("host", context.nextPlayer("B", result.state.surrenderedPlayerIds))
        assertEquals("B", context.nextPlayer("host", result.state.surrenderedPlayerIds))
    }

    @Test fun `surrender while paused changes to a frozen normal turn and no watchdog skip occurs`() {
        val clock = FakeClock(); val policy = MultiplayerMatchLifecyclePolicy("42", "host", true, "host", players.map { it.id }, clock)
        policy.onTurnStarted("A", 2); policy.pauseMatch()
        val result = MultiplayerGameplayReducer.surrender(surrender(), context, state().copy(isPaused = true))
        assertTrue(result.state.isPaused); assertEquals(30_000L, result.state.remainingTurnMillis)
        result.state.surrenderedPlayerIds.forEach(policy::recordSurrender)
        policy.onTurnStarted(result.state.currentPlayerId, result.state.turnNumber, forceReset = true)
        clock.now += 20_000
        assertTrue(policy.onTick().none { it is LifecycleAction.TurnAutoSkipped || it is LifecycleAction.TurnPlayerInactive })
        assertTrue(policy.resumeMatch(withCountdown = false))
        assertEquals(3, policy.currentTurnNumber); assertEquals("B", policy.currentTurnPlayerId)
    }

    @Test fun `queued skip cannot affect the next occurrence of the same player or a different player`() {
        val policy = MultiplayerMatchLifecyclePolicy("42", "host", true, "host", players.map { it.id }, FakeClock())
        val old = LifecycleAction.TurnAutoSkipped("A", 2)
        assertTrue(policy.isCurrentTurnAction(old, "A", 2))
        assertFalse(policy.isCurrentTurnAction(old, "A", 5)); assertFalse(policy.isCurrentTurnAction(old, "B", 3))
        policy.recordSurrender("A"); assertFalse(policy.isCurrentTurnAction(old, "A", 2))
    }

    @Test fun `host snapshot carries permanent departures and restart restores roster turn and frozen time together`() {
        OngoingMatchStore.customPrefs = AuthRepositoryTest.FakeSharedPreferences()
        try {
            val departed = MultiplayerGameplayReducer.surrender(surrender(), context, state().copy(isPaused = true)).state
            val application = reconcile(sync(emptyList(), 3).copy(surrenderedPlayerIds = listOf("A")), state())!!
            assertEquals(setOf("A"), application.checkpoint.surrenderedPlayerIds)
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData("ROOM", 42, 5, accountUid = "host", participants = players,
                playerBoard = departed.playerBoard, opponentBoard = departed.opponentBoard, allPlayerBoards = departed.allPlayerBoards,
                isPaused = true, remainingTurnMillis = departed.remainingTurnMillis, pauseRemainingSeconds = 12,
                turnNumber = departed.turnNumber, currentTurnPlayerId = departed.currentPlayerId,
                multiplayerSnapshotCheckpoint = application.checkpoint)))
            val restored = OngoingMatchStore.getOngoingMatch(null, "host")!!
            val policy = MultiplayerMatchLifecyclePolicy("42", "host", true, "host", players.map { it.id }, FakeClock())
            restored.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.forEach(policy::recordSurrender)
            policy.onTurnStarted(restored.currentTurnPlayerId, restored.turnNumber)
            assertEquals("B", policy.currentTurnPlayerId); assertEquals(3, policy.currentTurnNumber)
            assertFalse(policy.activeParticipants.contains("A")); assertEquals(30_000L, restored.remainingTurnMillis)
            assertSame(departed, MultiplayerGameplayReducer.surrender(surrender(), context, departed).state)
            val old = sync(emptyList(), 2, sequence = 2)
            assertNull(reconcile(old, departed, restored.multiplayerSnapshotCheckpoint))
        } finally { OngoingMatchStore.customPrefs = null }
    }

    private fun pendingWin(): Pair<MultiplayerGameplayState, RoomMessagePacket> {
        var marked = boards.getValue("A"); var last = 0
        for (n in 1..25) { marked = engine.markCell(marked, n, "A", true, n); if (marked.isBingo) { last = n; break } }
        val prior = (1 until last).map { MatchPick(it, "A", it) }
        fun apply(board: Board): Board { var result = board; prior.forEach { result = engine.markCell(result, it.number, it.playerId, true, it.turn) }; return result }
        val all = boards.mapValues { apply(it.value) }
        val before = state(last).copy(picks = prior, allPlayerBoards = all, playerBoard = all.getValue("host"), opponentBoard = all.getValue("A"))
        assertFalse(before.opponentBoard.isBingo)
        val pick = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "PICK_NUMBER", seed = 42, playerId = "A", number = last,
            turnNumber = last + 1, pickedHistory = (prior.map { it.number } + last), pickedByHistory = List(last) { "A" }, turnIds = (1..last).toList()), 42, "A")
        return before to wire(pick)
    }

    @Test fun `guest optimistic winning pick remains recoverable until host confirms and timeout rejection removes bingo`() {
        val (before, packet) = pendingWin(); val guest = context.copy(localId = "A")
        val pending = MultiplayerGameplayReducer.pick(packet, guest, before)
        assertTrue(pending.accepted); assertTrue(pending.state.allPlayerBoards.getValue("A").isBingo)
        assertFalse(MultiplayerResultAuthority.mayFinalize(guest)); assertFalse(pending.state.isGameOver)
        val hostExpired = MultiplayerGameplayReducer.timeout(MultiplayerGameplayReducer.timeoutPacket(42, "host", "A", before.turnNumber, "B"), context, before)
        assertTrue(hostExpired.accepted)
        val cp = MultiplayerSnapshotCheckpoint(turnNumber = before.turnNumber, picks = before.picks)
        val rejected = reconcile(sync(hostExpired.state.picks, hostExpired.state.turnNumber), pending.state, cp)!!
        assertFalse(rejected.allPlayerBoards.getValue("A").isBingo); assertFalse(rejected.hasPendingPicks)
        assertEquals(before.picks, rejected.picks); assertEquals("", rejected.confirmedWinnerId)
        assertFalse(MultiplayerResultAuthority.mayFinalize(guest, packet))
    }

    @Test fun `a snapshot from before the pending turn keeps the mark without confirming a win`() {
        val (before, packet) = pendingWin()
        val pending = MultiplayerGameplayReducer.pick(packet, context.copy(localId = "A"), before).state
        val application = reconcile(sync(before.picks, before.turnNumber), pending)!!
        assertTrue(application.hasPendingPicks); assertTrue(application.allPlayerBoards.getValue("A").isBingo)
        assertEquals("", application.confirmedWinnerId)
        val unproved = reconcile(sync(before.picks, before.turnNumber, 2, "A"), pending)!!
        assertEquals("", unproved.confirmedWinnerId) // Pending marks are never result proof.
    }

    @Test fun `only confirmed host history finishes a pending bingo and duplicate or older snapshots cannot finish twice`() {
        val (before, packet) = pendingWin(); val guest = context.copy(localId = "A")
        val accepted = MultiplayerGameplayReducer.pick(packet, context, before).state
        val terminal = sync(accepted.picks, accepted.turnNumber, winner = "A")
        assertTrue(MultiplayerResultAuthority.mayFinalize(guest, terminal))
        val result = reconcile(terminal, accepted)!!
        assertEquals("A", result.confirmedWinnerId); assertFalse(result.hasPendingPicks)
        assertNull(reconcile(terminal, accepted, result.checkpoint))
        assertNull(reconcile(sync(before.picks, before.turnNumber, 2), accepted, result.checkpoint))
        assertFalse(MultiplayerResultAuthority.mayFinalize(guest, terminal.copy(seed = 41)))
        assertFalse(MultiplayerResultAuthority.mayFinalize(guest, terminal.copy(playerId = "B")))
    }

    @Test fun `peer result claims are proposals to the host and cannot finalize other guests`() {
        val (before, packet) = pendingWin()
        val claim = packet.copy(type = "BINGO_CLAIMED", winnerPlayerId = "A")
        val guest = context.copy(localId = "B")
        assertFalse(MultiplayerGameplayReducer.result(claim, guest, before).accepted)
        assertTrue(MultiplayerGameplayReducer.result(claim, context, before).accepted)
        assertTrue(MultiplayerResultAuthority.mayFinalize(context))
        assertTrue(MultiplayerResultAuthority.mayFinalize(guest.copy(requiresHostResultConfirmation = false))) // Nearby's existing flow.
    }

    @Test fun `terminal host proof discards speculative future picks instead of keeping a second local result`() {
        val (before, packet) = pendingWin()
        val accepted = MultiplayerGameplayReducer.pick(packet, context, before).state
        val unused = (1..25).first { n -> accepted.picks.none { it.number == n } }
        val speculative = accepted.copy(picks = accepted.picks + MatchPick(unused, "B", accepted.turnNumber))
        val result = reconcile(sync(accepted.picks, accepted.turnNumber, winner = "A"), speculative)!!
        assertEquals("A", result.confirmedWinnerId); assertFalse(result.hasPendingPicks)
        assertEquals(accepted.picks, result.picks)
        assertFalse(result.allPlayerBoards.values.any { b -> b.cells.single { it.number == unused }.isMarked })
    }

    @Test fun `explicit host surrender result remains valid without fabricating a Bingo`() {
        val packet = sync(emptyList(), 3, winner = "host").copy(isMatchFinished = true,
            winReason = "Opponent Surrendered", surrenderedPlayerIds = listOf("A", "B"))
        val result = reconcile(wire(packet), state())!!
        assertFalse(result.playerBoard.isBingo)
        assertEquals("host", result.confirmedWinnerId)
        assertEquals(setOf("A", "B"), result.checkpoint.surrenderedPlayerIds)
        assertEquals("", reconcile(packet.copy(isMatchFinished = false), state())!!.confirmedWinnerId)
        assertEquals("", reconcile(packet.copy(winReason = "Bingo"), state())!!.confirmedWinnerId)
    }

    @Test fun `snapshot cannot reassign an explicitly departed player`() {
        val old = sync(emptyList(), 4).copy(currentTurnPlayerId = "A")
        assertNull(reconcile(old, state(), MultiplayerSnapshotCheckpoint(surrenderedPlayerIds = setOf("A"))))
    }

    @Test fun `later host direct pick cannot confirm a rejected earlier guest win from local history`() {
        val (before, candidate) = pendingWin()
        val order = listOf(players[0], players[2], players[1]) // A's next player is the host.
        val guest = context.copy(localId = "A", turnOrder = order)
        val pending = MultiplayerGameplayReducer.pick(candidate, guest, before).state
        assertEquals("host", pending.currentPlayerId)
        val hostExpired = MultiplayerGameplayReducer.timeout(MultiplayerGameplayReducer.timeoutPacket(42, "host", "A", before.turnNumber, "host"),
            guest.copy(localId = "host"), before).state
        val number = (1..25).first { n -> n != candidate.number && hostExpired.picks.none { it.number == n } }
        val hostHistory = hostExpired.picks + MatchPick(number, "host", hostExpired.turnNumber)
        val hostPick = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "PICK_NUMBER", playerId = "host", seed = 42,
            number = number, turnNumber = hostExpired.turnNumber + 1, pickedHistory = hostHistory.map { it.number },
            pickedByHistory = hostHistory.map { it.playerId }, turnIds = hostHistory.map { it.turn }), 42, "host")
        val observed = MultiplayerGameplayReducer.pick(wire(hostPick), guest, pending)
        assertTrue(observed.accepted)
        assertTrue(observed.state.picks.any { it.number == candidate.number }) // Still speculative until sync.
        val checkpoint = MultiplayerSnapshotReconciler.recordHostProgress(MultiplayerSnapshotCheckpoint(picks = before.picks), wire(hostPick))
        assertFalse(checkpoint.picks.any { it.number == candidate.number })
        val correction = reconcile(sync(hostHistory, observed.state.turnNumber), observed.state, checkpoint)!!
        assertFalse(correction.picks.any { it.number == candidate.number })
        assertFalse(correction.allPlayerBoards.getValue("A").cells.single { it.number == candidate.number }.isMarked)
    }

    @Test fun `legacy host direct pick without complete history advances turn fence without accepting local pending history`() {
        val prior = listOf(MatchPick(1, "A", 1))
        val checkpoint = MultiplayerSnapshotCheckpoint(picks = prior, turnNumber = 2)
        val direct = RoomMessagePacket(type = "PICK_NUMBER", playerId = "host", number = 7, turnNumber = 4, seed = 42)
        val result = MultiplayerSnapshotReconciler.recordHostProgress(checkpoint, direct)
        assertEquals(4, result.turnNumber); assertEquals(prior, result.picks)
    }

    @Test fun `process restart retains unconfirmed winning pick and recovery record for host correction`() {
        OngoingMatchStore.customPrefs = AuthRepositoryTest.FakeSharedPreferences()
        try {
            val (before, packet) = pendingWin(); val guest = context.copy(localId = "A")
            val pending = MultiplayerGameplayReducer.pick(packet, guest, before).state
            val checkpoint = MultiplayerSnapshotCheckpoint(turnNumber = before.turnNumber, picks = before.picks)
            assertFalse(MultiplayerResultAuthority.mayFinalize(guest))
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData("ROOM", 42, 5, accountUid = "A", participants = players,
                playerBoard = pending.playerBoard, opponentBoard = pending.opponentBoard, allPlayerBoards = pending.allPlayerBoards,
                pickedNumbers = pending.picks.map { it.number }, pickedByPlayers = pending.picks.map { it.playerId }, turnIds = pending.picks.map { it.turn },
                turnNumber = pending.turnNumber, currentTurnPlayerId = pending.currentPlayerId, multiplayerSnapshotCheckpoint = checkpoint)))
            val saved = OngoingMatchStore.getOngoingMatch(null, "A")!!
            assertEquals(packet.number, saved.pickedNumbers.last())
            val corrected = MultiplayerSnapshotReconciler.reconcile(sync(before.picks, before.turnNumber + 1), 42, "host", "A",
                saved.multiplayerSnapshotCheckpoint, saved.pickedNumbers.mapIndexed { i,n -> MatchPick(n, saved.pickedByPlayers[i], saved.turnIds[i]) },
                saved.playerBoard!!, saved.opponentBoard!!, saved.allPlayerBoards)!!
            assertFalse(corrected.allPlayerBoards.getValue("A").isBingo)
            assertEquals(before.picks, corrected.picks)
            assertNotNull(OngoingMatchStore.getOngoingMatch(null, "A"))
        } finally { OngoingMatchStore.customPrefs = null }
    }
}
