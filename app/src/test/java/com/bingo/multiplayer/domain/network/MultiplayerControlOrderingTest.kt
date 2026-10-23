package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.Clock
import com.bingo.multiplayer.domain.engine.MultiplayerMatchLifecyclePolicy
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MultiplayerControlOrderingTest {
    private class TestClock(var now: Long = 100_000L) : Clock {
        override fun elapsedRealtime() = now
    }
    private val clock = TestClock()
    private fun policy(host: Boolean = false) = MultiplayerMatchLifecyclePolicy(
        "42", if (host) "host" else "guest", host, "host", listOf("host", "guest", "third"), clock
    )
    private fun command(type: String, revision: Int, timestamp: Long, paused: Boolean = type != "GAME_RESUMED") =
        RoomMessagePacket(type = type, playerId = "host", seed = 42L, matchId = "42",
            timestamp = timestamp, pauseEpisode = revision, isPaused = paused, pauseRemainingSeconds = if (paused) 30 else 0)
    private fun receive(guest: MultiplayerMatchLifecyclePolicy, packet: RoomMessagePacket): Boolean {
        val decoded = FastPacketCodec.decode(FastPacketCodec.encode(packet))
        assertEquals(packet.pauseEpisode, decoded.pauseEpisode)
        return if (decoded.type == "PAUSE_EXTEND") guest.applyAuthoritativePauseExtension(
            timestamp = decoded.timestamp, remotePauseEpisode = decoded.pauseEpisode)
        else guest.syncRemotePause(decoded.isPaused, decoded.pauseRemainingSeconds,
            timestamp = decoded.timestamp, remotePauseEpisode = decoded.pauseEpisode)
    }
    @Before fun setup() { OngoingMatchStore.customPrefs = AuthRepositoryTest.FakeSharedPreferences() }
    @After fun cleanup() { OngoingMatchStore.customPrefs = null }

    @Test fun `pause extend resume pause never reuses a revision and old traffic cannot alter it`() {
        val host = policy(true)
        val guest = policy()
        host.pauseMatch()
        val pauseA = command("GAME_PAUSED", host.pauseEpisode, 1000L)
        assertTrue(receive(guest, pauseA))
        clock.now += 25_000L
        host.extendPause()
        val extendA = command("PAUSE_EXTEND", host.pauseEpisode, 26_000L)
        assertTrue(receive(guest, extendA))
        host.resumeMatch(false)
        val resumeA = command("GAME_RESUMED", host.pauseEpisode, 27_000L)
        assertTrue(receive(guest, resumeA))
        host.pauseMatch()
        assertEquals(4, host.pauseEpisode)
        assertTrue(receive(guest, command("GAME_PAUSED", host.pauseEpisode, 28_000L)))
        clock.now += 10_000L
        for (old in listOf(pauseA, extendA, resumeA, command("GAME_SYNC", 2, 99_000L))) {
            assertFalse(receive(guest, old))
            assertTrue(guest.isMatchPaused)
            assertEquals(20, guest.pauseRemainingSeconds)
            assertEquals(4, guest.pauseEpisode)
        }
    }

    @Test fun `same revision samples and duplicate commands cannot add time at any delivery latency`() {
        for (latency in listOf(0L, 1000L, 2000L, 7000L, 15_000L)) {
            val guest = policy()
            assertTrue(receive(guest, command("GAME_PAUSED", 1, 1000L)))
            clock.now += 2000L
            val sample = command("GAME_SYNC", 1, 3000L).copy(pauseRemainingSeconds = 28)
            clock.now += latency
            val before = guest.pauseRemainingSeconds
            assertTrue(receive(guest, sample))
            assertEquals("Latency $latency must not extend the accepted expiry", before, guest.pauseRemainingSeconds)
            assertFalse(receive(guest, sample))
            assertFalse(receive(guest, command("PAUSE_EXTEND", 1, 4000L)))
            assertEquals(before, guest.pauseRemainingSeconds)
        }
    }

    @Test fun `only a new revision can recover a missed extension including after local expiry`() {
        val guest = policy()
        receive(guest, command("GAME_PAUSED", 1, 1000L))
        clock.now += 30_000L
        guest.onHostHeartbeat()
        guest.onTick()
        assertFalse(guest.isMatchPaused)
        assertEquals(1, guest.pauseEpisode)
        assertFalse(receive(guest, command("GAME_SYNC", 1, 31_000L)))
        assertTrue(receive(guest, command("GAME_SYNC", 2, 32_000L).copy(pauseRemainingSeconds = 29)))
        assertTrue(guest.isMatchPaused)
        assertEquals(29, guest.pauseRemainingSeconds)
        assertFalse(receive(guest, command("PAUSE_EXTEND", 2, 26_000L)))
    }

    @Test fun `auto resume advances only the host control revision`() {
        val host = policy(true)
        val guest = policy()
        host.pauseMatch()
        receive(guest, command("GAME_PAUSED", host.pauseEpisode, 1000L))
        clock.now += 30_000L
        host.onTick()
        guest.onHostHeartbeat()
        guest.onTick()
        assertEquals(2, host.pauseEpisode)
        assertEquals(1, guest.pauseEpisode)
        assertTrue(receive(guest, command("GAME_RESUMED", host.pauseEpisode, 31_000L)))
        assertFalse(receive(guest, command("GAME_PAUSED", 1, 99_000L)))
    }

    @Test fun `process restoration retains pause ordering and snapshot fence across a later pause`() {
        val host = policy(true)
        host.pauseMatch()
        clock.now += 25_000L
        host.extendPause()
        host.resumeMatch(false)
        host.pauseMatch()
        val snapshot = MultiplayerSnapshotCheckpoint(revision = 15L, timestamp = 1000L, turnNumber = 1)
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData(
            roomCode = "ROOM", matchSeed = 42L, boardSize = 5, accountUid = "host", isHost = true,
            participants = listOf(Player("host", "Kevin", isHost = true), Player("guest", "Stuart"), Player("third", "Bob"))
        )))
        assertTrue(OngoingMatchStore.updateMatchGameState(accountUid = "host", expectedMatchSeed = 42L,
            isPaused = true, pauseRemainingSeconds = 20, remainingTurnMillis = 17_000L, pauseControlRevision = host.pauseEpisode,
            versionedPauseControl = host.hasVersionedPauseControl, pauseControlTimestamp = 5000L,
            multiplayerSnapshotCheckpoint = snapshot))
        val saved = OngoingMatchStore.getOngoingMatch(null, "host")!!
        assertEquals(snapshot, saved.multiplayerSnapshotCheckpoint)
        assertEquals(17_000L, saved.remainingTurnMillis)
        for (hostRole in listOf(true, false)) {
            val restored = policy(hostRole)
            restored.restorePauseCheckpoint(saved.isPaused, saved.pauseRemainingSeconds!!,
                saved.pauseControlRevision, saved.versionedPauseControl, saved.pauseControlTimestamp)
            assertEquals(4, restored.pauseEpisode)
            assertFalse(receive(restored, command("GAME_PAUSED", 1, 99_000L)))
            assertFalse(receive(restored, command("PAUSE_EXTEND", 2, 99_000L)))
            assertFalse(receive(restored, command("GAME_RESUMED", 3, 99_000L)))
            assertTrue(restored.isMatchPaused)
            if (hostRole) {
                restored.resumeMatch(false)
                restored.pauseMatch()
                assertEquals(6, restored.pauseEpisode)
            }
        }
    }

    @Test fun `versioned control cannot downgrade to legacy timestamp inference`() {
        val guest = policy()
        receive(guest, command("GAME_PAUSED", 1, 1000L))
        clock.now += 10_000L
        assertFalse(receive(guest, command("GAME_SYNC", 0, 99_000L)))
        assertFalse(receive(guest, command("GAME_RESUMED", 0, 100_000L)))
        assertFalse(receive(guest, command("PAUSE_EXTEND", 0, 101_000L)))
        assertTrue(guest.isMatchPaused)
        assertEquals(20, guest.pauseRemainingSeconds)
    }

    @Test fun `new control revision survives host wall clock moving backward`() {
        val guest = policy()
        receive(guest, command("GAME_PAUSED", 10, 99_000L))
        assertTrue(receive(guest, command("GAME_RESUMED", 11, 90_000L)))
        assertEquals(99_000L, guest.lastAppliedPauseTimestamp)
        assertFalse(receive(guest, command("GAME_PAUSED", 10, 100_000L)))
        assertFalse(guest.isMatchPaused)
    }
    @Test fun `guest process restoration keeps pending picks separate from accepted boards and snapshot history`() {
        val engine = com.bingo.multiplayer.domain.engine.BingoEngine()
        val mine = engine.generateBoard(5, 11L)
        val other = engine.generateBoard(5, 22L)
        val acceptedPacket = RoomMessagePacket(type = "GAME_SYNC", playerId = "host", seed = 42L,
            sequence = 5L, timestamp = 5000L, turnNumber = 2, pickedHistory = listOf(7),
            pickedByHistory = listOf("host"), turnIds = listOf(1))
        val accepted = MultiplayerSnapshotReconciler.reconcile(acceptedPacket, 42L, "host", "guest",
            MultiplayerSnapshotCheckpoint(), emptyList(), mine, other, emptyMap())!!
        val local = engine.markCell(accepted.playerBoard, 9, "guest", true, 2)
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData(
            roomCode = "ROOM", matchSeed = 42L, boardSize = 5, accountUid = "guest",
            playerBoard = local, opponentBoard = accepted.opponentBoard,
            pickedNumbers = listOf(7, 9), pickedByPlayers = listOf("host", "guest"), turnIds = listOf(1, 2),
            turnNumber = 3, multiplayerSnapshotCheckpoint = accepted.checkpoint)))
        val restored = OngoingMatchStore.getOngoingMatch(null, "guest")!!
        val picks = restored.pickedNumbers.mapIndexed { i, n ->
            com.bingo.multiplayer.domain.engine.MatchPick(n, restored.pickedByPlayers[i], restored.turnIds[i])
        }
        assertEquals(listOf(7), restored.multiplayerSnapshotCheckpoint.picks.map { it.number })
        fun restoreSample(packet: RoomMessagePacket) = MultiplayerSnapshotReconciler.reconcile(packet,
            restored.matchSeed, "host", "guest", restored.multiplayerSnapshotCheckpoint, picks,
            restored.playerBoard!!, restored.opponentBoard!!, restored.allPlayerBoards)
        assertNull(restoreSample(acceptedPacket.copy(sequence = 4L, pickedHistory = emptyList(), turnNumber = 1)))
        val inFlight = restoreSample(acceptedPacket.copy(sequence = 6L, timestamp = 6000L))!!
        assertTrue(inFlight.hasPendingPicks)
        assertTrue(inFlight.playerBoard.cells.single { it.number == 9 }.isMarked)
        val rejected = restoreSample(acceptedPacket.copy(sequence = 7L, timestamp = 7000L, turnNumber = 3))!!
        assertFalse(rejected.hasPendingPicks)
        assertFalse(rejected.playerBoard.cells.single { it.number == 9 }.isMarked)
        assertTrue(rejected.playerBoard.cells.single { it.number == 7 }.isMarked)
    }

}
