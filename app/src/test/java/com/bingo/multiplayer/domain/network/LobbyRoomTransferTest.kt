package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LobbyRoomTransferTest {
    private val host=Player("a","A","a",isHost=true)
    private val friend=Player("b","B","b")
    private fun room(status:String="WAITING",others:List<Player> = emptyList())=OnlineRoomSession("MOVE01","a","a","A",status=status,sessionId="old-session",players=listOf(host)+others)
    private class Storage(var state:OnlineRoomSession):RoomMetadataTransport {
        var commits=0
        override suspend fun read(code:String)=Result.success(state)
        override suspend fun commit(code:String,base:OnlineRoomSession?,next:OnlineRoomSession):RoomMetadataCommit {
            commits++;state=next;return RoomMetadataCommit.Applied(next)
        }
    }
    @Test fun failedAdmissionNeverDepartsOrRollsBackTheExistingLobby()=runBlocking {
        for(failure in listOf(RoomJoinResult.AlreadyFull("Full"),RoomJoinResult.AlreadyStarted("Started"),RoomJoinResult.NotFound("Gone"),RoomJoinResult.Error("Offline"))){
            var departures=0;var rollbacks=0
            assertEquals(failure,LobbyRoomTransfer.join({failure},{departures++;true},{rollbacks++},{true}))
            assertEquals(0,departures);assertEquals(0,rollbacks)
        }
    }
    @Test fun newMembershipIsRolledBackIfTheExistingLobbyCannotBeLeft()=runBlocking {
        var rollbacks=0
        val result=LobbyRoomTransfer.join({RoomJoinResult.Success(room())},{false},{rollbacks++},{true})
        assertTrue(result is RoomJoinResult.Error);assertEquals(1,rollbacks)
    }
    @Test fun navigationDuringAdmissionPreventsSourceDeparture()=runBlocking {
        var active=true;var departures=0;var rollbacks=0
        val result=LobbyRoomTransfer.join({active=false;RoomJoinResult.Success(room())},{departures++;true},{rollbacks++},{active})
        assertTrue(result is RoomJoinResult.Error);assertEquals(0,departures);assertEquals(1,rollbacks)
    }
    @Test fun actualHostedDepartureRequiresTheSameWaitingSessionAndApprovedOccupants()=runBlocking {
        for(source in listOf(room().copy(sessionId="replacement"),room("PLAYING"),room().copy(hostId="other"),room(others=listOf(friend)))){
            val storage=Storage(source)
            assertFalse(LobbyRoomTransfer.departHostedLobby("MOVE01","old-session","a",emptySet(),RoomMetadataCoordinator(storage)))
            assertEquals(0,storage.commits);assertEquals(source,storage.state)
        }
    }
    @Test fun anApprovedHostedDepartureUsesTheProductionCoordinatorAndClosesOnlyThatLobby()=runBlocking {
        val storage=Storage(room(others=listOf(friend)))
        assertTrue(LobbyRoomTransfer.departHostedLobby("MOVE01","old-session","a",setOf("b"),RoomMetadataCoordinator(storage)))
        assertEquals("CLOSED",storage.state.status);assertTrue(storage.state.isTerminalClosed)
        assertEquals(listOf(host,friend),storage.state.players);assertEquals(1,storage.commits)
    }
    @Test fun successfulTransferAdmitsBeforeDepartingAndNeverRollsBack()=runBlocking {
        val events=mutableListOf<String>();val admitted=RoomJoinResult.Success(room())
        assertEquals(admitted,LobbyRoomTransfer.join({events+="admit";admitted},{events+="depart";true},{events+="rollback"},{true}))
        assertEquals(listOf("admit","depart"),events)
    }
    @Test fun requestToJoinCannotRejoinAStartedMatchButOrdinaryRejoinStillWorks()=runBlocking {
        val previous=RoomMetadataApi.transportOverride;val storage=Storage(room("PLAYING",listOf(friend)))
        RoomMetadataApi.transportOverride=storage
        try {
            val requestResult=OnlineRoomRegistry.validateAndJoinRoom("MOVE01",friend,expectedSessionId="old-session",expectedHostId="a",waitingOnly=true)
            assertTrue(requestResult is RoomJoinResult.AlreadyStarted)
            val existing=OnlineRoomRegistry.validateAndJoinRoom("MOVE01",friend,expectedSessionId="old-session",expectedHostId="a")
            assertTrue(existing is RoomJoinResult.Success);assertEquals(0,storage.commits)
        }finally {RoomMetadataApi.transportOverride=previous;OnlineRoomRegistry.recentRoomCache.remove("MOVE01")}
    }
}
