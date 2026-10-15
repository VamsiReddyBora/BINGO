package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ReliablePersistenceTest {
    @After fun cleanup() { OngoingMatchStore.customPrefs = null }
    @Test fun ownerlessHostCheckpointIsNotAdoptedByGuestAndLegacyDataIsPreserved() {
        val prefs = FakeSharedPreferences(); OngoingMatchStore.customPrefs = prefs
        val legacy = """{"roomCode":"LEGACY","matchSeed":1234,"boardSize":5,"isHost":true,"accountUid":"","participants":[{"id":"A","displayName":"Kevin","isHost":true},{"id":"B","displayName":"Stuart"}]}"""
        prefs.edit().putString("active_match_data", legacy).commit()
        assertNull(OngoingMatchStore.getOngoingMatch(null, "B"))
        assertFalse(prefs.contains("active_match_data_b")); assertEquals(legacy, prefs.getString("active_match_data", null))
        assertNull(OngoingMatchStore.getOngoingMatch(null, null))
    }
    @Test fun scopedCleanupNeverRemovesTheOtherAccountsCheckpoint() {
        val prefs = FakeSharedPreferences(); OngoingMatchStore.customPrefs = prefs
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData("A_ROOM",1,5, accountUid="A",is1v1Match=true)))
        assertTrue(OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData("B_ROOM",2,5, accountUid="B",is1v1Match=true)))
        val b = prefs.getString("active_match_data_b", null)
        assertTrue(OngoingMatchStore.clearOngoingMatch(null,"A"))
        assertEquals(b,prefs.getString("active_match_data_b",null)); assertNotNull(OngoingMatchStore.getOngoingMatch(null,"B"))
    }
    @Test fun emptyAcceptedHistoryCanReplaceAnOldHistory() {
        val prefs = FakeSharedPreferences(); OngoingMatchStore.customPrefs = prefs
        OngoingMatchStore.saveOngoingMatch(null, OngoingMatchData("ROOM",5,5, accountUid="A",is1v1Match=true,pickedNumbers=listOf(7),turnIds=listOf(3)))
        assertTrue(OngoingMatchStore.updateMatchGameState(null,pickedNumbers=emptyList(),turnIds=emptyList(),accountUid="A"))
        assertTrue(OngoingMatchStore.getOngoingMatch(null,"A")!!.pickedNumbers.isEmpty())
        assertTrue(OngoingMatchStore.getOngoingMatch(null,"A")!!.turnIds.isEmpty())
    }
    @Test fun cancelledControllerCannotOverwriteANewerMatchOrResurrectDismissedData() {
        val prefs=FakeSharedPreferences(); OngoingMatchStore.customPrefs=prefs
        val players=listOf(com.bingo.multiplayer.domain.model.Player("A","Kevin",isHost=true),com.bingo.multiplayer.domain.model.Player("B","Stuart"))
        val snap=com.bingo.multiplayer.domain.engine.ReliableMatchSnapshot(matchId="10",roomCode="OLD",seed=10,hostId="A",ownerId="owner",
            participants=players,boards=mapOf("A" to com.bingo.multiplayer.domain.engine.BingoEngine().generateBoard(3,1),"B" to com.bingo.multiplayer.domain.engine.BingoEngine().generateBoard(3,2)),
            order=listOf("A","B"),currentPlayerId="A")
        val old=OngoingMatchData("OLD",10,3,accountUid="A",is1v1Match=true,reliableSnapshot=snap)
        assertTrue(OngoingMatchStore.saveOngoingMatch(null,old))
        assertTrue(OngoingMatchStore.saveReliableCheckpoint(null,old))
        assertTrue(OngoingMatchStore.updateMatchGameState(null,committedEpisodeCount=1,accountUid="A"))
        assertEquals(0L,OngoingMatchStore.getOngoingMatch(null,"A")!!.matchRevision)
        OngoingMatchStore.saveOngoingMatch(null,OngoingMatchData("NEW",11,3,accountUid="A",is1v1Match=true))
        assertFalse(OngoingMatchStore.saveReliableCheckpoint(null,old))
        assertFalse(OngoingMatchStore.updateMatchGameState(null,committedEpisodeCount=3,expectedMatchSeed=10,accountUid="A"))
        assertEquals(11L,OngoingMatchStore.getOngoingMatch(null,"A")!!.matchSeed)
        OngoingMatchStore.clearOngoingMatch(null,"A")
        assertFalse(OngoingMatchStore.saveReliableCheckpoint(null,old))
        assertNull(OngoingMatchStore.getOngoingMatch(null,"A"))
    }

}
