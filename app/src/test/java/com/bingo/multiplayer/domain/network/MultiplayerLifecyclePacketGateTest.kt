package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.*
import org.junit.Test

class MultiplayerLifecyclePacketGateTest {
    private class FakeClock(var now: Long = 100_000L) : Clock { override fun elapsedRealtime() = now }
    private val players = listOf(Player("host", "Kevin", isHost = true), Player("A", "Stuart"), Player("B", "Bob"))
    private val guest = MultiplayerGameplayContext(42, "A", "host", players, players)
    private val host = guest.copy(localId = "host")
    private val room = "ROOM"
    private fun packet(type: String, seed: Long = 42) = RoomMessagePacket(type = type, playerId = "host", isHost = true,
        seed = seed, matchId = seed.toString(), roomCode = room, senderInstanceId = "host-process", lifecycleSequence = 1)
    private fun wire(p: RoomMessagePacket) = FastPacketCodec.decode(FastPacketCodec.encode(p))
    private fun response(gate: MultiplayerLifecyclePacketGate, request: RoomMessagePacket, peer: MultiplayerGameplayContext = host,
                         instance: String = "host-process", seq: Long = 5) = wire(gate.respond(wire(request), peer, room)!!.copy(senderInstanceId = instance, lifecycleSequence = seq))

