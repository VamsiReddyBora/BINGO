package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LobbyMessagesTest {
    private fun session(me: String = "a", host: String = "a") = LobbyMessageSession().also {
        it.bind("CHAT01", "session-one", me, host, setOf("a", "b"))
    }
    private fun roundTrip(packet: RoomMessagePacket) = FastPacketCodec.decode(FastPacketCodec.encode(packet))
    @Test fun actualCodecAndSessionDeliverOnlyTheLatestMessagePerPlayer() = runBlocking {
        val a = session(); val b = session("b"); val sent = mutableListOf<RoomMessagePacket>()
        assertTrue(a.send("Wait a minute ⏳") { sent.add(roundTrip(it)); true })
        b.receive(sent.last()); assertEquals("Wait a minute ⏳", b.messages.value["a"]?.text)
        assertTrue(a.send("Ready now 👍") { sent.add(roundTrip(it)); true })
        b.receive(sent.last()); b.receive(sent.first())
        assertEquals("Ready now 👍", b.messages.value["a"]?.text); assertEquals(1,b.messages.value.size)
        assertEquals("LOBBY_MESSAGE",sent.last().type); assertEquals(0L,sent.last().seed)
        assertTrue(sent.last().chatMessages.isEmpty()); assertTrue(sent.last().pickedHistory.isEmpty())
    }
    @Test fun reconnectingGuestRequestsTheActualHostLatestMessages() = runBlocking {
        val host=session();host.send("Still waiting") { true }
        val fresh=session("b");val reply=host.receive(roundTrip(fresh.request()!!))!!
        fresh.receive(roundTrip(reply));assertEquals("Still waiting",fresh.messages.value["a"]?.text)
        assertEquals("b",reply.targetPlayerId)
    }
    @Test fun unrelatedRoomIncarnationAndNonMembersCannotChangeMessages() = runBlocking {
        val host=session();var sent:RoomMessagePacket?=null;host.send("Hello") { sent=it;true }
        val guest=session("b");val p=sent!!
        for(invalid in listOf(p.copy(roomCode="OTHER1"),p.copy(lobbySessionId="old"),p.copy(playerId="stranger"),
            p.copy(senderPlayerId="stranger"),p.copy(targetPlayerId="someone-else")))guest.receive(invalid)
        assertTrue(guest.messages.value.isEmpty())
        guest.receive(p);assertEquals("Hello",guest.messages.value["a"]?.text)
    }
    @Test fun aGuestCannotPublishAnotherPlayersMessageOrAnAuthoritativeSnapshot() = runBlocking {
        val host=session();val guest=session("b");var sent:RoomMessagePacket?=null
        guest.send("Mine") {sent=it;true};val p=sent!!
        host.receive(p.copy(lobbyMessages=p.lobbyMessages.map {it.copy(playerId="a")}))
        assertTrue(host.messages.value.isEmpty())
        host.receive(p.copy(type="LOBBY_MESSAGES_SYNC",lobbyMessages=p.lobbyMessages.map {it.copy(playerId="a")}))
        assertTrue(host.messages.value.isEmpty())
    }
    @Test fun leavingPlayerAndStartingMatchClearOnlyTransientMessages() = runBlocking {
        val host=session();host.send("Hi") {true}
        host.bind("CHAT01","session-one","a","a",setOf("b"));assertTrue(host.messages.value.isEmpty())
        host.bind("CHAT01","session-one","a","a",setOf("a","b"));host.send("Again") {true}
        host.clear();assertTrue(host.messages.value.isEmpty());assertNull(host.request());assertFalse(host.send("No lobby") {true})
    }
    @Test fun failedPublicationOrNavigationDuringSendDoesNotInventASentMessage() = runBlocking {
        val host=session();assertFalse(host.send("Not sent") {false});assertTrue(host.messages.value.isEmpty())
        assertFalse(host.send("Late") {host.clear();true});assertTrue(host.messages.value.isEmpty())
    }
    @Test fun whitespaceIsTrimmedAndOversizeBlankMessagesNeverPublish() = runBlocking {
        val host=session();var count=0
        assertFalse(host.send("  ") {count++;true});assertFalse(host.send("x".repeat(501)) {count++;true})
        assertEquals(0,count);assertTrue(host.send("  Hello 🩵\nKevin  ") {count++;true})
        assertEquals("Hello 🩵\nKevin",host.messages.value["a"]?.text)
    }
    @Test fun memberClockDifferencesDoNotBlockIndependentPlayersMessages() {
        val receiver=session("b")
        val message=LobbyProfileMessage("id","a","Clock independent",System.currentTimeMillis()+3_600_000L)
        receiver.receive(RoomMessagePacket("LOBBY_MESSAGE",playerId="a",roomCode="CHAT01",lobbySessionId="session-one",lobbyMessages=listOf(message)))
        assertEquals(message,receiver.messages.value["a"])
    }
    @Test fun reconnectedHostRecoversEachMembersLatestMessageWithoutHistoryOrRetention()=runBlocking {
        val host=session();val guest=session("b");guest.send("Sent while host was away") {true}
        val response=guest.receive(roundTrip(host.request()!!))!!
        host.receive(roundTrip(response));assertEquals("Sent while host was away",host.messages.value["b"]?.text)
        assertEquals(1,host.messages.value.size)
    }
    @Test fun nonHostReplayCanOnlyRestoreThatSendersOwnMessage()=runBlocking {
        val host=session();val guest=session("b");guest.send("My own") {true}
        val own=guest.receive(host.request()!!)!!;host.receive(own)
        assertEquals("My own",host.messages.value["b"]?.text);assertNull(host.messages.value["a"])
        host.receive(own.copy(lobbyMessages=own.lobbyMessages.map {it.copy(playerId="a")}))
        assertNull(host.messages.value["a"])
    }
    @Test fun changingLobbySessionOrAccountDoesNotLeakPreviousMessages() = runBlocking {
        val s=session();s.send("Old lobby") {true};s.bind("CHAT01","session-two","a","a",setOf("a","b"));assertTrue(s.messages.value.isEmpty())
        s.send("Own account") {true};s.bind("CHAT01","session-two","b","a",setOf("a","b"));assertTrue(s.messages.value.isEmpty())
    }

    @Test fun ownerDeletionUsesActualCodecAndCannotBeUndoneByDelayedMessageOrHostSync() = runBlocking {
        val host=session(); val guest=session("b"); val packets=mutableListOf<RoomMessagePacket>()
        host.send("Wrong message") { packets += roundTrip(it); true }
        val old=packets.last(); guest.receive(old)
        assertTrue(host.delete(host.messages.value.getValue("a").id) { packets += roundTrip(it); true })
        val deleted=packets.last(); guest.receive(deleted)
        assertTrue(host.messages.value.isEmpty()); assertTrue(guest.messages.value.isEmpty())
        guest.receive(old); guest.receive(old.copy(type="LOBBY_MESSAGES_SYNC"))
        assertTrue(guest.messages.value.isEmpty())
        val rejoined=session("b"); rejoined.receive(roundTrip(host.receive(rejoined.request()!!)!!))
        rejoined.receive(old); assertTrue(rejoined.messages.value.isEmpty())
        assertEquals("LOBBY_MESSAGE",deleted.type); assertEquals("",deleted.lobbyMessages.single().text)
        assertTrue(host.send("Replacement") { guest.receive(roundTrip(it)); true })
        guest.receive(deleted)
        assertEquals("Replacement",guest.messages.value["a"]?.text)
    }

    @Test fun guestDeletionSurvivesHostReconnectAndCannotRemoveSomeoneElsesBubble() = runBlocking {
        val host=session(); val guest=session("b")
        host.send("Host bubble") { guest.receive(roundTrip(it)); true }
        val hostId=guest.messages.value.getValue("a").id
        assertFalse(guest.delete(hostId) { fail("Cannot delete another member"); true })
        guest.send("Guest bubble") { host.receive(roundTrip(it)); true }
        val id=guest.messages.value.getValue("b").id
        guest.delete(id) { host.receive(roundTrip(it)); true }
        assertEquals("Host bubble",host.messages.value["a"]?.text); assertNull(host.messages.value["b"])
        val returnedHost=session()
        val reply=guest.receive(returnedHost.request()!!)!!
        returnedHost.receive(roundTrip(reply)); assertTrue(returnedHost.messages.value.isEmpty())
        assertEquals("",reply.lobbyMessages.single().text)
    }

    @Test fun failedDeletionAndStaleConfirmationCannotHideAnUnchangedOrNewerMessage() = runBlocking {
        val host=session(); host.send("Original") { true }
        val original=host.messages.value.getValue("a")
        assertFalse(host.delete(original.id) { false }); assertEquals(original,host.messages.value["a"])
        host.send("Newer") { true }
        assertFalse(host.delete(original.id) { fail("Stale confirmation"); true })
        assertEquals("Newer",host.messages.value["a"]?.text)
        assertFalse(host.delete("") { fail("Blank id"); true })
        val current=host.messages.value.getValue("a")
        assertFalse(host.delete(current.id) { host.clear(); true }); assertTrue(host.messages.value.isEmpty())
    }

    @Test fun deletedMarkersStillRespectMembershipRoomAndSenderChecks() = runBlocking {
        val host=session(); var packet:RoomMessagePacket?=null
        host.send("Keep") { packet=it; true }; val guest=session("b"); guest.receive(packet!!)
        val delete=packet!!.copy(lobbyMessages=packet!!.lobbyMessages.map { it.copy(id="del",text="",revision=it.revision+1) })
        for (invalid in listOf(delete.copy(playerId="b",senderPlayerId="b"),delete.copy(roomCode="OTHER"),
            delete.copy(lobbySessionId="old"),delete.copy(playerId="stranger"))) guest.receive(roundTrip(invalid))
        assertEquals("Keep",guest.messages.value["a"]?.text)
        guest.receive(roundTrip(delete)); assertTrue(guest.messages.value.isEmpty())
    }
}
