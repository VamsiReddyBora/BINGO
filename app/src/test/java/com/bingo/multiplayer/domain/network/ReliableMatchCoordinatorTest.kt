package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class ReliableMatchCoordinatorTest {
    @Test fun recoveryHoldRejectsActionsAndWrongResumeAndKeepsExactRemainder() {
        val clock=FakeClock(); val c=ReliableMatchCoordinator(initial(),"A","installation-A",clock){true}
        c.resumeObservation();clock.now=15_750
        assertTrue(c.holdForRecovery("attempt-one"))
        clock.now+=600_000
        assertEquals(14_250L,c.remainingMillis())
        assertEquals(15_750L,c.sampled().elapsedPlayMillis)
        assertFalse(c.dispatch(intent(c,"PICK",number=5)))
        assertFalse(c.dispatch(intent(c,"TIMEOUT")))
        assertFalse(c.resumeAfterRecovery("old-attempt"))
        assertTrue(c.resumeAfterRecovery("attempt-one"))
        assertEquals(14_250L,c.remainingMillis())
        clock.now+=14_250
        assertTrue(c.dispatch(intent(c,"TIMEOUT")))
        assertEquals(30_000L,c.remainingMillis())
    }

    @Test fun oldFrozenPositionCannotRewindAnAcceptedPickDuringRecovery() {
        val clock=FakeClock();val c=ReliableMatchCoordinator(initial(),"A","installation-A",clock){true}
        c.resumeObservation();val old=c.sampled().copy(remainingMillis=100)
        assertTrue(c.dispatch(intent(c,"PICK",number=5)))
        assertTrue(c.holdForRecovery("current-attempt",old))
        assertEquals(listOf(5),c.state.picks.map{it.number})
        assertEquals(2,c.state.turn)
        assertEquals(30_000L,c.remainingMillis())
    }
    private class FakeClock(var now: Long = 0) : Clock { override fun elapsedRealtime() = now }
    private fun initial() = ReliableMatchSnapshot(matchId = "123", roomCode = "TEST", seed = 123,
        hostId = "A", ownerId = "installation-A", participants = listOf(Player("A", "Kevin", isHost = true), Player("B", "Stuart")),
        boards = mapOf("A" to BingoEngine().generateBoard(3, 11), "B" to BingoEngine().generateBoard(3, 12)),
        order = listOf("A", "B"), currentPlayerId = "A")
    private fun intent(c: ReliableMatchCoordinator, kind: String, actor: String = "A", id: String = "$kind-${c.state.revision}", number: Int = 0, episode: String = "") =
        MatchIntent(id, "123", actor, c.state.revision, c.state.turn, kind, number = number, episodeId = episode)

    @Test fun pauseResumePreserves14250MillisAndNextTurnIsExactly30000() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        clock.now = 15_750
        assertTrue(c.dispatch(intent(c, "PAUSE")))
        assertEquals(14_250L, c.remainingMillis())
        clock.now += 100_000
        assertEquals(14_250L, c.remainingMillis())
        assertTrue(c.dispatch(intent(c, "RESUME")))
        clock.now += 14_249
        assertEquals(1L, c.remainingMillis()); assertFalse(c.dispatch(intent(c, "TIMEOUT")))
        clock.now++
        assertTrue(c.dispatch(intent(c, "TIMEOUT")))
        assertEquals(2, c.state.turn); assertEquals(30_000L, c.remainingMillis())
    }
    @Test fun duplicateTimeoutNeverAdvancesTheNextTurn() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        clock.now = 30_000
        val timeout = intent(c, "TIMEOUT", id = "timeout-turn-1")
        assertTrue(c.dispatch(timeout)); assertTrue(c.dispatch(timeout))
        assertEquals(2, c.state.turn); assertEquals(30_000L, c.remainingMillis())
        assertFalse(c.dispatch(timeout.copy(id = "delayed-packet")))
        assertEquals(2, c.state.turn)
    }
    @Test fun unavailablePlayerKeepsItsNormalTurnsWithoutAutomaticDefeat() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        repeat(20) {
            assertEquals(30_000L, c.remainingMillis())
            clock.now += 30_000
            assertTrue(c.dispatch(intent(c, "TIMEOUT")))
        }
        assertEquals(21, c.state.turn); assertNull(c.state.final)
    }
    @Test fun pickTimeoutRaceHasOneAcceptedOutcomeInBothOrders() {
        for (timeoutFirst in listOf(false, true)) {
            val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
            val pick = intent(c, "PICK", number = 5); val timeout = intent(c, "TIMEOUT")
            if (timeoutFirst) { clock.now = 30_000; assertTrue(c.dispatch(timeout)); assertFalse(c.dispatch(pick)) }
            else { assertTrue(c.dispatch(pick)); clock.now = 30_000; assertFalse(c.dispatch(timeout)) }
            assertEquals(2, c.state.turn)
        }
    }
    @Test fun failedRequiredCommitBlocksFurtherMutationAndLeavesAcceptedState() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { false }; c.resumeObservation()
        assertFalse(c.dispatch(intent(c, "PICK", number = 5))); assertFalse(c.ready)
        assertEquals(1, c.state.turn); assertTrue(c.state.picks.isEmpty())
        assertFalse(c.dispatch(intent(c, "PICK", number = 6)))
    }
    @Test fun guestCannotBecomeOwnerOrAdvanceAHostTimeout() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "B", "installation-B", clock) { true }; c.resumeObservation()
        clock.now = 30_000
        assertFalse(c.dispatch(intent(c, "TIMEOUT", "B"))); assertEquals(1, c.state.turn)
    }
    @Test fun claimRequiresTheActualCurrentThirdOpenEpisodeAndHealthyOwner() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        assertTrue(c.updatePeerEpisode("B", MatchEpisode(2, "episode-2", true, true)))
        assertFalse(c.dispatch(intent(c, "CLAIM", episode = "episode-2")))
        assertTrue(c.updatePeerEpisode("B", MatchEpisode(3, "episode-3", true, true)))
        assertFalse(c.dispatch(intent(c, "CLAIM", episode = "invented")))
        assertFalse(c.dispatch(intent(c, "CLAIM", episode = "episode-3"), healthy = false))
        assertTrue(c.updatePeerEpisode("B", MatchEpisode(3, "episode-3", true, false)))
        assertFalse(c.dispatch(intent(c, "CLAIM", episode = "episode-3")))
        assertTrue(c.updatePeerEpisode("B", MatchEpisode(3, "episode-3", true, true)))
        assertTrue(c.dispatch(intent(c, "CLAIM", episode = "episode-3"))); assertEquals("A", c.state.final?.winnerId)
    }
    @Test fun surrenderAndConflictingTerminalActionsProduceOneResult() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        val surrender = intent(c, "SURRENDER", "B")
        assertTrue(c.dispatch(surrender)); val result = c.state.final
        assertTrue(c.dispatch(surrender)); assertFalse(c.dispatch(intent(c, "PICK", number = 5)))
        assertEquals("A", result?.winnerId); assertEquals(result, c.state.final)
    }
    @Test fun fullHistoryKeepsRealTurnsAndRejectsMissingBoardOrStalePause() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        assertTrue(c.dispatch(intent(c, "PICK", number = 5)))
        clock.now += 30_000; assertTrue(c.dispatch(intent(c, "TIMEOUT")))
        assertTrue(c.dispatch(intent(c, "PICK", number = 9)))
        assertEquals(listOf(1,3), c.state.picks.map { it.turn }); assertTrue(c.state.valid())
        assertFalse(c.state.copy(boards = emptyMap()).valid())
        assertTrue(c.dispatch(intent(c, "PAUSE")))
        assertFalse(c.acceptRemote(initial().copy(sequence = 1)))
        assertTrue(c.state.paused)
    }
    @Test fun restartedOwnerSuspendsInsteadOfReplayingTimeoutBacklog() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial().copy(remainingMillis = 12_000), "A", "installation-A", clock) { true }
        clock.now += 600_000
        assertEquals(12_000L, c.remainingMillis()); assertFalse(c.ready)
        c.resumeObservation(); assertEquals(12_000L, c.remainingMillis())
    }
    @Test fun completeLegitimateBingoMatchUsesTheAcceptedWinningPickAndCannotRestart() {
        val clock = FakeClock(); val c = ReliableMatchCoordinator(initial(), "A", "installation-A", clock) { true }; c.resumeObservation()
        for (number in 1..9) {
            if (c.state.final != null) break
            assertTrue(c.dispatch(intent(c, "PICK", actor=c.state.currentPlayerId, number=number)))
        }
        val result = c.state.final!!
        assertTrue(c.state.boards[result.winnerId]!!.isBingo)
        assertTrue(c.state.valid())
        assertFalse(c.dispatch(intent(c,"TIMEOUT")))
        assertEquals(result,c.state.final)
    }

    @Test fun existingTenByTenBoardOptionRemainsSupported() {
        val clock=FakeClock()
        val snapshot=initial().copy(boards=mapOf("A" to BingoEngine().generateBoard(10,1),"B" to BingoEngine().generateBoard(10,2)))
        assertTrue(snapshot.valid())
        val c=ReliableMatchCoordinator(snapshot,"A","installation-A",clock){true};c.resumeObservation()
        assertTrue(c.dispatch(intent(c,"PICK",number=100)))
        assertEquals(100,c.state.picks.single().number)
    }
    @Test fun chatDisplayIdsDoNotUseCollidingThirtyTwoBitStringHashes() {
        assertEquals("FB".hashCode(),"Ea".hashCode())
        assertNotEquals(MatchChat("FB","A","Kevin","one",1).localId(),MatchChat("Ea","A","Kevin","two",2).localId())
    }

}