    @Test fun `old sync pause and host departure cannot affect current hold or end rematch`() {
        val clock = FakeClock(); val gate = MultiplayerLifecyclePacketGate(clock)
        val policy = MultiplayerMatchLifecyclePolicy("42", "A", false, "host", players.map { it.id }, clock)
        clock.now += 15_000; policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)
        for (type in listOf("GAME_SYNC", "GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND", "HOST_LEFT", "PLAYER_DISCONNECTED", "REJOIN_GAME")) {
            assertFalse(type, gate.accepts(wire(packet(type, 41)), guest, room, "guest-connection"))
        }
        clock.now += 3_000; policy.onTick()
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)
        assertEquals(42, policy.hostHoldRemainingSeconds)
    }

    @Test fun `fresh current nonce alone resumes host through the existing three second countdown`() {
        val clock = FakeClock(); val gate = MultiplayerLifecyclePacketGate(clock)
        val policy = MultiplayerMatchLifecyclePolicy("42", "A", false, "host", players.map { it.id }, clock)
        clock.now += 15_000; policy.onTick()
        // A valid state packet is data, and cannot pass the presence proof API.
        assertTrue(gate.accepts(packet("GAME_SYNC"), guest, room, "g1"))
        assertFalse(gate.acceptResponse(packet("GAME_SYNC"), guest, room, "g1"))
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)
        val probe = gate.beginProbe(guest, room, "g1")
        val reply = response(gate, probe)
        assertTrue(gate.acceptResponse(reply, guest, room, "g1"))
        assertTrue(policy.onHostHeartbeat() is LifecycleAction.HostReturningCountdown)
        assertFalse(gate.acceptResponse(reply, guest, room, "g1"))
        clock.now += 2_999; assertFalse(policy.onTick().contains(LifecycleAction.HostResumed))
        clock.now += 1; assertTrue(policy.onTick().contains(LifecycleAction.HostResumed))
    }

    @Test fun `proof expires and is fenced by room match receiver connection nonce and target`() {
        val clock = FakeClock(); val gate = MultiplayerLifecyclePacketGate(clock)
        val probe = gate.beginProbe(guest, room, "g1"); val reply = response(gate, probe)
        for (invalid in listOf(reply.copy(seed = 41), reply.copy(matchId = "41"), reply.copy(roomCode = "OTHER"),
            reply.copy(connectionGeneration = "old"), reply.copy(requestId = "old"), reply.copy(targetPlayerId = "B"),
            reply.copy(playerId = "stranger"), reply.copy(senderInstanceId = ""))) {
            assertFalse(gate.acceptResponse(invalid, guest, room, "g1"))
        }
        clock.now += 5_000; assertFalse(gate.acceptResponse(reply, guest, room, "g1"))
        val later = response(gate, gate.beginProbe(guest, room, "g1"))
        assertFalse(gate.acceptResponse(later, guest, room, "g2"))
        assertFalse(gate.acceptResponse(later, guest.copy(seed = 43), room, "g2"))
    }

    @Test fun `all host peers can answer one probe once and unrelated guests do not prove host presence`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        val probe = gate.beginProbe(host, room, "host-generation")
        for (peer in listOf(guest, guest.copy(localId = "B"))) {
            val reply = response(gate, probe, peer, peer.localId)
            assertTrue(gate.acceptResponse(reply, host, room, "host-generation"))
            assertFalse(gate.acceptResponse(reply, host, room, "host-generation"))
        }
        val guestProbe = gate.beginProbe(guest, room, "g1")
        val otherGuestReply = response(gate, guestProbe, guest.copy(localId = "B"), "B")
        assertTrue(gate.acceptResponse(otherGuestReply, guest, room, "g1"))
        val clock = FakeClock()
        val policy = MultiplayerMatchLifecyclePolicy("42", "A", false, "host", players.map { it.id }, clock)
        clock.now += 10_000; policy.onTick()
        policy.onPeerHeartbeat("B")
        assertEquals(HostAbsenceState.HOLD, policy.hostAbsenceState)
    }

    @Test fun `all eight healthy players remain visible through repeated fresh probe rounds`() {
        val clock = FakeClock(); val gate = MultiplayerLifecyclePacketGate(clock)
        val roster = (0..7).map { Player("p$it", "Player $it", isHost = it == 0) }
        val me = MultiplayerGameplayContext(42, "p1", "p0", roster, roster)
        val policy = MultiplayerMatchLifecyclePolicy("42", "p1", false, "p0", roster.map { it.id }, clock)
        repeat(16) {
            clock.now += 2_500
            val probe = gate.beginProbe(me, room, "g1")
            for (peer in roster.filter { it.id != me.localId }) {
                val reply = gate.respond(probe, me.copy(localId = peer.id), room)!!.copy(senderInstanceId = peer.id)
                assertTrue(gate.acceptResponse(wire(reply), me, room, "g1"))
                if (peer.id == me.hostId) policy.onHostHeartbeat() else policy.onPeerHeartbeat(peer.id)
            }
            policy.onTick()
            assertEquals(HostAbsenceState.NORMAL, policy.hostAbsenceState)
            assertTrue(policy.disconnectedPlayerIds.isEmpty())
        }
    }

    @Test fun `old process and delayed departure behind a fresh proof cannot mutate active match`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        assertTrue(gate.acceptResponse(response(gate, gate.beginProbe(guest, room, "g1"), seq = 5), guest, room, "g1"))
        assertFalse(gate.accepts(packet("PLAYER_DISCONNECTED").copy(lifecycleSequence = 4), guest, room, "g1"))
        assertTrue(gate.acceptResponse(response(gate, gate.beginProbe(guest, room, "g1"), instance = "new-process", seq = 1), guest, room, "g1"))
        assertFalse(gate.accepts(packet("HOST_LEFT").copy(lifecycleSequence = 999), guest, room, "g1"))
        assertFalse(gate.acceptResponse(response(gate, gate.beginProbe(guest, room, "g1"), seq = 999), guest, room, "g1"))
        val leave = packet("HOST_LEFT").copy(senderInstanceId = "new-process", lifecycleSequence = 2)
        assertTrue(gate.accepts(wire(leave), guest, room, "g1"))
        assertFalse(gate.accepts(wire(leave), guest, room, "g1"))
    }

    @Test fun `transport leave is hint only during match while explicit host exit and lobby leave remain valid`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        for (hint in listOf(packet("LEAVE", 0).copy(transportOnly = true), packet("LEAVE", 0), packet("HOST_LEFT").copy(transportOnly = true))) {
            assertFalse(gate.accepts(wire(hint), guest, room, "g1"))
            if (hint.seed == 0L) assertTrue(gate.accepts(wire(hint), guest.copy(isActive = false), room, "g1"))
            else assertFalse(gate.accepts(wire(hint), guest.copy(isActive = false), room, "g1"))
        }
        assertTrue(gate.accepts(wire(packet("HOST_LEFT")), guest, room, "g1"))
        assertFalse(gate.accepts(packet("HOST_LEFT").copy(playerId = "B"), guest, room, "g1"))
        assertFalse(gate.accepts(packet("HOST_LEFT").copy(roomCode = "OTHER"), guest.copy(isActive = false), room, "g1"))
    }

    @Test fun `previous match departure cannot close a new lobby while unscoped lobby host exit still works`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        val lobby = guest.copy(isActive = false)
        assertFalse(gate.accepts(packet("HOST_LEFT", 41), lobby, room, "g1"))
        assertFalse(gate.accepts(packet("PLAYER_DISCONNECTED"), lobby, room, "g1"))
        assertTrue(gate.accepts(packet("HOST_LEFT", 0), lobby, room, "g1"))
    }

    @Test fun `lobby expiry cannot end an active match and peer host flag cannot grant control`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        assertFalse(gate.accepts(packet("LOBBY_EXPIRED", 0), guest, room, "g1"))
        assertTrue(gate.accepts(packet("LOBBY_EXPIRED", 0), guest.copy(isActive = false), room, "g1"))
        for (type in listOf("GAME_PAUSED", "GAME_RESUMED", "PAUSE_EXTEND", "PLAYER_ELIMINATED", "HOST_HOLD", "HOST_RESUMED")) {
            assertFalse(gate.accepts(packet(type).copy(playerId = "B", isHost = true), guest, room, "g1"))
        }
    }

    @Test fun `Nearby keeps its existing socket departure behavior while MQTT leave remains hint only`() {
        val gate = MultiplayerLifecyclePacketGate(FakeClock())
        val nearby = guest.copy(allowPeerTimeouts = true)
        val socketLeave = RoomMessagePacket(type = "LEAVE", playerId = "B")
        val hostClosed = RoomMessagePacket(type = "HOST_LEFT", playerId = "host")
        val hostSocketEof = RoomMessagePacket(type = "HOST_LEFT") // Existing LAN reader emits no player ID on EOF.
        for (event in listOf(socketLeave, hostClosed, hostSocketEof)) {
            assertTrue(gate.accepts(wire(event), nearby, room, "nearby-session"))
            assertFalse(gate.accepts(wire(event), guest, room, "mqtt-session"))
            assertFalse(gate.accepts(wire(event.copy(transportOnly = true)), nearby, room, "nearby-session"))
        }
        assertFalse(gate.accepts(socketLeave.copy(playerId = "stranger"), nearby, room, "nearby-session"))
        assertFalse(gate.accepts(hostClosed.copy(playerId = "B"), nearby, room, "nearby-session"))
        assertFalse(gate.accepts(hostClosed.copy(seed = 41, matchId = "41"), nearby, room, "nearby-session"))
    }

    @Test fun `delayed outgoing lifecycle controls preserve original match and codec metadata`() {
        for (type in listOf("HOST_LEFT", "PLAYER_DISCONNECTED", "GAME_PAUSED", "REJOIN_GAME", "SYNC_REQUEST")) {
            val original = MultiplayerGameplayReducer.prepareOutgoing(packet(type, 41), 41, "host")
            val delayed = MultiplayerGameplayReducer.prepareOutgoing(original, 42, "host")
            assertEquals(41L, delayed.seed); assertEquals("41", delayed.matchId)
            assertFalse(MultiplayerLifecyclePacketGate(FakeClock()).accepts(wire(delayed), guest, room, "g1"))
            assertEquals(original.lifecycleSequence, wire(original).lifecycleSequence)
        }
    }
}
