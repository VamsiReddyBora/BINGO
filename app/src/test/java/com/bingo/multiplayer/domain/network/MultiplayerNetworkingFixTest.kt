package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MultiplayerNetworkingFixTest {
    @Test fun guestInvitationUsesTheLobbySessionRatherThanInviterAsHost() = runBlocking {
        val wire = EmqxWireFixture()
        val code = UUID.randomUUID().toString().take(6).uppercase()
        val host = Player("kevin", "Kevin", "kevin", isHost = true)
        val guest = Player("stuart", "Stuart", "stuart")
        val third = Player("bob", "Bob", "bob")
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val ss = MqttRoomMetadataTransport(wire, { guest.id })
        val bs = MqttRoomMetadataTransport(wire, { third.id })
        val old = RoomMetadataApi.transportOverride
        try {
            assertTrue(RoomMetadataCoordinator(hs).mutate(code, initial = OnlineRoomSession(code, host.id,
                host.username, host.displayName, players = listOf(host))) { it } is MutationResult.Applied)
            assertTrue(RoomMetadataCoordinator(ss).mutate(code) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            val room = hs.read(code).getOrThrow()!!
            RoomMetadataApi.transportOverride = bs
            val invite = GameInvite(guest.username, guest.displayName, roomCode = code,
                senderUid = guest.id, roomSessionId = room.sessionId)
            assertTrue("An invitation from another admitted player belongs to the same active lobby",
                OnlineRoomRegistry.joinRoom(code, third, invite) is RoomJoinResult.Success)
            assertEquals(setOf(host.id, guest.id, third.id), hs.read(code).getOrThrow()!!.players.map { it.id }.toSet())
            assertTrue("An obsolete invitation must still be refused",
                OnlineRoomRegistry.joinRoom(code, third, invite.copy(roomSessionId = "previous-session")) is RoomJoinResult.NotFound)
        } finally {
            RoomMetadataApi.transportOverride = old; hs.close(); ss.close(); bs.close()
            OnlineRoomRegistry.recentRoomCache.remove(code)
        }
    }

    private suspend fun withManager(block: suspend (OnlineRoomSyncManager, ControlledLobbyMqttClient, CopyOnWriteArrayList<Long>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wire = EmqxWireFixture()
        val oldWire = EmqxTransport.wireOverride
        val oldProvider = OnlineRoomSyncManager.clientProvider
        val oldActive = OnlineRoomSyncManager.activeInstance
        val client = ControlledLobbyMqttClient("rematch-${UUID.randomUUID()}")
        EmqxTransport.wireOverride = wire
        OnlineRoomSyncManager.clientProvider = { _, _ -> client }
        val manager = OnlineRoomSyncManager(scope)
        val ready = CopyOnWriteArrayList<Long>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.incomingPackets.collect { if (it.type == "PEER_SYNC_READY") ready.add(it.seed) }
        }
        try {
            manager.connectToRoom("FIX123", Player("kevin", "Kevin", "kevin", isHost = true))
            withTimeout(5000L) { while (!manager.connectionReady.value) delay(10L) }
            block(manager, client, ready)
        } finally {
            collector.cancelAndJoin(); manager.disconnect(); manager.drainForTests()
            scope.cancel(); scope.coroutineContext[Job]?.join()
            OnlineRoomSyncManager.clientProvider = oldProvider
            OnlineRoomSyncManager.activeInstance = oldActive
            EmqxTransport.wireOverride = oldWire
        }
    }

    @Test fun rematchesKeepOnlyTheCurrentMatchSubscription() = runBlocking {
        withManager { manager, client, ready ->
            for (seed in 1L..20L) {
                manager.bindPeerMatch(seed)
                withTimeout(2000L) { while (seed !in ready) delay(10L) }
            }
            assertTrue("No match may consume an obsolete subscription slot", client.rejected.isEmpty())
            assertEquals(setOf("bingo/emqx7/match/FIX123/20/#"), client.filters.filter { it.startsWith("bingo/emqx7/match/") }.toSet())
        }
    }

    @Test fun rejectedMatchSubscriptionRetriesBeforeCompletingRecovery() = runBlocking {
        withManager { manager, client, ready ->
            client.rejectNextMatchSubscription = true
            manager.bindPeerMatch(99L)
            withTimeout(4500L) { while (99L !in ready) delay(10L) }
            assertEquals(1, client.rejected.size)
            assertEquals(1, ready.count { it == 99L })
            assertTrue(client.filters.contains("bingo/emqx7/match/FIX123/99/#"))
        }
    }

    @Test fun lostRecoveryMarkerDoesNotRemainPendingForever() = runBlocking {
        withManager { manager, client, ready ->
            client.dropNextMarker = true
            manager.bindPeerMatch(77L)
            withTimeout(6500L) { while (77L !in ready) delay(10L) }
            assertTrue(client.markerPublications.get() >= 2)
            assertEquals(1, ready.count { it == 77L })
        }
    }

    @Test fun rapidMatchChangesReleaseAllObsoleteSubscriptions() = runBlocking {
        withManager { manager, client, ready ->
            for (seed in 1L..20L) { manager.bindPeerMatch(seed); delay(5L) }
            withTimeout(4500L) { while (20L !in ready) delay(10L) }
            assertEquals(setOf("bingo/emqx7/match/FIX123/20/#"), client.filters.filter { it.startsWith("bingo/emqx7/match/") }.toSet())
        }
    }

    @Test fun busyLobbyRecipientGetsATransientRequestOverTheActualPublisher() = runBlocking {
        val now = System.currentTimeMillis()
        val invite = GameInvite("kevin", "Kevin", roomCode = "FIX123", recipientUid = "stuart",
            recipientUsername = "stuart", roomSessionId = "current-lobby")
        for (detail in listOf("AI (Easy)", "Online Match")) {
            val selected = GameInviteManager.forLobbyRecipient(invite,
                PlayerPresence("stuart", "PLAYING", now, activityDetail = detail), now)
            assertTrue("Busy players should get Request, not Invite", selected.isPlayRequest)
            assertEquals("", selected.roomCode)
            assertEquals("", selected.roomSessionId)
            assertEquals(now + 15000L, selected.expiresAt)
            val wire = EmqxWireFixture()
            GameInviteManager.publishMqttInviteAwaited("stuart", selected, wire)
            assertEquals(listOf("bingo/emqx7/social/request/stuart/${selected.eventId}"), wire.published.toList())
            assertTrue("Requests must never become retained invitations", wire.retained.isEmpty())
        }
        assertEquals(invite, GameInviteManager.forLobbyRecipient(invite, PlayerPresence("stuart", "ONLINE", now), now))
    }

    @Test fun acknowledgedSocketWithoutAnyEchoIsNotHealthy() = runBlocking {
        val client = ControlledLobbyMqttClient("no-echo-${UUID.randomUUID()}").apply { mute = true }
        val connection = EmqxConnection("no-echo-${UUID.randomUUID()}", initialClientProvider = { _, _ -> client })
        try {
            withTimeout(5000L) { while (!client.online) delay(10L) }
            assertFalse("CONNACK and SUBACK alone cannot establish live message delivery", connection.awaitHealthy(500L))
            assertEquals(0L, connection.rtt.value)
            client.mute = false
            assertTrue("The existing echo loop must restore health without an additional protocol", connection.awaitHealthy(5000L))
            assertTrue(connection.rtt.value > 0L)
        } finally { connection.close() }
    }

    @Test fun expiredEchoMakesBothHealthViewsUnhealthy() = runBlocking {
        val client = ControlledLobbyMqttClient("stale-echo-${UUID.randomUUID()}")
        val connection = EmqxConnection("stale-echo-${UUID.randomUUID()}", initialClientProvider = { _, _ -> client })
        try {
            assertTrue(connection.awaitHealthy(5000L))
            connection.simulateZombieStall()
            assertFalse(connection.isHealthy)
            assertFalse(connection.healthy.value)
        } finally { connection.close() }
    }
    @Test fun samplingACachedEchoDoesNotRefreshTheSuccessfulProbeTime() = runBlocking {
        NetworkPingMonitor.stopAndAwait()
        val wallNow = System.currentTimeMillis()
        val now = wallNow - 16000L
        try {
            NetworkPingMonitor.recordBrokerEcho(null, wallNow + 15000L)
            val sample = EmqxConnection.EchoSample(40L, now)
            NetworkPingMonitor.recordBrokerEcho(sample, now + 100L)
            assertEquals(now, NetworkPingMonitor.lastSuccessfulProbeTimestamp)
            NetworkPingMonitor.recordBrokerEcho(sample, now + 5000L)
            assertEquals("Polling the same sample is not a fresh reply", now, NetworkPingMonitor.lastSuccessfulProbeTimestamp)
            NetworkPingMonitor.recordBrokerEcho(sample, now + 15000L)
            assertEquals(0L, NetworkPingMonitor.pingMs.value)
            NetworkPingMonitor.recordBrokerEcho(EmqxConnection.EchoSample(80L, now + 16000L), now + 16000L)
            assertEquals(now + 16000L, NetworkPingMonitor.lastSuccessfulProbeTimestamp)
            assertEquals(80L, NetworkPingMonitor.pingMs.value)
        } finally { NetworkPingMonitor.start() }
    }

    @Test fun retainedRefreshDoesNotBlockRepliesOnAHealthySocket() = runBlocking {
        val client = ControlledLobbyMqttClient("refresh-${UUID.randomUUID()}")
        val connection = EmqxConnection("refresh-${UUID.randomUUID()}", initialClientProvider = { _, _ -> client })
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var handle: AutoCloseable? = null
        try {
            assertTrue(connection.awaitHealthy(5000L))
            val topic = "bingo/emqx7/metadata/REF123/state"
            handle = connection.subscribe(topic) { _, _ -> }
            client.beforeUnsubscribe = { filter ->
                if (filter == "bingo/emqx7/metadata/REF123/+") {
                    entered.countDown(); release.await(3L, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            val read = async(Dispatchers.IO) { connection.readRetained(topic) }
            try {
                assertTrue(entered.await(2L, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("The current subscribed echo remains fresh during a metadata refresh", connection.isHealthy)
                connection.publish("bingo/emqx7/metadata/reply/owned-test", "reply".toByteArray())
            } finally { release.countDown() }
            read.await()
            Unit
        } finally { release.countDown(); handle?.close(); connection.close() }
    }

}
