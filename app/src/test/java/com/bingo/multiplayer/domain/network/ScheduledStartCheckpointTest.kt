package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScheduledStartCheckpointTest {
    private val host = Player("host", "Kevin", "kevin", isHost = true, lobbyReadyStatus = "READY", readyVersion = 1L)
    private val guest = Player("guest", "Stuart", "stuart", lobbyReadyStatus = "READY", readyVersion = 2L)
    private fun packet(code: String = "CP0123"): RoomMessagePacket {
        val deadline = System.currentTimeMillis() + 3300L
        return RoomMessagePacket("START_GAME", playerId = host.id, senderPlayerId = host.id,
            roomCode = code, seed = 99L, requestId = "exact-epoch", connectionGeneration = "host-generation",
            boardSize = 5, players = listOf(host, guest), currentTurnPlayerId = host.id,
            startAcknowledgedIds = listOf(host.id, guest.id), startClockOffsets = mapOf(host.id to 0L, guest.id to 60_000L),
            startCommitDeadline = deadline, turnClock = MultiplayerTurnClock.start(1, host.id, deadline))
    }

    @Test fun scheduledHostAndGuestSurviveRestartAndEnterOnlyAnExactlyCommittedEpoch() = runBlocking {
        val originalPrefs = OngoingMatchStore.customPrefs
        val originalTransport = RoomMetadataApi.transportOverride
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id }); val gs = MqttRoomMetadataTransport(wire, { guest.id })
        OngoingMatchStore.customPrefs = FakeSharedPreferences()
        val start = packet()
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(start.roomCode, host))
            assertTrue(RoomMetadataCoordinator(gs).mutate(start.roomCode) { it.copy(players = it.players + guest) } is MutationResult.Applied)
            val session = hs.read(start.roomCode).getOrThrow()!!.sessionId
            assertTrue(ScheduledStartCheckpoint.stage(null, start, host.id, session))
            val localGuest = MatchPacketClock.ingress(start, 0L, guest.id, 0L)
            assertTrue(ScheduledStartCheckpoint.stage(null, localGuest, guest.id, session))
            val savedHost = OngoingMatchStore.getOngoingMatch(null, host.id)!!
            val savedGuest = OngoingMatchStore.getOngoingMatch(null, guest.id)!!
            assertEquals(savedHost.allPlayerBoards, savedGuest.allPlayerBoards)
            assertEquals(savedHost.playerBoard, savedGuest.allPlayerBoards[host.id])
            assertEquals(savedGuest.playerBoard, savedHost.allPlayerBoards[guest.id])
            assertEquals(60_000L, savedGuest.turnClock!!.startedAt - savedHost.turnClock!!.startedAt)
            assertTrue(OnlineRoomRegistry.updateRoomStatus(start.roomCode, "STARTING", seed = 99L,
                expectedSeed = 0L, canonicalStartPacketJson = FastPacketCodec.encode(start)) is MutationResult.Applied)
            assertEquals(ScheduledStartCheckpoint.Resolution.Pending, ScheduledStartCheckpoint.resolve(null, savedHost))
            assertEquals(ScheduledStartCheckpoint.Resolution.Pending, ScheduledStartCheckpoint.resolve(null, savedGuest))
            assertTrue(OnlineRoomRegistry.updateRoomStatus(start.roomCode, "PLAYING", seed = 99L,
                expectedSeed = 99L, canonicalStartPacketJson = FastPacketCodec.encode(start.copy(startCommitted = true, startReleased = true))) is MutationResult.Applied)
            assertTrue(ScheduledStartCheckpoint.resolve(null, savedHost) is ScheduledStartCheckpoint.Resolution.Ready)
            assertTrue(ScheduledStartCheckpoint.resolve(null, savedGuest) is ScheduledStartCheckpoint.Resolution.Ready)
            val recoveredGuest = OngoingMatchStore.getOngoingMatch(null, guest.id)!!
            assertEquals("", recoveredGuest.pendingStartEpoch)
            assertEquals(30_000L, recoveredGuest.turnClock!!.remainingMillis(start.startCommitDeadline + 60_000L))
        } finally {
            OngoingMatchStore.customPrefs = originalPrefs; RoomMetadataApi.transportOverride = originalTransport
            hs.close(); gs.close(); OnlineRoomRegistry.recentRoomCache.remove(start.roomCode)
        }
    }

    @Test fun staleCancellationCannotClearAnotherAccountOrANewerCheckpoint() = runBlocking {
        val originalPrefs = OngoingMatchStore.customPrefs; val originalTransport = RoomMetadataApi.transportOverride
        OngoingMatchStore.customPrefs = FakeSharedPreferences()
        val start = packet()
        try {
            assertTrue(ScheduledStartCheckpoint.stage(null, start, host.id, "session"))
            assertTrue(ScheduledStartCheckpoint.stage(null, MatchPacketClock.ingress(start, 0L, guest.id, 0L), guest.id, "session"))
            val old = OngoingMatchStore.getOngoingMatch(null, host.id)!!
            val new = old.copy(matchSeed = 200L, pendingStartEpoch = "new-epoch")
            assertTrue(OngoingMatchStore.saveOngoingMatch(null, new))
            RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
                override suspend fun read(code: String) = Result.success<OnlineRoomSession?>(null)
                override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession) = error("No write")
            }
            assertEquals(ScheduledStartCheckpoint.Resolution.Cancelled, ScheduledStartCheckpoint.resolve(null, old))
            assertEquals(new, OngoingMatchStore.getOngoingMatch(null, host.id))
            assertNotNull(OngoingMatchStore.getOngoingMatch(null, guest.id))
        } finally { OngoingMatchStore.customPrefs = originalPrefs; RoomMetadataApi.transportOverride = originalTransport }
    }

    @Test fun unavailableBrokerOrWrongEpochNeverTurnsAProvisionalCheckpointIntoAGame() = runBlocking {
        val originalPrefs = OngoingMatchStore.customPrefs; val originalTransport = RoomMetadataApi.transportOverride
        OngoingMatchStore.customPrefs = FakeSharedPreferences()
        val start = packet()
        var unavailable = true
        try {
            assertTrue(ScheduledStartCheckpoint.stage(null, start, host.id, "session"))
            val saved = OngoingMatchStore.getOngoingMatch(null, host.id)!!
            RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
                override suspend fun read(code: String): Result<OnlineRoomSession?> = if (unavailable) Result.failure(java.io.IOException("Controlled EMQX outage")) else
                    Result.success(OnlineRoomSession(code, host.id, host.username, host.displayName, status = "PLAYING", currentSeed = start.seed,
                        sessionId = "session", canonicalStartPacketJson = FastPacketCodec.encode(start.copy(startCommitted = true, startReleased = true, requestId = "wrong-epoch"))))
                override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession) = error("No write")
            }
            assertEquals(ScheduledStartCheckpoint.Resolution.Unavailable, ScheduledStartCheckpoint.resolve(null, saved))
            unavailable = false
            assertEquals(ScheduledStartCheckpoint.Resolution.Unavailable, ScheduledStartCheckpoint.resolve(null, saved))
            assertEquals(saved, OngoingMatchStore.getOngoingMatch(null, host.id))
            assertFalse(OngoingMatchStore.clearScheduledStart(null, host.id, 99L, "wrong-epoch"))
        } finally { OngoingMatchStore.customPrefs = originalPrefs; RoomMetadataApi.transportOverride = originalTransport }
    }

    @Test fun exitPublicationWaitsForTheRetiredWritersInflightRetainedWrite() = runBlocking {
        val originalPrefs = OngoingMatchStore.customPrefs; val originalTransport = RoomMetadataApi.transportOverride
        OngoingMatchStore.customPrefs = FakeSharedPreferences()
        val wire = EmqxWireFixture(); val hs = MqttRoomMetadataTransport(wire, { host.id })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val code = "DW0123"
        try {
            RoomMetadataApi.transportOverride = hs
            assertTrue(OnlineRoomRegistry.createRoom(code, host))
            assertTrue(OnlineRoomRegistry.updateRoomStatus(code, "PLAYING", seed = 99L) is MutationResult.Applied)
            wire.beforePublish = { topic, bytes ->
                if (topic.endsWith("/state") && bytes.toString(Charsets.UTF_8).contains("\"boardSize\":6")) {
                    entered.complete(Unit); release.await()
                }
            }
            val write = async { RoomMetadataCoordinator(hs).mutate(code) { it.copy(boardSize = 6) } }
            withTimeout(2000L) { entered.await() }
            val exit = PendingMatchExit(host.id, code, RoomMessagePacket("SURRENDER", roomCode = code, playerId = host.id, seed = 99L))
            assertTrue(OngoingMatchStore.enqueueExit(null, exit))
            val drain = async(start = CoroutineStart.UNDISPATCHED) { RoomMetadataApi.prepareExitDelivery(exit) }
            assertFalse("Exit delivery must not overtake the former writer", drain.isCompleted)
            release.complete(Unit)
            assertTrue(write.await() is MutationResult.Applied)
            withTimeout(2000L) { drain.await() }
            assertTrue(RoomMetadataCoordinator(hs).mutate(code) { it.copy(boardSize = 7) } is MutationResult.Rejected)
        } finally {
            release.complete(Unit); hs.close(); OngoingMatchStore.customPrefs = originalPrefs
            RoomMetadataApi.transportOverride = originalTransport; OnlineRoomRegistry.recentRoomCache.remove(code)
        }
    }
}
