package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MatchPresentationPersistenceTest {
    @Before fun setup() { OngoingMatchStore.customPrefs = AuthRepositoryTest.FakeSharedPreferences() }
    @After fun teardown() { OngoingMatchStore.customPrefs = null }
    private fun data(): OngoingMatchData {
        val players = listOf(Player("A", "Kevin", isHost = true), Player("B", "Stuart"))
        val boards = mapOf("A" to BingoEngine().generateBoard(3, 42), "B" to BingoEngine().generateBoard(3, 43))
        val snapshot = ReliableMatchSnapshot(matchId = "123", roomCode = "ROOM", seed = 123, hostId = "A", ownerId = "install-A",
            participants = players, boards = boards, order = listOf("A", "B"), currentPlayerId = "A")
        return OngoingMatchData("ROOM", 123, 3, isHost = true, participants = players, playerBoard = boards["A"],
            opponentBoard = boards["B"], allPlayerBoards = boards, is1v1Match = true, accountUid = "A", protocolVersion = 2,
            reliableSnapshot = snapshot)
    }
    @Test fun newAuthoritativeCheckpointCannotEraseSystemHistoryOrReopenDecidedPopup() {
        val initial = data()
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, initial))
        val notice = MatchSystemMessages.notice(123, "A", "disconnect:1", "Stuart disconnected (warning 1)", 1000)
        val prompt = InterruptionPromptState.open(InterruptionPromptState(), "episode", 1, 1000, false)
        assertTrue(OngoingMatchStore.savePresentation(null, "A", 123, notice, prompt.decide()))
        assertTrue(OngoingMatchStore.saveReliableCheckpoint(null, initial.copy(
            reliableSnapshot = initial.reliableSnapshot!!.copy(sequence = 1), interruptionPrompt = prompt)))
        val saved = OngoingMatchStore.getOngoingMatch(null, "A")!!
        assertEquals(listOf(notice), saved.systemMessages)
        assertTrue(saved.interruptionPrompt.decided)
        assertEquals(16_000L, saved.interruptionPrompt.deadlineMillis)
        assertEquals(initial.allPlayerBoards, saved.allPlayerBoards)
        assertEquals(initial.pickedNumbers, saved.pickedNumbers)
    }
    @Test fun delayedPresentationWritesCannotAffectAnotherAccountOrANewMatch() {
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, data()))
        val prompt = InterruptionPromptState.open(InterruptionPromptState(), "episode", 1, 1000, false)
        assertFalse(OngoingMatchStore.savePresentation(null, "B", 123, prompt = prompt))
        assertFalse(OngoingMatchStore.savePresentation(null, "A", 999, prompt = prompt))
        assertTrue(OngoingMatchStore.getOngoingMatch(null, "A")!!.interruptionPrompt.episodeId.isBlank())
    }
}
