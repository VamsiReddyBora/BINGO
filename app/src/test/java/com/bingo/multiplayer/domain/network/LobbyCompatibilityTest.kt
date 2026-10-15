package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.ReliablePolicyAgreement
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class LobbyCompatibilityTest {
    private val host = Player("A", "Kevin", "kevin", isHost = true, lobbyReadyStatus = "READY")
    private val guest = Player("B", "Stuart", "stuart", lobbyReadyStatus = "READY")
    private val ids = listOf("A", "B")

    private class Registry : RoomRegistryGateway {
        var session: OnlineRoomSession? = null
        override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>) = session
        override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
        override suspend fun remove(code: String, uid: String) {}
        override suspend fun close(code: String) {}
    }
    private class WireClient(private val deliver: (RoomMessagePacket) -> Unit) :
        MqttAsyncClient("tcp://localhost:1883", "unit_" + UUID.randomUUID().toString().take(8), MemoryPersistence()) {
        @Volatile var online = true
        @Volatile var forceClosed = false
        override fun close(force: Boolean) { if (force) forceClosed = true; super.close(force) }
        override fun isConnected() = online
        override fun publish(topic: String, message: MqttMessage): IMqttDeliveryToken {
            deliver(FastPacketCodec.decode(String(message.payload, Charsets.UTF_8)))
            return MqttDeliveryToken(clientId)
        }
    }
    private inner class PairFixture : AutoCloseable {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val registry = Registry()
        val a = OnlineRoomSyncManager(scope, registry)
        val b = OnlineRoomSyncManager(scope, registry)
        val packets = CopyOnWriteArrayList<RoomMessagePacket>()
        @Volatile var drop = false
        var transform: (RoomMessagePacket) -> RoomMessagePacket = { it }
        val ac = WireClient { receive(b, it) }
        val bc = WireClient { receive(a, it) }
        init { attach(a, host, ac); attach(b, guest, bc) }
        fun receive(to: OnlineRoomSyncManager, p: RoomMessagePacket) {
            packets += p
            if (!drop) to.handleIncomingPacket(transform(p))
        }
        fun attach(manager: OnlineRoomSyncManager, me: Player, client: WireClient) {
            fun set(name: String, value: Any) {
                manager.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
            }
            set("currentRoomCode", "UNIT_ROOM")
            set("localPlayer", me)
            set("mqttClient", client)
            set("isSubscribed", true)
            val field = manager.javaClass.getDeclaredField("playerRegistry").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val players = field.get(manager) as MutableMap<String, Player>
            players[host.id] = host; players[guest.id] = guest
        }
        override fun close() {
            ac.online = false; bc.online = false
            a.disconnect(); b.disconnect()
            ac.close(); bc.close(); scope.cancel()
        }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(3_000L) {
        while (!condition()) delay(10)
    }

    @Test fun updatedReadyPacketCarriesCompatibilityThroughActualManagerIngress() {
        val packet = RoomMessagePacket("READY_STATUS", playerId = "B", username = "stuart", displayName = "Stuart",
            readyStatus = "READY", readyVersion = 100, senderInstanceId = "guest-install",
            reliablePolicyVersion = 3, hostWaitVersion = 1, protocolVersion = 2)
        val manager = OnlineRoomSyncManager(roomRegistry = Registry())
        manager.handleIncomingPacket(FastPacketCodec.decode(FastPacketCodec.encode(packet)))
        assertEquals(ReliablePolicyAgreement.Status.COMPATIBLE, manager.reliableCompatibilityStatus(ids, "A"))
    }

    @Test fun missingOrLegacyReadyInformationIsWaitingAndDoesNotClaimAnOldApp() {
        val manager = OnlineRoomSyncManager(roomRegistry = Registry())
        manager.handleIncomingPacket(FastPacketCodec.decode("R|B|READY|stuart|Stuart|100|guest-install"))
        assertEquals(ReliablePolicyAgreement.Status.WAITING, manager.reliableCompatibilityStatus(ids, "A"))
        manager.handleIncomingPacket(RoomMessagePacket("JOIN", playerId = "B", reliablePolicyVersion = 2))
        assertEquals(ReliablePolicyAgreement.Status.INCOMPATIBLE, manager.reliableCompatibilityStatus(ids, "A"))
    }

    @Test fun smallRequestReplyStartsSameBuildWithoutDependingOnJoinOrHeartbeat() = runBlocking {
        PairFixture().use { f ->
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
            assertTrue(f.a.awaitReliableCompatibility(ids, "A"))
            assertTrue(f.b.awaitReliableCompatibility(ids, "B"))
            val caps = f.packets.filter { it.type.startsWith("LOBBY_CAPS_") }
            assertTrue(caps.any { it.type == "LOBBY_CAPS_REQUEST" })
            assertTrue(caps.any { it.type == "LOBBY_CAPS_RESPONSE" })
            assertTrue(caps.all { FastPacketCodec.encode(it).toByteArray().size < 600 })
            assertTrue(caps.all { it.avatarUrl == null && it.players.isEmpty() })
        }
    }

    @Test fun delayedConfirmationIsRetriedAndCanStartWithinTheSameButtonAttempt() = runBlocking {
        PairFixture().use { f ->
            f.drop = true
            val task = async { f.a.awaitReliableCompatibility(ids, "A", 2_000) }
            delay(350)
            assertFalse(task.isCompleted)
            f.drop = false
            assertTrue(task.await())
        }
    }

    @Test fun unreachablePeerTimesOutWithoutEnablingPlayOrCallingItAnOldApp() = runBlocking {
        PairFixture().use { f ->
            f.drop = true
            assertFalse(f.a.awaitReliableCompatibility(ids, "A", 400))
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
            assertEquals(ReliablePolicyAgreement.Status.WAITING, f.a.reliableCompatibilityStatus(ids, "A"))
        }
    }

    @Test fun explicitOldResponseRemainsBlocked() = runBlocking {
        PairFixture().use { f ->
            f.transform = { if (it.type == "LOBBY_CAPS_RESPONSE") it.copy(reliablePolicyVersion = 2, protocolVersion = 1, hostWaitVersion = 0) else it }
            assertFalse(f.a.awaitReliableCompatibility(ids, "A", 400))
            assertEquals(ReliablePolicyAgreement.Status.INCOMPATIBLE, f.a.reliableCompatibilityStatus(ids, "A"))
        }
    }

    @Test fun mismatchedResponsesCannotSatisfyTheHandshake() = runBlocking {
        val changes: List<(RoomMessagePacket) -> RoomMessagePacket> = listOf(
            { it.copy(roomCode = "OLD_ROOM") }, { it.copy(targetPlayerId = "somebody-else") },
            { it.copy(requestId = "old-request") }, { it.copy(connectionGeneration = "old-generation") },
            { it.copy(protocolVersion = 1) }, { it.copy(hostWaitVersion = 0) }, { it.copy(senderInstanceId = "") }
        )
        for (change in changes) PairFixture().use { f ->
            f.transform = { if (it.type == "LOBBY_CAPS_RESPONSE") change(it) else it }
            assertFalse(f.a.awaitReliableCompatibility(ids, "A", 100))
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
        }
    }

    @Test fun disconnectionCancelsStartAndOldResponseCannotUnlockTheNextSession() = runBlocking {
        var oldResponse: RoomMessagePacket? = null
        PairFixture().use { f ->
            f.transform = {
                if (it.type == "LOBBY_CAPS_RESPONSE") { oldResponse = it; it.copy(requestId = "discard") } else it
            }
            assertFalse(f.a.awaitReliableCompatibility(ids, "A", 150))
            assertNotNull(oldResponse)
            f.ac.online = false; f.a.disconnect()
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
        }
        PairFixture().use { fresh ->
            fresh.a.handleIncomingPacket(oldResponse!!)
            assertFalse(fresh.a.canStartReliableMatch(ids, "A"))
        }
    }

    @Test fun cloudStartWaitsForCompatibilityThenPreservesProtocolAndBoardSettings() = runBlocking {
        PairFixture().use { f ->
            f.drop = true
            f.registry.session = OnlineRoomSession("UNIT_ROOM", "A", "kevin", "Kevin", status = "PLAYING",
                boardSize = 6, players = listOf(host, guest), currentSeed = 123, isManualBoard = true, isDynamicBoard = true)
            val received = CopyOnWriteArrayList<RoomMessagePacket>()
            val reader = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.b.incomingPackets.collect { if (it.type == "START_GAME") received += it }
            }
            f.b.refreshNow()
            assertTrue(received.isEmpty())
            f.drop = false
            assertTrue(f.b.awaitReliableCompatibility(ids, "B"))
            f.b.refreshNow()
            until { received.size == 1 }
            val start = received.single()
            assertEquals(2, start.protocolVersion); assertEquals(1, start.hostWaitVersion)
            assertEquals(3, start.reliablePolicyVersion); assertEquals("123", start.matchId)
            assertEquals(6, start.boardSize); assertTrue(start.isManualBoard); assertTrue(start.isDynamicBoard)
            assertEquals(ids.toSet(), start.players.map { it.id }.toSet())
            f.b.refreshNow(); delay(20)
            assertEquals(1, received.size)
            reader.cancel()
        }
    }

    @Test fun pipeInDisplayNameDoesNotCorruptCompatibilityOrIdentity() {
        val original = RoomMessagePacket("READY_STATUS", playerId = "B", displayName = "Stuart | Bingo",
            username = "stuart", readyStatus = "READY", readyVersion = 100, senderInstanceId = "install",
            reliablePolicyVersion = 3, hostWaitVersion = 1, protocolVersion = 2)
        assertEquals(original, FastPacketCodec.decode(FastPacketCodec.encode(original)))
        val heartbeat = original.copy(type = "HEARTBEAT")
        assertEquals(heartbeat, FastPacketCodec.decode(FastPacketCodec.encode(heartbeat)))
    }

    @Test fun noConnectionOrInvalidParticipantsCannotStartFromAnOldAdvertisement() = runBlocking {
        PairFixture().use { f ->
            assertTrue(f.a.awaitReliableCompatibility(ids, "A"))
            assertFalse(f.a.awaitReliableCompatibility(listOf("A", "A"), "A", 100))
            f.ac.online = false
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
        }
    }
    @Test fun disconnectReleasesTheOwnedMqttClientEvenAfterConnectionLoss() = runBlocking {
        PairFixture().use { f ->
            f.ac.online = false
            f.a.disconnect()
            until { f.ac.forceClosed }
            assertFalse(f.a.canStartReliableMatch(ids, "A"))
        }
    }

}
