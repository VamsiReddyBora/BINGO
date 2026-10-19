package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Exercises the production recovery/reducer pipeline, rather than a second gameplay implementation. */
class SimplifiedPeerSimulationTest {
    private val engine = BingoEngine()
    private val epoch = 1_000_000L
    private fun initial(count: Int = 3, size: Int = 5, seed: Long = 42): OngoingMatchData {
        val players = (0 until count).map { Player("id$it", "Same display name", isHost = it == 0) }
        val boards = players.mapIndexed { index, p -> p.id to engine.generateBoard(size, seed + index * 7919L) }.toMap()
        return OngoingMatchData("QA_ROOM", seed, size, accountUid = "id0", participants = players,
            playerBoard = boards.getValue("id0"), opponentBoard = boards.getValue("id1"), allPlayerBoards = boards,
            currentTurnPlayerId = "id0", randomizedTurnOrder = players, startedAt = epoch, lastPlayerActivityAt = epoch,
            turnClock = MultiplayerTurnClock.start(1, "id0", epoch), simplifiedRulesVersion = 1)
    }
    private fun command(data: OngoingMatchData, number: Int, at: Long): RoomMessagePacket {
        val ctx = MultiplayerGameplayContext(data.matchSeed, data.accountUid, "", data.participants, data.randomizedTurnOrder,
            data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds, allowPeerTimeouts = true, strictIdentity = true)
        val next = ctx.nextPlayer(data.currentTurnPlayerId)
        return RoomMessagePacket(if (number > 0) "PICK_NUMBER" else "TURN_TIMEOUT", playerId = data.currentTurnPlayerId,
            senderPlayerId = if (number > 0) data.currentTurnPlayerId else "id1", number = number,
            roomCode = data.roomCode, seed = data.matchSeed, closedTurnNumber = data.turnNumber,
            turnNumber = data.turnNumber + 1, currentTurnPlayerId = next, timestamp = at,
            closedTurnClock = data.turnClock, turnClock = MultiplayerTurnClock.start(data.turnNumber + 1, next, at,
                data.presenceState.isAbsent(next)), simplifiedRulesVersion = 1)
    }
    private fun snapshot(data: OngoingMatchData, sender: String, sequence: Long) = RoomMessagePacket("GAME_SYNC",
        playerId = sender, senderPlayerId = sender, roomCode = data.roomCode, seed = data.matchSeed, timestamp = epoch + sequence,
        sequence = sequence, turnNumber = data.turnNumber, currentTurnPlayerId = data.currentTurnPlayerId,
        pickedHistory = data.pickedNumbers, pickedByHistory = data.pickedByPlayers, turnIds = data.turnIds,
        surrenderedPlayerIds = data.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.toList(),
        turnClock = data.turnClock, remainingTurnMillis = data.remainingTurnMillis ?: 0L, lastPlayerActivityAt = data.lastPlayerActivityAt,
        boardNumbers = data.allPlayerBoards.mapValues { it.value.cells.map { cell -> cell.number } },
        isMatchFinished = data.isMatchFinished, winnerPlayerId = data.winnerPlayerId, winReason = data.winReason,
        runnerPlayerIds = data.runnerPlayerIds, simplifiedRulesVersion = 1)

    @Test fun `two through eight peers converge across board sizes and cold recovery`() {
        var games = 0
        for (count in 2..8) for (size in 5..8) for (variation in 0..3) {
            val start = initial(count, size, 42L + variation * 1009)
            var a = start
            var b = start.copy(accountUid = "id1", playerBoard = start.allPlayerBoards.getValue("id1"),
                opponentBoard = start.allPlayerBoards.getValue("id0"))
            val commands = mutableListOf<RoomMessagePacket>()
            for (number in (1..size * size).shuffled(Random(variation + count * 100 + size))) {
                val packet = FastPacketCodec.decode(FastPacketCodec.encode(command(a, number, epoch + commands.size * 100L + 1)))
                commands += packet
                a = PeerMatchRecovery.merge(a, listOf(packet, packet), epoch + 20_000)
                b = PeerMatchRecovery.merge(b, listOf(packet), epoch + 20_000)
                assertEquals(a.pickedNumbers, b.pickedNumbers)
                assertEquals(a.turnClock, b.turnClock)
                assertEquals(a.allPlayerBoards, b.allPlayerBoards)
                assertEquals(a.winnerPlayerId, b.winnerPlayerId)
                assertEquals(a.runnerPlayerIds, b.runnerPlayerIds)
                if (a.isMatchFinished) break
            }
            assertTrue("A full number pool must produce Bingo", a.isMatchFinished)
            // Restart from the first persisted board, with duplicate/reordered durable commands.
            val cold = PeerMatchRecovery.merge(start, (commands + commands).shuffled(Random(variation)), epoch + 25_000)
            assertEquals(a.pickedNumbers, cold.pickedNumbers)
            assertEquals(a.winnerPlayerId, cold.winnerPlayerId)
            assertEquals(a.runnerPlayerIds, cold.runnerPlayerIds)
            assertEquals(a.allPlayerBoards, cold.allPlayerBoards)
            assertEquals(a, PeerMatchRecovery.merge(a, listOf(snapshot(start, "id1", 9999)), epoch + 100_000))
            games++
        }
        assertEquals(112, games)
    }

