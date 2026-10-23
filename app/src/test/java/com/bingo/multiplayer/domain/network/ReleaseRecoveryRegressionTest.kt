package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.LobbyLifecycleEngine
import com.bingo.multiplayer.domain.engine.ManualMatchIngressTarget
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ReleaseRecoveryRegressionTest {
    private val host = Player(id = "host", displayName = "Kevin", isHost = true)
    private val guest = Player(id = "guest", displayName = "Stuart")
    private val roster = listOf(host, guest)

    @Test
    fun obsoleteClosureDoesNotQueueOrHideTheActiveRematch() = runBlocking {
        val code = "LOCAL_RELEASE_CLOSURE"
        val active = OnlineRoomSession(code, host.id, "kevin", "Kevin", status = "PLAYING",
            players = roster, currentSeed = 200L)
        val originalClient = OnlineRoomRegistry.client
        val originalTransport = RoomMetadataApi.transportOverride
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> = Result.success(active)
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit = error("Obsolete close must not write")
        }
        val requests = CopyOnWriteArrayList<String>()
        OnlineRoomRegistry.client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Controlled read outage").body("unavailable".toResponseBody()).build()
        }.build()
        OnlineRoomRegistry.recentRoomCache[code] = OnlineRoomRegistry.CachedRoom(active, System.currentTimeMillis())
        OnlineRoomRegistry.simulatedHttpException = IOException("Controlled HTTP outage")
        try {
            OnlineRoomRegistry.closeRoom(code, expectedSeed = 100L)
            assertFalse(OnlineRoomRegistry.pendingClosures.containsKey(code))
            assertFalse(OnlineRoomRegistry.terminallyClosedRooms.contains(code))
            assertEquals(active, OnlineRoomRegistry.getRoomCachedOnly(code))
            assertEquals(active, OnlineRoomRegistry.getRoom(code))
            assertTrue("Obsolete closure cannot issue any write", requests.none { "/UpdateValue/" in it })

            // A durable entry left by an older app must also leave the new generation visible.
            OnlineRoomRegistry.pendingClosures[code] = OnlineRoomRegistry.PendingClosure(code, 100L, 1L)
            assertEquals(active, OnlineRoomRegistry.getRoomCachedOnly(code))
            assertEquals(active, OnlineRoomRegistry.getRoom(code))
            assertFalse(OnlineRoomRegistry.isRoomClosedHttpCancellable(code))

            OnlineRoomRegistry.pendingClosures[code] = OnlineRoomRegistry.PendingClosure(code, 200L, 1L)
            assertNull(OnlineRoomRegistry.getRoomCachedOnly(code))
            assertNull(OnlineRoomRegistry.getRoom(code))
        } finally {
            OnlineRoomRegistry.client = originalClient
            RoomMetadataApi.transportOverride = originalTransport
            OnlineRoomRegistry.simulatedHttpException = null
            OnlineRoomRegistry.pendingClosures.remove(code)
            OnlineRoomRegistry.recentRoomCache.remove(code)
            OnlineRoomRegistry.terminallyClosedRooms.remove(code)
        }
    }

    @Test
    fun canonicalRecoveryRejectsWrongPacketTypeOrSender() {
        val sync = OnlineRoomSyncManager()
        val clock = MultiplayerTurnClock.start(2, guest.id, 10_000L)
        val valid = RoomMessagePacket(type = "START_GAME", roomCode = "RELEASE", seed = 123L,
            playerId = host.id, senderPlayerId = host.id, players = roster, turnClock = clock)
        val session = OnlineRoomSession("RELEASE", host.id, "kevin", "Kevin", status = "PLAYING",
            players = roster, currentSeed = 123L, currentTurnPlayerId = guest.id, turnClock = clock)
        for (bad in listOf(valid.copy(type = "GAME_OVER"), valid.copy(senderPlayerId = "former-host"))) {
            val recovered = sync.validateAndBuildStartPacket(session.copy(canonicalStartPacketJson = FastPacketCodec.encode(bad)))
            assertEquals("START_GAME", recovered.type)
            assertEquals(host.id, recovered.playerId)
            assertEquals(host.id, MultiplayerGameplayReducer.sender(recovered))
            assertEquals(clock, recovered.turnClock)
            assertEquals(roster, recovered.players)
        }
        assertEquals(valid.copy(isRetainedDelivery = true), sync.validateAndBuildStartPacket(
            session.copy(canonicalStartPacketJson = FastPacketCodec.encode(valid))))
    }

    @Test
    fun freshManualMetadataKeepsDesignerAndRunningMetadataPreservesBoards() {
        val preparing = RoomMessagePacket(type = "START_GAME", seed = 10L, players = roster,
            isManualBoard = true, manualPreparationComplete = false, isRetainedDelivery = true)
        assertEquals(ManualMatchIngressTarget.DESIGN,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(preparing, false, false))
        assertEquals(ManualMatchIngressTarget.DESIGN,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(preparing.copy(isRetainedDelivery = false), false, false))
        val running = preparing.copy(manualPreparationComplete = true)
        assertEquals(ManualMatchIngressTarget.GAME,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(running, false, false))
        val accepted = preparing.copy(boardNumbers = roster.associate { it.id to (1..25).toList() })
        assertEquals(ManualMatchIngressTarget.GAME,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(accepted, false, false))
        assertEquals(ManualMatchIngressTarget.GAME,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(preparing.copy(manualPreparationComplete = null), false, false))
    }

    @Test
    fun manualPhaseAndAcceptedBoardsSurviveMetadataRecovery() {
        val sync = OnlineRoomSyncManager()
        val boards = roster.associate { it.id to (1..25).toList() }
        val clock = MultiplayerTurnClock.start(1, guest.id, 20_000L)
        val start = RoomMessagePacket(type = "START_GAME", roomCode = "MANUAL", seed = 50L,
            playerId = host.id, senderPlayerId = host.id, players = roster, isManualBoard = true,
            manualPreparationComplete = true, boardNumbers = boards, turnClock = clock)
        val session = OnlineRoomSession("MANUAL", host.id, "kevin", "Kevin", status = "PLAYING",
            players = roster, currentSeed = 50L, isManualBoard = true, turnClock = clock,
            manualPreparationComplete = true, canonicalStartPacketJson = FastPacketCodec.encode(start))
        val recovered = sync.validateAndBuildStartPacket(session)
        assertEquals(true, recovered.manualPreparationComplete)
        assertEquals(boards, recovered.boardNumbers)
        assertEquals(clock, recovered.turnClock)
        assertEquals(ManualMatchIngressTarget.GAME,
            LobbyLifecycleEngine.resolveManualPacketIngressTarget(recovered, false, false))
    }

    @Test
    fun unavailableCanonicalServiceIsReportedAsAnErrorWithoutRepeatedRoomLookups() = runBlocking {
        val previous = RoomMetadataApi.transportOverride
        var reads = 0
        RoomMetadataApi.transportOverride = object : RoomMetadataTransport {
            override suspend fun read(code: String): Result<OnlineRoomSession?> {
                if (code == "ABC123") reads++
                return Result.failure(IOException("Controlled service outage"))
            }
            override suspend fun commit(code: String, base: OnlineRoomSession?, next: OnlineRoomSession): RoomMetadataCommit =
                error("An unavailable lookup must not attempt a write")
        }
        try {
            val result = OnlineRoomRegistry.validateAndJoinRoom("ABC123", guest)
            assertTrue(result is RoomJoinResult.Error)
            assertEquals(1, reads)
        } finally { RoomMetadataApi.transportOverride = previous }
    }
}
