package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LobbyTypingTest {
    private var clock = 0L
    private fun session(id: String) = LobbyMessageSession { clock }.also {
        it.bind("TYPE01", "session", id, "a", setOf("a", "b"))
    }
    private fun wire(packet: RoomMessagePacket) = FastPacketCodec.decode(FastPacketCodec.encode(packet))

    @Test fun actualCodecAndSessionShowRemoteTypingWithoutSendingTheDraftOrChangingMessages() {
        val a=session("a"); val b=session("b"); val packet=wire(a.typingPacket(true)!!)
        b.receive(packet)
        assertEquals(setOf("a"),b.typingPlayerIds.value); assertTrue(b.messages.value.isEmpty())
        assertEquals("LOBBY_TYPING",packet.type); assertEquals("1",packet.displayName)
        assertTrue("Typing is a tiny status packet",FastPacketCodec.encode(packet).toByteArray().size<512)
        assertEquals(0L,packet.seed); assertTrue(packet.lobbyMessages.isEmpty())
        assertTrue(packet.chatMessages.isEmpty()); assertTrue(packet.pickedHistory.isEmpty())
    }

    @Test fun keystrokesAreRateLimitedButStopIsImmediateAndDelayedStartCannotRestoreIt() {
        val a=session("a"); val b=session("b"); val first=a.typingPacket(true)!!
        b.receive(wire(first)); clock=500
        assertNull(a.typingPacket(true)); assertNull(a.typingPacket(true))
        val stop=a.typingPacket(false)!!; b.receive(wire(stop)); b.receive(wire(first))
        assertTrue(b.typingPlayerIds.value.isEmpty()); assertNull(a.typingPacket(false))
        clock=600; assertNotNull(a.typingPacket(true)) // New typing starts immediately after a real stop.
    }

    @Test fun continuousTypingRenewsOnlyAfterTheMinimumInterval() {
        val a=session("a"); val first=a.typingPacket(true)!!
        clock=LobbyMessageSession.TYPING_MIN_INTERVAL_MS-1; assertNull(a.typingPacket(true))
        clock++; val renewal=a.typingPacket(true)!!
        assertTrue(renewal.sequence>first.sequence)
    }

    @Test fun lostStopExpiresLocallyAndDuplicatePacketsCannotExtendTheLease() {
        val a=session("a"); val b=session("b"); val packet=a.typingPacket(true)!!
        b.receive(wire(packet)); clock=LobbyMessageSession.TYPING_EXPIRY_MS-1
        b.receive(wire(packet)); b.expireTyping(); assertEquals(setOf("a"),b.typingPlayerIds.value)
        clock++; b.expireTyping(); assertTrue(b.typingPlayerIds.value.isEmpty())
        b.receive(wire(packet)); assertTrue(b.typingPlayerIds.value.isEmpty())
        clock++; b.receive(wire(a.typingPacket(true)!!)); assertEquals(setOf("a"),b.typingPlayerIds.value)
    }

    @Test fun finalMessageClearsTypingAndRejectsOlderInFlightTypingUpdates() = runBlocking {
        val a=session("a"); val b=session("b")
        b.receive(wire(a.typingPacket(true)!!)); clock=1500
        val late=a.typingPacket(true)!!
        assertTrue(a.send("Finished") { b.receive(wire(it)); true })
        assertTrue(b.typingPlayerIds.value.isEmpty())
        b.receive(wire(late)); assertTrue(b.typingPlayerIds.value.isEmpty())
        assertEquals("Finished",b.messages.value["a"]?.text)
        // A later, distinct draft can still show typing after the message.
        clock=3000; b.receive(wire(a.typingPacket(true)!!)); assertEquals(setOf("a"),b.typingPlayerIds.value)
    }

    @Test fun idleTypingStopsWithoutErasingThePreviousProfileMessage() = runBlocking {
        val a=session("a"); val b=session("b"); a.send("Previous message") { b.receive(wire(it)); true }
        b.receive(wire(a.typingPacket(true)!!)); assertEquals("Previous message",b.messages.value["a"]?.text)
        b.receive(wire(a.typingPacket(false)!!))
        assertTrue(b.typingPlayerIds.value.isEmpty()); assertEquals("Previous message",b.messages.value["a"]?.text)
    }

    @Test fun ownEchoUnrelatedRoomsAndUnadmittedOrMismatchedActorsCannotShowTyping() {
        val a=session("a"); val b=session("b"); val p=a.typingPacket(true)!!
        a.receive(wire(p)); assertTrue(a.typingPlayerIds.value.isEmpty())
        for (invalid in listOf(p.copy(roomCode="OTHER"),p.copy(lobbySessionId="old"),
            p.copy(playerId="stranger"),p.copy(senderPlayerId="b"),p.copy(targetPlayerId="other"),
            p.copy(displayName="invalid"),p.copy(sequence=0))) b.receive(wire(invalid))
        assertTrue(b.typingPlayerIds.value.isEmpty())
    }

    @Test fun leavingChangingLobbyAndClearingSessionRemoveTypingAndDoNotSyncItAsHistory() {
        val a=session("a"); val b=session("b"); val p=a.typingPacket(true)!!
        b.receive(wire(p)); val reply=b.receive(a.request()!!)!!
        assertTrue(reply.lobbyMessages.isEmpty()); assertEquals("LOBBY_MESSAGES_SYNC",reply.type)
        b.bind("TYPE01","session","b","a",setOf("b")); assertTrue(b.typingPlayerIds.value.isEmpty())
        b.bind("TYPE01","new-session","b","a",setOf("a","b")); b.receive(wire(p))
        assertTrue(b.typingPlayerIds.value.isEmpty()); a.clear(); assertNull(a.typingPacket(true))
    }
}
