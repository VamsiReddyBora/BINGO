package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.engine.MatchPick
import org.junit.Assert.*
import org.junit.Test

class MultiplayerSnapshotReconcilerTest {
    private val engine = BingoEngine()
    private val mine = engine.generateBoard(5, 11L)
    private val other = engine.generateBoard(5, 22L)
    private val initial = MultiplayerSnapshotCheckpoint()
    private fun snapshot(revision: Long, numbers: List<Int>, turn: Int = numbers.size + 1) = RoomMessagePacket(
        type = "GAME_SYNC", playerId = "host", seed = 42L, sequence = revision,
        timestamp = revision * 1000L, pickedHistory = numbers, pickedByHistory = numbers.map { "host" },
        turnIds = numbers.indices.map { it + 1 }, turnNumber = turn, currentTurnPlayerId = "guest"
    )
    private fun apply(packet: RoomMessagePacket, previous: MultiplayerSnapshotApplication? = null,
                      pending: List<MatchPick>? = null) = MultiplayerSnapshotReconciler.reconcile(
        packet, 42L, "host", "guest", previous?.checkpoint ?: initial,
        pending ?: previous?.picks.orEmpty(), previous?.playerBoard ?: mine,
        previous?.opponentBoard ?: other, previous?.allPlayerBoards ?: mapOf("guest" to mine, "host" to other)
    )

    @Test fun `old empty and partial histories cannot erase committed selections`() {
        val current = apply(snapshot(3, listOf(7, 9)))!!
        for (old in listOf(snapshot(1, emptyList()), snapshot(2, listOf(7)))) {
            assertNull(apply(old, current))
            assertEquals(listOf(7, 9), current.picks.map { it.number })
            assertTrue(current.playerBoard.cells.single { it.number == 7 }.isMarked)
            assertTrue(current.opponentBoard.cells.single { it.number == 9 }.isMarked)
            assertEquals(3, current.checkpoint.turnNumber)
        }
    }

    @Test fun `replay and a shrinking history with a larger revision are rejected`() {
        val current = apply(snapshot(3, listOf(7, 9)))!!
        assertNull(apply(snapshot(3, listOf(7, 9)), current))
        assertNull(apply(snapshot(4, listOf(7), 3), current))
    }

    @Test fun `open turn sample keeps an in-flight pick but closed turn rejects it`() {
        val current = apply(snapshot(1, listOf(7)))!!
        val tentative = current.picks + MatchPick(9, "guest", 2)
        val inFlight = apply(snapshot(2, listOf(7)), current, tentative)!!
        assertTrue(inFlight.hasPendingPicks)
        assertEquals(listOf(7, 9), inFlight.picks.map { it.number })
        assertEquals(listOf(7), inFlight.checkpoint.picks.map { it.number })
        val rejected = apply(snapshot(3, listOf(7), 3), inFlight)!!
        assertFalse(rejected.hasPendingPicks)
        assertEquals(listOf(7), rejected.picks.map { it.number })
        assertFalse(rejected.playerBoard.cells.single { it.number == 9 }.isMarked)
        assertTrue(rejected.playerBoard.cells.single { it.number == 7 }.isMarked)
        assertEquals(3, rejected.checkpoint.turnNumber)
    }

    @Test fun `accepted pending pick has the host picker and turn metadata on every board`() {
        val current = apply(snapshot(1, listOf(7)))!!
        val pending = current.picks + MatchPick(9, "guest", 2)
        val packet = snapshot(2, listOf(7, 9)).copy(pickedByHistory = listOf("host", "guest"))
        val accepted = apply(FastPacketCodec.decode(FastPacketCodec.encode(packet)), current, pending)!!
        assertFalse(accepted.hasPendingPicks)
        assertEquals(pending, accepted.picks)
        assertEquals(accepted.picks, accepted.checkpoint.picks)
        assertTrue(accepted.allPlayerBoards.values.all { b -> b.cells.single { it.number == 9 }.isMarked })
    }

    @Test fun `stale winner snapshot cannot change a completed board or accepted history`() {
        val won = apply(snapshot(26, (1..25).toList()))!!
        assertTrue(won.playerBoard.isBingo)
        assertNull(apply(snapshot(20, (1..19).toList()).copy(winnerPlayerId = "someone_else"), won))
        assertTrue(won.playerBoard.isBingo)
        assertEquals(25, won.picks.size)
        assertEquals(26, won.checkpoint.turnNumber)
    }

    @Test fun `direct host progress is protected before its first periodic snapshot`() {
        val move = snapshot(0, listOf(7, 9)).copy(type = "PICK_NUMBER")
        val checkpoint = MultiplayerSnapshotReconciler.recordHostProgress(initial, move)
        val result = MultiplayerSnapshotReconciler.reconcile(snapshot(1, emptyList()), 42L, "host", "guest",
            checkpoint, checkpoint.picks, mine, other, emptyMap())
        assertNull(result)
    }

    @Test fun `wrong sender match and unversioned downgrade are rejected before board changes`() {
        val current = apply(snapshot(1, listOf(7)))!!
        assertNull(apply(snapshot(2, listOf(7)).copy(playerId = "other", isHost = true), current))
        assertNull(apply(snapshot(2, listOf(7)).copy(seed = 43L), current))
        assertNull(apply(snapshot(0, listOf(7)).copy(timestamp = 99_000L), current))
    }

    @Test fun `legacy snapshots use a separate timestamp fence until first versioned snapshot`() {
        val first = apply(snapshot(0, listOf(7)).copy(timestamp = 1000L))!!
        assertNull(apply(snapshot(0, emptyList()).copy(timestamp = 500L), first))
        val next = apply(snapshot(0, listOf(7, 9)).copy(timestamp = 2000L), first)!!
        assertEquals(listOf(7, 9), next.picks.map { it.number })
        assertNotNull(apply(snapshot(1, listOf(7, 9)).copy(timestamp = 1000L), next))
    }
}
