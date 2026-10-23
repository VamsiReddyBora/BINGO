package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** Real LAN sockets and the production manager, without an Android emulator. */
class NearbyTransportRecoveryTest {
    @Test fun `unexpected guest socket closure is presence loss and never intentional leave`() = runBlocking {
        val manager = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { manager.incomingPackets.collect { events.send(it) } }
        val port = ServerSocket(0).use { it.localPort }
        var socket: Socket? = null
        try {
            manager.connectAsHost(Player("host", "Kevin", isHost = true), port)
            withTimeout(5_000) { while (manager.boundPort == 8999) delay(10) }
            socket = Socket("127.0.0.1", manager.boundPort)
            socket.getOutputStream().write((Json.encodeToString(RoomMessagePacket(type = "JOIN", playerId = "guest", displayName = "Stuart")) + "\n").toByteArray())
            socket.getOutputStream().flush()
            withTimeout(5_000) { while (manager.players.value.none { it.id == "guest" }) delay(10) }
            socket.close()
            val departure = withTimeout(5_000) {
                var packet = events.receive()
                while (packet.type != "LEAVE" && !(packet.type == "PLAYER_PRESENCE" && packet.readyStatus == "OFFLINE")) packet = events.receive()
                packet
            }
            assertEquals("Socket loss is not surrender", "PLAYER_PRESENCE", departure.type)
            assertEquals("OFFLINE", departure.readyStatus)
            assertTrue("Keep the guest eligible to rejoin", manager.players.value.any { it.id == "guest" })
        } finally { socket?.close(); manager.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `unexpected host socket closure never emits intentional host left`() = runBlocking {
        val manager = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { manager.incomingPackets.collect { events.send(it) } }
        val server = ServerSocket(0)
        var socket: Socket? = null
        try {
            manager.connectAsClient("127.0.0.1", Player("guest", "Stuart"), server.localPort)
            socket = withContext(Dispatchers.IO) { server.accept() }
            socket.getOutputStream().write((Json.encodeToString(RoomMessagePacket(type = "HEARTBEAT", playerId = "host", displayName = "Kevin", isHost = true)) + "\n").toByteArray())
            socket.getOutputStream().flush()
            withTimeout(5_000) { while (manager.players.value.none { it.id == "host" }) delay(10) }
            socket.close()
            val departure = withTimeout(5_000) { events.receive() }
            assertEquals("Socket loss must hold the match", "NEARBY_TRANSPORT_LOST", departure.type)
        } finally { socket?.close(); server.close(); manager.disconnect(); collector.cancelAndJoin() }
    }
    @Test fun `client reconnects to same host after unexpected EOF without recreating the lobby`() = runBlocking {
        val manager = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { manager.incomingPackets.collect { events.send(it) } }
        val server = ServerSocket(0).apply { soTimeout = 8_000 }
        var first: Socket? = null; var second: Socket? = null
        try {
            manager.connectAsClient("127.0.0.1", Player("guest", "Stuart"), server.localPort)
            first = withContext(Dispatchers.IO) { server.accept() }
            first.close()
            withTimeout(5_000) { while (events.receive().type != "NEARBY_TRANSPORT_LOST") Unit }
            second = withContext(Dispatchers.IO) { server.accept() }
            second.soTimeout = 5_000
            val reader = second.getInputStream().bufferedReader()
            val join = withContext(Dispatchers.IO) { Json.decodeFromString<RoomMessagePacket>(reader.readLine()) }
            assertEquals("JOIN", join.type)
            assertEquals("guest", join.playerId)
            assertTrue(manager.connectionReady.value)
            assertEquals("guest", manager.localPlayer!!.id)
        } finally { first?.close(); second?.close(); server.close(); manager.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `explicit client disconnect still sends intentional leave`() = runBlocking {
        val host = LanP2pSessionManager(); val guest = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { host.incomingPackets.collect { events.send(it) } }
        try {
            host.connectAsHost(Player("host", "Kevin", isHost = true), 0)
            withTimeout(5_000) { while (!host.connectionReady.value) delay(10) }
            guest.connectAsClient("127.0.0.1", Player("guest", "Stuart"), host.boundPort)
            withTimeout(5_000) { while (host.players.value.none { it.id == "guest" }) delay(10) }
            guest.disconnect()
            val leave = withTimeout(5_000) {
                var event = events.receive()
                while (event.type != "LEAVE") event = events.receive()
                event
            }
            assertEquals("guest", leave.playerId)
        } finally { guest.disconnect(); host.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `real socket delivers full host hold and resume controls with the preserved countdown`() = runBlocking {
        val host = LanP2pSessionManager(); val guest = LanP2pSessionManager()
        val hostMatch = NearbyMatchSession().apply { bind(77, "host", "host"); bindTurn(1, "guest", false, 1_000) }
        val guestMatch = NearbyMatchSession().apply { bind(77, "guest", "host"); bindTurn(1, "guest", false, 1_000) }
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { guest.incomingPackets.collect { events.send(it) } }
        suspend fun sendState(type: String, now: Long): RoomMessagePacket {
            val outgoing = hostMatch.prepareOutgoing(RoomMessagePacket(type = type, playerId = "host", senderPlayerId = "host",
                seed = 77, turnNumber = 1, currentTurnPlayerId = "guest", remainingTurnMillis = hostMatch.turnClock!!.remainingMillis(now)))
            host.broadcastPacket(outgoing)
            val incoming = withTimeout(5_000) {
                var event = events.receive()
                while (event.type != type) event = events.receive()
                event
            }
            return guestMatch.normalizePacket(incoming)
        }
        try {
            host.connectAsHost(Player("host", "Kevin", isHost = true), 0)
            withTimeout(5_000) { while (!host.connectionReady.value) delay(10) }
            guest.connectAsClient("127.0.0.1", Player("guest", "Stuart"), host.boundPort)
            withTimeout(5_000) { while (host.players.value.none { it.id == "guest" }) delay(10) }
            hostMatch.background(13_000)
            val hold = sendState("HOST_HOLD", 13_000)
            assertTrue(guestMatch.canApplySnapshot(hold)); guestMatch.snapshotApplied(hold, 13_010)
            assertTrue(guestMatch.state.hostUnavailable)
            assertEquals(18_000, guestMatch.remainingMillis(90_000, true, false))
            hostMatch.foreground(100_000)
            val resume = sendState("HOST_RESUMED", 100_000)
            assertTrue(guestMatch.canApplySnapshot(resume)); guestMatch.snapshotApplied(resume, 100_010)
            assertFalse(guestMatch.state.suspended)
            assertEquals(18_000, guestMatch.remainingMillis(100_010, true, false))
        } finally { guest.disconnect(); host.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `voluntary exit flushes after earlier local packets before the socket closes`() = runBlocking {
        val host = LanP2pSessionManager(); val guest = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { host.incomingPackets.collect { events.send(it) } }
        try {
            host.connectAsHost(Player("host", "Kevin", isHost = true), 0)
            withTimeout(5_000) { while (!host.connectionReady.value) delay(10) }
            guest.connectAsClient("127.0.0.1", Player("guest", "Stuart"), host.boundPort)
            withTimeout(5_000) { while (host.players.value.none { it.id == "guest" }) delay(10) }
            guest.broadcastPacket(RoomMessagePacket(type = "PICK_NUMBER", playerId = "guest", number = 9))
            guest.sendBeforeDisconnect(RoomMessagePacket(type = "SURRENDER", playerId = "guest"))
            guest.disconnect()
            val received = mutableListOf<String>()
            withTimeout(5_000) {
                while (received.size < 2) {
                    val event = events.receive()
                    if (event.type in setOf("PICK_NUMBER", "SURRENDER")) received.add(event.type)
                }
            }
            assertEquals(listOf("PICK_NUMBER", "SURRENDER"), received)
        } finally { guest.disconnect(); host.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `silent peer becomes offline once and fresh heartbeat restores online without leaving`() = runBlocking {
        val manager = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { manager.incomingPackets.collect { events.send(it) } }
        try {
            manager.handleIncomingPacket(RoomMessagePacket(type = "HEARTBEAT", playerId = "guest", displayName = "Stuart"))
            val lastSeen = manager.getLastDirectHeartbeat("guest")
            manager.checkPeerLiveness(lastSeen + 14_999)
            yield(); assertTrue(events.tryReceive().isFailure)
            manager.checkPeerLiveness(lastSeen + 15_000)
            val offline = withTimeout(2_000) { events.receive() }
            assertEquals("PLAYER_PRESENCE", offline.type); assertEquals("OFFLINE", offline.readyStatus)
            manager.checkPeerLiveness(lastSeen + 30_000)
            yield(); assertTrue("No duplicate disconnect or leave", events.tryReceive().isFailure)
            assertTrue(manager.players.value.any { it.id == "guest" })
            manager.handleIncomingPacket(RoomMessagePacket(type = "HEARTBEAT", playerId = "guest"))
            assertEquals("ONLINE", withTimeout(2_000) { events.receive() }.readyStatus)
        } finally { manager.disconnect(); collector.cancelAndJoin() }
    }

    @Test fun `known minimized peer remains away and explicit surrender is not replaced by silent disconnect`() = runBlocking {
        val manager = LanP2pSessionManager()
        val events = Channel<RoomMessagePacket>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { manager.incomingPackets.collect { events.send(it) } }
        try {
            manager.handleIncomingPacket(RoomMessagePacket(type = "HEARTBEAT", playerId = "guest", displayName = "Stuart"))
            val lastSeen = manager.getLastDirectHeartbeat("guest")
            manager.handleIncomingPacket(RoomMessagePacket(type = "PLAYER_PRESENCE", playerId = "guest", readyStatus = "AWAY"))
            assertEquals("AWAY", withTimeout(2_000) { events.receive() }.readyStatus)
            manager.checkPeerLiveness(lastSeen + 60_000)
            yield(); assertTrue(events.tryReceive().isFailure)
            manager.handleIncomingPacket(RoomMessagePacket(type = "SURRENDER", playerId = "guest"))
            assertEquals("SURRENDER", withTimeout(2_000) { events.receive() }.type)
            manager.checkPeerLiveness(lastSeen + 120_000)
            yield(); assertTrue(events.tryReceive().isFailure)
        } finally { manager.disconnect(); collector.cancelAndJoin() }
    }

}