    @Test fun `current creator surrender then remaining player's pick recover in order`() {
        val start = initial()
        val exit = RoomMessagePacket("SURRENDER", playerId = "id0", senderPlayerId = "id0", seed = 42,
            roomCode = "QA_ROOM", timestamp = epoch + 1,
            turnClock = MultiplayerTurnClock.start(2, "id1", epoch + 1), simplifiedRulesVersion = 1)
        val afterExit = PeerMatchRecovery.merge(start, listOf(exit), epoch + 2)
        assertFalse(afterExit.isMatchFinished)
        assertEquals("id1", afterExit.currentTurnPlayerId)
        val pick = command(afterExit, 7, epoch + 3)
        val recovered = PeerMatchRecovery.merge(start, listOf(pick, exit, pick), epoch + 4)
        assertEquals(listOf(7), recovered.pickedNumbers)
        assertEquals(3, recovered.turnNumber)
        assertEquals("id2", recovered.currentTurnPlayerId)
        assertEquals(setOf("id0"), recovered.multiplayerSnapshotCheckpoint.surrenderedPlayerIds)
        assertEquals(1, recovered.chatMessages.count { it.isSystemMessage })
    }

    @Test fun `six consecutive absence passes never forfeit and retain every eligible seat`() {
        var data = initial().copy(presenceState = MultiplayerPresenceState(mapOf("id0" to
            MultiplayerPresenceRecord("c", "i", 1, epoch, "AWAY"))))
        repeat(12) {
            val timeout = command(data, -1, data.turnClock!!.deadlineAt)
            val next = PeerMatchRecovery.merge(data, listOf(timeout, timeout), epoch + 200_000)
            assertEquals(data.turnNumber + 1, next.turnNumber)
            assertTrue(next.multiplayerSnapshotCheckpoint.surrenderedPlayerIds.isEmpty())
            assertFalse(next.isMatchFinished)
            assertTrue(next.pickedNumbers.isEmpty())
            if (next.currentTurnPlayerId == "id0") assertEquals(5_000, next.turnClock!!.initialBudgetMillis)
            data = next
        }
        assertEquals(epoch, data.lastPlayerActivityAt)
    }

    @Test fun `old five second timeout cannot close promoted thirty second turn`() {
        val start = initial()
        val absentClock = MultiplayerTurnClock.start(1, "id0", epoch, true)
        val before = start.copy(turnClock = absentClock)
        val stale = command(before, -1, epoch + 5000)
        val promoted = before.copy(turnClock = absentClock.welcomeBack(epoch + 4000))
        assertEquals(promoted, PeerMatchRecovery.merge(promoted, listOf(stale), epoch + 6000))
        assertEquals(28, promoted.turnClock!!.remainingSeconds(epoch + 6000))
    }

    @Test fun `stored final result and expiry cleanup are isolated by account and match`() {
        OngoingMatchStore.customPrefs = AuthRepositoryTest.FakeSharedPreferences()
        try {
            val a = initial()
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, a))
            assertTrue(OngoingMatchStore.recordMatchResultTombstone(null, "QA_ROOM", 42, "id1", "Bingo", accountUid = "id0"))
            assertNull(OngoingMatchStore.getMatchResultTombstone(null, 42, "id1"))
            assertFalse(OngoingMatchStore.recordMatchResultTombstone(null, "QA_ROOM", 42, "id0", "Bingo", accountUid = "id0"))
            val rematch = a.copy(matchSeed = 43)
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, rematch))
            assertFalse(OngoingMatchStore.clearMatchIfCurrent(null, "id0", "QA_ROOM", 42))
            assertEquals(43L, OngoingMatchStore.getOngoingMatch(null, "id0")!!.matchSeed)
        } finally { OngoingMatchStore.customPrefs = null }
    }

    @Test fun `manual board packet roundtrip preserves scope and common start timestamp`() {
        val packet = RoomMessagePacket("BOARD_READY", playerId = "id0", senderPlayerId = "id0", roomCode = "QA_ROOM",
            seed = 42, matchId = "42", timestamp = epoch, pickedHistory = (1..25).toList(), simplifiedRulesVersion = 1)
        assertEquals(packet, FastPacketCodec.decode(FastPacketCodec.encode(packet)))
    }
    @Test fun `same-turn clock merge is independent of snapshot arrival order`() {
        val base = MultiplayerTurnClock.start(1, "id0", epoch)
        val freeze = base.freeze(epoch + 13_000)
        val resume = freeze.resume(epoch + 60_000)
        val alternatives = listOf(base, freeze, resume)
        val left = alternatives.fold(base) { a, b -> a.merge(b) }
        val right = alternatives.reversed().fold(base) { a, b -> a.merge(b) }
        assertEquals(resume, left)
        assertEquals(left, right)
        assertEquals(17, left.remainingSeconds(epoch + 60_000))
    }

    @Test fun `old pick snapshot cannot replace a more recent saved seventeen seconds with thirty`() {
        val start = initial()
        val checkpoint = start.copy(lastUpdatedAt = epoch + 13_000, remainingTurnMillis = 17_000)
        val retained = snapshot(start.copy(remainingTurnMillis = 30_000), "id1", 1)
        val recovered = PeerMatchRecovery.merge(checkpoint, listOf(retained), epoch + 120_000)
        assertEquals(17_000L, recovered.remainingTurnMillis)
        val frozen = recovered.turnClock!!.copy(frozenRemainingMillis = recovered.remainingTurnMillis)
        assertEquals(17, frozen.resume(epoch + 120_000).remainingSeconds(epoch + 120_000))
    }

}
