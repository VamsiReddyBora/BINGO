package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.ManualBoardEngine
import com.bingo.multiplayer.domain.engine.MatchPick
import com.bingo.multiplayer.domain.model.Board
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.Assert.*
import org.junit.Test

class SimplifiedRulesRegressionTest {
    private val engine = BingoEngine()
    private val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
    private val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
    private val now = 1_000_000L
    private fun context(local: String = "K") = MultiplayerGameplayContext(42, local, "K", players, players,
        allowPeerTimeouts = true, requiresHostResultConfirmation = false)
    private fun state(turn: Int = 1, current: String = "K") = MultiplayerGameplayState(emptyList(), boards.getValue("K"),
        boards.getValue("S"), boards, turn, current, 17_000L)
    private fun stored() = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "K",
        playerBoard = boards.getValue("K"), opponentBoard = boards.getValue("S"), allPlayerBoards = boards,
        currentTurnPlayerId = "K", randomizedTurnOrder = players, startedAt = now, lastUpdatedAt = now, lastPlayerActivityAt = now,
        simplifiedRulesVersion = 1, turnClock = MultiplayerTurnClock.start(1, "K", now))
    private fun snapshot(numbers: List<Int> = emptyList(), turn: Int = numbers.size + 1, sequence: Long = 1, sender: String = "S") =
        RoomMessagePacket(type = "GAME_SYNC", playerId = sender, senderPlayerId = sender, roomCode = "ROOM", seed = 42,
            sequence = sequence, timestamp = now + sequence, currentTurnPlayerId = "K", turnNumber = turn,
            pickedHistory = numbers, pickedByHistory = numbers.map { "K" }, turnIds = numbers.indices.map { it + 1 },
            turnClock = MultiplayerTurnClock.start(turn, "K", now + sequence), lastPlayerActivityAt = now,
            simplifiedRulesVersion = 1)
    private fun marked(board: Board, numbers: List<Int>) = numbers.fold(board) { b, n -> engine.markCell(b, n, "K", false, n) }

    @Test fun `online turn starts with thirty and absence turn with five seconds`() {
        assertEquals(30, MultiplayerTurnClock.start(1,"K",now).remainingSeconds(now))
        assertEquals(5, MultiplayerTurnClock.start(1,"K",now,true).remainingSeconds(now))
    }

    @Test fun `online turn background and return preserve eighteen then twelve seconds`() {
        val clock = MultiplayerTurnClock.start(1,"K",now)
        assertEquals(18,clock.remainingSeconds(now+12_000))
        assertEquals(clock,clock.welcomeBack(now+18_000))
        assertEquals(12,clock.welcomeBack(now+18_000).remainingSeconds(now+18_000))
    }

    @Test fun `absence started turn can promote with one second left only once`() {
        val clock = MultiplayerTurnClock.start(1,"S",now,true)
        val promoted = clock.welcomeBack(now+4_000)
        assertEquals(30,promoted.remainingSeconds(now+4_000))
        assertEquals(18,promoted.welcomeBack(now+16_000).remainingSeconds(now+16_000))
        assertTrue(promoted.welcomeBackGranted)
    }

    @Test fun `expired absence turn cannot be recovered or promoted`() {
        val clock = MultiplayerTurnClock.start(1,"S",now,true)
        assertSame(clock,clock.welcomeBack(now+5_000))
        assertEquals(0,clock.remainingSeconds(now+100_000))
    }

    @Test fun `pause and empty-room freeze preserve the exact remainder`() {
        val frozen = MultiplayerTurnClock.start(1,"K",now).freeze(now+13_000)
        assertEquals(17,frozen.remainingSeconds(now+120_000))
        val resumed = frozen.resume(now+120_000)
        assertEquals(17,resumed.remainingSeconds(now+120_000))
        assertEquals(16,resumed.remainingSeconds(now+121_000))
    }

    @Test fun `legacy recovery at zero stays zero instead of receiving a fresh budget`() {
        val restored = MultiplayerTurnClock.restore(8,"S",now,0)
        assertEquals(0,restored.remainingSeconds(now))
    }

    @Test fun `presence is exclusive and old versions cannot overwrite a newer transition`() {
        val online = MultiplayerPresenceRecord("c1","app",1,now,"ONLINE")
        val away = online.copy(version=2,observedAt=now+1,status="AWAY")
        val state = MultiplayerPresenceState().update("S",online).update("S",away)
        assertEquals("AWAY",state.records.getValue("S").status)
        assertEquals(state,state.update("S",online))
        assertEquals("ONLINE",state.update("S",online.copy(version=3)).records.getValue("S").status)
    }

    @Test fun `old connection will cannot disconnect a returned player`() {
        val old = MultiplayerPresenceRecord("old","app",1,now,"ONLINE")
        val fresh = old.copy(connectionId="fresh",version=0,observedAt=now+20)
        val state = MultiplayerPresenceState().update("S",old).update("S",fresh)
        assertEquals(state,state.update("S",old.copy(status="OFFLINE"),true))
        assertEquals(state,state.update("S",old.copy(version=100,observedAt=now+100)))
        assertEquals("ONLINE",state.records.getValue("S").status)
    }

    @Test fun `current connection will works even though its timestamp predates background`() {
        val online = MultiplayerPresenceRecord("c1","app",1,now,"ONLINE")
        val away = online.copy(version=2,observedAt=now+100,status="AWAY")
        val result = MultiplayerPresenceState().update("S",away).update("S",online.copy(status="OFFLINE"),true)
        assertEquals("OFFLINE",result.records.getValue("S").status)
    }

    @Test fun `pure timeouts do not mark healthy players offline or remove future turns`() {
        var s = state()
        repeat(12) {
            val packet = MultiplayerGameplayReducer.timeoutPacket(42,"B",s.currentPlayerId,s.turnNumber,
                context().nextPlayer(s.currentPlayerId,s.surrenderedPlayerIds))
            val decision = MultiplayerGameplayReducer.timeout(packet,context(),s)
            assertTrue(decision.reason,decision.accepted)
            assertTrue(decision.state.disconnectedPlayerIds.isEmpty())
            assertTrue(decision.state.missedTurns.isEmpty())
            assertTrue(decision.state.surrenderedPlayerIds.isEmpty())
            assertTrue(decision.state.picks.isEmpty())
            assertFalse(MultiplayerGameplayReducer.timeout(packet,context(),decision.state).accepted)
            s=decision.state
        }
        assertFalse(s.isGameOver)
        assertEquals("K",s.currentPlayerId)
    }

    @Test fun `any eligible peer can close an expired turn when the lobby host is absent`() {
        val packet = MultiplayerGameplayReducer.timeoutPacket(42,"B","K",1,"S")
        assertTrue(MultiplayerGameplayReducer.timeout(packet,context("S"),state()).accepted)
    }

    @Test fun `free text game-over and finished flags cannot fabricate a winner`() {
        val packet = RoomMessagePacket(type="GAME_OVER",seed=42,playerId="K",senderPlayerId="K",winnerPlayerId="K",winReason="Bingo")
        assertFalse(MultiplayerGameplayReducer.result(packet,context(),state()).accepted)
        val bad = snapshot().copy(winnerPlayerId="intruder",isMatchFinished=true,winReason="Bingo")
        assertEquals("",PeerMatchRecovery.merge(stored(),listOf(bad),now+100).winnerPlayerId)
        assertFalse(PeerMatchRecovery.merge(stored(),listOf(bad),now+100).isMatchFinished)
    }

    @Test fun `another players pick can make an offline board the sole winner`() {
        val stuart = marked(ManualBoardEngine.buildBoard((1..25).toList(),5),(1..21).toList())
        val kevin = (1L..1000L).asSequence().map { marked(engine.generateBoard(5,it),(1..21).toList()) }.first { !it.isBingo }
        val result = MultiplayerResultRules.evaluate(mapOf("K" to kevin,"S" to stuart),listOf("K","S"),emptySet(),"K")!!
        assertEquals("S",result.winnerId)
        assertTrue(result.runnerIds.isEmpty())
    }

    @Test fun `caller wins simultaneous bingo and offline finishers remain runners`() {
        val board = marked(ManualBoardEngine.buildBoard((1..25).toList(),5),(1..21).toList())
        val result = MultiplayerResultRules.evaluate(mapOf("K" to board,"S" to board),listOf("K","S"),emptySet(),"K")!!
        assertEquals("K",result.winnerId)
        assertEquals(listOf("S"),result.runnerIds)
    }

    @Test fun `sole survivor uses permanent departures rather than connectivity`() {
        assertNull(MultiplayerResultRules.evaluate(boards,players.map { it.id },setOf("K"),""))
        assertEquals("B",MultiplayerResultRules.evaluate(boards,players.map { it.id },setOf("K","S"),"")!!.winnerId)
    }

    @Test fun `same display names never identify different participant UIDs as the local board`() {
        assertFalse(LobbyLifecycleEngine.isPlayerMe(Player("S","Alex",username="stuart"),"B","bob","Alex",false))
    }

    @Test fun `peer snapshot catches up without original host and stale heads cannot erase picks`() {
        val current = PeerMatchRecovery.merge(stored(),listOf(snapshot(listOf(7,9))),now+100)
        assertEquals(listOf(7,9),current.pickedNumbers)
        assertEquals(3,current.turnNumber)
        val replay = PeerMatchRecovery.merge(current,listOf(snapshot(emptyList(),1,10,"B")),now+200)
        assertEquals(current.pickedNumbers,replay.pickedNumbers)
        assertEquals(current.multiplayerSnapshotCheckpoint,replay.multiplayerSnapshotCheckpoint)
    }

    @Test fun `per-peer revisions do not reject another peers newer accepted progress`() {
        val one = PeerMatchRecovery.merge(stored(),listOf(snapshot(listOf(7),sequence=99,sender="S")),now+100)
        val two = PeerMatchRecovery.merge(one,listOf(snapshot(listOf(7,9),sequence=1,sender="B")),now+200)
        assertEquals(listOf(7,9),two.pickedNumbers)
        assertEquals(99L,two.multiplayerSnapshotCheckpoint.senderRevisions["S"])
        assertEquals(1L,two.multiplayerSnapshotCheckpoint.senderRevisions["B"])
    }

    @Test fun `accepted snapshot keeps the same shared seventeen-second clock through restore`() {
        val clock = MultiplayerTurnClock.start(1,"K",now).freeze(now+13_000)
        val recovered = PeerMatchRecovery.merge(stored(),listOf(snapshot().copy(turnClock=clock,remainingTurnMillis=17_000)),now+20_000)
        assertEquals(clock,recovered.turnClock)
        assertEquals(17_000L,recovered.remainingTurnMillis)
    }

    @Test fun `recovered chat is deduplicated and aligned to the receiving account`() {
        val chat = InGameChatMessage(123,"hello",isSelf=true,senderPlayerId="S",senderName="Stuart",timestamp=now)
        val packet = snapshot().copy(chatMessages=listOf(chat))
        val recovered = PeerMatchRecovery.merge(stored(),listOf(packet,packet),now+100)
        assertEquals(1,recovered.chatMessages.size)
        assertFalse(recovered.chatMessages.single().isSelf)
        assertEquals("Stuart",recovered.chatMessages.single().senderName)
    }

    @Test fun `a recovered permanent exit is durable and does not forfeit another absent player`() {
        val exit = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type="SURRENDER",seed=42,roomCode="ROOM",playerId="K",timestamp=now),42,"K")
        val recovered = PeerMatchRecovery.merge(stored(),listOf(exit),now+100)
        assertEquals(setOf("K"),recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        assertFalse(recovered.isMatchFinished)
        assertEquals("S",recovered.currentTurnPlayerId)
        assertEquals(recovered,PeerMatchRecovery.merge(recovered,listOf(exit),now+100))
    }

    @Test fun `old match lifecycle packets and transport-only exit cannot change eligibility`() {
        val p = RoomMessagePacket(type="SURRENDER",seed=42,roomCode="ROOM",playerId="K",senderPlayerId="K")
        assertTrue(SimplifiedMatchPacketGate.accepts(p,42,"ROOM",setOf("K","S")))
        assertFalse(SimplifiedMatchPacketGate.accepts(p.copy(seed=41),42,"ROOM",setOf("K","S")))
        assertFalse(SimplifiedMatchPacketGate.accepts(p.copy(roomCode="OTHER"),42,"ROOM",setOf("K","S")))
        assertFalse(SimplifiedMatchPacketGate.accepts(p.copy(type="LEAVE",transportOnly=true),42,"ROOM",setOf("K","S")))
    }

    @Test fun `snapshot cannot alter accepted immutable card layouts`() {
        val altered = snapshot().copy(boardNumbers=mapOf("K" to stored().allPlayerBoards.getValue("K").cells.map { it.number }.reversed()))
        assertEquals(stored(),PeerMatchRecovery.merge(stored(),listOf(altered),now+100))
    }

    @Test fun `auto snapshots do not refresh human activity and real new activity does`() {
        val tick = PeerMatchRecovery.merge(stored(),listOf(snapshot().copy(lastPlayerActivityAt=now)),now+360_000)
        assertEquals(now,tick.lastPlayerActivityAt)
        val active = PeerMatchRecovery.merge(stored(),listOf(snapshot().copy(lastPlayerActivityAt=now+350_000)),now+360_000)
        assertEquals(now+350_000,active.lastPlayerActivityAt)
    }

    @Test fun `new-match checkpoint is independent of a previous finished match`() {
        val old = PeerMatchRecovery.merge(stored(),listOf(snapshot(listOf(7,9))),now+100)
        val next = stored().copy(matchSeed=43)
        val p = snapshot().copy(seed=43)
        assertEquals(emptyList<Int>(),PeerMatchRecovery.merge(next,listOf(p),now+200).pickedNumbers)
        assertEquals(listOf(7,9),old.multiplayerSnapshotCheckpoint.picks.map { it.number })
    }

    @Test fun `outbox survives process recreation and account isolation`() {
        OngoingMatchStore.customPrefs=AuthRepositoryTest.FakeSharedPreferences()
        try {
            val packet = RoomMessagePacket(type="SURRENDER",seed=42,roomCode="ROOM",playerId="K")
            assertTrue(OngoingMatchStore.enqueueExit(null,PendingMatchExit("K","ROOM",packet)))
            assertEquals(1,OngoingMatchStore.pendingExits(null,"K").size)
            assertTrue(OngoingMatchStore.pendingExits(null,"S").isEmpty())
            assertTrue(OngoingMatchStore.acknowledgeExit(null,"K",42))
            assertTrue(OngoingMatchStore.pendingExits(null,"K").isEmpty())
        } finally { OngoingMatchStore.customPrefs=null }
    }

    @Test fun `recovery checkpoint cannot overwrite a replacement match`() {
        OngoingMatchStore.customPrefs=AuthRepositoryTest.FakeSharedPreferences()
        try {
            assertTrue(OngoingMatchStore.saveOngoingMatch(null,stored().copy(matchSeed=43)))
            assertFalse(OngoingMatchStore.saveRecoveredMatch(null,stored()))
            assertEquals(43L,OngoingMatchStore.getOngoingMatch(null,"K")!!.matchSeed)
        } finally { OngoingMatchStore.customPrefs=null }
    }

    @Test fun `finished checkpoint and its result remain available to the rejoin card`() {
        OngoingMatchStore.customPrefs=AuthRepositoryTest.FakeSharedPreferences()
        try {
            val final = stored().copy(isMatchFinished=true,winnerPlayerId="S",runnerPlayerIds=listOf("K"),winReason="Bingo")
            assertTrue(OngoingMatchStore.saveOngoingMatch(null,final))
            assertTrue(OngoingMatchStore.recordMatchResultTombstone(null,"ROOM",42,"S","Bingo",accountUid="K",runnerPlayerIds=listOf("K")))
            val restored = OngoingMatchStore.getOngoingMatch(null,"K")!!
            assertTrue(restored.isMatchFinished)
            assertEquals("S",restored.winnerPlayerId)
            assertEquals(listOf("K"),restored.runnerPlayerIds)
        } finally { OngoingMatchStore.customPrefs=null }
    }

    @Test fun `pick-first live acceptance preserves accepted pick when proposals arrive in reverse order`() {
        val start = stored()
        val pick1 = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 1000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 1000), simplifiedRulesVersion = 1)
        val timeout1 = RoomMessagePacket("TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 30_500,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 30_500), simplifiedRulesVersion = 1)
        val pick2 = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 9, timestamp = now + 2000,
            pickedHistory = listOf(7), turnClock = MultiplayerTurnClock.start(3, "B", now + 2000), simplifiedRulesVersion = 1)

        val live = PeerMatchRecovery.merge(start, listOf(pick1, pick2), now + 3000)
        assertEquals(listOf(7, 9), live.pickedNumbers)

        val coldOrder1 = PeerMatchRecovery.merge(start, listOf(pick1, timeout1, pick2), now + 3000)
        assertEquals(listOf(7, 9), coldOrder1.pickedNumbers)

        val coldReversed = PeerMatchRecovery.merge(start, listOf(timeout1, pick2, pick1), now + 3000)
        assertEquals(listOf(7, 9), coldReversed.pickedNumbers)

        val coldDuplicates = PeerMatchRecovery.merge(start, listOf(timeout1, pick1, timeout1, pick2, pick1), now + 3000)
        assertEquals(listOf(7, 9), coldDuplicates.pickedNumbers)
    }

    @Test fun `timeout-first live acceptance preserves accepted timeout when proposals arrive in reverse order`() {
        val start = stored()
        val pick1 = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 31_000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 31_000), simplifiedRulesVersion = 1)
        val timeout1 = RoomMessagePacket("TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 30_500,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 30_500), simplifiedRulesVersion = 1)
        val pick2 = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 9, timestamp = now + 35_000,
            pickedHistory = emptyList(), turnClock = MultiplayerTurnClock.start(3, "B", now + 35_000), simplifiedRulesVersion = 1)

        val live = PeerMatchRecovery.merge(start, listOf(timeout1, pick2), now + 40_000)
        assertEquals(listOf(9), live.pickedNumbers)

        val coldOrder1 = PeerMatchRecovery.merge(start, listOf(timeout1, pick1, pick2), now + 40_000)
        assertEquals(listOf(9), coldOrder1.pickedNumbers)

        val coldReversed = PeerMatchRecovery.merge(start, listOf(pick1, pick2, timeout1), now + 40_000)
        assertEquals(listOf(9), coldReversed.pickedNumbers)

        val coldDuplicates = PeerMatchRecovery.merge(start, listOf(pick1, timeout1, pick2, pick1, timeout1), now + 40_000)
        assertEquals(listOf(9), coldDuplicates.pickedNumbers)
    }

    @Test fun `off-turn surrender permutations all preserve chronological history`() {
        val start = stored()
        val first = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 1000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 1000), simplifiedRulesVersion = 1)
        val exit = RoomMessagePacket("SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, timestamp = now + 500, simplifiedRulesVersion = 1)
        val second = RoomMessagePacket("PICK_NUMBER", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "K", number = 9, timestamp = now + 2000,
            turnClock = MultiplayerTurnClock.start(3, "K", now + 2000), simplifiedRulesVersion = 1)

        val expected = listOf(7, 9)
        val permutations = listOf(
            listOf(first, second, exit),
            listOf(exit, first, second),
            listOf(first, exit, second),
            listOf(second, exit, first),
            listOf(exit, second, first),
            listOf(second, first, exit)
        )
        for (perm in permutations) {
            val result = PeerMatchRecovery.merge(start, perm, now + 3000)
            assertEquals("Permutation $perm must retain [7, 9]", expected, result.pickedNumbers)
            assertTrue("S must be in surrendered players", "S" in result.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertEquals(3, result.turnNumber)
        }
    }

    @Test fun `outcome publication preserves original caller identity and recovers correctly across observing peers`() {
        val start = stored()
        val originalPick = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 1000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 1000), simplifiedRulesVersion = 1)
        val stuartObservedOutcome = originalPick.copy(
            senderPlayerId = originalPick.senderPlayerId.ifBlank { originalPick.playerId },
            recoveryReplay = true
        )
        val competingTimeout = RoomMessagePacket("TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 30_500,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 30_500), simplifiedRulesVersion = 1)

        val order1 = PeerMatchRecovery.merge(start, listOf(stuartObservedOutcome, competingTimeout), now + 35_000)
        assertEquals(listOf(7), order1.pickedNumbers)

        val order2 = PeerMatchRecovery.merge(start, listOf(competingTimeout, stuartObservedOutcome), now + 35_000)
        assertEquals(listOf(7), order2.pickedNumbers)

        val duplicates = PeerMatchRecovery.merge(start, listOf(competingTimeout, stuartObservedOutcome, competingTimeout, stuartObservedOutcome), now + 35_000)
        assertEquals(listOf(7), duplicates.pickedNumbers)
    }

    @Test fun `command recovery across empty-room frozen checkpoint replays historical picks`() {
        val start = stored()
        val frozenClock = start.turnClock?.freeze(now)
        val emptyRoomSaved = start.copy(turnClock = frozenClock, remainingTurnMillis = 30_000L, isPaused = false)
        val pick1 = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 2000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 2000), simplifiedRulesVersion = 1)
        val pick2 = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 9, timestamp = now + 4000,
            turnClock = MultiplayerTurnClock.start(3, "B", now + 4000), simplifiedRulesVersion = 1)

        val recoveredForward = PeerMatchRecovery.merge(emptyRoomSaved, listOf(pick1, pick2), now + 60_000)
        assertEquals(listOf(7, 9), recoveredForward.pickedNumbers)
        assertEquals(3, recoveredForward.turnNumber)

        val recoveredReversed = PeerMatchRecovery.merge(emptyRoomSaved, listOf(pick2, pick1), now + 60_000)
        assertEquals(listOf(7, 9), recoveredReversed.pickedNumbers)
        assertEquals(3, recoveredReversed.turnNumber)
    }

    @Test fun `manual pause on frozen checkpoint blocks command recovery`() {
        val start = stored()
        val pausedClock = start.turnClock?.freeze(now)
        val manuallyPaused = start.copy(turnClock = pausedClock, remainingTurnMillis = 30_000L, isPaused = true)
        val pick1 = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 2000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 2000), simplifiedRulesVersion = 1)

        val recovered = PeerMatchRecovery.merge(manuallyPaused, listOf(pick1), now + 60_000)
        assertTrue("Manual pause must block command recovery", recovered.pickedNumbers.isEmpty())
        assertEquals(1, recovered.turnNumber)
    }

    @Test fun `future turn 3 surrender cannot erase accepted turn 2 pick across arrival permutations`() {
        val start = stored()
        val pick1 = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 1000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 1000), simplifiedRulesVersion = 1)
        val pick2 = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 9, timestamp = now + 2000,
            turnClock = MultiplayerTurnClock.start(3, "B", now + 2000), simplifiedRulesVersion = 1)
        val exitTurn3 = RoomMessagePacket("SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 3, timestamp = now + 3000, turnClock = MultiplayerTurnClock.start(3, "B", now + 2000), simplifiedRulesVersion = 1)

        val expected = listOf(7, 9)
        val permutations = listOf(
            listOf(exitTurn3, pick1, pick2),
            listOf(pick1, exitTurn3, pick2),
            listOf(pick2, exitTurn3, pick1),
            listOf(exitTurn3, pick2, pick1),
            listOf(pick1, pick2, exitTurn3)
        )
        for (perm in permutations) {
            val result = PeerMatchRecovery.merge(start, perm, now + 5000)
            assertEquals("Permutation $perm must retain [7, 9]", expected, result.pickedNumbers)
            assertTrue("S must be marked surrendered", "S" in result.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertEquals(3, result.turnNumber)
        }
    }

    @Test fun `stale checkpoint with alive remote activity retains alive match status`() {
        val staleLocal = stored().copy(lastPlayerActivityAt = now - 360_000L) // 6 minutes ago (stale locally)
        val remotePacket = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now - 5000L,
            lastPlayerActivityAt = now - 5000L, turnClock = MultiplayerTurnClock.start(2, "S", now - 5000L), simplifiedRulesVersion = 1)

        val result = PeerMatchRecovery.merge(staleLocal, listOf(remotePacket), now)
        assertEquals(now - 5000L, result.lastPlayerActivityAt)
        assertFalse(result.isMatchFinished)
        assertTrue(now - result.lastPlayerActivityAt < MultiplayerTurnClock.INACTIVITY_MILLIS)
    }

    @Test fun `r01 duplicate proposal upgraded by outcome receipt preserves outcome priority`() {
        val start = stored()
        val pickProposal = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 10_000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 10_000), recoveryReplay = false, simplifiedRulesVersion = 1)
        val pickOutcome = pickProposal.copy(recoveryReplay = true)
        val competingTimeout = RoomMessagePacket("TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 30_500,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 30_500),
            recoveryReplay = false, simplifiedRulesVersion = 1)

        val order1 = PeerMatchRecovery.merge(start, listOf(pickProposal, pickOutcome, competingTimeout), now + 35_000)
        assertEquals(listOf(7), order1.pickedNumbers)

        val order2 = PeerMatchRecovery.merge(start, listOf(pickProposal, competingTimeout, pickOutcome), now + 35_000)
        assertEquals(listOf(7), order2.pickedNumbers)

        val order3 = PeerMatchRecovery.merge(start, listOf(competingTimeout, pickProposal, pickOutcome), now + 35_000)
        assertEquals(listOf(7), order3.pickedNumbers)

        val duplicates = PeerMatchRecovery.merge(start, listOf(pickProposal, pickProposal, competingTimeout, pickOutcome, pickProposal), now + 35_000)
        assertEquals(listOf(7), duplicates.pickedNumbers)
    }

    @Test fun `r02 pending turn 1 pick survives stale turn 1 exit and subsequent picks`() {
        val start = stored()
        val kevinPick = RoomMessagePacket("PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 5000,
            turnClock = MultiplayerTurnClock.start(2, "S", now + 5000), simplifiedRulesVersion = 1)
        val kevinExitStaleTurn = RoomMessagePacket("SURRENDER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 1, timestamp = now + 6000, turnClock = start.turnClock, simplifiedRulesVersion = 1)
        val stuartPick = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 9, timestamp = now + 8000,
            turnClock = MultiplayerTurnClock.start(3, "B", now + 8000), simplifiedRulesVersion = 1)

        val permutations = listOf(
            listOf(kevinPick, kevinExitStaleTurn, stuartPick),
            listOf(kevinPick, stuartPick, kevinExitStaleTurn),
            listOf(kevinExitStaleTurn, kevinPick, stuartPick),
            listOf(kevinExitStaleTurn, stuartPick, kevinPick),
            listOf(stuartPick, kevinPick, kevinExitStaleTurn),
            listOf(stuartPick, kevinExitStaleTurn, kevinPick)
        )

        for ((index, perm) in permutations.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, perm, now + 10_000)
            assertEquals("Permutation $index must preserve picks", listOf(7, 9), recovered.pickedNumbers)
            assertEquals("Turn should be Bob's turn 3", 3, recovered.turnNumber)
            assertEquals("Current turn player should be Bob", "B", recovered.currentTurnPlayerId)
            assertTrue("Kevin must be surrendered", "K" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertFalse("Stuart must not be surrendered", "S" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        }
    }

    @Test fun `r04a historical timeout replay reconstructs resumed clock from older checkpoint`() {
        val start = stored()
        val frozenClock = start.turnClock!!.freeze(now + 13_000)
        val olderCheckpoint = start.copy(turnClock = frozenClock, remainingTurnMillis = 17_000L, lastUpdatedAt = now + 13_000)

        val resumedClock = frozenClock.resume(now + 20_000)
        val timeout = RoomMessagePacket("TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 37_500,
            closedTurnClock = resumedClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 37_500),
            recoveryReplay = true, simplifiedRulesVersion = 1)
        val stuartPick = RoomMessagePacket("PICK_NUMBER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "B", number = 15, timestamp = now + 40_000,
            turnClock = MultiplayerTurnClock.start(3, "B", now + 40_000), simplifiedRulesVersion = 1)

        val forward = PeerMatchRecovery.merge(olderCheckpoint, listOf(timeout, stuartPick), now + 50_000)
        assertEquals(listOf(15), forward.pickedNumbers)
        assertEquals(3, forward.turnNumber)
        assertEquals("B", forward.currentTurnPlayerId)

        val reversed = PeerMatchRecovery.merge(olderCheckpoint, listOf(stuartPick, timeout), now + 50_000)
        assertEquals(listOf(15), reversed.pickedNumbers)
        assertEquals(3, reversed.turnNumber)
        assertEquals("B", reversed.currentTurnPlayerId)
    }

    @Test fun `r04b already surrendered player or obsolete pick drains cleanly`() {
        val start = stored()
        val exit1 = RoomMessagePacket("SURRENDER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 1, timestamp = now + 1000, turnClock = start.turnClock, simplifiedRulesVersion = 1)
        val once = PeerMatchRecovery.merge(start, listOf(exit1), now + 2000)
        assertTrue("K" in once.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)

        val reconnected = PeerMatchRecovery.merge(once, listOf(exit1, exit1), now + 3000)
        assertTrue("K" in reconnected.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        assertEquals(once.turnNumber, reconnected.turnNumber)
    }

    @Test fun `r04c off turn surrender with committed pick outcomes preserves picks and next player`() {
        val start = stored()
        val stuartExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = now + 1000,
            turnClock = start.turnClock, simplifiedRulesVersion = 1
        )
        val kevinPick = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "B", number = 7, timestamp = now + 2000,
            turnClock = MultiplayerTurnClock.start(2, "B", now + 2000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )
        val bobPick = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "K", number = 9, timestamp = now + 4000,
            turnClock = MultiplayerTurnClock.start(3, "K", now + 4000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )
        val kevinPick2 = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 3, turnNumber = 4, currentTurnPlayerId = "B", number = 11, timestamp = now + 6000,
            turnClock = MultiplayerTurnClock.start(4, "B", now + 6000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )

        val permutations = listOf(
            listOf(stuartExit, kevinPick, bobPick, kevinPick2),
            listOf(kevinPick, stuartExit, bobPick, kevinPick2),
            listOf(bobPick, kevinPick, stuartExit, kevinPick2),
            listOf(kevinPick, bobPick, stuartExit, kevinPick2),
            listOf(stuartExit, bobPick, kevinPick, kevinPick2),
            listOf(kevinPick2, bobPick, kevinPick, stuartExit),
            listOf(stuartExit, kevinPick, bobPick, kevinPick2, stuartExit, kevinPick)
        )

        for ((index, perm) in permutations.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, perm, now + 10_000)
            assertEquals("Permutation $index must preserve picks", listOf(7, 9, 11), recovered.pickedNumbers)
            assertEquals("Turn should advance to 4", 4, recovered.turnNumber)
            assertEquals("Current turn player should be Bob", "B", recovered.currentTurnPlayerId)
            assertTrue("Stuart must be surrendered", "S" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertFalse("Kevin must not be surrendered", "K" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
            assertFalse("Bob must not be surrendered", "B" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        }
    }

    @Test fun `r04d pending exit when pick loses to timeout preserves timeout outcome and departure`() {
        val start = stored()
        val rejectedPickProposal = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 30_500,
            recoveryReplay = false, simplifiedRulesVersion = 1
        )
        val timeoutOutcome = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 31_000,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 31_000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )
        val kevinPendingExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 2, closedTurnNumber = 0, timestamp = now + 32_000,
            simplifiedRulesVersion = 1
        )

        val permutations = listOf(
            listOf(rejectedPickProposal, timeoutOutcome, kevinPendingExit),
            listOf(timeoutOutcome, rejectedPickProposal, kevinPendingExit),
            listOf(kevinPendingExit, timeoutOutcome, rejectedPickProposal),
            listOf(timeoutOutcome, kevinPendingExit, rejectedPickProposal)
        )

        for ((index, perm) in permutations.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, perm, now + 50_000)
            assertTrue("Permutation $index: pick 7 must not be invented", recovered.pickedNumbers.isEmpty())
            assertEquals("Turn should be turn 2", 2, recovered.turnNumber)
            assertEquals("Current turn player should be Stuart", "S", recovered.currentTurnPlayerId)
            assertTrue("Kevin must be surrendered", "K" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        }
    }

    @Test fun `r04e promoted frozen clock survives rejected uncommitted five second timeout proposal`() {
        val start = stored()
        val absenceClock = MultiplayerTurnClock.start(1, "K", now, isAbsent = true)
        val promotedClock = absenceClock.welcomeBack(now + 2000)
        assertTrue(promotedClock.welcomeBackGranted)
        assertEquals(MultiplayerTurnClock.ABSENCE_MILLIS, promotedClock.initialBudgetMillis)
        assertEquals(30_000L, promotedClock.remainingMillis(now + 2000))

        val frozenClock = promotedClock.copy(frozenRemainingMillis = 17_000L, changedAt = now + 15_000)
        val checkpoint = start.copy(turnClock = frozenClock, remainingTurnMillis = 17_000L)

        val rejectedProposal = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 5500,
            closedTurnClock = absenceClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 5500),
            recoveryReplay = false, simplifiedRulesVersion = 1
        )

        val recovered = PeerMatchRecovery.merge(checkpoint, listOf(rejectedProposal), now + 30_000)
        assertEquals("Turn 1 must survive", 1, recovered.turnNumber)
        assertEquals("Current player must remain Kevin", "K", recovered.currentTurnPlayerId)
        assertEquals("Promoted clock 17s remainder must survive", 17_000L, recovered.remainingTurnMillis)
        assertEquals("Turn clock must retain 17s frozen remainder", 17_000L, recovered.turnClock?.frozenRemainingMillis)
        assertTrue("Turn clock must retain welcomeBackGranted", recovered.turnClock?.welcomeBackGranted == true)
    }

    @Test fun `r04f retained baseline snapshot with rejected proposal and committed timeout preserves outcome`() {
        val start = stored()
        val baselineSnapshot = snapshot(emptyList(), turn = 1, sequence = 1)
        val rejectedProposal = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 30_500,
            recoveryReplay = false, simplifiedRulesVersion = 1
        )
        val timeoutOutcome = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 31_000,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 31_000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )

        val orders = listOf(
            listOf(baselineSnapshot, rejectedProposal, timeoutOutcome),
            listOf(rejectedProposal, baselineSnapshot, timeoutOutcome),
            listOf(timeoutOutcome, baselineSnapshot, rejectedProposal)
        )

        for ((index, order) in orders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 40_000)
            assertTrue("Order $index: pick 7 must not be invented", recovered.pickedNumbers.isEmpty())
            assertEquals("Turn 2 must be reached via timeout", 2, recovered.turnNumber)
            assertEquals("Current turn player must be Stuart", "S", recovered.currentTurnPlayerId)
        }
    }

    @Test fun `r05a snapshot proposal outcome and marker permutations preserve committed outcome`() {
        val start = stored()
        val baselineSnapshot = snapshot(emptyList(), turn = 1, sequence = 1)
        val rejectedPickProposal = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 30_500,
            recoveryReplay = false, simplifiedRulesVersion = 1
        )
        val committedTimeoutOutcome = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 31_000,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 31_000),
            recoveryReplay = true, simplifiedRulesVersion = 1
        )
        val rejectedTimeoutProposal = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 30_500,
            closedTurnClock = start.turnClock, turnClock = MultiplayerTurnClock.start(2, "S", now + 30_500),
            recoveryReplay = false, simplifiedRulesVersion = 1
        )
        val committedPickOutcome = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 30_000,
            recoveryReplay = true, simplifiedRulesVersion = 1
        )

        // Timeout-won scenarios: pick proposal vs committed timeout outcome across permutations + duplicates
        val timeoutOrders = listOf(
            listOf(rejectedPickProposal, baselineSnapshot, committedTimeoutOutcome),
            listOf(baselineSnapshot, rejectedPickProposal, committedTimeoutOutcome),
            listOf(committedTimeoutOutcome, baselineSnapshot, rejectedPickProposal),
            listOf(rejectedPickProposal, committedTimeoutOutcome, baselineSnapshot),
            listOf(rejectedPickProposal, rejectedPickProposal, baselineSnapshot, committedTimeoutOutcome, committedTimeoutOutcome)
        )
        for ((idx, order) in timeoutOrders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 40_000)
            assertTrue("Timeout-won order $idx: pick 7 must not be accepted", recovered.pickedNumbers.isEmpty())
            assertEquals("Timeout-won order $idx: turn 2 reached via timeout", 2, recovered.turnNumber)
            assertEquals("Timeout-won order $idx: current player is Stuart", "S", recovered.currentTurnPlayerId)
        }

        // Pick-won scenarios: timeout proposal vs committed pick outcome across permutations + duplicates
        val pickOrders = listOf(
            listOf(rejectedTimeoutProposal, baselineSnapshot, committedPickOutcome),
            listOf(baselineSnapshot, rejectedTimeoutProposal, committedPickOutcome),
            listOf(committedPickOutcome, baselineSnapshot, rejectedTimeoutProposal),
            listOf(rejectedTimeoutProposal, committedPickOutcome, baselineSnapshot),
            listOf(rejectedTimeoutProposal, rejectedTimeoutProposal, baselineSnapshot, committedPickOutcome, committedPickOutcome)
        )
        for ((idx, order) in pickOrders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 40_000)
            assertEquals("Pick-won order $idx: pick 7 must be accepted", listOf(7), recovered.pickedNumbers)
            assertEquals("Pick-won order $idx: turn 2 reached via pick", 2, recovered.turnNumber)
            assertEquals("Pick-won order $idx: current player is Stuart", "S", recovered.currentTurnPlayerId)
        }
    }

    @Test fun `r05b cold recovery with matching retained wills preserves remainders matrix`() {
        val testRemainders = listOf(17_000L, 5_000L, 30_000L, 0L)
        for (remainder in testRemainders) {
            val baseClock = MultiplayerTurnClock.start(1, "K", now - 40_000) // deadline elapsed
            val savedClock = if (remainder == 0L) baseClock.copy(frozenRemainingMillis = 0L) else baseClock
            val start = stored().copy(
                turnClock = savedClock,
                remainingTurnMillis = remainder,
                presenceState = MultiplayerPresenceState(mapOf(
                    "K" to MultiplayerPresenceRecord("c1", "i1", 1, now - 40_000, "ONLINE"),
                    "S" to MultiplayerPresenceRecord("c2", "i2", 1, now - 40_000, "ONLINE"),
                    "B" to MultiplayerPresenceRecord("c3", "i3", 1, now - 40_000, "ONLINE")
                ))
            )
            val wills = listOf(
                RoomMessagePacket("PLAYER_DISCONNECTED", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
                    connectionGeneration = "c1", senderInstanceId = "i1", timestamp = now - 35_000, transportOnly = true),
                RoomMessagePacket("PLAYER_DISCONNECTED", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
                    connectionGeneration = "c2", senderInstanceId = "i2", timestamp = now - 35_000, transportOnly = true),
                RoomMessagePacket("PLAYER_DISCONNECTED", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
                    connectionGeneration = "c3", senderInstanceId = "i3", timestamp = now - 35_000, transportOnly = true)
            )
            val recovered = PeerMatchRecovery.merge(start, wills, now)
            if (remainder > 0L) {
                assertEquals("Positive remainder $remainder must survive", remainder, recovered.remainingTurnMillis)
                val resumed = recovered.turnClock?.resume(now)
                assertEquals("Resumed clock must preserve remainder seconds", remainder / 1000L, resumed?.remainingSeconds(now)?.toLong())
            } else {
                assertEquals("Zero remainder must be preserved", 0L, recovered.remainingTurnMillis)
            }
        }
    }

    @Test fun `r05c delayed outbox exit across accepted pick preserves causal sequence and next player`() {
        val threePlayers = listOf(
            Player("K", "Kevin", isHost = true),
            Player("S", "Stuart", isHost = false),
            Player("B", "Bob", isHost = false)
        )
        val threeBoards = threePlayers.associate { it.id to engine.generateBoard(5, 42L + it.id.hashCode()) }
        val start = OngoingMatchData(
            "ROOM", 42, 5, participants = threePlayers, accountUid = "K",
            playerBoard = threeBoards.getValue("K"), opponentBoard = threeBoards.getValue("S"),
            allPlayerBoards = threeBoards, currentTurnPlayerId = "K", randomizedTurnOrder = threePlayers,
            startedAt = now, lastPlayerActivityAt = now, turnClock = MultiplayerTurnClock.start(1, "K", now),
            simplifiedRulesVersion = 1
        )
        val ctx = MultiplayerGameplayContext(42, "K", "K", threePlayers, threePlayers,
            allowPeerTimeouts = true, strictIdentity = false)
        val baseState = MultiplayerGameplayState(
            emptyList(), threeBoards.getValue("K"), threeBoards.getValue("S"),
            threeBoards, 1, "K", 30_000L, turnClock = start.turnClock
        )

        fun publishAndDecode(packet: RoomMessagePacket, topic: String): RoomMessagePacket {
            val encoded = FastPacketCodec.encode(packet)
            val decoded = FastPacketCodec.decode(encoded)
            val isOutcome = topic.contains("/outcome/")
            val isAcceptedDeparture = topic.contains("/accepted_exit/")
            val isExitReceipt = (decoded.closedTurnNumber > 0 || decoded.recoveryReplay) && decoded.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT")
            return decoded.copy(
                recoveryReplay = decoded.recoveryReplay || isOutcome || isAcceptedDeparture || (isExitReceipt && topic.contains("/exit/")),
                isRetainedDelivery = true
            )
        }

        fun acceptDeparture(
            state: MultiplayerGameplayState,
            packet: RoomMessagePacket,
            acceptTime: Long = System.currentTimeMillis()
        ): Pair<MultiplayerGameplayState, RoomMessagePacket> {
            val preTurnNumber = state.turnNumber
            val preTurnPlayerId = state.currentPlayerId
            val decision = MultiplayerGameplayReducer.surrender(packet, ctx, state)
            assertTrue("Surrender must be accepted", decision.accepted)
            val afterState = decision.state
            val closesTurn = afterState.turnNumber > preTurnNumber || (afterState.currentPlayerId != preTurnPlayerId && packet.playerId == preTurnPlayerId)
            val clock = if (closesTurn) MultiplayerTurnClock.start(afterState.turnNumber, afterState.currentPlayerId, acceptTime) else packet.turnClock
            val receipt = packet.copy(
                turnNumber = if (closesTurn) afterState.turnNumber else preTurnNumber,
                closedTurnNumber = if (closesTurn) preTurnNumber else 0,
                turnClock = clock,
                recoveryReplay = true
            )
            return afterState to receipt
        }

        // --- Scenario 1: Stuart exits AFTER Kevin's pick on turn 1 (Stuart departs on turn 2) ---
        val kevinPickRaw = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7, timestamp = now + 500
        )
        val pick1Decision = MultiplayerGameplayReducer.pick(kevinPickRaw, ctx, baseState)
        assertTrue("Kevin pick must be accepted", pick1Decision.accepted)
        val kevinPickOutcome = publishAndDecode(kevinPickRaw.copy(recoveryReplay = true), "bingo/v5/match/ROOM/42/outcome/1")

        val stuartRawExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = now + 1_000
        )
        val stuartOutboxProposal = publishAndDecode(stuartRawExit, "bingo/v5/match/ROOM/42/exit/S")
        assertFalse("Raw outbox exit must not be marked recoveryReplay", stuartOutboxProposal.recoveryReplay)

        val (stateAfterStuartExit, stuartAcceptedReceipt) = acceptDeparture(pick1Decision.state, stuartOutboxProposal)
        assertEquals("Stuart exit on turn 2 must close turn 2", 2, stuartAcceptedReceipt.closedTurnNumber)
        assertEquals("Stuart exit on turn 2 must advance to turn 3", 3, stuartAcceptedReceipt.turnNumber)
        val decodedStuartReceipt = publishAndDecode(stuartAcceptedReceipt, "bingo/v5/match/ROOM/42/accepted_exit/S")
        assertTrue("Accepted exit receipt must be recoveryReplay", decodedStuartReceipt.recoveryReplay)

        val bobPickRaw = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 3, turnNumber = 4, currentTurnPlayerId = "K", number = 9, timestamp = now + 1_500
        )
        val pick2Decision = MultiplayerGameplayReducer.pick(bobPickRaw, ctx, stateAfterStuartExit)
        assertTrue("Bob pick must be accepted", pick2Decision.accepted)
        val bobPickOutcome = publishAndDecode(bobPickRaw.copy(recoveryReplay = true), "bingo/v5/match/ROOM/42/outcome/3")

        val receiptOrders = listOf(
            listOf(decodedStuartReceipt, kevinPickOutcome, bobPickOutcome),
            listOf(kevinPickOutcome, decodedStuartReceipt, bobPickOutcome),
            listOf(bobPickOutcome, kevinPickOutcome, decodedStuartReceipt),
            listOf(stuartOutboxProposal, decodedStuartReceipt, kevinPickOutcome, bobPickOutcome),
            listOf(decodedStuartReceipt, stuartOutboxProposal, kevinPickOutcome, bobPickOutcome),
            listOf(kevinPickOutcome, stuartOutboxProposal, bobPickOutcome, decodedStuartReceipt),
            listOf(stuartOutboxProposal, kevinPickOutcome, bobPickOutcome, decodedStuartReceipt, stuartOutboxProposal)
        )

        for ((idx, order) in receiptOrders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 10_000)
            assertEquals("Order $idx: picks must be [7, 9]", listOf(7, 9), recovered.pickedNumbers)
            assertEquals("Order $idx: turn must advance to 4", 4, recovered.turnNumber)
            assertEquals("Order $idx: next player must be Kevin (Stuart departed)", "K", recovered.currentTurnPlayerId)
            assertTrue("Order $idx: Stuart must be in surrendered players", recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.contains("S"))
        }

        // --- Scenario 2: Stuart exits BEFORE Kevin's pick (off-turn surrender on turn 1) ---
        val (_, offTurnReceipt) = acceptDeparture(baseState, stuartOutboxProposal)
        assertEquals("Off-turn exit does not close turn", 0, offTurnReceipt.closedTurnNumber)
        assertEquals("Off-turn exit keeps turn 1", 1, offTurnReceipt.turnNumber)
        val decodedOffTurnReceipt = publishAndDecode(offTurnReceipt, "bingo/v5/match/ROOM/42/accepted_exit/S")

        val kevinPickAfterOffTurn = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "B", number = 7, timestamp = now + 2000
        )
        val bobPickAfterOffTurn = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "K", number = 9, timestamp = now + 3000
        )
        val offTurnOrders = listOf(
            listOf(decodedOffTurnReceipt, kevinPickAfterOffTurn.copy(recoveryReplay = true), bobPickAfterOffTurn.copy(recoveryReplay = true)),
            listOf(kevinPickAfterOffTurn.copy(recoveryReplay = true), decodedOffTurnReceipt, bobPickAfterOffTurn.copy(recoveryReplay = true)),
            listOf(stuartOutboxProposal, decodedOffTurnReceipt, kevinPickAfterOffTurn.copy(recoveryReplay = true), bobPickAfterOffTurn.copy(recoveryReplay = true)),
            listOf(decodedOffTurnReceipt, stuartOutboxProposal, kevinPickAfterOffTurn.copy(recoveryReplay = true), bobPickAfterOffTurn.copy(recoveryReplay = true))
        )
        for ((idx, order) in offTurnOrders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 10_000)
            assertEquals("Off-turn order $idx: picks must be [7, 9]", listOf(7, 9), recovered.pickedNumbers)
            assertEquals("Off-turn order $idx: turn must advance to 3", 3, recovered.turnNumber)
            assertEquals("Off-turn order $idx: next player must be Kevin", "K", recovered.currentTurnPlayerId)
            assertTrue("Off-turn order $idx: Stuart surrendered", recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.contains("S"))
        }

        // --- Scenario 3: Stuart exits AFTER timeout on turn 1 ---
        val timeoutOnTurn1 = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now + 500, recoveryReplay = true
        )
        val timeoutDecision = MultiplayerGameplayReducer.timeout(timeoutOnTurn1, ctx, baseState)
        assertTrue("Timeout must be accepted", timeoutDecision.accepted)
        val (_, timeoutExitReceipt) = acceptDeparture(timeoutDecision.state, stuartOutboxProposal)
        assertEquals("Exit after timeout closes turn 2", 2, timeoutExitReceipt.closedTurnNumber)
        assertEquals("Exit after timeout advances to turn 3", 3, timeoutExitReceipt.turnNumber)
        val decodedTimeoutReceipt = publishAndDecode(timeoutExitReceipt, "bingo/v5/match/ROOM/42/accepted_exit/S")

        val timeoutOrders = listOf(
            listOf(timeoutOnTurn1, decodedTimeoutReceipt, bobPickOutcome),
            listOf(decodedTimeoutReceipt, timeoutOnTurn1, bobPickOutcome),
            listOf(stuartOutboxProposal, timeoutOnTurn1, decodedTimeoutReceipt, bobPickOutcome)
        )
        for ((idx, order) in timeoutOrders.withIndex()) {
            val recovered = PeerMatchRecovery.merge(start, order, now + 10_000)
            assertEquals("Timeout order $idx: picks must be [9]", listOf(9), recovered.pickedNumbers)
            assertEquals("Timeout order $idx: turn must advance to 4", 4, recovered.turnNumber)
            assertEquals("Timeout order $idx: next player must be Kevin", "K", recovered.currentTurnPlayerId)
            assertTrue("Timeout order $idx: Stuart surrendered", recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.contains("S"))
        }
    }

    @Test fun `r05d stale timeout proposal against promoted clock is drained without holding reconciliation`() {
        val promotedClock = MultiplayerTurnClock.start(1, "K", now - 10_000, isAbsent = false).copy(
            welcomeBackGranted = true,
            frozenRemainingMillis = 17_000L
        )
        val start = stored().copy(
            turnNumber = 1,
            currentTurnPlayerId = "K",
            turnClock = promotedClock,
            remainingTurnMillis = 17_000L
        )
        val uncommittedFiveSecTimeoutProposal = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", timestamp = now - 5_000,
            closedTurnClock = MultiplayerTurnClock.start(1, "K", now - 10_000, isAbsent = true).copy(initialBudgetMillis = 5_000L),
            recoveryReplay = false, simplifiedRulesVersion = 1
        )

        val recovered = PeerMatchRecovery.merge(start, listOf(uncommittedFiveSecTimeoutProposal, uncommittedFiveSecTimeoutProposal), now)
        assertEquals("Turn 1 must survive", 1, recovered.turnNumber)
        assertEquals("Current player must remain Kevin", "K", recovered.currentTurnPlayerId)
        assertEquals("Remaining 17s must survive", 17_000L, recovered.remainingTurnMillis)
        assertTrue("Welcome back must remain granted", recovered.turnClock?.welcomeBackGranted == true)

        val sameTurnSnapshot = snapshot(emptyList(), turn = 1, sequence = 2).copy(
            turnClock = promotedClock, remainingTurnMillis = 17_000L
        )
        val recoveredWithSnapshot = PeerMatchRecovery.merge(start, listOf(uncommittedFiveSecTimeoutProposal, sameTurnSnapshot), now)
        assertEquals("Turn 1 survives with same-turn snapshot", 1, recoveredWithSnapshot.turnNumber)
        assertEquals("17s remainder survives with same-turn snapshot", 17_000L, recoveredWithSnapshot.remainingTurnMillis)
    }

    data class SimulationCallbackResult(
        val isExpired: Boolean,
        val turnNumber: Int,
        val currentTurnPlayerId: String,
        val turnClock: MultiplayerTurnClock?,
        val remainingMillis: Long?,
        val lastActivityAt: Long,
        val picks: List<Int>,
        val isReconciling: Boolean,
        val publishedSnapshots: List<Long> = emptyList()
    )

    private fun simulateProductionCallbackCompletion(
        checkpoint: OngoingMatchData,
        bufferedCommands: List<RoomMessagePacket>,
        pendingWills: Map<String, RoomMessagePacket> = emptyMap(),
        recoveryRemainingMillis: Long? = checkpoint.remainingTurnMillis,
        recoveryRemainingTurn: Int = checkpoint.turnNumber,
        recoveryRemainingPlayerId: String = checkpoint.currentTurnPlayerId,
        recoveryRemainingSeed: Long = checkpoint.matchSeed,
        coldPeerRejoin: Boolean = true,
        isGamePaused: Boolean = checkpoint.isPaused,
        freshPeerRecoveryResponse: Boolean = false,
        pendingHumanReturn: Boolean = false,
        pendingHumanReturnAt: Long = 0L,
        nowTime: Long = System.currentTimeMillis()
    ): SimulationCallbackResult {
        var startCheckpoint = checkpoint
        for (will in pendingWills.values) {
            val pres = startCheckpoint.presenceState.update(will.playerId, MultiplayerPresenceRecord(
                will.connectionGeneration, will.senderInstanceId, 0L, will.timestamp, "OFFLINE"
            ), isLastWill = true)
            startCheckpoint = startCheckpoint.copy(presenceState = pres)
        }
        val session = PeerRecoverySession(
            initialData = startCheckpoint,
            localUid = checkpoint.accountUid,
            isColdRejoin = coldPeerRejoin,
            isGamePaused = isGamePaused,
            pendingHumanReturn = pendingHumanReturn,
            pendingHumanReturnAt = pendingHumanReturnAt,
            initialFenceCompleted = false
        )
        if (recoveryRemainingMillis != null) {
            session.recoveryRemainingMillis = recoveryRemainingMillis
            session.recoveryRemainingTurn = recoveryRemainingTurn
            session.recoveryRemainingPlayerId = recoveryRemainingPlayerId
            session.recoveryRemainingSeed = recoveryRemainingSeed
            session.recoveryRemainingSavedAt = checkpoint.lastUpdatedAt
        }
        if (freshPeerRecoveryResponse) {
            session.processPacket(
                snapshot().copy(
                    playerId = "other",
                    isRetainedDelivery = false,
                    recoveryReplay = false
                ),
                nowTime
            )
        }
        bufferedCommands.forEach { session.bufferCommand(it) }
        session.completeRecoveryFence(nowTime)

        return SimulationCallbackResult(
            isExpired = session.isExpired,
            turnNumber = session.data.turnNumber,
            currentTurnPlayerId = session.data.currentTurnPlayerId,
            turnClock = session.data.turnClock,
            remainingMillis = session.recoveryRemainingMillis,
            lastActivityAt = session.data.lastPlayerActivityAt,
            picks = session.data.pickedNumbers,
            isReconciling = session.isReconciling,
            publishedSnapshots = session.publishedSnapshots
        )
    }

    @Test fun `r06a cold recovery with command progress preserves turn remainder matrix and resumes online`() {
        val testNow = 2_000_000L
        val expiredDeadline = testNow - 100_000L // Elapsed while apps were closed

        // Case 1: Same turn remainders matrix (0s, 5s, 17s, 30s)
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val startClock = MultiplayerTurnClock(
                turnNumber = 1, playerId = "K", startedAt = expiredDeadline - 30_000L,
                deadlineAt = expiredDeadline, initialBudgetMillis = 30_000L
            )
            val checkpoint = stored().copy(
                turnNumber = 1, currentTurnPlayerId = "K", turnClock = startClock,
                remainingTurnMillis = remainder, lastPlayerActivityAt = testNow - 10_000L
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = checkpoint, bufferedCommands = emptyList(),
                recoveryRemainingMillis = remainder, recoveryRemainingTurn = 1,
                recoveryRemainingPlayerId = "K", nowTime = testNow
            )
            assertFalse("Match must be alive", result.isExpired)
            assertEquals("Turn must remain 1", 1, result.turnNumber)
            assertEquals("Player must remain Kevin", "K", result.currentTurnPlayerId)
            val resumedRemaining = result.turnClock?.remainingMillis(testNow) ?: -1L
            assertEquals("Remainder $remainder ms must be preserved upon resume", remainder, resumedRemaining)
        }

        // Case 2: Command progress to Turn 2 remainder matrix (0s, 5s, 17s, 30s)
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val startClock = MultiplayerTurnClock(
                turnNumber = 1, playerId = "K", startedAt = expiredDeadline - 30_000L,
                deadlineAt = expiredDeadline, initialBudgetMillis = 30_000L
            )
            val checkpoint = stored().copy(
                turnNumber = 1, currentTurnPlayerId = "K", turnClock = startClock,
                remainingTurnMillis = 30_000L, lastPlayerActivityAt = testNow - 10_000L
            )
            // Command advances turn 1 -> turn 2 (Stuart). Clock carries frozenRemainingMillis = remainder
            val kevinPick = RoomMessagePacket(
                type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
                closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7,
                timestamp = expiredDeadline + 1_000L, recoveryReplay = true,
                turnClock = MultiplayerTurnClock(
                    turnNumber = 2, playerId = "S", startedAt = expiredDeadline,
                    deadlineAt = expiredDeadline + 30_000L, initialBudgetMillis = 30_000L,
                    frozenRemainingMillis = remainder
                )
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = checkpoint, bufferedCommands = listOf(kevinPick),
                recoveryRemainingMillis = 30_000L, recoveryRemainingTurn = 1,
                recoveryRemainingPlayerId = "K", nowTime = testNow
            )
            assertFalse("Match must be alive after command progress", result.isExpired)
            assertEquals("Turn must advance to 2", 2, result.turnNumber)
            assertEquals("Player must be Stuart", "S", result.currentTurnPlayerId)
            assertEquals("Picks must include 7", listOf(7), result.picks)
            val resumedRemaining = result.turnClock?.remainingMillis(testNow) ?: -1L
            assertEquals("Turn 2 remainder $remainder ms must be restored upon resume", remainder, resumedRemaining)
        }
    }

    @Test fun `r06b recovery callback updates activity from buffered accepted picks and guards against stale expiry`() {
        val testNow = 2_000_000L
        val staleActivityTime = testNow - 400_000L // 6.6 minutes ago > 5 minute inactivity limit

        val startClock = MultiplayerTurnClock.start(1, "K", staleActivityTime)
        val checkpoint = stored().copy(
            turnNumber = 1, currentTurnPlayerId = "K", turnClock = startClock,
            lastPlayerActivityAt = staleActivityTime
        )

        // Sub-scenario 1: Recent peer pick (20 seconds ago) revives match activity
        val recentPeerPick = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7,
            timestamp = testNow - 20_000L, recoveryReplay = true,
            turnClock = MultiplayerTurnClock.start(2, "S", testNow - 20_000L)
        )
        val aliveResult = simulateProductionCallbackCompletion(
            checkpoint = checkpoint, bufferedCommands = listOf(recentPeerPick),
            nowTime = testNow, pendingHumanReturn = false
        )
        assertFalse("Match with recent peer pick must not expire", aliveResult.isExpired)
        assertEquals("Activity must be updated to recent pick", testNow - 20_000L, aliveResult.lastActivityAt)
        assertEquals("Turn must advance to 2", 2, aliveResult.turnNumber)

        // Sub-scenario 2: No peer picks, background automatic reconnect (pendingHumanReturn = false)
        val deadResult = simulateProductionCallbackCompletion(
            checkpoint = checkpoint, bufferedCommands = emptyList(),
            nowTime = testNow, pendingHumanReturn = false
        )
        assertTrue("Match with stale activity and no peer picks must expire on automatic reconnect", deadResult.isExpired)

        // Sub-scenario 3: Stale checkpoint but human returns within 5m of reconnect
        val humanReturnResult = simulateProductionCallbackCompletion(
            checkpoint = checkpoint.copy(lastPlayerActivityAt = testNow - 200_000L), // 3.3 min ago
            bufferedCommands = emptyList(),
            nowTime = testNow, pendingHumanReturn = true, pendingHumanReturnAt = testNow
        )
        assertFalse("Match must survive when human returns within 5m", humanReturnResult.isExpired)
        assertEquals("Human return must update last activity to now", testNow, humanReturnResult.lastActivityAt)
    }

    @Test fun `r06c correcting snapshot after marker reconciliation updates state cleanly`() {
        val testNow = 2_000_000L
        val candidateBoards = boards
        val snapshotPacket = RoomMessagePacket(
            type = "GAME_SYNC", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            sequence = 5, timestamp = testNow - 1000L, currentTurnPlayerId = "B", turnNumber = 3,
            pickedHistory = listOf(7, 9), pickedByHistory = listOf("K", "S"), turnIds = listOf(1, 2),
            boardNumbers = candidateBoards.mapValues { (_, b) -> b.cells.map { it.number } },
            turnClock = MultiplayerTurnClock.start(3, "B", testNow - 1000L),
            lastPlayerActivityAt = testNow - 1000L, simplifiedRulesVersion = 1
        )
        val reconciled = MultiplayerSnapshotReconciler.reconcile(
            packet = snapshotPacket, matchSeed = 42L, hostId = "K", localId = "K",
            checkpoint = MultiplayerSnapshotCheckpoint(revision = 1, turnNumber = 1),
            localPicks = emptyList(),
            playerBoard = boards.getValue("K"), opponentBoard = boards.getValue("S"),
            allPlayerBoards = candidateBoards, allowPeerSnapshots = true,
            participantIds = players.map { it.id }, turnOrder = players.map { it.id }
        )
        assertNotNull("Reconciled application must not be null", reconciled)
        assertEquals("Picks must be reconciled", listOf(7, 9), reconciled!!.picks.map { it.number })
        assertEquals("Checkpoint turn must update", 3, reconciled.checkpoint.turnNumber)

        val session = PeerRecoverySession(
            initialData = stored().copy(lastPlayerActivityAt = testNow - 1000L),
            localUid = "K",
            isColdRejoin = true
        )
        session.completeRecoveryFence(testNow)
        assertEquals(1, session.data.turnNumber)
        assertFalse(session.isReconciling)
        session.processPacket(snapshotPacket, testNow)
        assertEquals(listOf(7, 9), session.data.pickedNumbers)
        assertEquals(3, session.data.turnNumber)
        assertEquals("B", session.data.currentTurnPlayerId)
    }

    @Test fun `r07a accepted off-turn exit with earlier created later delivered pick preserves turn owner and picks in lookup and callback recovery`() {
        val threePlayers = listOf(
            Player("K", "Kevin", isHost = true),
            Player("S", "Stuart", isHost = false),
            Player("B", "Bob", isHost = false)
        )
        val threeBoards = threePlayers.associate { it.id to engine.generateBoard(5, 42L + it.id.hashCode()) }
        val start = OngoingMatchData(
            "ROOM", 42, 5, participants = threePlayers, accountUid = "K",
            playerBoard = threeBoards.getValue("K"), opponentBoard = threeBoards.getValue("S"),
            allPlayerBoards = threeBoards, currentTurnPlayerId = "K", randomizedTurnOrder = threePlayers,
            startedAt = now, lastPlayerActivityAt = now, turnClock = MultiplayerTurnClock.start(1, "K", now),
            simplifiedRulesVersion = 1
        )

        // Stuart confirms Exit off-turn during turn 1 at T=20_000
        val stuartRawExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = now + 20_000L
        )
        val stuartAcceptedReceipt = RoomMessagePacket(
            type = "SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = now + 20_000L, recoveryReplay = true,
            isRetainedDelivery = true
        )

        // Kevin's pick 7 created earlier at T=10_000, but delivered later
        val kevinPickOutcome = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "B", number = 7,
            timestamp = now + 10_000L, recoveryReplay = true, isRetainedDelivery = true
        )

        // Bob's pick 9 on turn 2 at T=30_000
        val bobPickOutcome = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "B", senderPlayerId = "B", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 2, turnNumber = 3, currentTurnPlayerId = "K", number = 9,
            timestamp = now + 30_000L, recoveryReplay = true, isRetainedDelivery = true
        )

        val permutations = listOf(
            listOf(stuartAcceptedReceipt, kevinPickOutcome, bobPickOutcome),
            listOf(kevinPickOutcome, stuartAcceptedReceipt, bobPickOutcome),
            listOf(bobPickOutcome, kevinPickOutcome, stuartAcceptedReceipt),
            listOf(stuartRawExit, stuartAcceptedReceipt, kevinPickOutcome, bobPickOutcome),
            listOf(stuartAcceptedReceipt, stuartRawExit, kevinPickOutcome, bobPickOutcome),
            // Inverted timestamps: Kevin T=5000, Stuart T=25000, Bob T=15000
            listOf(
                stuartAcceptedReceipt.copy(timestamp = now + 25_000L),
                kevinPickOutcome.copy(timestamp = now + 5_000L),
                bobPickOutcome.copy(timestamp = now + 15_000L)
            ),
            // Duplicates with raw proposals
            listOf(
                stuartRawExit,
                kevinPickOutcome,
                stuartAcceptedReceipt,
                bobPickOutcome,
                stuartRawExit
            )
        )

        for ((idx, order) in permutations.withIndex()) {
            // Path 1: Lookup recovery via PeerMatchRecovery.merge
            val recovered = PeerMatchRecovery.merge(start, order, now + 50_000L)
            assertEquals("Permutation $idx (lookup): picks must be [7, 9]", listOf(7, 9), recovered.pickedNumbers)
            assertEquals("Permutation $idx (lookup): turn must advance to 3", 3, recovered.turnNumber)
            assertEquals("Permutation $idx (lookup): next player must be Kevin", "K", recovered.currentTurnPlayerId)
            assertTrue("Permutation $idx (lookup): Stuart permanently departed", recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.contains("S"))

            // Path 2: Production callback recovery
            val callbackResult = simulateProductionCallbackCompletion(
                checkpoint = start, bufferedCommands = order, nowTime = now + 50_000L
            )
            assertFalse("Permutation $idx (callback): must not expire", callbackResult.isExpired)
            assertEquals("Permutation $idx (callback): picks must be [7, 9]", listOf(7, 9), callbackResult.picks)
            assertEquals("Permutation $idx (callback): turn must advance to 3", 3, callbackResult.turnNumber)
            assertEquals("Permutation $idx (callback): next player must be Kevin", "K", callbackResult.currentTurnPlayerId)
        }
    }

    @Test fun `r07b recovery completion publishes single positive remainder without intermediate zeros across remainder matrix`() {
        val testNow = 3_000_000L
        val expiredDeadline = testNow - 50_000L

        // Sub-test 1: Same-turn recovery for remainders 0s, 5s, 17s, 30s
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val startClock = MultiplayerTurnClock(
                turnNumber = 1, playerId = "K", startedAt = expiredDeadline - 30_000L,
                deadlineAt = expiredDeadline, initialBudgetMillis = 30_000L
            )
            val checkpoint = stored().copy(
                turnNumber = 1, currentTurnPlayerId = "K", turnClock = startClock,
                remainingTurnMillis = remainder, lastPlayerActivityAt = testNow - 10_000L
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = checkpoint, bufferedCommands = emptyList(),
                recoveryRemainingMillis = remainder, recoveryRemainingTurn = 1,
                recoveryRemainingPlayerId = "K", nowTime = testNow
            )
            assertFalse("Must not be expired", result.isExpired)
            assertEquals("Turn must remain 1", 1, result.turnNumber)
            assertEquals("Must have published exactly once upon completion", 1, result.publishedSnapshots.size)
            if (remainder > 0L) {
                assertTrue("Positive remainder must never publish intermediate zero: ${result.publishedSnapshots}",
                    result.publishedSnapshots.none { it == 0L })
                assertEquals("Published remainder must match exact positive remainder", remainder, result.publishedSnapshots.first())
            } else {
                assertEquals("Genuine 0s expiry must publish 0s remainder", 0L, result.publishedSnapshots.first())
            }
        }

        // Sub-test 2: Command-progress recovery (Turn 1 -> Turn 2) for remainders 0s, 5s, 17s, 30s
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val startClock = MultiplayerTurnClock(
                turnNumber = 1, playerId = "K", startedAt = expiredDeadline - 30_000L,
                deadlineAt = expiredDeadline, initialBudgetMillis = 30_000L
            )
            val checkpoint = stored().copy(
                turnNumber = 1, currentTurnPlayerId = "K", turnClock = startClock,
                remainingTurnMillis = 30_000L, lastPlayerActivityAt = testNow - 10_000L
            )
            val kevinPick = RoomMessagePacket(
                type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
                closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7,
                timestamp = expiredDeadline + 1_000L, recoveryReplay = true,
                turnClock = MultiplayerTurnClock(
                    turnNumber = 2, playerId = "S", startedAt = expiredDeadline,
                    deadlineAt = expiredDeadline + 30_000L, initialBudgetMillis = 30_000L,
                    frozenRemainingMillis = remainder
                )
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = checkpoint, bufferedCommands = listOf(kevinPick),
                recoveryRemainingMillis = 30_000L, recoveryRemainingTurn = 1,
                recoveryRemainingPlayerId = "K", nowTime = testNow
            )
            assertFalse("Must not be expired", result.isExpired)
            assertEquals("Turn must advance to 2", 2, result.turnNumber)
            assertEquals("Must publish exactly once", 1, result.publishedSnapshots.size)
            if (remainder > 0L) {
                assertTrue("Positive remainder must never publish intermediate zero",
                    result.publishedSnapshots.none { it == 0L })
                assertEquals("Published remainder must match restored turn 2 remainder", remainder, result.publishedSnapshots.first())
            } else {
                assertEquals("Genuine zero must publish 0s", 0L, result.publishedSnapshots.first())
            }
        }

        // Sub-test 3: Manual pause during recovery
        val pausedClock = MultiplayerTurnClock.start(1, "K", testNow - 10_000L).copy(frozenRemainingMillis = 17_000L)
        val pausedCheckpoint = stored().copy(
            turnNumber = 1, currentTurnPlayerId = "K", turnClock = pausedClock,
            remainingTurnMillis = 17_000L, isPaused = true, lastPlayerActivityAt = testNow - 5_000L
        )
        val pausedResult = simulateProductionCallbackCompletion(
            checkpoint = pausedCheckpoint, bufferedCommands = emptyList(),
            recoveryRemainingMillis = 17_000L, isGamePaused = true, nowTime = testNow
        )
        assertEquals("Paused recovery must publish exact frozen remainder", listOf(17_000L), pausedResult.publishedSnapshots)
        assertTrue("Paused clock must remain frozen", pausedResult.turnClock?.frozenRemainingMillis == 17_000L)

        // Sub-test 4: Two returning peers (Peer 1 recovers, publishes 17s; Peer 2 reconciles)
        val peer1Result = simulateProductionCallbackCompletion(
            checkpoint = stored().copy(turnNumber = 1, currentTurnPlayerId = "K", remainingTurnMillis = 17_000L, lastPlayerActivityAt = testNow - 5_000L),
            bufferedCommands = emptyList(), recoveryRemainingMillis = 17_000L, nowTime = testNow
        )
        assertEquals(listOf(17_000L), peer1Result.publishedSnapshots)
        val peer2Result = simulateProductionCallbackCompletion(
            checkpoint = stored().copy(turnNumber = 1, currentTurnPlayerId = "K", remainingTurnMillis = 17_000L, lastPlayerActivityAt = testNow - 5_000L),
            bufferedCommands = emptyList(), recoveryRemainingMillis = 17_000L, nowTime = testNow
        )
        assertEquals(listOf(17_000L), peer2Result.publishedSnapshots)
    }

    @Test fun `r07c delayed off-turn exit accepted during departing player turn establishes fresh budget and preserves activity timestamp`() {
        val threePlayers = listOf(
            Player("K", "Kevin", isHost = true),
            Player("S", "Stuart", isHost = false),
            Player("B", "Bob", isHost = false)
        )
        val threeBoards = threePlayers.associate { it.id to engine.generateBoard(5, 42L + it.id.hashCode()) }
        val t0 = 1_000_000L

        // Turn 1 start at T+0
        val t0Clock = MultiplayerTurnClock.start(1, "K", t0, isAbsent = false)
        val startData = OngoingMatchData(
            "ROOM", 42, 5, participants = threePlayers, accountUid = "K",
            playerBoard = threeBoards.getValue("K"), opponentBoard = threeBoards.getValue("S"),
            allPlayerBoards = threeBoards, currentTurnPlayerId = "K", randomizedTurnOrder = threePlayers,
            startedAt = t0, lastPlayerActivityAt = t0, turnClock = t0Clock,
            simplifiedRulesVersion = 1
        )
        val ctx = MultiplayerGameplayContext(42, "K", "K", threePlayers, threePlayers,
            allowPeerTimeouts = true, strictIdentity = false)
        val baseState = MultiplayerGameplayState(
            emptyList(), threeBoards.getValue("K"), threeBoards.getValue("S"),
            threeBoards, 1, "K", 30_000L, turnClock = t0Clock
        )

        // Stuart confirms Exit off-turn during Kevin's turn at T+0, but publication is delayed
        val stuartDelayedExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "S", senderPlayerId = "S", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = t0,
            turnClock = t0Clock // carries Kevin's turn 1 clock from T+0
        )

        // Kevin picks 7 at T+28,000, advancing turn to 2 (Stuart)
        val kevinPick = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = "S", number = 7,
            timestamp = t0 + 28_000L, recoveryReplay = true,
            turnClock = MultiplayerTurnClock.start(2, "S", t0 + 28_000L, isAbsent = true)
        )
        val pickDecision = MultiplayerGameplayReducer.pick(kevinPick, ctx, baseState)
        assertTrue("Kevin pick accepted", pickDecision.accepted)
        val stateTurn2 = pickDecision.state
        assertEquals(2, stateTurn2.turnNumber)
        assertEquals("S", stateTurn2.currentPlayerId)

        // Stuart's delayed exit arrives at T+31,000 during Stuart's turn 2
        val acceptTime = t0 + 31_000L
        val preTurnNumber = stateTurn2.turnNumber
        val preTurnPlayerId = stateTurn2.currentPlayerId
        val decision = MultiplayerGameplayReducer.surrender(stuartDelayedExit, ctx, stateTurn2)
        assertTrue("Surrender must be accepted", decision.accepted)
        val stateAfterSurrender = decision.state
        val clock = PeerMatchRecovery.resolveTransitionClock(
            packet = stuartDelayedExit,
            nextTurnNumber = stateAfterSurrender.turnNumber,
            nextPlayerId = stateAfterSurrender.currentPlayerId,
            now = acceptTime,
            isAbsent = false,
            currentClock = stateTurn2.turnClock
        )
        val acceptedDeparture = PeerMatchRecovery.buildAcceptedDepartureReceipt(
            packet = stuartDelayedExit,
            preTurnNumber = preTurnNumber,
            preTurnPlayerId = preTurnPlayerId,
            nextTurnNumber = stateAfterSurrender.turnNumber,
            nextPlayerId = stateAfterSurrender.currentPlayerId,
            successorClock = clock
        )
        assertEquals(3, stateAfterSurrender.turnNumber)
        assertEquals("B", stateAfterSurrender.currentPlayerId)
        assertEquals("Stuart exit closes turn 2", 2, acceptedDeparture.closedTurnNumber)
        assertEquals("Advances to turn 3", 3, acceptedDeparture.turnNumber)
        assertTrue("Accepted departure carries recoveryReplay", acceptedDeparture.recoveryReplay)

        // Successor Bob is ONLINE: clock must start at acceptTime (T+31,000) giving full 30s budget
        val bobClock = acceptedDeparture.turnClock
        assertNotNull("Accepted departure must record the newly established clock", bobClock)
        assertEquals("Bob clock turn must be 3", 3, bobClock!!.turnNumber)
        assertEquals("Bob clock player must be Bob", "B", bobClock.playerId)
        assertEquals("Bob clock started at acceptance time", acceptTime, bobClock.startedAt)
        assertEquals("Bob clock deadline must be acceptance time + 30s", acceptTime + 30_000L, bobClock.deadlineAt)
        assertEquals("Bob must receive 30s remaining at acceptTime", 30_000L, bobClock.remainingMillis(acceptTime))

        // Activity timestamp check: human activity must remain original T+0 (or Kevin's pick T+28s), NOT acceptTime T+31s
        val updatedActivity = maxOf(t0 + 28_000L, stuartDelayedExit.timestamp.coerceAtMost(acceptTime))
        assertEquals("Activity timestamp must not be refreshed by delayed transport", t0 + 28_000L, updatedActivity)

        // Replay in recovery: PeerMatchRecovery.merge reproduces the accepted clock
        val recovered = PeerMatchRecovery.merge(
            startData, listOf(kevinPick, acceptedDeparture), acceptTime + 2_000L
        )
        assertEquals(3, recovered.turnNumber)
        assertEquals("B", recovered.currentTurnPlayerId)
        val recoveredClock = recovered.turnClock
        assertNotNull(recoveredClock)
        assertEquals("Recovery reproduces accepted clock turn 3", 3, recoveredClock!!.turnNumber)
        assertEquals("Recovery reproduces accepted clock player Bob", "B", recoveredClock.playerId)
        assertEquals("Recovery reproduces accepted clock deadline", acceptTime + 30_000L, recoveredClock.deadlineAt)
        assertEquals("Recovery preserves 28s remaining at T+33s", 28_000L, recoveredClock.remainingMillis(acceptTime + 2_000L))
        assertEquals("Activity timestamp preserved without transport refresh", t0 + 28_000L, recovered.lastPlayerActivityAt)

        // Repeat with Bob ABSENT: successor receives 5s budget
        val absentBobPresence = MultiplayerPresenceState(mapOf("B" to MultiplayerPresenceRecord("gen", "inst", 1L, t0, "AWAY")))
        val absentBobClock = MultiplayerTurnClock.start(3, "B", acceptTime, isAbsent = true)
        val absentReceipt = acceptedDeparture.copy(turnClock = absentBobClock)
        val recoveredAbsent = PeerMatchRecovery.merge(
            startData.copy(presenceState = absentBobPresence), listOf(kevinPick, absentReceipt), acceptTime + 1_000L
        )
        assertEquals("Absent successor gets 5s budget", 5_000L, recoveredAbsent.turnClock?.initialBudgetMillis)
        assertEquals("Absent successor gets 4s remaining at T+32s", 4_000L, recoveredAbsent.turnClock?.remainingMillis(acceptTime + 1_000L))
    }

    @Test fun `r08a baseline snapshot before rejected proposal committed outcome and marker preserves committed outcome and remainder matrix`() {
        val threePlayers = listOf(
            Player("K", "Kevin", isHost = true),
            Player("S", "Stuart", isHost = false),
            Player("B", "Bob", isHost = false)
        )
        val threeBoards = threePlayers.associate { it.id to engine.generateBoard(5, 42L + it.id.hashCode()) }
        val testNow = 5_000_000L
        val t0 = testNow - 100_000L
        val t0Clock = MultiplayerTurnClock.start(1, "K", t0)

        val startData = OngoingMatchData(
            "ROOM", 42, 5, participants = threePlayers, accountUid = "B",
            playerBoard = threeBoards.getValue("B"), opponentBoard = threeBoards.getValue("K"),
            allPlayerBoards = threeBoards, currentTurnPlayerId = "K", randomizedTurnOrder = threePlayers,
            startedAt = t0, lastPlayerActivityAt = t0, turnClock = t0Clock, turnNumber = 1,
            simplifiedRulesVersion = 1
        )

        // Scenario 1: Timeout won live against Kevin's pick 7 on turn 1
        // Baseline snapshot at turn 1 (0 picks)
        val baselineSnapshot = RoomMessagePacket(
            type = "GAME_SYNC", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            sequence = 1, timestamp = t0 + 10_000L, currentTurnPlayerId = "K", turnNumber = 1,
            pickedHistory = emptyList(), pickedByHistory = emptyList(), turnIds = emptyList(),
            boardNumbers = threeBoards.mapValues { (_, b) -> b.cells.map { it.number } },
            turnClock = t0Clock, lastPlayerActivityAt = t0 + 10_000L, simplifiedRulesVersion = 1
        )
        // Kevin's rejected pick proposal (unconfirmed proposal, recoveryReplay = false)
        val kevinPickProposal = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 2, closedTurnNumber = 1, currentTurnPlayerId = "S", number = 7,
            timestamp = t0 + 29_000L, recoveryReplay = false
        )
        // Committed timeout outcome (recoveryReplay = true, closedTurnNumber = 1)
        val committedTimeout = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 2, closedTurnNumber = 1, currentTurnPlayerId = "S",
            timestamp = t0 + 30_000L, recoveryReplay = true,
            turnClock = MultiplayerTurnClock.start(2, "S", t0 + 30_000L)
        )

        // Permutations of delivery before recovery fence marker
        val timeoutPermutations = listOf(
            listOf(baselineSnapshot, kevinPickProposal, committedTimeout),
            listOf(baselineSnapshot, committedTimeout, kevinPickProposal),
            listOf(kevinPickProposal, baselineSnapshot, committedTimeout),
            listOf(committedTimeout, baselineSnapshot, kevinPickProposal),
            listOf(baselineSnapshot, kevinPickProposal, baselineSnapshot, committedTimeout, kevinPickProposal)
        )

        for ((idx, order) in timeoutPermutations.withIndex()) {
            val session = PeerRecoverySession(
                initialData = startData,
                localUid = "B",
                isColdRejoin = true
            )
            for (packet in order) {
                session.processPacket(packet, testNow)
            }
            assertTrue("Reconciliation active before recovery fence", session.isReconciling)
            session.completeRecoveryFence(testNow)
            assertFalse("Reconciliation completed after fence", session.isReconciling)
            assertEquals("Permutation $idx: callback picks empty", emptyList<Int>(), session.data.pickedNumbers)
            assertEquals("Permutation $idx: callback turn 2", 2, session.data.turnNumber)
            assertEquals("Permutation $idx: callback player Stuart", "S", session.data.currentTurnPlayerId)

            val recovered = PeerMatchRecovery.merge(startData, order, testNow)
            assertEquals("Permutation $idx: Timeout won in merge, picks must be empty", emptyList<Int>(), recovered.pickedNumbers)
            assertEquals("Permutation $idx: Turn must advance to 2 in merge", 2, recovered.turnNumber)
            assertEquals("Permutation $idx: Current player must be Stuart in merge", "S", recovered.currentTurnPlayerId)
        }

        // Scenario 2: Committed pick outcome won against stale timeout proposal
        val committedPick = RoomMessagePacket(
            type = "PICK_NUMBER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 2, closedTurnNumber = 1, currentTurnPlayerId = "S", number = 7,
            timestamp = t0 + 29_000L, recoveryReplay = true,
            turnClock = MultiplayerTurnClock.start(2, "S", t0 + 29_000L)
        )
        val staleTimeoutProposal = RoomMessagePacket(
            type = "TURN_TIMEOUT", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 2, closedTurnNumber = 1, currentTurnPlayerId = "S",
            timestamp = t0 + 30_000L, recoveryReplay = false
        )
        val pickPermutations = listOf(
            listOf(baselineSnapshot, staleTimeoutProposal, committedPick),
            listOf(baselineSnapshot, committedPick, staleTimeoutProposal),
            listOf(staleTimeoutProposal, baselineSnapshot, committedPick),
            listOf(committedPick, baselineSnapshot, staleTimeoutProposal)
        )
        for ((idx, order) in pickPermutations.withIndex()) {
            val session = PeerRecoverySession(
                initialData = startData,
                localUid = "B",
                isColdRejoin = true
            )
            for (packet in order) {
                session.processPacket(packet, testNow)
            }
            assertTrue("Reconciliation active before recovery fence", session.isReconciling)
            session.completeRecoveryFence(testNow)
            assertFalse("Reconciliation completed after fence", session.isReconciling)
            assertEquals("Pick won permutation $idx: session picks [7]", listOf(7), session.data.pickedNumbers)
            assertEquals("Pick won permutation $idx: Turn must advance to 2", 2, session.data.turnNumber)
            assertEquals("Pick won permutation $idx: Current player must be Stuart", "S", session.data.currentTurnPlayerId)

            val recovered = PeerMatchRecovery.merge(startData, order, testNow)
            assertEquals("Pick won permutation $idx: picks must be [7] in merge", listOf(7), recovered.pickedNumbers)
            assertEquals("Pick won permutation $idx: Turn must advance to 2 in merge", 2, recovered.turnNumber)
            assertEquals("Pick won permutation $idx: Current player must be Stuart in merge", "S", recovered.currentTurnPlayerId)
        }

        // Scenario 3: Turn remainder matrix (0s, 5s, 17s, 30s) with delayed snapshots and manual pause
        // Sub-case 3a: Same-turn recovery under manual pause
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val pausedCheckpoint = startData.copy(
                isPaused = true,
                turnClock = t0Clock.copy(frozenRemainingMillis = remainder),
                remainingTurnMillis = remainder
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = pausedCheckpoint, bufferedCommands = emptyList(),
                recoveryRemainingMillis = remainder, recoveryRemainingTurn = 1,
                recoveryRemainingPlayerId = "K", nowTime = testNow
            )
            assertFalse("Paused match alive", result.isExpired)
            assertEquals("Turn must remain 1", 1, result.turnNumber)
            assertEquals("Player must remain Kevin", "K", result.currentTurnPlayerId)
            assertEquals("Paused remainder $remainder ms preserved", remainder, result.turnClock?.frozenRemainingMillis)
        }

        // Sub-case 3b: New-turn recovery (Turn 2) with remainder matrix under manual pause
        for (remainder in listOf(0L, 5_000L, 17_000L, 30_000L)) {
            val pausedTurn2Clock = MultiplayerTurnClock.start(2, "S", t0 + 30_000L).copy(frozenRemainingMillis = remainder)
            val pausedCheckpoint = startData.copy(
                turnNumber = 2,
                currentTurnPlayerId = "S",
                isPaused = true,
                turnClock = pausedTurn2Clock,
                remainingTurnMillis = remainder
            )
            val result = simulateProductionCallbackCompletion(
                checkpoint = pausedCheckpoint, bufferedCommands = emptyList(),
                recoveryRemainingMillis = remainder, recoveryRemainingTurn = 2,
                recoveryRemainingPlayerId = "S", nowTime = testNow
            )
            assertFalse("Paused match alive", result.isExpired)
            assertEquals("Turn must remain 2", 2, result.turnNumber)
            assertEquals("Player must remain Stuart", "S", result.currentTurnPlayerId)
            assertEquals("Paused remainder $remainder ms preserved on turn 2", remainder, result.turnClock?.frozenRemainingMillis)
        }
    }

    @Test fun `r08b delayed current turn exit awards fresh budget to successor and preserves activity without transport refresh`() {
        val threePlayers = listOf(
            Player("K", "Kevin", isHost = true),
            Player("S", "Stuart", isHost = false),
            Player("B", "Bob", isHost = false)
        )
        val threeBoards = threePlayers.associate { it.id to engine.generateBoard(5, 42L + it.id.hashCode()) }
        val t0 = 10_000_000L
        val t0Clock = MultiplayerTurnClock.start(1, "K", t0)

        val startData = OngoingMatchData(
            "ROOM", 42, 5, participants = threePlayers, accountUid = "S",
            playerBoard = threeBoards.getValue("S"), opponentBoard = threeBoards.getValue("K"),
            allPlayerBoards = threeBoards, currentTurnPlayerId = "K", randomizedTurnOrder = threePlayers,
            startedAt = t0, lastPlayerActivityAt = t0, turnClock = t0Clock, turnNumber = 1,
            simplifiedRulesVersion = 1
        )
        val ctx = MultiplayerGameplayContext(42, "S", "K", threePlayers, threePlayers,
            allowPeerTimeouts = true, strictIdentity = false)
        val baseState = MultiplayerGameplayState(
            emptyList(), threeBoards.getValue("S"), threeBoards.getValue("K"),
            threeBoards, 1, "K", 30_000L, turnClock = t0Clock
        )

        // Production current-turn exit path: Kevin confirms Exit during his online turn 1 at T+0
        val kevinCurrentTurnExit = RoomMessagePacket(
            type = "SURRENDER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = t0,
            turnClock = MultiplayerTurnClock.start(2, "S", t0, isAbsent = false),
            recoveryReplay = false
        )

        // Delay delivery: Kevin's exit proposal arrives at T+20,000, while turn 1 remains open
        val acceptTime = t0 + 20_000L

        // Acceptance through production reducer
        val decision = MultiplayerGameplayReducer.surrender(kevinCurrentTurnExit, ctx, baseState)
        assertTrue("Kevin surrender must be accepted", decision.accepted)
        val afterState = decision.state
        assertEquals(2, afterState.turnNumber)
        assertEquals("S", afterState.currentPlayerId)

        val preTurnNumber = baseState.turnNumber
        val preTurnPlayerId = baseState.currentPlayerId
        val changedTurn = afterState.turnNumber != preTurnNumber || afterState.currentPlayerId != preTurnPlayerId
        assertTrue("Turn must change on departure", changedTurn)
        val isDeparture = kevinCurrentTurnExit.type in setOf("SURRENDER", "LEAVE", "HOST_LEFT")
        val isAcceptedDeparture = isDeparture && (kevinCurrentTurnExit.recoveryReplay || kevinCurrentTurnExit.closedTurnNumber > 0)
        assertFalse("Proposal is not yet an accepted departure", isAcceptedDeparture)

        // Online Stuart: fresh 30s budget established at acceptTime via production seam
        val isStuartAbsent = false
        val stuartOnlineClock = PeerMatchRecovery.resolveTransitionClock(
            packet = kevinCurrentTurnExit,
            nextTurnNumber = afterState.turnNumber,
            nextPlayerId = afterState.currentPlayerId,
            now = acceptTime,
            isAbsent = isStuartAbsent,
            currentClock = baseState.turnClock
        )
        assertEquals("Stuart must receive fresh 30s budget starting from acceptance time", 30_000L, stuartOnlineClock.initialBudgetMillis)
        assertEquals("Stuart clock starts at acceptTime", acceptTime, stuartOnlineClock.startedAt)
        assertEquals("Stuart deadline is acceptTime + 30s", acceptTime + 30_000L, stuartOnlineClock.deadlineAt)
        assertEquals("Stuart has full 30s remaining at acceptance", 30_000L, stuartOnlineClock.remainingMillis(acceptTime))

        // Accepted departure receipt published via production seam
        val closesTurn = afterState.turnNumber > preTurnNumber || (afterState.currentPlayerId != preTurnPlayerId && kevinCurrentTurnExit.playerId == preTurnPlayerId)
        assertTrue("Exit closed the turn", closesTurn)
        val acceptedReceipt = PeerMatchRecovery.buildAcceptedDepartureReceipt(
            packet = kevinCurrentTurnExit,
            preTurnNumber = preTurnNumber,
            preTurnPlayerId = preTurnPlayerId,
            nextTurnNumber = afterState.turnNumber,
            nextPlayerId = afterState.currentPlayerId,
            successorClock = stuartOnlineClock
        )

        // Verify recovery (PeerMatchRecovery.merge) preserves the recorded successor clock
        val recovered = PeerMatchRecovery.merge(startData, listOf(acceptedReceipt), acceptTime + 5_000L)
        assertEquals(2, recovered.turnNumber)
        assertEquals("S", recovered.currentTurnPlayerId)
        val recClock = recovered.turnClock
        assertNotNull("Recovered clock must be present", recClock)
        assertEquals("Recovery reproduces accepted clock turn 2", 2, recClock!!.turnNumber)
        assertEquals("Recovery reproduces accepted clock player Stuart", "S", recClock.playerId)
        assertEquals("Recovery reproduces accepted clock deadline", acceptTime + 30_000L, recClock.deadlineAt)
        assertEquals("Recovery preserves 25s remaining at T+25s", 25_000L, recClock.remainingMillis(acceptTime + 5_000L))

        // Verify human activity timestamp is preserved without transport refresh
        val updatedActivity = maxOf(t0, kevinCurrentTurnExit.timestamp.coerceAtMost(acceptTime))
        assertEquals("Activity timestamp preserved at original T+0", t0, updatedActivity)

        // Sub-scenario 2: Successor Stuart is ABSENT at acceptance time
        val isStuartAbsentAtAccept = true
        val stuartAbsentClock = PeerMatchRecovery.resolveTransitionClock(
            packet = kevinCurrentTurnExit,
            nextTurnNumber = afterState.turnNumber,
            nextPlayerId = afterState.currentPlayerId,
            now = acceptTime,
            isAbsent = isStuartAbsentAtAccept,
            currentClock = baseState.turnClock
        )
        assertEquals("Absent successor Stuart must receive 5s budget from acceptance", 5_000L, stuartAbsentClock.initialBudgetMillis)
        assertEquals("Absent Stuart deadline is acceptTime + 5s", acceptTime + 5_000L, stuartAbsentClock.deadlineAt)
        assertEquals("Absent Stuart has 5s remaining at acceptance", 5_000L, stuartAbsentClock.remainingMillis(acceptTime))

        val absentReceipt = PeerMatchRecovery.buildAcceptedDepartureReceipt(
            packet = kevinCurrentTurnExit,
            preTurnNumber = preTurnNumber,
            preTurnPlayerId = preTurnPlayerId,
            nextTurnNumber = afterState.turnNumber,
            nextPlayerId = afterState.currentPlayerId,
            successorClock = stuartAbsentClock
        )
        val recoveredAbsent = PeerMatchRecovery.merge(
            startData.copy(presenceState = MultiplayerPresenceState(mapOf("S" to MultiplayerPresenceRecord("gen", "inst", 1L, t0, "AWAY")))),
            listOf(absentReceipt), acceptTime + 1_000L
        )
        assertEquals("Absent successor gets 5s budget in recovery", 5_000L, recoveredAbsent.turnClock?.initialBudgetMillis)
        assertEquals("Absent successor gets 4s remaining at acceptTime + 1s", 4_000L, recoveredAbsent.turnClock?.remainingMillis(acceptTime + 1_000L))
    }

    @Test fun `delayed current turn departure gives online successor thirty seconds`() {
        val t0 = 10_000_000L
        val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
        val startCheckpoint = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "B",
            playerBoard = boards.getValue("B"), opponentBoard = boards.getValue("K"), allPlayerBoards = boards,
            turnNumber = 1, currentTurnPlayerId = "K", randomizedTurnOrder = players,
            startedAt = t0, lastUpdatedAt = t0 + 13_000, lastPlayerActivityAt = t0,
            simplifiedRulesVersion = 1, turnClock = MultiplayerTurnClock.start(1, "K", t0), remainingTurnMillis = 17_000L)

        val exitProposal = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "K",
            roomCode = "ROOM", seed = 42, turnNumber = 1, closedTurnNumber = 0,
            timestamp = t0, recoveryReplay = false, simplifiedRulesVersion = 1,
            turnClock = MultiplayerTurnClock.start(2, "S", t0, isAbsent = false)), 42, "K")

        val acceptTime = t0 + 20_000
        val recovered = PeerMatchRecovery.merge(startCheckpoint, listOf(exitProposal), acceptTime)
        assertEquals(2, recovered.turnNumber)
        assertEquals("S", recovered.currentTurnPlayerId)
        assertEquals(30_000L, recovered.turnClock!!.remainingMillis(acceptTime))
        assertEquals(t0, recovered.lastPlayerActivityAt)
    }

    @Test fun `delayed current turn departure gives absent successor five seconds`() {
        val t0 = 10_000_000L
        val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
        val presence = MultiplayerPresenceState(mapOf("S" to MultiplayerPresenceRecord("gen", "inst", 1L, t0, "AWAY")))
        val startCheckpoint = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "B",
            playerBoard = boards.getValue("B"), opponentBoard = boards.getValue("K"), allPlayerBoards = boards,
            turnNumber = 1, currentTurnPlayerId = "K", randomizedTurnOrder = players,
            startedAt = t0, lastUpdatedAt = t0 + 13_000, lastPlayerActivityAt = t0,
            simplifiedRulesVersion = 1, turnClock = MultiplayerTurnClock.start(1, "K", t0), remainingTurnMillis = 17_000L,
            presenceState = presence)

        val exitProposal = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "K",
            roomCode = "ROOM", seed = 42, turnNumber = 1, closedTurnNumber = 0,
            timestamp = t0, recoveryReplay = false, simplifiedRulesVersion = 1,
            turnClock = MultiplayerTurnClock.start(2, "S", t0, isAbsent = true)), 42, "K")

        val acceptTime = t0 + 20_000
        val recovered = PeerMatchRecovery.merge(startCheckpoint, listOf(exitProposal), acceptTime)
        assertEquals(2, recovered.turnNumber)
        assertEquals("S", recovered.currentTurnPlayerId)
        assertEquals(5_000L, recovered.turnClock!!.remainingMillis(acceptTime))
        assertEquals(t0, recovered.lastPlayerActivityAt)
    }

    @Test fun `off turn departure preserves current players running clock`() {
        val t0 = 10_000_000L
        val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
        val saved = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "B",
            playerBoard = boards.getValue("B"), opponentBoard = boards.getValue("K"), allPlayerBoards = boards,
            turnNumber = 1, currentTurnPlayerId = "K", randomizedTurnOrder = players,
            startedAt = t0, lastUpdatedAt = t0 + 13_000, lastPlayerActivityAt = t0,
            simplifiedRulesVersion = 1, turnClock = MultiplayerTurnClock.start(1, "K", t0), remainingTurnMillis = 17_000L)

        val exit = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "S",
            roomCode = "ROOM", seed = 42, turnNumber = 1, closedTurnNumber = 0, timestamp = t0 + 13_000,
            turnClock = saved.turnClock, recoveryReplay = false, simplifiedRulesVersion = 1), 42, "S")
        val recovered = PeerMatchRecovery.merge(saved, listOf(exit), t0 + 13_000)
        assertEquals(1, recovered.turnNumber)
        assertEquals("K", recovered.currentTurnPlayerId)
        assertTrue("Off-turn exit still removes Stuart", "S" in recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        assertEquals("Kevin's existing turn must stay at 17 seconds", 17_000L,
            recovered.turnClock!!.remainingMillis(t0 + 13_000))
        assertEquals(saved.turnClock, recovered.turnClock)
    }

    @Test fun `off turn departure preserves manual pause frozen clock`() {
        val t0 = 10_000_000L
        val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()
        val frozen = MultiplayerTurnClock.start(1, "K", t0).freeze(t0 + 13_000)
        val saved = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "B",
            playerBoard = boards.getValue("B"), opponentBoard = boards.getValue("K"), allPlayerBoards = boards,
            turnNumber = 1, currentTurnPlayerId = "K", randomizedTurnOrder = players,
            startedAt = t0, lastUpdatedAt = t0 + 13_000, lastPlayerActivityAt = t0,
            simplifiedRulesVersion = 1, turnClock = frozen, remainingTurnMillis = 17_000L).copy(isPaused = true, pauseControlRevision = 1)

        val exit = MultiplayerGameplayReducer.prepareOutgoing(RoomMessagePacket(type = "SURRENDER", playerId = "S",
            roomCode = "ROOM", seed = 42, turnNumber = 1, closedTurnNumber = 0, timestamp = t0 + 14_000,
            turnClock = frozen, recoveryReplay = false, simplifiedRulesVersion = 1), 42, "S")
        val recovered = PeerMatchRecovery.merge(saved, listOf(exit), t0 + 20_000)
        assertTrue(recovered.isPaused)
        assertEquals(1, recovered.turnNumber)
        assertEquals("K", recovered.currentTurnPlayerId)
        assertEquals("A different player's exit must not unfreeze Kevin's clock", frozen, recovered.turnClock)
        assertEquals(17_000L, recovered.turnClock!!.remainingMillis(t0 + 40_000))
    }

    @Test fun `delayed exit with newer online presence gives successor thirty seconds`() {
        val t0 = 10_000_000L
        val players = listOf(Player("K", "Kevin", isHost = true), Player("S", "Stuart"), Player("B", "Bob"))
        val boards = players.mapIndexed { i, p -> p.id to engine.generateBoard(5, 100L + i) }.toMap()

        // At T+0, Stuart is AWAY
        val stuartAwayRecord = MultiplayerPresenceRecord("connS", "instS", 1L, t0, "AWAY")
        val exitProposalAtT0 = RoomMessagePacket(
            type = "SURRENDER", playerId = "K", senderPlayerId = "K", roomCode = "ROOM", seed = 42,
            turnNumber = 1, closedTurnNumber = 0, timestamp = t0,
            turnClock = MultiplayerTurnClock.start(2, "S", t0, isAbsent = true),
            playerPresences = mapOf("S" to stuartAwayRecord),
            recoveryReplay = false, simplifiedRulesVersion = 1
        )

        // At T+10, Stuart returns ONLINE with higher version 2
        val stuartOnlineRecord = MultiplayerPresenceRecord("connS", "instS", 2L, t0 + 10_000L, "ONLINE")
        val presenceAtT10 = MultiplayerPresenceState(mapOf("S" to stuartOnlineRecord))

        val checkpointAtT10 = OngoingMatchData("ROOM", 42, 5, participants = players, accountUid = "B",
            playerBoard = boards.getValue("B"), opponentBoard = boards.getValue("K"), allPlayerBoards = boards,
            turnNumber = 1, currentTurnPlayerId = "K", randomizedTurnOrder = players,
            startedAt = t0, lastUpdatedAt = t0 + 10_000, lastPlayerActivityAt = t0,
            simplifiedRulesVersion = 1, turnClock = MultiplayerTurnClock.start(1, "K", t0), remainingTurnMillis = 17_000L,
            presenceState = presenceAtT10)

        // Kevin's delayed exit arrives at T+20 while turn 1 remains open
        val acceptTime = t0 + 20_000L
        val recovered = PeerMatchRecovery.merge(checkpointAtT10, listOf(exitProposalAtT0), acceptTime)
        assertEquals(2, recovered.turnNumber)
        assertEquals("S", recovered.currentTurnPlayerId)
        assertEquals("Stuart returned before exit was accepted, so successor budget must be 30s online",
            30_000L, recovered.turnClock!!.initialBudgetMillis)
        assertEquals(30_000L, recovered.turnClock!!.remainingMillis(acceptTime))
    }
}

