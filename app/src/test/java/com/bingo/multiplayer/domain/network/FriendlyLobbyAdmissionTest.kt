package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production registry and MQTT host/guest transports, not a replacement lobby. */
class FriendlyLobbyAdmissionTest {
    private suspend fun withRoom(
        hostId: String = "google_friendly_host",
        guestId: String = "google_friendly_guest",
        block: suspend (String, Player, Player, EmqxWireFixture, MqttRoomMetadataTransport, MqttRoomMetadataTransport) -> Unit
    ) {
        val code = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        val host = Player(hostId, "Kevin", "friendly_kevin", isHost = true)
        val guest = Player(guestId, "Stuart", "friendly_stuart")
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id })
        val gs = MqttRoomMetadataTransport(wire, { guest.id })
        val oldTransport = RoomMetadataApi.transportOverride
        val oldWire = EmqxTransport.wireOverride
        val oldResolver = AccountKeyStore.accountKeyResolver
        EmqxTransport.wireOverride = wire
        try {
            val initial = OnlineRoomSession(code, host.id, host.username, host.displayName, players = listOf(host))
            assertTrue(hs.commit(code, null, initial) is RoomMetadataCommit.Applied)
            RoomMetadataApi.transportOverride = gs
            block(code, host, guest, wire, hs, gs)
        } finally {
            hs.close()
            gs.close()
            RoomMetadataApi.transportOverride = oldTransport
            EmqxTransport.wireOverride = oldWire
            AccountKeyStore.accountKeyResolver = oldResolver
            OnlineRoomRegistry.recentRoomCache.remove(code)
            OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test fun freshGooglePlayersCanJoinByCodeWithoutAccountKeyDiscovery() = runBlocking {
        withRoom { code, _, guest, _, hs, _ ->
            val lookups = AtomicInteger()
            AccountKeyStore.accountKeyResolver = {
                lookups.incrementAndGet()
                AccountKeyStore.AccountKeyEvidence.Unavailable(IOException("Profile/key discovery unavailable"))
            }
            val joined = OnlineRoomRegistry.validateAndJoinRoom(code, guest)
            assertTrue("An active friendly room must admit a fresh player: $joined", joined is RoomJoinResult.Success)
            assertTrue(hs.read(code).getOrThrow()!!.players.any { it.id == guest.id })
            assertEquals("Lobby entry must not depend on profile key discovery", 0, lookups.get())
        }
    }

    @Test fun invitationWorksWithExistingGuestIdentityWithoutCryptographicUidBinding() = runBlocking {
        withRoom("guest_existing_kevin", "guest_existing_stuart") { code, host, guest, _, hs, _ ->
            val room = hs.read(code).getOrThrow()!!
            val joined = OnlineRoomRegistry.validateAndJoinRoom(code, guest, room.sessionId, host.id)
            assertTrue("An invited existing installation must join without a new crypto identity: $joined",
                joined is RoomJoinResult.Success)
            assertEquals(1, hs.read(code).getOrThrow()!!.players.count { it.id == guest.id })
        }
    }

    @Test fun activeHostCanAdmitGuestWhenRetainedDiscoveryIsMissing() = runBlocking {
        withRoom { code, _, guest, wire, hs, _ ->
            wire.retained.clear()
            val joined = OnlineRoomRegistry.validateAndJoinRoom(code, guest)
            assertTrue("Room discovery must ask the active host when retained storage is absent: $joined",
                joined is RoomJoinResult.Success)
            assertTrue(hs.read(code).getOrThrow()!!.players.any { it.id == guest.id })
        }
    }

    @Test fun joinedPlayerCanReadyWithoutTrustedKeyLookup() = runBlocking {
        withRoom { code, _, guest, _, hs, gs ->
            AccountKeyStore.accountKeyResolver = { AccountKeyStore.AccountKeyEvidence.Absent }
            assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.Success)
            val ready = RoomMetadataCoordinator(gs).mutate(code) { room ->
                room.copy(players = room.players.map {
                    if (it.id == guest.id) it.copy(lobbyReadyStatus = "READY", readyVersion = System.currentTimeMillis() + 1L) else it
                })
            }
            assertTrue("The host must acknowledge the guest's ready state: $ready", ready is MutationResult.Applied)
            assertEquals("READY", hs.read(code).getOrThrow()!!.players.single { it.id == guest.id }.lobbyReadyStatus)
        }
    }

    @Test fun oldInvitationDoesNotJoinADifferentSession() = runBlocking {
        withRoom { code, host, guest, _, _, _ ->
            val result = OnlineRoomRegistry.validateAndJoinRoom(code, guest, "previous-session", host.id)
            assertTrue(result is RoomJoinResult.NotFound)
        }
    }

    @Test fun unavailableTransportDoesNotCreateALocalOnlyLobbyMembership() = runBlocking {
        withRoom { code, _, guest, wire, hs, _ ->
            val room = hs.read(code).getOrThrow()!!
            RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
                override suspend fun read(code: String) = Result.success(room)
                override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession) =
                    RoomMetadataCommit.Unavailable(IOException("No host acknowledgement"))
            }
            val result = OnlineRoomRegistry.validateAndJoinRoom(code, guest)
            assertTrue("Joining must be confirmed by the host, not fabricated in the guest cache: $result",
                result is RoomJoinResult.Error)
            assertFalse(room.players.any { it.id == guest.id })
            assertTrue(wire.isHealthy)
        }
    }

    @Test fun brokerRetainedStorageFailureDoesNotBlockLiveAdmission() = runBlocking {
        withRoom { code, _, guest, wire, hs, _ ->
            wire.retained.clear()
            wire.afterPublish = { topic, _ ->
                if (topic.endsWith("/state") || topic.endsWith("/auth")) wire.retained.remove(topic)
            }
            val result = OnlineRoomRegistry.validateAndJoinRoom(code, guest)
            assertTrue("Live MQTT join must not depend on the optional discovery cache: $result", result is RoomJoinResult.Success)
            assertTrue(hs.read(code).getOrThrow()!!.players.any { it.id == guest.id })
        }
    }

    @Test fun transientHostReplyFailureIsRetriedWithoutAdmittingTwice() = runBlocking {
        withRoom { code, _, guest, wire, hs, _ ->
            val failures = AtomicInteger()
            wire.beforePublish = { topic, payload ->
                if (topic.contains("/reply/") && payload.toString(Charsets.UTF_8).contains("\"status\":\"applied\"") &&
                    failures.getAndIncrement() == 0) throw IOException("Temporary reply failure")
            }
            val result = OnlineRoomRegistry.validateAndJoinRoom(code, guest)
            assertTrue("The acknowledged join must survive a transient reply failure: $result", result is RoomJoinResult.Success)
            val room = hs.read(code).getOrThrow()!!
            assertEquals(1, room.players.count { it.id == guest.id })
            assertEquals(2L, room.revision)
            assertTrue(failures.get() >= 2)
        }
    }

    @Test fun duplicateJoinPacketsHaveOneHostMutation() = runBlocking {
        withRoom { code, _, guest, wire, hs, _ ->
            val duplicatingWire = object : EmqxWire by wire {
                override suspend fun publish(topic: String, payload: ByteArray, retained: Boolean) {
                    wire.publish(topic, payload, retained)
                    if (topic.endsWith("/command") && !payload.toString(Charsets.UTF_8).contains("\"kind\":\"lookup\"")) {
                        wire.publish(topic, payload, retained)
                    }
                }
            }
            val gs = MqttRoomMetadataTransport(duplicatingWire, { guest.id })
            RoomMetadataApi.transportOverride = gs
            try {
                assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.Success)
                val room = hs.read(code).getOrThrow()!!
                assertEquals(1, room.players.count { it.id == guest.id })
                assertEquals(2L, room.revision)
            } finally { gs.close() }
        }
    }

    @Test fun guestStillCannotChangeHostSettings() = runBlocking {
        withRoom { code, _, guest, _, hs, gs ->
            assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.Success)
            val result = RoomMetadataCoordinator(gs).mutate(code) { it.copy(boardSize = 7) }
            assertTrue(result is MutationResult.Rejected)
            assertEquals(5, hs.read(code).getOrThrow()!!.boardSize)
        }
    }

    @Test fun fullRoomStillRejectsANinthPlayer() = runBlocking {
        withRoom { code, _, guest, _, hs, _ ->
            val filled = RoomMetadataCoordinator(hs).mutate(code) { room ->
                room.copy(players = room.players + (1..7).map { Player("friend_$it", "Friend $it", "friend_$it") })
            }
            assertTrue(filled is MutationResult.Applied)
            assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.AlreadyFull)
            assertEquals(8, hs.read(code).getOrThrow()!!.players.size)
        }
    }

    @Test fun runningMatchStillRejectsANewParticipant() = runBlocking {
        withRoom { code, _, guest, _, hs, _ ->
            assertTrue(RoomMetadataCoordinator(hs).mutate(code) { it.copy(status = "PLAYING", currentSeed = 123L) } is MutationResult.Applied)
            assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.AlreadyStarted)
        }
    }

    @Test fun closedRoomStillRejectsAdmission() = runBlocking {
        withRoom { code, _, guest, _, hs, _ ->
            assertTrue(RoomMetadataCoordinator(hs).mutate(code) { it.copy(status = "CLOSED", isTerminalClosed = true) } is MutationResult.Applied)
            assertTrue(OnlineRoomRegistry.validateAndJoinRoom(code, guest) is RoomJoinResult.NotFound)
        }
    }
    @Test fun failedRoomWatchClosesEveryPartiallyOpenedSubscription() = runBlocking {
        withRoom { code, _, guest, wire, _, _ ->
            val fault = object : EmqxWire by wire {
                override suspend fun subscribe(topic: String, handler: (ByteArray, Boolean) -> Unit): AutoCloseable {
                    if (topic.endsWith("/committed")) throw IOException("Broker subscription limit")
                    return wire.subscribe(topic, handler)
                }
            }
            val gs = MqttRoomMetadataTransport(fault, { guest.id })
            try {
                assertTrue(gs.read(code).isFailure)
                assertEquals("Only the host's existing callback may remain after failed setup", 1,
                    wire.activeSubscriptions("bingo/emqx7/metadata/$code/state"))
            } finally { gs.close() }
        }
    }

}
